package cn.bank.devtool.regression;

import cn.bank.devtool.MockTestService;
import cn.bank.devtool.ContractService;
import cn.bank.devtool.Util;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class ContractScenarioService {
    public JsonNode draft(String contractId, JsonNode contract, String method, String path) {
        if (method == null || path == null) throw new IllegalArgumentException("必须提供HTTP方法和接口路径");
        ScenarioRules.path(path);
        String m = method.toUpperCase(Locale.ROOT);
        Set<String> issues = new java.util.LinkedHashSet<String>();
        JsonNode item = new ContractService().schema(contract.path("paths").path(path), contract, issues);
        if (!item.has(m.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("接口不存在");
        } else {
            JsonNode report = new MockTestService().generate(contract);
            JsonNode selected = null;

            for (JsonNode op : report.path("operations")) {
                if (op.path("method").asText().equals(m) && op.path("path").asText().equals(path) && op.path("default").asBoolean()) {
                    selected = op;
                    break;
                }
            }

            if (selected == null) {
                throw new IllegalArgumentException("契约没有可生成的显式成功响应，请手工建立场景");
            } else {
                ObjectNode s = Util.obj()
                    .put("schemaVersion", 1)
                    .put("id", "draft-" + UUID.randomUUID().toString().substring(0, 8))
                    .put("name", "接口草稿 " + m + " " + (path.length() > 70 ? path.substring(0, 70) : path))
                    .put("contractId", contractId)
                    .put("draft", true)
                    .put("failurePolicy", "STOP")
                    .put("cleanupPolicy", Arrays.asList("GET", "HEAD", "OPTIONS").contains(m) ? "NONE" : "RESET_TEST_DATABASE");
                s.putObject("variables");
                ObjectNode step = s.putArray("steps").addObject().put("id", "request1").put("name", "待补充业务预期");
                ObjectNode r = step.putObject("request").put("method", m).put("path", path).put("contractPath", path);
                JsonNode sample = selected.path("request");
                ObjectNode pv = Util.obj();
                String[] parts = path.split("/", -1);
                String[] actual = sample.path("path").asText().split("/", -1);

                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].matches("\\{[^{}]+\\}")) {
                        String value = i < actual.length ? actual[i] : "SYNTHETIC";

                        try {
                            value = URLDecoder.decode(value, "UTF-8");
                        } catch (Exception var18) {
                            value = "SYNTHETIC";
                        }

                        pv.put(parts[i].substring(1, parts[i].length() - 1), value);
                    }
                }

                if (pv.size() > 0) {
                    r.set("pathVariables", pv);
                }

                r.set("query", sample.path("query").deepCopy());
                ObjectNode headers = Util.obj();
                sample.path("headers").fields().forEachRemaining(e -> {
                    try {
                        ScenarioRules.header((String)e.getKey());
                        headers.set((String)e.getKey(), (JsonNode)e.getValue());
                    } catch (IllegalArgumentException var3) {
                    }
                });
                r.set("headers", headers);
                String body = sample.path("bodyText").asText();
                if (!body.isEmpty()) {
                    r.set("body", Util.parse(Util.bytes(body)));
                }

                step.putArray("assertions").addObject().put("source", "status").put("op", "eq").put("expected", selected.path("status").asInt());
                return s;
            }
        }
    }

    public JsonNode impact(JsonNode oldDoc, JsonNode newDoc, List<JsonNode> scenarios, String baseline, String target) {
        ObjectNode r = Util.obj().put("baselineId", baseline).put("targetId", target).put("scope", "按接口、请求字段、响应断言和提取字段检查直接依赖；不能覆盖所有间接业务变化");
        ArrayNode rows = r.putArray("scenarios");
        Set<String> issues = new java.util.LinkedHashSet<String>();
        ContractService contracts = new ContractService();

        for (JsonNode s : scenarios) {
            ArrayNode affected = Util.JSON.createArrayNode();

            for (String phase : Arrays.asList("steps", "cleanupSteps")) {
            for (JsonNode step : s.path(phase)) {
                JsonNode req = step.path("request");
                String path = req.path("contractPath").asText(req.path("path").asText());
                String method = req.path("method").asText().toLowerCase(Locale.ROOT);
                JsonNode oldItem = contracts.schema(oldDoc.path("paths").path(path), oldDoc, issues);
                JsonNode newItem = contracts.schema(newDoc.path("paths").path(path), newDoc, issues);
                JsonNode oldOp = oldItem.path(method);
                JsonNode newOp = newItem.path(method);
                boolean changed = !Util.sha(Util.bytes(Util.pretty(this.dependency(oldDoc, oldOp, oldItem, issues))))
                    .equals(Util.sha(Util.bytes(Util.pretty(this.dependency(newDoc, newOp, newItem, issues)))));
                if (changed) {
                    ObjectNode a = affected.addObject()
                        .put("stepId", step.path("id").asText()).put("phase", phase)
                        .put("method", method.toUpperCase(Locale.ROOT))
                        .put("path", path)
                        .put("reason", oldOp.isMissingNode() ? "基线未声明该接口，需复核" : (newOp.isMissingNode() ? "目标接口已删除" : "接口或引用结构变化，需复核步骤参数和断言"));
                    ArrayNode pointers = a.putArray("responsePointers");

                    for (JsonNode x : step.path("assertions")) {
                        if (x.path("source").asText().equals("body")) {
                            pointers.add(x.path("pointer").asText());
                        }
                    }

                    for (JsonNode xx : step.path("extract")) {
                        if (xx.path("source").asText().equals("body")) {
                            pointers.add(xx.path("pointer").asText());
                        }
                    }

                    ArrayNode fields = a.putArray("requestFields");
                    req.path("body").fieldNames().forEachRemaining(fields::add);
                }
            }

            }
            if (affected.size() > 0) {
                ObjectNode row = rows.addObject().put("scenarioId", s.path("id").asText()).put("name", s.path("name").asText());
                row.set("steps", affected);
            }
        }

        r.put("affectedCount", rows.size()).put("scannedCount", scenarios.size()).put("totalCount", scenarios.size()).put("complete", issues.isEmpty());
        ArrayNode coverage = r.putArray("issues");
        for (String issue : issues) coverage.add(issue);
        return r;
    }

    private JsonNode dependency(JsonNode doc, JsonNode op, JsonNode item, Set<String> issues) {
        ObjectNode out = Util.obj();
        out.set("security", doc.path("security"));
        out.set("securitySchemes", doc.path("components").path("securitySchemes"));
        out.set("businessSemantics", doc.path("x-business-semantics"));
        JsonNode servers = op.has("servers") ? op.path("servers") : item.has("servers") ? item.path("servers") : doc.path("servers");
        if (servers.isMissingNode() || servers.isArray() && servers.isEmpty()) {
            out.putArray("effectiveServers").addObject().put("url", "/");
        } else out.set("effectiveServers", servers);
        ContractService contracts = new ContractService();
        out.set("operation", contracts.schema(op, doc, issues));
        out.set("pathParameters", contracts.schema(item.path("parameters"), doc, issues));
        return out;
    }
}
