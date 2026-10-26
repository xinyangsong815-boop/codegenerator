#!/usr/bin/env python3
"""Standard-library regression tests plus byte-for-byte checks of one real built JAR.

python verification/test_dependency_inventory.py --jar dist/app.jar --output verification/dependency-inventory-v6.json
"""
import argparse
import hashlib
import importlib.util
import io
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("dependency_inventory", ROOT / "tools" / "dependency-inventory.py")
SCANNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCANNER)


def archive(entries):
    data = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED) as result:
            for name, value in entries:
                result.writestr(name, value)
    return data.getvalue()


def props(group="org.example", artifact="example", version="1.0"):
    return ("groupId=" + group + "\nartifactId=" + artifact + "\nversion=" + version + "\n").encode("iso-8859-1")


def dep(entries=None):
    return archive(entries or [("META-INF/maven/org.example/example/pom.properties", props())])


class InventoryTests(unittest.TestCase):
    def setUp(self):
        parent = ROOT / "build" / "dependency-inventory-tests"
        parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="case-", dir=str(parent))
        self.directory = pathlib.Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def scan(self, entries):
        target = self.directory / "app.jar"
        target.write_bytes(archive(entries))
        return SCANNER.scan(target)

    def test_hashes_bound_to_actual_application_and_each_library(self):
        library = dep()
        inventory, sbom = self.scan([("BOOT-INF/lib/example-1.0.jar", library)])
        self.assertEqual(inventory["status"], "COMPLETE")
        self.assertEqual(inventory["dependencies"][0]["sha256"], hashlib.sha256(library).hexdigest())
        self.assertEqual(inventory["artifact"]["sha256"], hashlib.sha256((self.directory / "app.jar").read_bytes()).hexdigest())
        self.assertEqual(sbom["components"][0]["purl"], "pkg:maven/org.example/example@1.0")
        self.assertEqual(sbom["metadata"]["component"]["hashes"][0]["content"], inventory["artifact"]["sha256"])

    def test_unknown_identity_does_not_guess_coordinates_from_filename(self):
        inventory, sbom = self.scan([("BOOT-INF/lib/looks-like-spring-5.0.jar", archive([("a.class", b"x")]))])
        self.assertEqual(inventory["dependencies"][0]["coordinateStatus"], "UNIDENTIFIED")
        self.assertFalse(inventory["identityComplete"])
        self.assertNotIn("purl", sbom["components"][0])
        self.assertNotIn("version", sbom["components"][0])

    def test_ambiguous_metadata_remains_visible(self):
        library = dep([("META-INF/maven/a/a/pom.properties", props("a", "a")), ("META-INF/maven/b/b/pom.properties", props("b", "b"))])
        inventory, _ = self.scan([("BOOT-INF/lib/shaded.jar", library)])
        item = inventory["dependencies"][0]
        self.assertEqual(item["coordinateStatus"], "AMBIGUOUS")
        self.assertIsNone(item["coordinates"])
        self.assertEqual(len(item["mavenMetadata"]), 2)

    def test_shaded_metadata_is_retained_and_primary_match_is_explicit(self):
        library = dep([("META-INF/maven/a/a/pom.properties", props("a", "a")), ("META-INF/maven/b/b/pom.properties", props("b", "b"))])
        inventory, _ = self.scan([("BOOT-INF/lib/a-1.0.jar", library)])
        self.assertEqual(inventory["dependencies"][0]["coordinates"]["artifactId"], "a")
        self.assertEqual(len(inventory["dependencies"][0]["mavenMetadata"]), 2)

    def test_all_licenses_are_evidence_not_approvals(self):
        pom = b'<project><groupId>org.example</groupId><artifactId>example</artifactId><version>1.0</version><licenses><license><name>Example license</name><url>https://example.invalid/license</url></license></licenses></project>'
        license_body = b"license evidence only"
        library = dep([("META-INF/maven/a/a/pom.xml", pom), ("META-INF/LICENSE.txt", license_body), ("NOTICE", b"notice")])
        inventory, sbom = self.scan([("BOOT-INF/lib/example-1.0.jar", library)])
        item = inventory["dependencies"][0]
        self.assertEqual(item["licenseReview"], "REVIEW_REQUIRED")
        self.assertEqual(len(item["licenseEvidence"]), 2)
        self.assertEqual(item["licenseEvidence"][0]["sha256"], hashlib.sha256(license_body).hexdigest())
        self.assertEqual(item["licenseDeclarations"][0]["name"], "Example license")
        self.assertEqual(sbom["components"][0]["evidence"]["licenses"][0]["license"]["name"], "Example license")
        self.assertNotIn("licenses", sbom["components"][0])

    def test_parent_pom_coordinates_without_remote_resolution(self):
        pom = b'<project xmlns="http://maven.apache.org/POM/4.0.0"><parent><groupId>org.example</groupId><version>1.0</version></parent><artifactId>example</artifactId></project>'
        inventory, _ = self.scan([("BOOT-INF/lib/example-1.0.jar", dep([("META-INF/maven/a/a/pom.xml", pom)]))])
        self.assertEqual(inventory["dependencies"][0]["coordinates"]["groupId"], "org.example")

    def test_unresolved_pom_version_is_not_a_trusted_coordinate(self):
        pom = b'<project><groupId>org.example</groupId><artifactId>example</artifactId><version>${revision}</version></project>'
        inventory, _ = self.scan([("BOOT-INF/lib/example-1.0.jar", dep([("META-INF/maven/a/a/pom.xml", pom)]))])
        self.assertIsNone(inventory["dependencies"][0]["coordinates"])

    def test_duplicate_properties_keys_are_not_silently_overwritten(self):
        inventory, _ = self.scan([("BOOT-INF/lib/example-1.0.jar", dep([("META-INF/maven/a/a/pom.properties", props() + b"version=2.0\n")]))])
        self.assertEqual(inventory["status"], "INCOMPLETE")
        self.assertIsNone(inventory["dependencies"][0]["coordinates"])

    def test_java_properties_continuation_separator_and_unicode(self):
        values = SCANNER.properties(b"# comment\r\ngroupId : org.\\\r\n  example\r\nartifactId=ex\\u0061mple\r\nversion 1.0\r\nescaped\\:key:value\r\n")
        self.assertEqual(values["groupId"], "org.example")
        self.assertEqual(values["artifactId"], "example")
        self.assertEqual(values["version"], "1.0")
        self.assertEqual(values["escaped:key"], "value")

    def test_recursive_nested_libraries_keep_physical_parent(self):
        inner = dep()
        outer = dep([("nested/example-1.0.jar", inner)])
        inventory, sbom = self.scan([("BOOT-INF/lib/outer.jar", outer)])
        self.assertEqual(inventory["bootInfLibEntryCount"], 1)
        self.assertEqual(inventory["componentCount"], 2)
        self.assertEqual(inventory["dependencies"][1]["parentRef"], inventory["dependencies"][0]["bomRef"])
        self.assertNotIn("dependencies", sbom)  # Containment must not invent Maven dependency edges.

    def test_duplicate_zip_names_are_all_counted_and_reviewable(self):
        inventory, sbom = self.scan([("BOOT-INF/lib/example.jar", dep()), ("BOOT-INF/lib/example.jar", dep())])
        self.assertEqual(inventory["status"], "INCOMPLETE")
        self.assertEqual(inventory["bootInfLibEntryCount"], 2)
        self.assertEqual(inventory["componentCount"], 2)
        self.assertEqual(len({c["bom-ref"] for c in sbom["components"]}), 2)

    def test_broken_dependency_keeps_hash_and_incomplete_metadata(self):
        inventory, _ = self.scan([("BOOT-INF/lib/bad.jar", b"not a zip")])
        self.assertEqual(inventory["status"], "INCOMPLETE")
        self.assertEqual(inventory["componentCount"], 1)
        self.assertEqual(inventory["dependencies"][0]["sha256"], hashlib.sha256(b"not a zip").hexdigest())

    def test_dtd_and_entity_metadata_are_rejected(self):
        pom = b'<!DOCTYPE project [<!ENTITY data "fake">]><project><groupId>&data;</groupId></project>'
        inventory, _ = self.scan([("BOOT-INF/lib/evil.jar", dep([("META-INF/maven/a/a/pom.xml", pom)]))])
        self.assertEqual(inventory["status"], "INCOMPLETE")
        self.assertIn("DTD", inventory["dependencies"][0]["metadataErrors"][0])

    def test_utf16_dtd_is_also_rejected(self):
        pom = '<!DOCTYPE project [<!ENTITY data "fake">]><project><groupId>&data;</groupId></project>'.encode("utf-16")
        inventory, _ = self.scan([("BOOT-INF/lib/evil.jar", dep([("META-INF/maven/a/a/pom.xml", pom)]))])
        self.assertEqual(inventory["status"], "INCOMPLETE")
        self.assertIn("DTD", inventory["dependencies"][0]["metadataErrors"][0])

    def test_limits_produce_incomplete_not_silent_truncation(self):
        original = SCANNER.MAX_METADATA_BYTES
        SCANNER.MAX_METADATA_BYTES = 4
        try:
            inventory, _ = self.scan([("BOOT-INF/lib/example.jar", dep())])
        finally:
            SCANNER.MAX_METADATA_BYTES = original
        self.assertEqual(inventory["status"], "INCOMPLETE")
        self.assertGreater(inventory["metadataErrorCount"], 0)

    def test_no_boot_libs_is_not_a_supported_complete_inventory(self):
        inventory, _ = self.scan([("a.class", b"no boot lib")])
        self.assertEqual(inventory["status"], "INCOMPLETE")

    def test_bad_outer_archive_is_not_a_complete_inventory(self):
        target = self.directory / "bad.jar"
        target.write_bytes(b"bad archive")
        inventory, _ = SCANNER.scan(target)
        self.assertEqual(inventory["status"], "INCOMPLETE")
        self.assertTrue(inventory["errors"])

    def test_path_traversal_archive_names_are_not_extracted(self):
        inventory, _ = self.scan([("BOOT-INF/lib/example.jar", dep([("../../should-not-exist", b"x"), ("META-INF/maven/a/a/pom.properties", props())]))])
        self.assertEqual(inventory["status"], "COMPLETE")
        self.assertFalse((self.directory / "should-not-exist").exists())

    def test_cli_writes_inventory_and_sbom_with_no_security_pass_claim(self):
        target = self.directory / "app.jar"
        target.write_bytes(archive([("BOOT-INF/lib/example.jar", dep())]))
        output = self.directory / "output"
        result = subprocess.run([sys.executable, str(ROOT / "tools/dependency-inventory.py"), str(target), "--output-dir", str(output)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        inventory = json.loads((output / "dependency-inventory.json").read_text(encoding="utf-8"))
        sbom = json.loads((output / "sbom.cdx.json").read_text(encoding="utf-8"))
        self.assertEqual(inventory["vulnerabilityScan"], "NOT_RUN")
        self.assertEqual(sbom["specVersion"], "1.6")
        self.assertNotIn("vulnerabilities", sbom)


def verify_real_jar(jar, output_directory):
    inventory, sbom = SCANNER.scan(jar)
    assert inventory["status"] == "COMPLETE", inventory["errors"]
    with zipfile.ZipFile(jar) as built:
        libraries = [entry for entry in built.infolist() if entry.filename.startswith("BOOT-INF/lib/") and not entry.is_dir()]
        assert len(libraries) == inventory["bootInfLibEntryCount"]
        roots = [item for item in inventory["dependencies"] if item["parentRef"] == inventory["artifact"]["bomRef"]]
        assert len(roots) == len(libraries)
        for entry, observed in zip(libraries, roots):
            assert observed["sha256"] == hashlib.sha256(built.read(entry)).hexdigest()
            assert observed["licenseReview"] == "REVIEW_REQUIRED"
            assert observed["vulnerabilityScan"] == "NOT_RUN"
    assert len(sbom["components"]) == inventory["componentCount"]
    SCANNER.write_json(output_directory / "dependency-inventory.json", inventory)
    SCANNER.write_json(output_directory / "sbom.cdx.json", sbom)
    return {"artifact": inventory["artifact"], "bootInfLibEntries": inventory["bootInfLibEntryCount"], "components": inventory["componentCount"], "unidentified": inventory["unidentifiedComponentCount"], "allLibraryHashesVerified": True, "vulnerabilityScan": "NOT_RUN", "licenseReview": "REVIEW_REQUIRED"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(InventoryTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    failures = {test.id(): detail for test, detail in result.failures + result.errors}
    report = {"tests": result.testsRun, "passed": result.testsRun - len(failures), "failed": len(failures), "failures": failures}
    try:
        report["realArtifact"] = verify_real_jar(args.jar, args.output.with_suffix(""))
    except Exception as error:
        report["realArtifactError"] = str(error)
        report["failed"] += 1
    SCANNER.write_json(args.output, report)
    return 0 if report["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
