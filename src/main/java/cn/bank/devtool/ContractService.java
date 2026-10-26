package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

public final class ContractService {
    public ObjectNode prepare(JsonNode input) {
        if (input.isObject() && input.path("openapi").asText().matches("3\\.0\\.[0-3]") && input.path("paths").isObject()) {
            ObjectNode doc = (ObjectNode)input.deepCopy();
            Set<String> issues = new LinkedHashSet<>();
            if (doc.path("x-coverage").path("issues").isArray()) {
                doc.path("x-coverage").path("issues").forEach(x -> issues.add(x.asText()));
            }

            if (doc.path("x-coverage").has("complete") && !doc.path("x-coverage").path("complete").asBoolean() && issues.isEmpty()) {
                issues.add("导入文档声明覆盖不完整");
            }

            if (doc.path("paths").isEmpty()) {
                issues.add("文档没有接口");
            }

            int[] count = new int[]{0};
            this.walk(doc, doc, issues, 0, count);
            Set<String> verbs = new HashSet<>(Arrays.asList("get", "post", "put", "patch", "delete", "head", "options", "trace"));
            int operations = 0;
            Iterator<Entry<String, JsonNode>> paths = doc.path("paths").fields();

            while (paths.hasNext()) {
                Entry<String, JsonNode> path = paths.next();
                if (!path.getKey().startsWith("/")) {
                    throw new IllegalArgumentException("接口路径必须以/开头");
                }

                JsonNode item = path.getValue();
                if (!item.isObject()) {
                    throw new IllegalArgumentException("Path Item必须为对象");
                }

                Set<String> local = new LinkedHashSet<>();
                item = this.schema(item, doc, local);
                issues.addAll(local);
                auditParameters(item.path("parameters"), issues, path.getKey() + "/parameters");
                Iterator<String> keys = item.fieldNames();

                while (keys.hasNext()) {
                    String key = keys.next();
                    if (verbs.contains(key)) {
                        operations++;
                        if (!item.get(key).isObject() || !item.get(key).path("responses").isObject()) {
                            throw new IllegalArgumentException("Operation必须为对象并包含responses：" + path.getKey());
                        }
                        auditOperation(item.get(key), issues, key.toUpperCase(Locale.ROOT) + " " + path.getKey());
                    } else if (!Arrays.asList("$ref", "summary", "description", "parameters", "servers").contains(key) && !key.startsWith("x-")) {
                        throw new IllegalArgumentException("不支持的Path Item键（HTTP方法须小写）：" + key);
                    }
                }
            }

            if (operations == 0) {
                issues.add("没有有效HTTP接口，不能作为检查通过依据");
            }

            CapabilityRegistry.audit(doc, "contract", issues);
            if (count[0] > 100000) {
                throw new IllegalArgumentException("文档节点过多");
            } else {
                ObjectNode coverage = doc.with("x-coverage");
                coverage.put("complete", issues.isEmpty());
                ArrayNode arr = coverage.putArray("issues");

                for (String issue : issues) {
                    arr.add(issue);
                }

                return doc;
            }
        } else {
            throw new IllegalArgumentException("仅支持单文件OpenAPI 3.0.0至3.0.3 JSON，必须包含paths");
        }
    }

    private void walk(JsonNode n, JsonNode root, Set<String> issues, int depth, int[] count) {
        walk(n, root, issues, depth, count, "document");
    }

    private void walk(JsonNode n, JsonNode root, Set<String> issues, int depth, int[] count, String context) {
        if (context.equals("literal")) return;
        if (++count[0] > 100000 || depth > 80) throw new IllegalArgumentException("文档节点数量或深度超过限制");
        if (n.isObject()) {
            if (!context.equals("schemaMap") && !context.equals("exampleMap") && n.has("$ref")) reference(n, root);
            Iterator<Entry<String, JsonNode>> fields = n.fields();
            while (fields.hasNext()) { Entry<String, JsonNode> field = fields.next(); walk(field.getValue(), root, issues, depth + 1, count, childContext(context, field.getKey())); }
        } else if (n.isArray()) for (JsonNode child : n) walk(child, root, issues, depth + 1, count, context.equals("schemaArray") ? "schema" : context);
    }

    /** Example/default/enum values are user data, including literal $ref and keyword-like keys. */
    private static String childContext(String parent, String key) {
        if (parent.equals("schemaMap")) return "schema";
        if (parent.equals("exampleMap")) return "exampleObject";
        if (parent.equals("exampleObject") && key.equals("value")) return "literal";
        if (parent.equals("schema")) {
            if (Arrays.asList("example", "default", "enum").contains(key)) return "literal";
            if (key.equals("properties")) return "schemaMap";
            if (Arrays.asList("items", "additionalProperties", "not").contains(key)) return "schema";
            if (Arrays.asList("anyOf", "oneOf", "allOf").contains(key)) return "schemaArray";
        }
        if (key.equals("schema")) return "schema";
        if (key.equals("schemas")) return "schemaMap";
        if (key.equals("example")) return "literal";
        if (key.equals("examples")) return "exampleMap";
        return "document";
    }

