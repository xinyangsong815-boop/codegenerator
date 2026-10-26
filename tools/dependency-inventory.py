#!/usr/bin/env python3
"""Offline inventory of the libraries physically shipped in one Spring Boot JAR.

Python 3.8+, standard library only; no download, archive extraction or code execution.
Usage: python tools/dependency-inventory.py dist/app.jar --output-dir verification/supply-chain
The BOM uses https://raw.githubusercontent.com/CycloneDX/specification/1.6/schema/bom-1.6.schema.json.
This is inventory evidence, not a vulnerability, provenance or license approval scan.
"""
import argparse
import datetime
import hashlib
import io
import json
import pathlib
import re
import sys
import tempfile
import uuid
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter
from urllib.parse import quote

TOOL_VERSION = "1.0"
MAX_LIBRARY_BYTES = 256 * 1024 * 1024
MAX_METADATA_BYTES = 2 * 1024 * 1024
MAX_TOTAL_BYTES = 1024 * 1024 * 1024
MAX_EMBEDDED_DEPTH = 4


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_entry(archive, info, maximum):
    if info.file_size > maximum:
        raise ValueError("entry exceeds byte limit: " + info.filename)
    with archive.open(info) as stream:
        data = stream.read(maximum + 1)
        if len(data) > maximum:
            raise ValueError("entry expands beyond byte limit: " + info.filename)
        # Reading to EOF validates ZIP CRC; no untrusted pathname reaches the filesystem.
        return data


def properties(data):
    """Java .properties lexical rules, including continuations and Unicode escapes.

    Duplicate keys are rejected as identity evidence rather than silently choosing one.
    """
    lines, current = [], ""
    for line in data.decode("iso-8859-1").splitlines():
        current += line.lstrip(" \t\f") if current else line
        slashes = len(current) - len(current.rstrip("\\"))
        if slashes % 2:
            current = current[:-1]
        else:
            lines.append(current)
            current = ""
    if current:
        lines.append(current)

    def unescape(text):
        out, index = [], 0
        translations = {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}
        while index < len(text):
            char = text[index]
            index += 1
            if char != "\\":
                out.append(char)
                continue
            if index == len(text):
                raise ValueError("dangling properties escape")
            char = text[index]
            index += 1
            if char == "u":
                digits = text[index:index + 4]
                if len(digits) != 4 or not re.fullmatch(r"[0-9a-fA-F]{4}", digits):
                    raise ValueError("invalid properties Unicode escape")
                out.append(chr(int(digits, 16)))
                index += 4
            else:
                out.append(translations.get(char, char))
        return "".join(out)

    result = {}
    for line in lines:
        line = line.lstrip(" \t\f")
        if not line or line.startswith(("#", "!")):
            continue
        index, escaped = 0, False
        while index < len(line):
            char = line[index]
            if not escaped and char in "=: \t\f":
                break
            escaped = not escaped if char == "\\" else False
            index += 1
        key = unescape(line[:index])
        remainder = line[index:].lstrip(" \t\f")
        if remainder.startswith(("=", ":")):
            remainder = remainder[1:].lstrip(" \t\f")
        if key in result:
            raise ValueError("duplicate properties key: " + key)
        result[key] = unescape(remainder)
    return result


def safe_xml(data):
    # NUL removal also catches ASCII declarations encoded as UTF-16/UTF-32.
    if re.search(br"<!\s*(DOCTYPE|ENTITY)\b", data.replace(b"\x00", b""), flags=re.IGNORECASE):
        raise ValueError("DTD and entity declarations are not supported")
    return ET.fromstring(data)


def pom_metadata(data):
    document = safe_xml(data)
    namespace = document.tag.split("}")[0] + "}" if "}" in document.tag else ""

    def text(parent, name):
        node = parent.find(namespace + name) if parent is not None else None
        return (node.text or "").strip() if node is not None else ""

    parent = document.find(namespace + "parent")
    coordinate = {
        "groupId": text(document, "groupId") or text(parent, "groupId"),
        "artifactId": text(document, "artifactId"),
        "version": text(document, "version") or text(parent, "version"),
    }
    license_nodes = document.find(namespace + "licenses")
    licenses = []
    if license_nodes is not None:
        for item in license_nodes.findall(namespace + "license"):
            declaration = {key: text(item, key) for key in ("name", "url", "distribution", "comments") if text(item, key)}
            if declaration:
                licenses.append(declaration)
    return coordinate, licenses


