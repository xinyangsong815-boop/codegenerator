package cn.bank.devtool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.nio.file.*;
import java.util.*;

/** Generated mapping boundaries and meaningful Java documentation regression checks. */
public final class GeneratedQualitySuite {
    private interface Check { void run() throws Exception; }
    private static final List<ObjectNode> results = new ArrayList<>();
    private static final Map<String, Map<String, byte[]>> projects = new LinkedHashMap<>();
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void check(String name, Check test) {
        try { test.run(); results.add(Util.obj().put("name", name).put("passed", true)); }
        catch (Throwable failure) { results.add(Util.obj().put("name", name).put("passed", false).put("error", failure.toString())); }
    }
    private static String text(Map<String, byte[]> files, String suffix) {
        for (Map.Entry<String, byte[]> row : files.entrySet()) if (row.getKey().endsWith(suffix)) return SourceFiles.utf8(row.getValue());
        throw new AssertionError("Missing " + suffix);
    }
    private static MethodDeclaration method(String source, String name) {
        return StaticJavaParser.parse(source).findAll(MethodDeclaration.class).stream().filter(m -> m.getNameAsString().equals(name)).findFirst().orElseThrow(() -> new AssertionError("Missing method " + name));
    }
    private static ObjectNode fixture(Path root, String name) throws Exception {
        return (ObjectNode) Util.parse(Files.readAllBytes(root.resolve("src/main/resources/static/examples/" + name + ".json")));
    }
    private static void generate(String name, ObjectNode spec) {
        check("generate-" + name, () -> {
            Map<String, byte[]> files = new GeneratorService().generate(SpecParser.read(Util.bytes(Util.pretty(spec))));
            for (Map.Entry<String, byte[]> file : files.entrySet()) if (file.getKey().endsWith(".java")) StaticJavaParser.parse(SourceFiles.utf8(file.getValue()));
            projects.put(name, files);
        });
    }
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]); Path report = Paths.get(args[1]);
        for (String name : Arrays.asList("contract-spec", "contract-v2-spec", "interface-config-spec", "engineering-spec", "production-spec", "production-safe-spec")) generate(name, fixture(root, name));
        ObjectNode normal = fixture(root, "contract-v2-spec");
        ObjectNode full = normal.deepCopy(); full.put("updateMode", "FULL"); generate("full-update", full);
        ObjectNode selected = full.deepCopy();
        ObjectNode endpoints = selected.putObject("endpoints");
        endpoints.putObject("CREATE").putArray("fields").add("contractNo").add("amount");
        endpoints.putObject("UPDATE").putArray("fields").add("signDate");
        generate("selected-fields", selected);
        ObjectNode onlyPartial = normal.deepCopy(); onlyPartial.putArray("operations").add("UPDATE"); onlyPartial.put("updateMode", "PARTIAL"); generate("partial-only", onlyPartial);
        ObjectNode onlyDelete = normal.deepCopy(); onlyDelete.putArray("operations").add("DELETE"); onlyDelete.remove("updateMode"); generate("delete-only", onlyDelete);
        check("create-mapping-excludes-server-and-database-fields", () -> {
            String source = text(projects.get("production-safe-spec"), "Assembler.java");
            String body = method(source, "fromCreate").getBody().get().toString();
            require(body.contains("setContractNo") && body.contains("setAmount") && !body.contains("setOrgId") && !body.contains("setState") && !body.contains("setId") && !body.contains("setVersion"), "explicit CLIENT creation whitelist");
            String service = method(text(projects.get("production-safe-spec"), "ServiceImpl.java"), "create").getBody().get().toString();
            require(service.contains("Assembler.fromCreate") && service.contains("context.getOrgId()"), "trusted context remains in Service");
        });
        check("full-update-excludes-identity-and-optimistic-version", () -> {
            String body = method(text(projects.get("full-update"), "Assembler.java"), "fromFullUpdate").getBody().get().toString();
            require(body.contains("setContractNo") && !body.contains("setId") && !body.contains("getExpectedVersion"), "request identity must not be batch copied");
            require(method(text(projects.get("full-update"), "ServiceImpl.java"), "update").getBody().get().toString().contains("entity.setId(id)"), "Service binds checked ID separately");
        });
        check("create-and-full-update-use-different-endpoint-field-selections", () -> {
            String source = text(projects.get("selected-fields"), "Assembler.java");
            String create = method(source, "fromCreate").getBody().get().toString();
            String update = method(source, "fromFullUpdate").getBody().get().toString();
            require(create.contains("setContractNo") && create.contains("setAmount") && !create.contains("setSignDate"), "creation field subset");
            require(update.contains("setSignDate") && !update.contains("setContractNo") && !update.contains("setAmount"), "full update uses its own subset");
            String test = text(projects.get("selected-fields"), "AssemblerTest.java");
            require(test.contains("entity.getContractNo()") && test.contains("entity.getSignDate()"), "generated runtime tests retain negative mapping checks");
        });
        check("view-mapping-excludes-hidden-fields", () -> {
            String body = method(text(projects.get("production-safe-spec"), "Assembler.java"), "toView").getBody().get().toString();
            require(body.contains("setState") && body.contains("setVersion") && !body.contains("setOrgId") && !body.contains("setDeleted"), "only exposed response fields");
        });
        check("partial-update-keeps-present-dto-instead-of-entity", () -> {
            String body = method(text(projects.get("production-safe-spec"), "ServiceImpl.java"), "update").getBody().get().toString();
            require(body.contains("mapper.update(id, request,") && !body.contains("fromFullUpdate"), "PATCH presence unchanged");
            require(text(projects.get("production-safe-spec"), "Mapper.xml").contains("request.signDatePresent"), "explicit null presence still drives SQL");
        });
        check("no-empty-mapping-class-for-partial-or-delete-only", () -> {
            for (String name : Arrays.asList("partial-only", "delete-only")) require(projects.get(name).keySet().stream().noneMatch(p -> p.endsWith("Assembler.java") || p.endsWith("AssemblerTest.java")), "no unused converter for " + name);
        });
        check("generated-mapping-tests-assert-hidden-and-trusted-boundaries", () -> {
            String test = text(projects.get("production-safe-spec"), "AssemblerTest.java");
            require(test.contains("entity.getOrgId()") && test.contains("entity.getState()") && test.contains("getMethod(\"getOrgId\")"), "positive and negative whitelist assertions");
            require(text(projects.get("full-update"), "AssemblerTest.java").contains("fullUpdateKeepsExplicitNullRatherThanSkippingIt"), "full nullable replacement tested");
        });
        check("generated-manifest-hashes-new-mapping-and-test-files", () -> {
            Map<String, byte[]> files = projects.get("production-safe-spec");
            com.fasterxml.jackson.databind.JsonNode hashes = Util.parse(files.get("generation-manifest.json")).path("files");
            for (Map.Entry<String, byte[]> row : files.entrySet()) if (row.getKey().endsWith("Assembler.java") || row.getKey().endsWith("AssemblerTest.java")) require(hashes.path(row.getKey()).asText().equals(Util.sha(row.getValue())), "mapping candidate changes tracked");
        });
        check("plain-getters-setters-do-not-grow-redundant-comment-blocks", () -> {
            String source = GeneratedJavaStyle.format("public class X { /** 合同编号。 */ private String value; public String getValue(){return value;} public void setValue(String value){this.value=value;} }");
            require(!method(source, "getValue").getJavadocComment().isPresent() && !method(source, "setValue").getJavadocComment().isPresent(), "plain accessors stay short");
            require(source.contains("合同编号"), "field meaning preserved");
        });
        check("authored-accessor-and-present-side-effects-keep-documentation", () -> {
            String source = GeneratedJavaStyle.format("public class X { private String value; private boolean present; /** 显式传入包括null并记录标记。 */ public void setValue(String value){this.value=value;this.present=true;} /** 当前显示值。 */ public String getValue(){return value;} }");
            require(method(source, "setValue").getJavadocComment().get().getContent().contains("记录标记") && method(source, "getValue").getJavadocComment().get().getContent().contains("当前显示值"), "real behavior comments preserved");
        });
        check("computed-getter-and-undocumented-side-effect-setter-still-described", () -> {
            String source = GeneratedJavaStyle.format("public class X { private int value; private boolean present; public int getValue(){return value+1;} public void setValue(int value){this.value=value;present=true;} }");
            require(method(source, "getValue").getJavadocComment().isPresent() && method(source, "setValue").getJavadocComment().isPresent(), "nontrivial methods retain descriptions");
        });
        check("outbox-cancel-is-checked-before-scan-and-each-claim", () -> {
            String worker = text(projects.get("production-safe-spec"), "OutboxDispatcher.java");
            require(worker.indexOf("isInterrupted()") < worker.indexOf("jdbc.queryForList"), "pre-cancelled scheduler must not scan/claim");
            String test = text(projects.get("production-safe-spec"), "OutboxDeliveryTest.java");
            require(test.contains("interruptedWorkerDoesNotClaimOrSendPendingEvents") && test.contains("Thread.interrupted()"), "real JDBC cancellation test restores test thread");
        });
        Path generated = report.toAbsolutePath().getParent().resolve("generated-quality");
        for (Map.Entry<String, Map<String, byte[]>> project : projects.entrySet()) for (Map.Entry<String, byte[]> file : project.getValue().entrySet()) {
            Path path = generated.resolve(project.getKey()).resolve(file.getKey()); Files.createDirectories(path.getParent()); Files.write(path, file.getValue());
        }
        int failed = (int) results.stream().filter(r -> !r.path("passed").asBoolean()).count();
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.write(report, Util.bytes(Util.pretty(Util.mapOf("runtime", System.getProperty("java.version"), "tests", results.size(), "failed", failed, "generated", generated.toString(), "results", results))));
        System.out.println("GeneratedQuality: " + (results.size() - failed) + "/" + results.size());
        if (failed != 0) throw new AssertionError("GeneratedQuality failed=" + failed);
    }
}