    private static String reference(JsonNode n, JsonNode root) {
        String ref = n.path("$ref").asText();
        if (!n.path("$ref").isTextual() || !ref.startsWith("#/") || root.at(ref.substring(1)).isMissingNode()) throw new IllegalArgumentException("引用必须指向当前文档存在的 #/ 路径");
        return ref;
    }

    public ArrayNode index(JsonNode input) {
        ObjectNode doc = this.prepare(input);
        ArrayNode rows = Util.JSON.createArrayNode();
        Iterator<String> paths = doc.path("paths").fieldNames();

        while (paths.hasNext()) {
            String path = paths.next();
            Set<String> issues = new LinkedHashSet<>();
            JsonNode item = this.schema(doc.path("paths").path(path), doc, issues);

            for (String method : Arrays.asList("get", "post", "put", "patch", "delete", "head", "options", "trace")) {
                if (item.has(method)) {
                    ObjectNode row = rows.addObject()
                        .put("key", method.toUpperCase(Locale.ROOT) + " " + path)
                        .put("method", method.toUpperCase(Locale.ROOT))
                        .put("path", path);
                    ObjectNode operation = (ObjectNode) item.get(method).deepCopy();
                    Map<String, JsonNode> parameters = new java.util.LinkedHashMap<String, JsonNode>();
                    for (JsonNode parameter : item.path("parameters")) parameters.put(parameterKey(parameter), parameter);
                    for (JsonNode parameter : operation.path("parameters")) parameters.put(parameterKey(parameter), parameter);
                    if (!parameters.isEmpty()) { ArrayNode merged = operation.putArray("parameters"); for (JsonNode parameter : parameters.values()) merged.add(parameter); }
                    row.set("value", operation);
                    if (!issues.isEmpty()) { ArrayNode resolutionIssues = row.putArray("issues"); for (String issue : issues) resolutionIssues.add(issue); }
                }
            }
        }

        return rows;
    }

    /** Header names follow HTTP case-insensitive matching; query/path/cookie names retain case. */
    static String parameterKey(JsonNode parameter) {
        String location = parameter.path("in").asText();
        String name = parameter.path("name").asText();
        return location + ":" + (location.equals("header") ? name.toLowerCase(Locale.ROOT) : name);
    }

    /** Structural gaps must survive import so comparison, documentation and samples cannot claim PASS. */
    private static void auditOperation(JsonNode operation, Set<String> issues, String pointer) {
        auditParameters(operation.path("parameters"), issues, pointer + "/parameters");
        if (operation.has("requestBody")) {
            JsonNode body = operation.path("requestBody");
            if (!body.isObject()) issues.add("requestBody必须为对象：" + pointer);
            else {
                auditRequired(body, issues, pointer + "/requestBody");
                if (!body.path("content").isObject() || body.path("content").isEmpty()) issues.add("requestBody必须声明非空content：" + pointer);
                auditContent(body.path("content"), issues, pointer + "/requestBody/content");
            }
        }
        JsonNode responses = operation.path("responses"); boolean declared = false;
        Iterator<Entry<String,JsonNode>> entries = responses.fields();
        while (entries.hasNext()) {
            Entry<String,JsonNode> entry = entries.next(); String status = entry.getKey();
            if (status.startsWith("x-")) continue;
            declared = true;
            if (!status.equals("default") && !status.matches("[1-5]([0-9]{2}|XX)")) issues.add("响应状态码声明不合法：" + pointer + "/responses/" + status);
            JsonNode response = entry.getValue(); String location = pointer + "/responses/" + status;
            if (!response.isObject() || !response.path("description").isTextual()) issues.add("响应必须为对象并声明字符串description：" + location);
            if (response.has("content")) auditContent(response.path("content"), issues, location + "/content");
            if (response.has("headers")) {
                if (!response.path("headers").isObject()) issues.add("响应headers必须为对象：" + location);
                Set<String> names = new LinkedHashSet<String>();
                response.path("headers").fields().forEachRemaining(header -> {
                    String name = header.getKey().toLowerCase(Locale.ROOT); String hp = location + "/headers/" + header.getKey();
                    if (!names.add(name)) issues.add("响应头名称重复（忽略大小写）：" + hp);
                    if (!header.getValue().isObject()) issues.add("响应头必须为对象：" + hp);
                    auditRequired(header.getValue(), issues, hp);
                    if (header.getValue().has("content")) issues.add("响应头content编码暂未完整支持：" + hp);
                });
            }
        }
        if (!declared) issues.add("接口必须声明至少一个响应：" + pointer);
    }