def coordinate(values):
    selected = {key: values.get(key, "").strip() for key in ("groupId", "artifactId", "version")}
    try:
        for value in selected.values():
            value.encode("utf-8")
    except UnicodeEncodeError:
        return None
    if all(selected.values()) and all("${" not in value and not re.search(r"[\x00-\x20\x7f]", value) for value in selected.values()):
        return selected
    return None


def coordinate_key(value):
    return value["groupId"], value["artifactId"], value["version"]


def archive_identity(filename, candidates):
    unique = {coordinate_key(item["coordinates"]): item["coordinates"] for item in candidates if item.get("coordinates")}
    matching = []
    for value in unique.values():
        base = value["artifactId"] + "-" + value["version"]
        if filename == base + ".jar" or filename.startswith(base + "-") and filename.endswith(".jar"):
            matching.append(value)
    if len(matching) == 1:
        return matching[0], "DECLARED_METADATA_FILENAME_MATCH"
    if len(unique) == 1:
        return next(iter(unique.values())), "SINGLE_DECLARED_COORDINATE"
    return None, "AMBIGUOUS" if unique else "UNIDENTIFIED"


class Scanner:
    def __init__(self):
        self.components = []
        self.errors = []
        self.total_bytes = 0

    def inspect(self, info, data, location, parent_ref, depth=0):
        digest = sha256(data)
        # Location and ordinal distinguish duplicate artifacts and duplicate ZIP filenames.
        reference = "library:" + sha256((location + ":" + digest).encode("utf-8"))
        item = {
            "bomRef": reference, "archivePath": location, "parentRef": parent_ref,
            "filename": pathlib.PurePosixPath(info.filename).name, "size": len(data), "sha256": digest,
            "coordinates": None, "coordinateStatus": "UNIDENTIFIED", "mavenMetadata": [],
            "licenseEvidence": [], "licenseDeclarations": [], "licenseReview": "REVIEW_REQUIRED",
            "vulnerabilityScan": "NOT_RUN", "metadataErrors": [], "duplicateEntries": [],
        }
        self.components.append(item)
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                entries = archive.infolist()
                item["duplicateEntries"] = sorted(name for name, count in Counter(e.filename for e in entries).items() if count > 1)
                if item["duplicateEntries"]:
                    item["metadataErrors"].append("duplicate ZIP entries; classloader selection and metadata require review")
                for index, entry in enumerate(entries):
                    if entry.is_dir():
                        continue
                    name = entry.filename
                    lower = name.lower()
                    try:
                        if lower.endswith(("/pom.properties", "/pom.xml")) and lower.startswith("meta-inf/maven/"):
                            body = read_entry(archive, entry, MAX_METADATA_BYTES)
                            metadata = {"path": name, "sha256": sha256(body), "coordinates": None}
                            item["mavenMetadata"].append(metadata)
                            if lower.endswith("/pom.properties"):
                                metadata["coordinates"] = coordinate(properties(body))
                            else:
                                values, licenses = pom_metadata(body)
                                metadata["coordinates"] = coordinate(values)
                                item["licenseDeclarations"].extend(dict(value, source=name) for value in licenses)
                            if metadata["coordinates"] is None:
                                metadata["identityNote"] = "incomplete or unresolved Maven coordinates; no remote parent/POM resolution performed"
                        elif re.match(r"^(licen[cs]e|notice|copying)([._-].*)?$", pathlib.PurePosixPath(name).name, re.IGNORECASE):
                            body = read_entry(archive, entry, MAX_METADATA_BYTES)
                            item["licenseEvidence"].append({"path": name, "size": len(body), "sha256": sha256(body), "interpretation": "REVIEW_REQUIRED"})
                        elif lower == "meta-inf/manifest.mf":
                            manifest = read_entry(archive, entry, MAX_METADATA_BYTES).decode("utf-8", "replace")
                            manifest = re.sub(r"\r?\n ", "", manifest)
                            for line in manifest.splitlines():
                                if line.lower().startswith("bundle-license:"):
                                    item["licenseDeclarations"].append({"name": line.split(":", 1)[1].strip(), "source": name, "kind": "Bundle-License"})
                        elif lower.endswith(".jar"):
                            if depth >= MAX_EMBEDDED_DEPTH:
                                raise ValueError("nested JAR exceeds depth limit")
                            self.total_bytes += entry.file_size
                            if self.total_bytes > MAX_TOTAL_BYTES:
                                raise ValueError("nested libraries exceed total byte budget")
                            nested_data = read_entry(archive, entry, MAX_LIBRARY_BYTES)
                            self.inspect(entry, nested_data, location + "!/" + name + "#" + str(index), reference, depth + 1)
                    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile, RuntimeError) as error:
                        item["metadataErrors"].append(name + ": " + str(error))
                item["coordinates"], item["coordinateStatus"] = archive_identity(item["filename"], item["mavenMetadata"])
        except (OSError, zipfile.BadZipFile, RuntimeError) as error:
            item["metadataErrors"].append("not a readable dependency JAR: " + str(error))
        return item


