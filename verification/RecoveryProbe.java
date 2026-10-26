import cn.bank.devtool.GeneratorService;
import cn.bank.devtool.Spec;
import cn.bank.devtool.SpecParser;
import cn.bank.devtool.Util;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compare recovered behavior with the untouched original classes before applying fixes. */
public final class RecoveryProbe {
    public static void main(String[] args) throws Exception {
        Path root = java.nio.file.Paths.get(args[0]);
        Map<String, Object> evidence = new LinkedHashMap<>();
        Map<String, byte[]> fixtures = new LinkedHashMap<>();
        for (String fixture : new String[]{"contract-spec", "contract-v2-spec", "interface-config-spec",
                "engineering-spec", "production-spec", "production-safe-spec"}) {
            fixtures.put(fixture, Files.readAllBytes(root.resolve("src/main/resources/static/examples/" + fixture + ".json")));
        }
        if (args.length > 2 && args[2].equals("matrix")) {
            ObjectNode nativeSpec = (ObjectNode)Util.parse(fixtures.get("contract-v2-spec"));
            nativeSpec.with("options").put("wireFormat", "NATIVE").put("optimisticLock", true).put("deleteVersionCheck", true);
            ((ArrayNode)nativeSpec.path("fields")).addObject().put("name", "externalId").put("column", "external_id").put("javaType", "Long")
                .put("description", "外部编号").put("query", "IN").put("required", false).put("editable", true).put("exposed", true);
            ((ArrayNode)nativeSpec.path("fields")).addObject().put("name", "quantity").put("column", "quantity").put("javaType", "Integer")
                .put("description", "数量").put("query", "EQ").put("required", false).put("editable", true).put("exposed", true);
            fixtures.put("native-numbers", Util.bytes(Util.pretty(nativeSpec)));
            ObjectNode full = (ObjectNode)Util.parse(fixtures.get("contract-v2-spec")); full.put("updateMode", "FULL");
            fixtures.put("full-update", Util.bytes(Util.pretty(full)));
            ObjectNode noAudit = (ObjectNode)Util.parse(fixtures.get("production-safe-spec")); noAudit.with("options").put("transactionalAudit", false);
            noAudit.with("options").remove("auditFields");
            fixtures.put("record-access-without-audit", Util.bytes(Util.pretty(noAudit)));
            ObjectNode detail = (ObjectNode)Util.parse(fixtures.get("contract-spec")); detail.putArray("operations").add("DETAIL");
            fixtures.put("detail-only", Util.bytes(Util.pretty(detail)));
        }
        for (Map.Entry<String, byte[]> fixtureEntry : fixtures.entrySet()) {
            String fixture = fixtureEntry.getKey();
            Spec spec = SpecParser.read(fixtureEntry.getValue());
            Map<String, byte[]> files = new GeneratorService().generate(spec);
            Map<String, String> hashes = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                hashes.put(file.getKey(), Util.sha(file.getValue()));
                Path target = root.resolve(args[1]).resolve(fixture).resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, file.getValue());
            }
            evidence.put(fixture, hashes);
        }
        Files.write(root.resolve(args[1] + ".json"), Util.bytes(Util.pretty(evidence)));
        System.out.println("Generated " + evidence.size() + " fixture projects into " + args[1]);
    }
}
