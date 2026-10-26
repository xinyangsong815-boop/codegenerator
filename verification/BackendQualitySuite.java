package cn.bank.devtool;

import cn.bank.devtool.regression.RegressionReports;
import cn.bank.devtool.regression.ContractScenarioService;
import cn.bank.devtool.regression.ScenarioRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Counterexamples for effective media mapping, strict scenario pointers and usable contract documents. */
public final class BackendQualitySuite {
    private interface Check { void run() throws Exception; }
    private static final List<ObjectNode> results = new ArrayList<ObjectNode>();

    public static void main(String[] args) throws Exception {
        check("request-exact-media-to-wildcard-is-widening", () -> {
            ObjectNode a = request("application/json", schema("string"));
            ObjectNode b = request("application/*", schema("string"));
            verdict("PASS", compare(a, b));
        });
        check("request-wildcard-to-exact-media-restricts", () -> {
            verdict("FAIL", compare(request("application/*", schema("string")), request("application/json", schema("string"))));
        });
        check("request-new-specific-media-must-compare-overridden-schema", () -> {
            ObjectNode a = request("application/*", schema("string"));
            ObjectNode b = a.deepCopy(); requestContent(b).putObject("application/json").set("schema", schema("integer"));
            verdict("FAIL", compare(a, b));
        });
        check("request-new-specific-media-compatible-schema-is-safe", () -> {
            ObjectNode a = request("*/*", schema("integer"));
            ObjectNode b = a.deepCopy(); requestContent(b).putObject("application/json").set("schema", schema("number"));
            verdict("PASS", compare(a, b));
        });
        check("request-type-wildcard-to-global-wildcard-is-widening", () -> verdict("PASS", compare(request("application/*", schema("integer")), request("*/*", schema("number")))));
        check("request-global-wildcard-to-type-wildcard-restricts", () -> verdict("FAIL", compare(request("*/*", schema("integer")), request("application/*", schema("integer")))));
        check("response-new-specific-media-must-compare-overridden-schema", () -> {
            ObjectNode a = response("application/*", schema("integer"));
            ObjectNode b = a.deepCopy(); responseContent(b).putObject("application/json").set("schema", schema("string"));
            verdict("FAIL", compare(a, b));
        });
        check("response-new-uncovered-media-needs-review", () -> {
            ObjectNode a = response("application/json", schema("object"));
            ObjectNode b = a.deepCopy(); responseContent(b).putObject("text/plain").set("schema", schema("string"));
            verdict("REVIEW_REQUIRED", compare(a, b));
        });
        check("media-finding-pointers-escape-slashes", () -> {
            JsonNode report = compare(request("application/json", schema("string")), request("application/json", schema("integer")));
            boolean found = false;
            for (JsonNode finding : report.path("findings")) if (finding.path("schemaPointer").asText().equals("/requestBody/content/application~1json/schema")) found = true;
            require(found, "media key must be a single escaped JSON Pointer token");
        });
        check("empty-json-response-is-a-failed-sample", () -> verdict("FAIL", sample(response("application/json", schema("object")), "200", "")));
        check("whitespace-json-response-is-a-failed-sample", () -> verdict("FAIL", sample(response("application/json", schema("object")), "200", " \n\t")));
        check("valid-json-sample-still-passes", () -> verdict("PASS", sample(response("application/json", schema("object")), "200", "{}")));
        check("sample-rejects-missing-method-as-invalid-input", () -> invalid(() -> new SampleValidator().validate(response("application/json", schema("object")), null, "/probe", "200", "application/json", "{}")));
        check("sample-rejects-missing-path-as-invalid-input", () -> invalid(() -> new SampleValidator().validate(response("application/json", schema("object")), "POST", null, "200", "application/json", "{}")));
        check("mock-205-does-not-generate-response-content", () -> {
            ObjectNode d = response("application/json", schema("object")); ObjectNode responses = (ObjectNode) d.at("/paths/~1probe/post/responses"); responses.set("205", responses.remove("200"));
            JsonNode op = new MockTestService().generate(d).path("operations").get(0);
            require(op.path("bodyText").asText().isEmpty() && op.path("available").asBoolean(), "205 must have an empty usable Mock");
        });
        check("mock-matches-json-media-case-insensitively", () -> require(new MockTestService().generate(response("Application/JSON", schema("object"))).path("operations").get(0).path("available").asBoolean(), "valid case-insensitive JSON media must be usable"));
        check("mock-and-description-match-request-json-media-case", () -> {
            ObjectNode shape = schema("object"); shape.putObject("properties").putObject("title").put("type", "string");
            ObjectNode d = request("Application/JSON", shape); ((ObjectNode) d.at("/paths/~1probe/post/requestBody")).put("required", true);
            JsonNode operation = new MockTestService().generate(d).path("operations").get(0);
            require(operation.path("requestComplete").asBoolean() && operation.path("request").path("bodyText").asText().contains("title"), "request media matching must agree with sample validation");
            require(InterfaceDescriptor.describe(d, "POST", "/probe").path("requestDefinition").path("bodyFields").size() == 1, "request fields missing from interface description");
        });
        for (final String status : Arrays.asList("101", "199", "204", "205", "304")) {
            check("no-content-status-" + status + "-rejects-body", () -> {
                ObjectNode d = response("application/json", schema("object"));
                ObjectNode responses = (ObjectNode) d.at("/paths/~1probe/post/responses");
                responses.set(status, responses.remove("200"));
                verdict("FAIL", sample(d, status, "{}"));
                verdict("PASS", sample(d, status, ""));
            });
        }
        check("head-response-accepts-empty-body-and-rejects-payload", () -> {
            ObjectNode d = response("application/json", schema("object")); ObjectNode path = (ObjectNode) d.at("/paths/~1probe"); path.set("head", path.remove("post"));
            verdict("PASS", new SampleValidator().validate(d, "HEAD", "/probe", "200", "application/json", ""));
            verdict("FAIL", new SampleValidator().validate(d, "HEAD", "/probe", "200", "application/json", "{}"));
        });
        for (final JsonNode invalid : Arrays.asList(Util.obj(), Util.JSON.createArrayNode(), Util.JSON.nullNode(), Util.JSON.getNodeFactory().numberNode(0))) {
            check("scenario-rejects-nonstring-assertion-pointer-" + invalid.getNodeType(), () -> {
                ObjectNode s = scenario(); ((ObjectNode) s.at("/steps/0/assertions/0")).set("pointer", invalid); invalid(() -> ScenarioRules.check(s));
            });
            check("scenario-rejects-nonstring-extract-pointer-" + invalid.getNodeType(), () -> {
                ObjectNode s = scenario(); ((ObjectNode) s.at("/steps/0")).putArray("extract").addObject().put("name", "result").put("source", "body").put("type", "object").set("pointer", invalid); invalid(() -> ScenarioRules.check(s));
            });
        }
        for (final JsonNode invalid : Arrays.asList(Util.JSON.createArrayNode(), Util.JSON.nullNode(), Util.JSON.getNodeFactory().textNode("ignored"))) {
            check("scenario-rejects-nonobject-pathVariables-" + invalid.getNodeType(), () -> {
                ObjectNode s = scenario(); ((ObjectNode) s.at("/steps/0/request")).set("pathVariables", invalid); invalid(() -> ScenarioRules.check(s));
            });
        }
        check("scenario-allows-explicit-root-pointer", () -> ScenarioRules.check(scenario()));
        check("document-includes-effective-server-and-variables", () -> {
            ObjectNode d = response("application/json", schema("object"));
            ObjectNode server = d.putArray("servers").addObject().put("url", "https://{region}.example.invalid/v2");
            server.putObject("variables").putObject("region").put("default", "east").putArray("enum").add("east").add("west");
            for (String text : documents(d)) require(text.contains("https://{region}.example.invalid/v2") && text.contains("east") && text.contains("west") && text.contains("有效服务地址"), "server access information missing");
        });
        check("document-displays-operation-server-overrides", () -> {
            ObjectNode d = response("application/json", schema("object"));
            d.putArray("servers").addObject().put("url", "https://root.example.invalid");
            ((ObjectNode) d.at("/paths/~1probe")).putArray("servers").addObject().put("url", "https://path.example.invalid");
            ((ObjectNode) d.at("/paths/~1probe/post")).putArray("servers").addObject().put("url", "https://operation.example.invalid");
            for (String text : documents(d)) require(text.contains("https://operation.example.invalid") && text.contains("Operation"), "operation server override missing");
        });
        check("document-includes-authentication-scheme-details", () -> {
            ObjectNode d = response("application/json", schema("object"));
            d.putArray("security").addObject().putArray("ApiToken");
            d.putObject("components").putObject("securitySchemes").putObject("ApiToken").put("type", "apiKey").put("in", "header").put("name", "X-Contract-Key");
            for (String text : documents(d)) require(text.contains("X-Contract-Key") && text.contains("securitySchemes"), "authentication scheme definition missing");
        });
        check("document-server-descriptions-are-escaped", () -> {
            ObjectNode d = response("application/json", schema("object")); d.putArray("servers").addObject().put("url", "/").put("description", "<script>bad()</script>");
            String html = text(ContractReports.html("quality", d));
            require(!html.contains("<script>") && html.contains("&lt;script&gt;"), "unescaped server description");
            String markdown = text(ContractReports.markdown("quality", d));
            for (String line : markdown.split("\n")) if (line.contains("<script>")) require(line.startsWith("    "), "Markdown server JSON must remain an indented code block");
        });
        check("interface-description-merges-header-overrides-ignoring-case", () -> {
            ObjectNode d = response("application/json", schema("object"));
            ((ObjectNode) d.at("/paths/~1probe")).putArray("parameters").addObject().put("in", "header").put("name", "X-Cursor").put("required", false).putObject("schema").put("type", "string");
            ((ObjectNode) d.at("/paths/~1probe/post")).putArray("parameters").addObject().put("in", "header").put("name", "x-cursor").put("required", true).putObject("schema").put("type", "string");
            JsonNode parameters = InterfaceDescriptor.describe(d, "POST", "/probe").path("requestDefinition").path("parameters");
            require(parameters.size() == 1 && parameters.get(0).path("required").asBoolean(), "header override must agree with generated documents");
        });
        check("impact-detects-effective-root-server-changes", () -> {
            ObjectNode a = response("application/json", schema("object")); a.putArray("servers").addObject().put("url", "https://old.example.invalid");
            ObjectNode b = a.deepCopy(); ((ObjectNode) b.at("/servers/0")).put("url", "https://new.example.invalid"); affected(1, a, b);
        });
        check("impact-ignores-root-server-change-hidden-by-operation-override", () -> {
            ObjectNode a = response("application/json", schema("object")); a.putArray("servers").addObject().put("url", "https://old.example.invalid");
            ((ObjectNode) a.at("/paths/~1probe/post")).putArray("servers").addObject().put("url", "https://fixed.example.invalid");
            ObjectNode b = a.deepCopy(); ((ObjectNode) b.at("/servers/0")).put("url", "https://new.example.invalid"); affected(0, a, b);
        });
        check("impact-detects-path-server-changes", () -> {
            ObjectNode a = response("application/json", schema("object")); ((ObjectNode) a.at("/paths/~1probe")).putArray("servers").addObject().put("url", "/old");
            ObjectNode b = a.deepCopy(); ((ObjectNode) b.at("/paths/~1probe/servers/0")).put("url", "/new"); affected(1, a, b);
        });
        check("impact-detects-operation-referenced-through-path-item", () -> {
            ObjectNode a = response("application/json", schema("object")); JsonNode item = ((ObjectNode) a.path("paths")).remove("/probe");
            a.putObject("x-path-items").set("probe", item); ((ObjectNode) a.path("paths")).putObject("/probe").put("$ref", "#/x-path-items/probe");
            ObjectNode b = a.deepCopy(); ((ObjectNode) b.at("/x-path-items/probe/post/responses/200")).put("description", "changed"); affected(1, a, b);
            require(new ContractScenarioService().draft("contract", a, "POST", "/probe").path("draft").asBoolean(), "referenced Path Item must support scenario draft generation");
        });
        check("impact-detects-global-business-semantics-change", () -> {
            ObjectNode a = response("application/json", schema("object")); a.putObject("x-business-semantics").put("amountUnit", "CNY");
            ObjectNode b = a.deepCopy(); ((ObjectNode) b.path("x-business-semantics")).put("amountUnit", "CNY-cent"); affected(1, a, b);
        });
        check("junit-does-not-pass-an-unfinished-step-marked-pass", () -> {
            ObjectNode run = report(); ((ObjectNode) run.at("/steps/0")).put("state", "DISPATCHING");
            require(RegressionReports.overall(run).equals("INCOMPLETE"), "unfinished execution must not pass");
            require(text(RegressionReports.junit(run)).contains("<error"), "JUnit must expose unfinished execution");
        });
        check("junit-does-not-pass-empty-successful-run", () -> {
            ObjectNode run = report(); run.putArray("steps");
            require(RegressionReports.overall(run).equals("INCOMPLETE"), "no executed assertions");
            require(text(RegressionReports.junit(run)).contains("<error"), "empty JUnit must not look successful");
        });
        check("junit-overall-agrees-with-failed-main-step", () -> {
            ObjectNode run = report(); ((ObjectNode) run.at("/steps/0")).put("verdict", "FAIL");
            require(RegressionReports.overall(run).equals("FAIL"), "main step failure must survive summary");
            require(text(RegressionReports.junit(run)).contains("<failure"), "main failure must be visible");
        });
        check("junit-successful-finished-step-still-passes", () -> {
            ObjectNode run = report(); require(RegressionReports.overall(run).equals("PASS"), "valid report");
            String xml = text(RegressionReports.junit(run)); require(xml.contains("failures=\"0\"") && xml.contains("errors=\"0\""), "valid JUnit");
        });
        check("junit-does-not-pass-missing-declared-steps", () -> {
            ObjectNode run = report(); run.putObject("scenarioSnapshot").putArray("steps").addObject().put("id", "one");
            ((ObjectNode) run.path("scenarioSnapshot")).withArray("steps").addObject().put("id", "two");
            require(RegressionReports.overall(run).equals("INCOMPLETE"), "incomplete execution plan");
            require(text(RegressionReports.junit(run)).contains("<error"), "missing planned test must be visible");
        });
        check("junit-does-not-pass-done-with-missing-planned-cleanup", () -> {
            ObjectNode run = cleanupPlan(); run.put("cleanupStatus", "DONE");
            require(RegressionReports.overall(run).equals("INCOMPLETE"), "DONE cannot hide absent cleanup execution");
            require(text(RegressionReports.junit(run)).contains("<error"), "missing planned cleanup must be visible");
        });
        check("junit-complete-declared-cleanup-still-passes", () -> {
            ObjectNode run = cleanupPlan(); run.put("cleanupStatus", "DONE");
            run.withArray("cleanupSteps").addObject().put("id", "clean").put("state", "FINISHED").put("verdict", "PASS");
            require(RegressionReports.overall(run).equals("PASS"), "all planned cleanup executed");
            String xml = text(RegressionReports.junit(run)); require(xml.contains("failures=\"0\"") && xml.contains("errors=\"0\""), "valid cleanup JUnit");
        });
        check("junit-manual-cleanup-confirmation-does-not-claim-automatic-steps", () -> {
            ObjectNode run = cleanupPlan(); run.put("cleanupStatus", "MANUALLY_CONFIRMED");
            require(RegressionReports.overall(run).equals("PASS"), "explicit manual confirmation remains supported");
        });
        check("junit-no-write-run-does-not-require-optional-cleanup-execution", () -> {
            ObjectNode run = cleanupPlan(); run.put("cleanupStatus", "NOT_REQUIRED");
            require(RegressionReports.overall(run).equals("PASS"), "no write dispatched, cleanup not required");
        });
        long failed = results.stream().filter(r -> !r.path("passed").asBoolean()).count();
        ObjectNode report = Util.obj().put("javaVersion", System.getProperty("java.version")).put("tests", results.size()).put("passed", results.size() - failed).put("failed", failed);
        report.set("results", Util.JSON.valueToTree(results)); Path output = Paths.get(args[0]).resolve(args[1]); Files.createDirectories(output.toAbsolutePath().getParent()); Files.write(output, Util.bytes(Util.pretty(report)));
        System.out.println("BackendQualitySuite tests=" + results.size() + " passed=" + (results.size() - failed) + " failed=" + failed); if (failed > 0) System.exit(1);
    }

