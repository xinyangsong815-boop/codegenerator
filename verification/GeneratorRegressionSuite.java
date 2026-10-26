package cn.bank.devtool;

import cn.bank.devtool.regression.RegressionEngine;
import cn.bank.devtool.regression.RegressionReports;
import cn.bank.devtool.regression.ScenarioRules;
import cn.bank.devtool.regression.TargetPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;

/** Executable regression tests; uses real loopback HTTP requests and isolated disposable data. */
public final class GeneratorRegressionSuite {
    private static final List<Map<String, Object>> results = new ArrayList<>();
    private static Path root;
    private static String origin;
    private static final AtomicInteger calls = new AtomicInteger();
    private static final AtomicInteger tempIds = new AtomicInteger();
    @FunctionalInterface interface Test { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        root = java.nio.file.Paths.get(args[0]).toAbsolutePath();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String body = path.equals("/text") ? "upstream failed" : path.equals("/null") ? "null"
                    : path.equals("/duplicate") ? "{\"amount\":1,\"amount\":2}"
                    : path.equals("/echo") ? new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8)
                    : "{\"amount\":1234567890123456.78,\"id\":9223372036854775806}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", path.equals("/text") ? "text/plain" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            test("json-decimals-preserve-value-and-scale", () -> {
                JsonNode value = Util.JSON.readTree("{\"amount\":1234567890123456.78,\"rate\":1.230}");
                require(value.path("amount").decimalValue().equals(new java.math.BigDecimal("1234567890123456.78")), "exact decimal value");
                require(value.path("rate").decimalValue().scale() == 3, "decimal scale survives JSON persistence");
            });
            test("partial-update-doc-requires-a-business-field", () -> {
                Spec spec = fixture("contract-v2-spec");
                JsonNode schema = GeneratedContract.create(spec).at("/components/schemas/ContractUpdateDTO");
                require(schema.path("anyOf").isArray() && !schema.path("anyOf").isEmpty(), "id/version-only updates must be excluded by OpenAPI");
                for (JsonNode alternative : schema.path("anyOf")) {
                    String name = alternative.path("required").path(0).asText();
                    require(!name.equals("id") && !name.equals("expectedVersion") && !name.isEmpty(), "business field required");
                }
            });
            test("openapi-native-integer-bounds", () -> {
                ObjectNode input = fixtureJson("contract-v2-spec");
                input.with("options").put("wireFormat", "NATIVE");
                ((ArrayNode)input.path("fields")).addObject().put("name", "quantity").put("column", "quantity")
                        .put("javaType", "Integer").put("description", "数量").put("query", "NONE");
                JsonNode schema = GeneratedContract.create(SpecParser.read(Util.bytes(Util.pretty(input))))
                        .at("/components/schemas/ContractCreateDTO/properties/quantity");
                require(schema.path("minimum").asLong() == Integer.MIN_VALUE && schema.path("maximum").asLong() == Integer.MAX_VALUE, "document Java integer range");
            });
            test("size-assertion-does-not-overflow-int", () -> require(!ScenarioRules.assertValue(TextNode.valueOf(""), "size",
                    BigIntegerNode.valueOf(new java.math.BigInteger("4294967296"))), "huge expected size must not become zero"));
            test("exists-false-honors-expected", () -> require(ScenarioRules.assertValue(MissingNode.getInstance(), "exists", BooleanNode.FALSE), "exists false should check absence"));
            test("not-exists-is-supported", () -> {
                ObjectNode scenario = scenario("absent", "/json", "body", "/missing", "notExists", null);
                ScenarioRules.check(scenario);
                require(ScenarioRules.assertValue(MissingNode.getInstance(), "notExists", MissingNode.getInstance()), "notExists should match absence");
            });
            test("invalid-type-assertion-rejected-before-run", () -> expectInvalid(() -> ScenarioRules.check(scenario("badtype", "/json", "body", "/amount", "type", TextNode.valueOf("decimalish")))));
            test("invalid-size-assertion-rejected-before-run", () -> expectInvalid(() -> ScenarioRules.check(scenario("badsize", "/json", "body", "", "size", IntNode.valueOf(-1)))));
            test("invalid-comparison-assertion-rejected-before-run", () -> expectInvalid(() -> ScenarioRules.check(scenario("badcompare", "/json", "body", "/amount", "gt", TextNode.valueOf("1")))));
            test("invalid-exists-assertion-rejected-before-run", () -> expectInvalid(() -> ScenarioRules.check(scenario("badexists", "/json", "body", "/amount", "exists", TextNode.valueOf("no")))));
            test("http-json-money-is-exact", () -> require(run(scenario("money", "/json", "body", "/amount", "eq", DecimalNode.valueOf(new java.math.BigDecimal("1234567890123456.78")))).path("verdict").asText().equals("PASS"), "money equality"));
            test("http-json-long-is-exact", () -> require(run(scenario("longid", "/json", "body", "/id", "eq", LongNode.valueOf(9223372036854775806L))).path("verdict").asText().equals("PASS"), "long equality"));
            test("http-status-only-allows-text", () -> require(run(scenario("textstatus", "/text", "status", "", "eq", IntNode.valueOf(200))).path("verdict").asText().equals("PASS"), "status-only HTTP is independent of JSON"));
            test("http-non-json-cannot-pass-absence-assertion", () -> require(run(scenario("textbody", "/text", "body", "/amount", "exists", BooleanNode.FALSE)).path("verdict").asText().equals("FAIL"), "non-JSON must not satisfy a body assertion"));
            test("http-duplicate-json-cannot-pass-body-assertion", () -> require(run(scenario("duplicate", "/duplicate", "body", "/missing", "exists", BooleanNode.FALSE)).path("verdict").asText().equals("FAIL"), "duplicate JSON must fail body checks"));
            test("http-null-is-valid-json", () -> require(run(scenario("nullbody", "/null", "body", "", "type", TextNode.valueOf("null"))).path("verdict").asText().equals("PASS"), "JSON null is valid"));
            test("run-unique-is-independent-per-variable", () -> {
                ObjectNode scenario = scenario("unique", "/echo", "body", "/first", "ne", Util.obj().put("$var", "second"));
                scenario.put("cleanupPolicy", "RESET_TEST_DATABASE");
                scenario.with("variables").set("first", Util.obj().put("generator", "runUnique").put("prefix", "T"));
                scenario.with("variables").set("second", Util.obj().put("generator", "runUnique").put("prefix", "T"));
                ObjectNode request = (ObjectNode)scenario.path("steps").path(0).path("request");
                request.put("method", "POST").set("body", Util.obj().set("first", Util.obj().put("$var", "first")));
                require(run(scenario).path("verdict").asText().equals("PASS"), "two runUnique variables must differ");
            });
            test("cancel-does-not-reopen-finished-job", () -> {
                try (RegressionEngine engine = engine()) {
                    ObjectNode result = Util.obj().put("id", "finished").put("state", "FINISHED").put("verdict", "PASS");
                    Class<?> jobType = Class.forName("cn.bank.devtool.regression.RegressionEngine$Job");
                    java.lang.reflect.Constructor<?> constructor = jobType.getDeclaredConstructor(ObjectNode.class, JsonNode.class, JsonNode.class, Map.class);
                    constructor.setAccessible(true);
                    Object job = constructor.newInstance(result, Util.obj(), environment(), Util.mapOf());
                    Field jobs = RegressionEngine.class.getDeclaredField("jobs"); jobs.setAccessible(true);
                    ((ConcurrentMap)jobs.get(engine)).put("finished", job);
                    JsonNode cancelled = engine.cancel("finished");
                    require(cancelled.path("state").asText().equals("FINISHED") && cancelled.path("verdict").asText().equals("PASS"), "finished state is final");
                }
            });
            test("batch-is-rejected-before-any-http-when-invalid", () -> {
                try (RegressionEngine engine = engine()) {
                    engine.environment(environment());
                    engine.scenario(scenario("good", "/json", "status", "", "eq", IntNode.valueOf(200)));
                    int before = calls.get();
                    try { engine.batch(Util.listOf("good", "missing"), "testenv", "batch", Util.mapOf()); throw new AssertionError("missing scenario accepted"); }
                    catch (IllegalArgumentException | NoSuchElementException expected) { }
                    require(engine.list("runs").isEmpty() && calls.get() == before, "no partial batch should be launched");
                }
            });
            test("junit-includes-cleanup-step-results", () -> {
                ObjectNode run = report();
                String xml = new String(RegressionReports.junit(run), StandardCharsets.UTF_8);
                require(xml.contains("cleanup-repro"), "cleanup testcase missing from JUnit");
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(Util.bytes(xml)));
            });
            test("junit-name-is-valid-xml", () -> {
                ObjectNode run = report().put("name", "bad\u0001name <&\"");
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(RegressionReports.junit(run)));
            });
            test("html-includes-cleanup-step-results", () -> require(new String(RegressionReports.html(report()), StandardCharsets.UTF_8).contains("cleanup-repro</td>"), "cleanup table row missing"));
            test("credentials-not-persisted-in-run", () -> {
                try (RegressionEngine engine = engine()) {
                    ObjectNode env = environment().put("authMode", "TOKEN"); engine.environment(env);
                    engine.scenario(scenario("secretcheck", "/json", "status", "", "eq", IntNode.valueOf(200)));
                    JsonNode launched = engine.start("secretcheck", "testenv", "secretrequest", Util.mapOf("token", "TEST_SECRET_123"), null);
                    JsonNode done = await(engine, launched.path("id").asText());
                    require(!Util.pretty(done).contains("TEST_SECRET_123"), "credential leaked");
                }
            });
            test("target-prefix-enforced", () -> {
                TargetPolicy policy = new TargetPolicy(origin);
                ObjectNode env = environment().put("pathPrefix", "/allowed");
                expectInvalid(() -> policy.uri(env, Util.obj().put("path", "/allowed-sibling")));
            });
            test("all-generated-manifest-hashes-match", () -> {
                Map<String, byte[]> generated = new GeneratorService().generate(fixture("production-safe-spec"));
                JsonNode manifest = Util.parse(generated.get("generation-manifest.json"));
                for (Iterator<Map.Entry<String, JsonNode>> entries = manifest.path("files").fields(); entries.hasNext();) {
                    Map.Entry<String, JsonNode> entry = entries.next();
                    require(generated.containsKey(entry.getKey()) && Util.sha(generated.get(entry.getKey())).equals(entry.getValue().asText()), "manifest hash mismatch: " + entry.getKey());
                }
            });
            test("source-scan-verifies-intact-generated-project", () -> {
                Map<String, byte[]> generated = new GeneratorService().generate(fixture("contract-v2-spec"));
                JsonNode doc = new SourceContractService().scan(ZipFiles.write(generated));
                require(doc.at("/x-coverage/mode").asText().equals("verified-generated-contract"), "intact generated sources must be verified");
                require(doc.at("/x-coverage/complete").asBoolean(), "coverage incomplete");
            });
            test("source-scan-does-not-trust-stale-document-after-source-edit", () -> {
                Map<String, byte[]> generated = new GeneratorService().generate(fixture("contract-v2-spec"));
                String controller = "src/main/java/com/bank/contract/controller/ContractController.java";
                String before = new String(generated.get(controller), StandardCharsets.UTF_8);
                String after = before.replace("/contracts", "/changed-contracts");
                require(!before.equals(after), "controller edit must be effective");
                generated.put(controller, Util.bytes(after));
                JsonNode doc = new SourceContractService().scan(ZipFiles.write(generated));
                require(!doc.at("/x-coverage/mode").asText().equals("verified-generated-contract"), "changed source must not be trusted by stale manifest");
                require(Util.pretty(doc.path("paths")).contains("/changed-contracts"), "actual changed mappings must be scanned");
            });
            test("source-scan-retains-numeric-constraints", () -> {
                String java = "package example; import org.springframework.web.bind.annotation.*; import jakarta.validation.constraints.*; @RestController class Bounds { @GetMapping(\"/bounds\") public Integer get(@RequestParam @Min(1) @Max(3) Integer count) { return count; } }";
                JsonNode doc = new SourceContractService().scan(ZipFiles.write(Util.mapOf("src/main/java/example/Bounds.java", Util.bytes(java))));
                JsonNode schema = doc.at("/paths/~1bounds/get/parameters/0/schema");
                require(schema.path("minimum").asInt() == 1 && schema.path("maximum").asInt() == 3, "source constraints missing");
            });
            for (String format : new String[]{"xlsx", "docx"}) {
                test("requirement-template-roundtrip-" + format, () -> {
                    byte[] template = new RequirementTemplates().create(format);
                    Spec imported = new RequirementImporter().load(template, "requirements." + format);
                    require(imported.fields().size() == 2, "template fields missing");
                    require(new GeneratorService().generate(imported).containsKey("openapi.json"), "imported template cannot generate");
                });
            }
            test("generated-partial-contract-has-complete-supported-coverage", () -> {
                JsonNode prepared = new ContractService().prepare(GeneratedContract.create(fixture("contract-v2-spec")));
                require(prepared.at("/x-coverage/complete").asBoolean(), Util.pretty(prepared.path("x-coverage")));
            });
            test("required-anyof-rejects-metadata-only-object", () -> require(new SampleValidator().validateSchema(requiredSchema(), Util.obj().put("id", 1)).path("verdict").asText().equals("FAIL"), "id-only object accepted"));
            test("required-anyof-accepts-either-business-field", () -> {
                for (String name : new String[]{"first", "second"}) {
                    require(new SampleValidator().validateSchema(requiredSchema(), Util.obj().put("id", 1).put(name, "X")).path("verdict").asText().equals("PASS"), "valid alternative rejected");
                }
            });
            test("mock-required-anyof-respects-property-budget", () -> {
                ObjectNode schema = requiredSchema().put("maxProperties", 2);
                Set<String> issues = new LinkedHashSet<>();
                JsonNode sample = new MockSynthesizer().sample(schema, issues, 0, new int[]{0});
                require(issues.isEmpty() && sample.size() == 2 && sample.has("id") && (sample.has("first") || sample.has("second")), "synthesized object failed anyOf or property budget");
            });
            test("unsupported-anyof-is-never-reported-pass", () -> {
                ObjectNode schema = requiredSchema();
                ((ObjectNode)schema.path("anyOf").path(0)).put("type", "object");
                require(new SampleValidator().validateSchema(schema, Util.obj().put("id", 1).put("first", "X")).path("verdict").asText().equals("INCOMPLETE"), "unsupported composition was trusted");
            });
        } finally { server.stop(0); }
        long failed = results.stream().filter(r -> !Boolean.TRUE.equals(r.get("passed"))).count();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tests", results.size()); report.put("passed", results.size() - failed); report.put("failed", failed); report.put("results", results);
        Files.write(root.resolve(args[1]), Util.bytes(Util.pretty(report)));
        System.out.println("Tests=" + results.size() + " Passed=" + (results.size() - failed) + " Failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static ObjectNode fixtureJson(String name) throws Exception {
        return (ObjectNode)Util.parse(Files.readAllBytes(root.resolve("src/main/resources/static/examples/" + name + ".json")));
    }
    private static Spec fixture(String name) throws Exception { return SpecParser.read(Util.bytes(Util.pretty(fixtureJson(name)))); }
    private static ObjectNode environment() { return Util.obj().put("id", "testenv").put("name", "loopback test").put("baseUrl", origin).put("pathPrefix", "/").put("authMode", "NONE"); }
    private static RegressionEngine engine() throws Exception {
        return new RegressionEngine(Files.createDirectories(root.resolve("build/test-data/" + UUID.randomUUID())), origin);
    }
    private static ObjectNode scenario(String id, String path, String source, String pointer, String op, JsonNode expected) {
        ObjectNode scenario = Util.obj().put("schemaVersion", 1).put("id", id).put("name", id);
        ObjectNode step = scenario.putArray("steps").addObject().put("id", "stepone").put("name", "HTTP assertion");
        step.putObject("request").put("method", "GET").put("path", path);
        ObjectNode assertion = step.putArray("assertions").addObject().put("source", source).put("op", op);
        if (source.equals("body")) assertion.put("pointer", pointer);
        if (expected != null) assertion.set("expected", expected);
        return scenario;
    }
    private static JsonNode run(ObjectNode scenario) throws Exception {
        try (RegressionEngine engine = engine()) {
            engine.environment(environment()); engine.scenario(scenario);
            JsonNode launched = engine.start(scenario.path("id").asText(), "testenv", "req" + tempIds.incrementAndGet(), Util.mapOf(), null);
            return await(engine, launched.path("id").asText());
        }
    }
    private static JsonNode await(RegressionEngine engine, String id) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            JsonNode current = engine.get("runs", id);
            if (current.path("state").asText().equals("FINISHED")) return current;
            Thread.sleep(15);
        }
        throw new AssertionError("HTTP scenario timed out");
    }
    private static ObjectNode report() {
        ObjectNode run = Util.obj().put("id", "report").put("name", "report").put("state", "FINISHED").put("verdict", "FAIL").put("cleanupStatus", "FAILED");
        run.putArray("steps").addObject().put("name", "main").put("state", "FINISHED").put("verdict", "PASS");
        run.putArray("cleanupSteps").addObject().put("name", "cleanup-repro").put("state", "FINISHED").put("verdict", "FAIL");
        return run;
    }
    private static ObjectNode requiredSchema() {
        ObjectNode schema = Util.obj().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("id").put("type", "integer");
        properties.putObject("first").put("type", "string");
        properties.putObject("second").put("type", "string");
        schema.putArray("required").add("id");
        ArrayNode anyOf = schema.putArray("anyOf");
        anyOf.addObject().putArray("required").add("first");
        anyOf.addObject().putArray("required").add("second");
        return schema;
    }
    private static void test(String name, Test test) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("name", name);
        try { test.run(); result.put("passed", true); }
        catch (Throwable ex) { result.put("passed", false); result.put("error", ex.toString()); System.out.println("FAIL " + name + ": " + ex); }
        results.add(result);
    }
    private static byte[] readAll(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void expectInvalid(Test action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid input was accepted");
    }
}