def build_sbom(inventory):
    root = inventory["artifact"]
    application_ref = root["bomRef"]
    components = []
    for item in inventory["dependencies"]:
        values = item["coordinates"]
        component = {
            "type": "library", "bom-ref": item["bomRef"],
            "name": values["artifactId"] if values else item["filename"],
            "hashes": [{"alg": "SHA-256", "content": item["sha256"]}] if item.get("sha256") else [],
            "properties": [
                {"name": "offline-dev-assistant:archivePath", "value": item["archivePath"]},
                {"name": "offline-dev-assistant:parentArchive", "value": item["parentRef"]},
                {"name": "offline-dev-assistant:coordinateStatus", "value": item["coordinateStatus"]},
                {"name": "offline-dev-assistant:licenseReview", "value": "REVIEW_REQUIRED"},
                {"name": "offline-dev-assistant:vulnerabilityScan", "value": "NOT_RUN"},
                {"name": "offline-dev-assistant:metadataEvidence", "value": json.dumps({key: item[key] for key in ("mavenMetadata", "licenseEvidence", "licenseDeclarations", "metadataErrors")}, ensure_ascii=False, sort_keys=True)},
            ],
        }
        if values:
            component["group"] = values["groupId"]
            component["version"] = values["version"]
            component["purl"] = "pkg:maven/{}/{}@{}".format(*(quote(values[key], safe=".-_") for key in ("groupId", "artifactId", "version")))
        # Observed names are not concluded SPDX licenses. All declarations remain reviewable evidence.
        declared_names = sorted({entry["name"] for entry in item["licenseDeclarations"] if entry.get("name")})
        if declared_names:
            component["evidence"] = {"licenses": [{"license": {"name": name}} for name in declared_names]}
        components.append(component)
    return {
        "$schema": "http://cyclonedx.org/schema/bom-1.6.schema.json", "bomFormat": "CycloneDX", "specVersion": "1.6",
        "serialNumber": "urn:uuid:" + str(uuid.uuid4()), "version": 1,
        "metadata": {
            "timestamp": inventory["generatedAt"],
            "component": {"type": "application", "bom-ref": application_ref, "name": root["filename"], "hashes": [{"alg": "SHA-256", "content": root["sha256"]}]},
            "tools": {"components": [{"type": "application", "name": "offline-dev-assistant-dependency-inventory", "version": TOOL_VERSION}]},
            "properties": [
                {"name": "offline-dev-assistant:vulnerabilityScan", "value": "NOT_RUN"},
                {"name": "offline-dev-assistant:licenseReview", "value": "REVIEW_REQUIRED"},
                {"name": "offline-dev-assistant:inventoryStatus", "value": inventory["status"]},
                {"name": "offline-dev-assistant:scope", "value": inventory["scope"]},
                {"name": "offline-dev-assistant:dependencyGraph", "value": "NOT_RESOLVED: archive containment is recorded in component properties; Maven dependency edges are not inferred"},
            ],
        },
        "components": components,
        "compositions": [{"aggregate": "incomplete", "assemblies": [application_ref]}],
    }