    private static ObjectNode schema(String type) { return Util.obj().put("type", type); }
    private static ObjectNode response(String media, JsonNode schema) {
        ObjectNode d = Util.obj().put("openapi", "3.0.3"); d.putObject("info").put("title", "quality checks").put("version", "1");
        d.putObject("paths").putObject("/probe").putObject("post").putObject("responses").putObject("200").put("description", "ok").putObject("content").putObject(media).set("schema", schema); return d;
    }
    private static ObjectNode request(String media, JsonNode schema) { ObjectNode d = response("application/json", schema("object")); ((ObjectNode) d.at("/paths/~1probe/post")).putObject("requestBody").putObject("content").putObject(media).set("schema", schema); return d; }
    private static ObjectNode requestContent(JsonNode d) { return (ObjectNode) d.at("/paths/~1probe/post/requestBody/content"); }
    private static ObjectNode responseContent(JsonNode d) { return (ObjectNode) d.at("/paths/~1probe/post/responses/200/content"); }
    private static JsonNode compare(JsonNode a, JsonNode b) { return new CompareService().compare(a, b, Util.JSON.createArrayNode()); }
    private static JsonNode sample(JsonNode d, String status, String body) { return new SampleValidator().validate(d, "POST", "/probe", status, "application/json", body); }
    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private static List<String> documents(JsonNode d) { return Arrays.asList(text(ContractReports.markdown("quality", d)), text(ContractReports.html("quality", d))); }
    private static ObjectNode scenario() { ObjectNode s = Util.obj().put("schemaVersion", 1).put("id", "quality").put("name", "quality"); ObjectNode step = s.putArray("steps").addObject().put("id", "one").put("name", "one"); step.putObject("request").put("method", "GET").put("path", "/probe"); step.putArray("assertions").addObject().put("source", "body").put("pointer", "").put("op", "type").put("expected", "object"); return s; }
    private static ObjectNode report() { ObjectNode r = Util.obj().put("id", "quality").put("name", "quality").put("state", "FINISHED").put("verdict", "PASS").put("cleanupStatus", "NOT_REQUIRED"); r.putArray("steps").addObject().put("id", "one").put("name", "one").put("state", "FINISHED").put("verdict", "PASS"); r.putArray("cleanupSteps"); return r; }
    private static ObjectNode cleanupPlan() { ObjectNode r = report(); ObjectNode snapshot = r.putObject("scenarioSnapshot").put("cleanupPolicy", "HTTP_CLEANUP"); snapshot.putArray("steps").addObject().put("id", "one"); snapshot.putArray("cleanupSteps").addObject().put("id", "clean"); return r; }
    private static void affected(int count, JsonNode a, JsonNode b) { ObjectNode s = scenario(); ((ObjectNode) s.at("/steps/0/request")).put("method", "POST"); JsonNode result = new ContractScenarioService().impact(a, b, Arrays.<JsonNode>asList(s), "a", "b"); require(result.path("affectedCount").asInt() == count, "expected affected " + count + ": " + result); }
    private static void verdict(String expected, JsonNode r) { require(expected.equals(r.path("verdict").asText()), "expected " + expected + ": " + r); }
    private static void invalid(Check c) throws Exception { try { c.run(); throw new AssertionError("invalid input was accepted"); } catch (IllegalArgumentException expected) { } }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void check(String name, Check c) { ObjectNode r = Util.obj().put("name", name); try { c.run(); r.put("passed", true); } catch (Throwable ex) { r.put("passed", false).put("error", ex.toString()); System.out.println("FAIL " + name + ": " + ex); } results.add(r); }
}