    private static void auditParameters(JsonNode parameters, Set<String> issues, String pointer) {
        if (parameters.isMissingNode()) return;
        if (!parameters.isArray()) { issues.add("parameters必须为数组：" + pointer); return; }
        Set<String> names = new LinkedHashSet<String>();
        for (JsonNode parameter : parameters) {
            if (!parameter.isObject() || !parameter.path("name").isTextual() || parameter.path("name").asText().isEmpty()
                    || !Arrays.asList("query", "header", "path", "cookie").contains(parameter.path("in").asText())) issues.add("参数name或in声明不合法：" + pointer);
            if (!names.add(parameterKey(parameter))) issues.add("同一作用域参数重复：" + pointer + "/" + parameterKey(parameter));
            auditRequired(parameter, issues, pointer);
            if (parameter.path("in").asText().equals("path") && !parameter.path("required").asBoolean()) issues.add("path参数必须required=true：" + pointer);
            if (parameter.has("schema") == parameter.has("content")) issues.add("参数必须且只能声明schema或content之一：" + pointer);
            if (parameter.has("content")) issues.add("参数content编码暂未完整支持：" + pointer);
        }
    }

    private static void auditRequired(JsonNode value, Set<String> issues, String pointer) {
        if (value.has("required") && !value.path("required").isBoolean()) issues.add("required必须为布尔值：" + pointer);
    }

    private static void auditContent(JsonNode content, Set<String> issues, String pointer) {
        if (!content.isObject()) { issues.add("content必须为对象：" + pointer); return; }
        Set<String> names = new LinkedHashSet<String>();
        content.fields().forEachRemaining(entry -> {
            String media = ContentTypes.base(entry.getKey());
            if (!media.matches("(?:[a-z0-9!#$&^_.+-]+|\\*)/(?:[a-z0-9!#$&^_.+-]+|\\*)") || media.startsWith("*/") && !media.equals("*/*")) issues.add("媒体类型声明不合法：" + pointer + "/" + entry.getKey());
            if (!names.add(ContentTypes.normalize(entry.getKey()))) issues.add("媒体类型重复（忽略类型大小写）：" + pointer + "/" + entry.getKey());
            if (!entry.getValue().isObject()) issues.add("媒体类型定义必须为对象：" + pointer + "/" + entry.getKey());
        });
    }

    public JsonNode schema(JsonNode n, JsonNode root, Set<String> issues) {
        String context = n.path("type").isTextual() || n.has("properties") || n.has("items") || n.has("anyOf") ? "schema" : "document";
        return this.expand(n, root, issues, new HashSet<>(), 0, new int[]{0}, context);
    }

    private JsonNode expand(JsonNode n, JsonNode root, Set<String> issues, Set<String> trail, int depth, int[] nodes, String context) {
        if (context.equals("literal")) return n.deepCopy();
        if (depth > 24 || ++nodes[0] > 20000) {
            issues.add("Schema展开超过24层或20000节点预算");
            return Util.obj();
        } else if (n.isObject() && n.has("$ref") && !context.equals("schemaMap") && !context.equals("exampleMap")) {
            String ref = reference(n, root);
            if (!trail.add(ref)) {
                issues.add("递归引用未展开：" + ref);
                return Util.obj();
            } else {
                JsonNode r = this.expand(root.at(ref.substring(1)), root, issues, trail, depth + 1, nodes, ref.startsWith("#/components/schemas/") ? "schema" : context);
                trail.remove(ref);
                return r;
            }
        } else if (n.isObject()) {
            ObjectNode r = Util.obj();
            Iterator<Entry<String, JsonNode>> it = n.fields();

            while (it.hasNext()) {
                Entry<String, JsonNode> e = it.next();
                r.set(e.getKey(), this.expand(e.getValue(), root, issues, new HashSet<>(trail), depth + 1, nodes, childContext(context, e.getKey())));
                if (nodes[0] > 20000) {
                    break;
                }
            }

            return r;
        } else if (!n.isArray()) {
            return n.deepCopy();
        } else {
            ArrayNode r = Util.JSON.createArrayNode();

            for (JsonNode x : n) {
                r.add(this.expand(x, root, issues, new HashSet<>(trail), depth + 1, nodes, context.equals("schemaArray") ? "schema" : context));
                if (nodes[0] > 20000) {
                    break;
                }
            }

            return r;
        }
    }
}