def scan(jar):
    path = pathlib.Path(jar)
    digest = file_hash(path)
    artifact_ref = "application:sha256:" + digest
    scanner = Scanner()
    inventory = {
        "schemaVersion": 1, "tool": "dependency-inventory.py", "toolVersion": TOOL_VERSION,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
        "artifact": {"filename": path.name, "size": path.stat().st_size, "sha256": digest, "bomRef": artifact_ref},
        "status": "COMPLETE", "vulnerabilityScan": "NOT_RUN", "licenseReview": "REVIEW_REQUIRED",
        "repositoryOrigin": "NOT_VERIFIED", "coordinateTrust": "DECLARED_NOT_AUTHENTICATED",
        "scope": "Physical BOOT-INF/lib entries and recursively embedded JARs only; excludes JDK, OS, build/test-only dependencies, downloaded runtime files and shaded code without metadata. No repository authenticity or vulnerability database checks.",
        "dependencies": scanner.components, "errors": scanner.errors,
    }
    try:
        with zipfile.ZipFile(path) as archive:
            entries = [entry for entry in archive.infolist() if entry.filename.startswith("BOOT-INF/lib/") and not entry.is_dir()]
            inventory["bootInfLibEntryCount"] = len(entries)
            inventory["duplicateBootInfEntries"] = sorted(name for name, count in Counter(e.filename for e in entries).items() if count > 1)
            if not entries:
                scanner.errors.append("No BOOT-INF/lib file entries; this is not a supported built Spring Boot archive")
            if inventory["duplicateBootInfEntries"]:
                scanner.errors.append("Duplicate BOOT-INF/lib entry names; archive selection requires review")
            for index, entry in enumerate(entries):
                location = entry.filename + "#" + str(index)
                try:
                    scanner.total_bytes += entry.file_size
                    if scanner.total_bytes > MAX_TOTAL_BYTES:
                        raise ValueError("libraries exceed total byte budget")
                    scanner.inspect(entry, read_entry(archive, entry, MAX_LIBRARY_BYTES), location, artifact_ref)
                except (ValueError, OSError, RuntimeError, zipfile.BadZipFile) as error:
                    scanner.errors.append(location + ": " + str(error))
            if file_hash(path) != digest:
                scanner.errors.append("Source artifact changed during inspection; regenerate from the final immutable release JAR")
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        inventory["bootInfLibEntryCount"] = 0
        scanner.errors.append("Cannot enumerate archive: " + str(error))
    inventory["componentCount"] = len(scanner.components)
    inventory["unidentifiedComponentCount"] = sum(item["coordinates"] is None for item in scanner.components)
    inventory["identityComplete"] = bool(scanner.components) and inventory["unidentifiedComponentCount"] == 0
    inventory["metadataErrorCount"] = sum(len(item["metadataErrors"]) for item in scanner.components)
    if scanner.errors or inventory["metadataErrorCount"]:
        inventory["status"] = "INCOMPLETE"
    return inventory, build_sbom(inventory)


def write_json(path, value):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n", prefix=".inventory-", suffix=".tmp", dir=str(path.parent), delete=False) as output:
        temp = pathlib.Path(output.name)
        json.dump(value, output, ensure_ascii=False, indent=2, sort_keys=True)
        output.write("\n")
    try:
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jar", type=pathlib.Path, help="final built Spring Boot JAR; never source or classpath inference")
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    args = parser.parse_args(argv)
    try:
        inventory, sbom = scan(args.jar)
        write_json(args.output_dir / "dependency-inventory.json", inventory)
        write_json(args.output_dir / "sbom.cdx.json", sbom)
        print(json.dumps({"status": inventory["status"], "bootInfLibEntries": inventory["bootInfLibEntryCount"], "components": inventory["componentCount"], "unidentified": inventory["unidentifiedComponentCount"], "vulnerabilityScan": "NOT_RUN", "licenseReview": "REVIEW_REQUIRED"}))
        return 0 if inventory["status"] == "COMPLETE" else 2
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print("Inventory failed: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
