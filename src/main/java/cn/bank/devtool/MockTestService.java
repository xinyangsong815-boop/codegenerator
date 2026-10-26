package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

public final class MockTestService {
    private static final int MAX_DEPTH = 14;
    private static final int MAX_NODES = 20000;
    private static final int MAX_STRING = 4096;
    private static final int MAX_ARRAY = 100;
    private static final int MAX_BODY = 1048576;
    private static final int MAX_OPERATIONS = 500;
    private static final int MAX_CASES = 5000;
    private static final int MAX_REPORT = 5242880;
    private static final String SCOPE = "仅合成数据和已声明契约；不调用银行后台。Mock不校验请求，不模拟鉴权、交易或幂等性；负例需人工核对。";
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList("get", "post", "put", "patch", "delete", "head", "options", "trace"));

    public JsonNode generate(JsonNode contract) {
        ContractService contracts = new ContractService();
        ObjectNode prepared = contracts.prepare(contract);
        MockTestService.State state = new MockTestService.State(prepared);

        for (JsonNode i : prepared.path("x-coverage").path("issues")) {
            state.issue(i.asText());
        }

        List<String> paths = keys(prepared.path("paths"));
        Collections.sort(paths);

        label270:
        for (String path : paths) {
            Set<String> pathIssues = new LinkedHashSet<>();
            JsonNode item = this.resolve(prepared.path("paths").path(path), state.doc, new LinkedHashSet<>(), 0, new int[]{0}, pathIssues);

            for (String issue : pathIssues) {
                state.issue(issue);
            }

            for (String method : keys(item)) {
                if (METHODS.contains(method.toLowerCase(Locale.ROOT))) {
                    if (method.equals(method.toLowerCase(Locale.ROOT))) {
                        JsonNode op = item.path(method);
                        state.currentInterface = InterfaceDescriptor.describe(prepared, method, path);
                        Set<String> requestIssues = new LinkedHashSet<>();
                        MockTestService.RequestData request = this.request(state, method, path, item, op, requestIssues);

                        for (String i : requestIssues) {
                            state.issue(i);
                        }

                        Set<String> responseIssues = new LinkedHashSet<>();
                        JsonNode responses = this.resolve(op.path("responses"), state.doc, new LinkedHashSet<>(), 0, new int[]{0}, responseIssues);

                        for (String issue : responseIssues) {
                            state.issue(issue);
                        }

                        List<Integer> statuses = new ArrayList<>();

                        for (String status : keys(responses)) {
                            if (status.matches("[1-5][0-9]{2}")) {
                                statuses.add(Integer.valueOf(status));
                            } else {
                                state.issue("未生成非显式状态码：" + line(method) + " " + line(path) + " " + line(status));
                            }
                        }

                        Collections.sort(statuses);
                        if (statuses.isEmpty()) {
                            state.issue("接口没有显式100至599响应：" + line(method) + " " + line(path));
                        }

                        Integer normalStatus = null;

                        for (Integer statusx : statuses) {
                            if (statusx >= 200 && statusx < 300) {
                                normalStatus = statusx;
                                break;
                            }
                        }

                        if (normalStatus == null) {
                            state.issue("接口没有声明2xx成功状态，不能选择默认Mock：" + line(method) + " " + line(path));
                        }

                        boolean normalAvailable = false;

                        for (Integer statusxx : statuses) {
                            if (state.operations.size() >= 500) {
                                state.budget("operations 500");
                                break label270;
                            }

                            Set<String> issues = new LinkedHashSet<>(pathIssues);
                            issues.addAll(responseIssues);
                            if (method.equals("trace")) {
                                issues.add("TRACE在本地Mock服务器不提供");
                            }

                            if (statusxx < 200) {
                                issues.add("100至199属于临时响应，本地Mock不能作为最终响应提供");
                            }

                            if (!safeContractPath(path)) {
                                issues.add("接口路径不安全或超过4096字符");
                            }

                            JsonNode response = this.resolve(responses.path(statusxx.toString()), state.doc, new LinkedHashSet<>(), 0, new int[]{0}, issues);
                            boolean empty = method.equals("head") || statusxx < 200 || statusxx == 204 || statusxx == 205 || statusxx == 304;
                            JsonNode body = NullNode.instance;
                            String bodyText = "";
                            if (!empty) {
                                JsonNode source = ContentTypes.match(response.path("content"), "application/json", issues).path("schema");
                                if (source.isMissingNode()) {
                                    issues.add("响应没有application/json Schema");
                                } else {
                                    JsonNode schema = this.resolvedSchema(source, state, issues);
                                    if (issues.isEmpty()) {
                                        body = this.sample(schema, issues, 0, state.nodes);
                                    }

                                    if (issues.isEmpty()) {
                                        bodyText = compact(body);
                                        if (Util.bytes(bodyText).length > 1048576) {
                                            issues.add("响应超过1MiB budget");
                                            body = NullNode.instance;
                                            bodyText = "";
                                        }
                                    }
                                }
                            }

                            JsonNode validation;
                            if (issues.isEmpty()) {
                                if (!empty || !method.equals("head") && statusxx != 304) {
                                    validation = new SampleValidator().validate(state.doc, method, path, statusxx.toString(), "application/json", bodyText);
                                } else {
                                    validation = emptyValidation(state.doc);
                                }
                            } else {
                                validation = incomplete(issues);
                            }

                            boolean available = issues.isEmpty() && validation.path("verdict").asText().equals("PASS");
                            ObjectNode out = Util.obj();
                            out.put("method", method.toUpperCase(Locale.ROOT));
                            out.put("path", path);
                            out.put("status", statusxx);
                            out.put("default", normalStatus != null && statusxx.equals(normalStatus));
                            out.put("media", "application/json");
                            out.put("summary", state.currentInterface.path("summary").asText());
                            out.set("interface", state.currentInterface.deepCopy());
                            out.put("operationKey", method.toUpperCase(Locale.ROOT) + " " + path);
                            out.put("responseKey", method.toUpperCase(Locale.ROOT) + " " + path + " #" + statusxx);
                            out.put("responseDescription", line(response.path("description").asText()));
                            out.put("requestComplete", requestIssues.isEmpty());
                            out.set("requestIssues", array(requestIssues));
                            out.set("body", body);
                            out.put("bodyText", bodyText);
                            out.set("validation", validation);
                            out.set("issues", array(issues));
                            out.put("available", available);
                            out.set("request", request.request.deepCopy());
                            if (!state.add(state.operations, out, 500)) {
                                break label270;
                            }

                            for (String i : issues) {
                                state.issue(i);
                            }

                            for (JsonNode i : validation.path("coverage").path("issues")) {
                                state.issue(i.asText());
                            }

                            if (normalStatus != null && statusxx.equals(normalStatus)) {
                                normalAvailable = available;
                            }

                            if (!statusxx.equals(normalStatus)) {
                                state.caseAdd("CONTRACT_ONLY", "CONTRACT_ONLY", "显式响应变体；静态Mock返回该变体，不说明业务请求会触发它。", request.request, statusxx, available);
                            }
                        }

                        if (normalStatus != null) {
                            String assertion = requestIssues.isEmpty() ? "CONTRACT_ONLY" : "REVIEW_REQUIRED";
                            state.caseAdd(
                                "NORMAL",
                                assertion,
                                requestIssues.isEmpty() ? "合成的契约请求；只验证已声明2xx响应。" : "请求合成覆盖不完整：" + join(requestIssues),
                                request.request,
                                normalStatus,
                                normalAvailable
                            );
                        }

                        for (String issue : requestIssues) {
                            state.caseAdd("CONTRACT_ONLY", "REVIEW_REQUIRED", issue, request.request, null, normalAvailable);
                        }

                        this.negativeCases(state, request, normalAvailable);
                        if (state.exhausted) {
                            break label270;
                        }
                    } else {
                        state.issue("HTTP方法键必须小写：" + line(method));
                    }
                } else if (!Arrays.asList("parameters", "summary", "description", "servers", "$ref").contains(method) && !method.startsWith("x-")) {
                    state.issue("不支持的HTTP方法：" + line(method));
                }
            }
        }

        ObjectNode coverage = state.report.putObject("coverage");
        coverage.put("complete", state.issues.isEmpty());
        coverage.set("issues", array(state.issues));
        state.report.put("scope", "仅合成数据和已声明契约；不调用银行后台。Mock不校验请求，不模拟鉴权、交易或幂等性；负例需人工核对。");
        boolean failed = false;
        int availablex = 0;
        Set<String> identities = new HashSet<>();

        for (JsonNode o : state.operations) {
            identities.add(o.path("operationKey").asText());
            if (o.path("available").asBoolean()) {
                availablex++;
            }

            if (o.path("validation").path("verdict").asText().equals("FAIL")) {
                failed = true;
            }
        }

        state.report.put("verdict", failed ? "FAIL" : (state.issues.isEmpty() ? "PASS" : "INCOMPLETE"));
        state.report
            .putObject("metrics")
            .put("interfaces", identities.size())
            .put("responses", state.operations.size())
            .put("available", availablex)
            .put("cases", state.cases.size());
        if (Util.bytes(Util.pretty(state.report)).length > 5242880) {
            throw new IllegalStateException("报告序列化超过5MiB budget");
        } else {
            return state.report;
        }
    }

    private MockTestService.RequestData request(MockTestService.State state, String method, String contractPath, JsonNode item, JsonNode op, Set<String> issues) {
        ObjectNode r = Util.obj();
        r.put("method", method.toUpperCase(Locale.ROOT));
        r.put("contractPath", contractPath);
        r.put("path", safePath(contractPath));
        r.putObject("query");
        r.putObject("headers");
        r.put("bodyText", "");
        MockTestService.RequestData result = new MockTestService.RequestData(r);
        Map<String, JsonNode> parameters = new LinkedHashMap<>();

        for (JsonNode list : Arrays.asList(item.path("parameters"), op.path("parameters"))) {
            if (!list.isMissingNode() && !list.isArray()) {
                issues.add("parameters必须为数组");
            } else {
                for (JsonNode raw : list) {
                    JsonNode p = this.resolve(raw, state.doc, new LinkedHashSet<>(), 0, new int[]{0}, issues);
                    parameters.put(p.path("in").asText() + ":" + p.path("name").asText(), p);
                }
            }
        }

        String actual = contractPath;

        for (JsonNode p : parameters.values()) {
            String name = p.path("name").asText();
            String in = p.path("in").asText();
            boolean required = p.path("required").asBoolean() || in.equals("path");
            if (name.isEmpty() || name.length() > 256 || containsControl(name)) {
                issues.add("参数名称不安全");
            } else if (!Arrays.asList("path", "query", "header").contains(in)) {
                issues.add("参数serialization不支持位置：" + line(in));
            } else {
                String expectedStyle = in.equals("query") ? "form" : "simple";
                if ((!p.has("style") || p.path("style").asText().equals(expectedStyle))
                    && (!p.has("allowReserved") || !p.path("allowReserved").asBoolean())
                    && (!p.has("allowEmptyValue") || !p.path("allowEmptyValue").asBoolean())
                    && !p.has("content")) {
                    Set<String> schemaIssues = new LinkedHashSet<>();
                    JsonNode schema = this.resolvedSchema(p.path("schema"), state, schemaIssues);
                    issues.addAll(schemaIssues);
                    if (schemaIssues.isEmpty()) {
                        String type = schema.path("type").asText();
                        if (type.equals("array")
                            && in.equals("query")
                            && (!p.has("explode") || p.path("explode").asBoolean())
                            && Arrays.asList("string", "integer", "number", "boolean").contains(schema.path("items").path("type").asText())) {
                            Set<String> local = new LinkedHashSet<>();
                            JsonNode value = this.sample(schema, local, 0, state.nodes);
                            issues.addAll(local);
                            if (local.isEmpty()) {
                                ((ObjectNode)r.path("query")).set(name, value);
                                result.params.add(new MockTestService.Param(name, in, required, schema));
                            }
                        } else if (!Arrays.asList("string", "integer", "number", "boolean").contains(type)) {
                            issues.add("参数serialization不支持object/array或未知类型：" + line(name));
                        } else {
                            Set<String> local = new LinkedHashSet<>();
                            JsonNode value = this.sample(schema, local, 0, state.nodes);
                            issues.addAll(local);
                            if (local.isEmpty()) {
                                if (!in.equals("header")
                                    || name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                                        && !name.equalsIgnoreCase("host")
                                        && !name.equalsIgnoreCase("content-length")
                                        && !name.equalsIgnoreCase("transfer-encoding")) {
                                    String text = scalar(value);
                                    if (!in.equals("header") || !containsControl(text) && text.length() <= 4096) {
                                        result.params.add(new MockTestService.Param(name, in, required, schema));
                                        if (in.equals("path")) {
                                            if (!actual.contains("{" + name + "}")) {
                                                issues.add("路径参数没有匹配占位符：" + line(name));
                                            }

                                            actual = actual.replace("{" + name + "}", encode(text));
                                        } else {
                                            ((ObjectNode)r.path(in.equals("query") ? "query" : "headers")).put(name, text);
                                        }
                                    } else {
                                        issues.add("Header值含控制字符或超过限制：" + line(name));
                                    }
                                } else {
                                    issues.add("Header名称不安全或属于传输控制：" + line(name));
                                }
                            }
                        }
                    }
                } else {
                    issues.add("参数serialization不支持style/content/allowReserved：" + line(name));
                }
            }
        }

        if (actual.matches(".*[{}].*")) {
            issues.add("路径占位符缺少可合成参数");
            actual = actual.replaceAll("\\{[^{}]*\\}", "SYNTHETIC");
        }

        if (!safeContractPath(actual)) {
            issues.add("合成请求路径含不安全路径段，必须人工处理");
        }

        r.put("path", safePath(actual));
        JsonNode requestBody = this.resolve(op.path("requestBody"), state.doc, new LinkedHashSet<>(), 0, new int[]{0}, issues);
        if (!requestBody.isMissingNode()) {
            result.bodyRequired = requestBody.path("required").asBoolean();
            JsonNode s = ContentTypes.match(requestBody.path("content"), "application/json", issues).path("schema");
            if (s.isMissingNode()) {
                issues.add("请求体serialization仅支持application/json Schema");
            } else {
                Set<String> local = new LinkedHashSet<>();
                result.bodySchema = this.resolvedSchema(s, state, local);
                if (local.isEmpty()) {
                    result.body = this.sample(result.bodySchema, local, 0, state.nodes);
                }

                issues.addAll(local);
                if (local.isEmpty()) {
                    String b = compact(result.body);
                    if (Util.bytes(b).length <= 1048576) {
                        r.put("bodyText", b);
                        ((ObjectNode)r.path("headers")).put("Content-Type", "application/json");
                    } else {
                        issues.add("请求体超过1MiB budget");
                    }
                }
            }
        }

        return result;
    }

    private JsonNode resolvedSchema(JsonNode source, MockTestService.State state, Set<String> issues) {
        JsonNode bounded = this.resolve(source, state.doc, new LinkedHashSet<>(), 0, new int[]{0}, issues);
        JsonNode schema = new ContractService().schema(bounded, state.doc, issues);
        this.inspectSchema(schema, issues, 0, new int[]{0});
        return schema;
    }

    private JsonNode resolve(JsonNode n, JsonNode root, Set<String> trail, int depth, int[] nodes, Set<String> issues) {
        if (depth > 48 || ++nodes[0] > 20000) {
            issues.add("Schema/ref展开超过depth 48或nodes 20000 budget");
            return Util.obj();
        } else if (n.isObject() && n.has("$ref")) {
            String ref = n.path("$ref").asText();
            if (!ref.startsWith("#/")) {
                issues.add("拒绝外部引用");
                return Util.obj();
            } else if (!trail.add(ref)) {
                issues.add("递归Schema引用：" + line(ref));
                return Util.obj();
            } else {
                JsonNode out = this.resolve(root.at(ref.substring(1)), root, trail, depth + 1, nodes, issues);
                trail.remove(ref);
                return out;
            }
        } else if (n.isObject()) {
            ObjectNode out = Util.obj();
            Iterator<Entry<String, JsonNode>> it = n.fields();

            while (it.hasNext()) {
                Entry<String, JsonNode> e = it.next();
                out.set(e.getKey(), this.resolve(e.getValue(), root, new LinkedHashSet<>(trail), depth + 1, nodes, issues));
                if (nodes[0] > 20000) {
                    break;
                }
            }

            return out;
        } else if (!n.isArray()) {
            return n.deepCopy();
        } else {
            ArrayNode out = Util.JSON.createArrayNode();

            for (JsonNode value : n) {
                out.add(this.resolve(value, root, new LinkedHashSet<>(trail), depth + 1, nodes, issues));
                if (nodes[0] > 20000) {
                    break;
                }
            }

            return out;
        }
    }

    private void inspectSchema(JsonNode schema, Set<String> issues, int depth, int[] nodes) {
        if (depth > 14 || ++nodes[0] > 20000) {
            issues.add("Schema超过depth 14或nodes 20000 budget");
        } else if (!schema.isObject()) {
            issues.add("Schema必须是对象");
        } else {
            String type = schema.path("type").asText();
            if (!Arrays.asList("object", "array", "string", "integer", "number", "boolean").contains(type)) {
                issues.add("Schema缺少明确类型或类型不受支持：" + line(type));
            }

            CapabilityRegistry.schema(schema, "mock", "", issues);
            this.checkCountBounds(schema, "minLength", "maxLength", 4096, issues);
            this.checkCountBounds(schema, "minItems", "maxItems", 100, issues);
            BigDecimal min = this.numberBound(schema, "minimum", issues);
            BigDecimal max = this.numberBound(schema, "maximum", issues);
            if (min != null && max != null && min.compareTo(max) > 0) {
                issues.add("minimum/maximum不一致");
            }

            if (type.equals("integer")
                && (
                    min != null && min.scale() > 0 && min.stripTrailingZeros().scale() > 0
                        || max != null && max.scale() > 0 && max.stripTrailingZeros().scale() > 0
                )) {
                issues.add("integer边界不是整数，需人工核对");
            }

            String format = schema.path("format").asText();
            if (!format.isEmpty() && !Arrays.asList("date", "date-time", "local-date-time", "int32", "int64", "float", "double").contains(format)) {
                issues.add("未支持Schema格式：" + line(format));
            }

            if (format.equals("int32")
                && (min != null && min.compareTo(BigDecimal.valueOf(2147483647L)) > 0 || max != null && max.compareTo(BigDecimal.valueOf(-2147483648L)) < 0)) {
                issues.add("int32边界不可表示");
            }

            if (format.equals("int64")
                && (
                    min != null && min.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
                        || max != null && max.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0
                )) {
                issues.add("int64边界不可表示");
            }

            if (schema.has("enum")) {
                if (schema.path("enum").isArray() && !schema.path("enum").isEmpty()) {
                    for (JsonNode e : schema.path("enum")) {
                        this.inspectValue(e, issues, 0, new int[]{0});
                    }
                } else {
                    issues.add("enum为空或不是数组");
                }
            }

            if (type.equals("object")) {
                if (schema.has("properties") && !schema.path("properties").isObject()) {
                    issues.add("properties必须是对象");
                }

                for (String key : keys(schema.path("properties"))) {
                    if (key.length() > 4096 || containsControl(key)) {
                        issues.add("属性名含控制字符或超过4096 budget");
                    }

                    this.inspectSchema(schema.path("properties").path(key), issues, depth + 1, nodes);
                }

                if (schema.has("required") && !schema.path("required").isArray()) {
                    issues.add("required必须是数组");
                }

                for (JsonNode f : schema.path("required")) {
                    if (!f.isTextual() || !schema.path("properties").has(f.asText())) {
                        issues.add("required字段未声明properties");
                    }
                }

                if (schema.path("additionalProperties").isObject()) {
                    this.inspectSchema(schema.path("additionalProperties"), issues, depth + 1, nodes);
                }
            }

            if (type.equals("array")) {
                this.inspectSchema(schema.path("items"), issues, depth + 1, nodes);
            }
        }
    }

    private void checkCountBounds(JsonNode s, String minKey, String maxKey, int limit, Set<String> issues) {
        BigInteger min = this.countBound(s, minKey, limit, issues);
        BigInteger max = this.countBound(s, maxKey, limit, issues);
        if (min != null && max != null && min.compareTo(max) > 0) {
            issues.add(minKey + "/" + maxKey + "不一致");
        }
    }

    private BigInteger countBound(JsonNode s, String key, int limit, Set<String> issues) {
        if (!s.has(key)) {
            return null;
        } else {
            JsonNode n = s.get(key);
            if (n.isIntegralNumber()
                && n.bigIntegerValue().signum() >= 0
                && n.canConvertToInt()
                && (!key.startsWith("min") || n.bigIntegerValue().compareTo(BigInteger.valueOf(limit)) <= 0)) {
                return n.bigIntegerValue();
            } else {
                issues.add(key + "非法或超过" + limit + " budget");
                return null;
            }
        }
    }

    private BigDecimal numberBound(JsonNode s, String key, Set<String> issues) {
        if (!s.has(key)) {
            return null;
        } else {
            JsonNode n = s.get(key);
            if (n.isNumber() && n.asText().length() <= 128) {
                try {
                    BigDecimal value = n.decimalValue();
                    if (Math.abs((long)value.scale()) <= 38L && value.precision() <= 38 && value.abs().compareTo(new BigDecimal("1e38")) <= 0) {
                        return value;
                    } else {
                        issues.add(key + "超过数值budget");
                        return null;
                    }
                } catch (Exception var6) {
                    issues.add(key + "不可表示");
                    return null;
                }
            } else {
                issues.add(key + "不是可处理的数字");
                return null;
            }
        }
    }

    private void inspectValue(JsonNode value, Set<String> issues, int depth, int[] nodes) {
        if (depth <= 14 && ++nodes[0] <= 20000) {
            if (value.isTextual() && value.asText().codePointCount(0, value.asText().length()) > 4096) {
                issues.add("enum字符串超过4096 budget");
            }

            if (value.isNumber() && (value.asText().length() > 128 || !Double.isFinite(value.doubleValue()))) {
                issues.add("enum数字超过budget");
            }

            if (value.isArray() && value.size() > 100) {
                issues.add("enum数组超过100 budget");
            }

            if (value.isContainerNode()) {
                for (JsonNode x : value) {
                    this.inspectValue(x, issues, depth + 1, nodes);
                }
            }
        } else {
            issues.add("enum值超过depth/nodes budget");
        }
    }

    private JsonNode sample(JsonNode s, Set<String> issues, int depth, int[] nodes) {
        return new MockSynthesizer().sample(s, issues, depth, nodes);
    }

    private void negativeCases(MockTestService.State state, MockTestService.RequestData data, boolean available) {
        for (MockTestService.Param p : data.params) {
            if (state.exhausted) {
                return;
            }

            if (p.required && p.in.equals("query")) {
                ObjectNode missing = data.request.deepCopy();
                ((ObjectNode)missing.path("query")).remove(p.name);
                state.caseAdd("MISSING_REQUIRED_QUERY", "REVIEW_REQUIRED", "缺少必填query " + p.name + "；契约未声明该输入的业务状态码。", missing, null, available);
            }

            if (!p.in.equals("path")) {
                ObjectNode wrong = data.request.deepCopy();
                this.setParameter(
                    wrong, p, p.schema.path("type").asText().equals("string") ? "123" : "NOT_A_" + p.schema.path("type").asText().toUpperCase(Locale.ROOT)
                );
                state.caseAdd("WRONG_TYPE", "REVIEW_REQUIRED", "参数 " + p.name + " 错误类型；HTTP文本到类型的转换规则需人工核对。", wrong, null, available);
                if (p.schema.has("minimum") || p.schema.has("maximum")) {
                    if (p.schema.has("minimum")) {
                        this.parameterBoundary(state, data, p, "minimum", available);
                    }

                    if (p.schema.has("maximum")) {
                        this.parameterBoundary(state, data, p, "maximum", available);
                    }
                }

                if (p.schema.has("minLength") || p.schema.has("maxLength")) {
                    if (p.schema.has("minLength")) {
                        this.parameterStringBoundary(state, data, p, "minLength", available);
                    }

                    if (p.schema.has("maxLength")) {
                        this.parameterStringBoundary(state, data, p, "maxLength", available);
                    }
                }
            }
        }

        if (data.bodySchema != null && data.body != null) {
            if (data.bodyRequired) {
                ObjectNode missing = data.request.deepCopy();
                missing.put("bodyText", "");
                state.caseAdd("MISSING_REQUIRED_BODY", "REVIEW_REQUIRED", "缺少必填请求体；预期状态需人工定义。", missing, null, available);
            }

            this.bodyNegative(state, data, data.bodySchema, data.body, "", available, 0);
        }
    }

    private void setParameter(ObjectNode r, MockTestService.Param p, String value) {
        ((ObjectNode)r.path(p.in.equals("query") ? "query" : "headers")).put(p.name, value);
    }

    private void parameterBoundary(MockTestService.State state, MockTestService.RequestData data, MockTestService.Param p, String key, boolean available) {
        ObjectNode r = data.request.deepCopy();
        BigDecimal value = p.schema.get(key).decimalValue().add(key.equals("minimum") ? BigDecimal.ONE.negate() : BigDecimal.ONE);
        this.setParameter(r, p, value.toPlainString());
        state.caseAdd("NUMERIC_BOUNDARY", "REVIEW_REQUIRED", p.name + " 超出 " + key + "；不推定错误码。", r, null, available);
    }

    private void parameterStringBoundary(MockTestService.State state, MockTestService.RequestData data, MockTestService.Param p, String key, boolean available) {
        long countLong = p.schema.get(key).asLong() + (key.equals("minLength") ? -1 : 1);
        int count = (int)Math.min(4097L, countLong);
        if (count >= 0) {
            if (count > 4096) {
                state.caseAdd("STRING_BOUNDARY", "REVIEW_REQUIRED", p.name + " 超长负例超过4096预算，请人工构造。", data.request, null, available);
            } else {
                ObjectNode r = data.request.deepCopy();
                this.setParameter(r, p, repeat(count));
                state.caseAdd("STRING_BOUNDARY", "REVIEW_REQUIRED", p.name + " 超出 " + key + "；不推定错误码。", r, null, available);
            }
        }
    }

    private void bodyNegative(
        MockTestService.State state, MockTestService.RequestData data, JsonNode schema, JsonNode value, String pointer, boolean available, int depth
    ) {
        if (depth <= 14 && !state.exhausted) {
            if (schema.path("type").asText().equals("object") && value.isObject()) {
                for (JsonNode field : schema.path("required")) {
                    if (state.exhausted) {
                        return;
                    }

                    JsonNode mutated = data.body.deepCopy();
                    JsonNode parent = pointer.isEmpty() ? mutated : mutated.at(pointer);
                    if (parent.isObject()) {
                        ((ObjectNode)parent).remove(field.asText());
                    }

                    this.bodyCase(state, data, mutated, "MISSING_REQUIRED_BODY_FIELD", "缺少必填请求字段 " + pointer + "/" + field.asText(), available);
                }

                for (String field : keys(schema.path("properties"))) {
                    this.bodyNegative(
                        state, data, schema.path("properties").path(field), value.path(field), pointer + "/" + Util.ptr(field), available, depth + 1
                    );
                }
            }

            if (schema.path("type").asText().equals("array") && value.isArray() && !value.isEmpty()) {
                this.bodyNegative(state, data, schema.path("items"), value.get(0), pointer + "/0", available, depth + 1);
            }

            JsonNode wrong = (JsonNode)(schema.path("type").asText().equals("string") ? IntNode.valueOf(123) : TextNode.valueOf("WRONG_TYPE"));
            this.bodyCase(state, data, this.replace(data.body, pointer, wrong), "BODY_WRONG_TYPE", "请求字段 " + pointer + " 类型不匹配", available);

            for (String bound : Arrays.asList("minimum", "maximum")) {
                if (schema.has(bound)) {
                    BigDecimal n = schema.get(bound).decimalValue().add(bound.equals("minimum") ? BigDecimal.ONE.negate() : BigDecimal.ONE);
                    JsonNode next = (JsonNode)(schema.path("type").asText().equals("integer")
                        ? BigIntegerNode.valueOf(n.toBigInteger())
                        : DecimalNode.valueOf(n));
                    this.bodyCase(state, data, this.replace(data.body, pointer, next), "BODY_NUMERIC_BOUNDARY", "请求字段 " + pointer + " 超出 " + bound, available);
                }
            }

            for (String boundx : Arrays.asList("minLength", "maxLength")) {
                if (schema.has(boundx)) {
                    long nLong = schema.get(boundx).asLong() + (boundx.equals("minLength") ? -1 : 1);
                    int n = (int)Math.min(4097L, nLong);
                    if (n >= 0 && n <= 4096) {
                        this.bodyCase(
                            state,
                            data,
                            this.replace(data.body, pointer, TextNode.valueOf(repeat(n))),
                            "BODY_STRING_BOUNDARY",
                            "请求字段 " + pointer + " 超出 " + boundx,
                            available
                        );
                    } else if (n > 4096) {
                        state.caseAdd("BODY_STRING_BOUNDARY", "REVIEW_REQUIRED", "请求字段 " + pointer + " 超长负例超过4096预算，请人工构造。", data.request, null, available);
                    }
                }
            }
        }
    }

    private void bodyCase(MockTestService.State state, MockTestService.RequestData data, JsonNode mutated, String kind, String note, boolean available) {
        if (!state.exhausted) {
            ObjectNode r = data.request.deepCopy();
            String body = compact(mutated);
            if (Util.bytes(body).length > 1048576) {
                state.issue("请求负例超过1MiB budget");
            } else {
                r.put("bodyText", body);
                state.caseAdd(kind, "REVIEW_REQUIRED", note + "；预期状态及业务断言需人工核对，Mock不校验请求。", r, null, available);
            }
        }
    }

    private JsonNode replace(JsonNode root, String pointer, JsonNode value) {
        if (pointer.isEmpty()) {
            return value;
        } else {
            JsonNode out = root.deepCopy();
            int slash = pointer.lastIndexOf(47);
            JsonNode parent = slash == 0 ? out : out.at(pointer.substring(0, slash));
            String key = pointer.substring(slash + 1).replace("~1", "/").replace("~0", "~");
            if (parent.isObject()) {
                ((ObjectNode)parent).set(key, value);
            } else if (parent.isArray()) {
                try {
                    ((ArrayNode)parent).set(Integer.parseInt(key), value);
                } catch (NumberFormatException var9) {
                }
            }

            return out;
        }
    }

    public byte[] bundle(JsonNode report) {
        if (Util.bytes(Util.pretty(report)).length > 5242880) {
            throw new IllegalArgumentException("报告超过5MiB budget");
        } else {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("report.json", Util.bytes(compact(report)));
            files.put("cases.json", Util.bytes(compact(report.path("cases"))));
            files.put("responses.json", Util.bytes(compact(report.path("operations"))));
            StringBuilder http = new StringBuilder("# 仅本机静态Mock；须替换已创建的配置UUID。负例仅作人工检查清单。\n# Mock不校验请求，不模拟鉴权、交易或幂等性，不自动执行银行后台。\n\n");

            for (JsonNode c : report.path("cases")) {
                http.append("### ").append(line(c.path("id").asText())).append(" ").append(line(c.path("kind").asText())).append('\n');
                http.append("# ").append(line(c.path("note").asText())).append('\n');
                if (c.path("kind").asText().equals("NORMAL") && !c.path("assertion").asText().equals("REVIEW_REQUIRED") && c.path("mockAvailable").asBoolean()) {
                    JsonNode r = c.path("request");
                    String method = r.path("method").asText();
                    if (METHODS.contains(method.toLowerCase(Locale.ROOT)) && !method.equalsIgnoreCase("TRACE")) {
                        http.append(method.toUpperCase(Locale.ROOT))
                            .append(" http://127.0.0.1:8790/mock/REPLACE_PROFILE_UUID")
                            .append(requestPath(r))
                            .append(" HTTP/1.1\n");

                        for (String header : keys(r.path("headers"))) {
                            String value = r.path("headers").path(header).asText();
                            if (header.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                                && !header.equalsIgnoreCase("host")
                                && !header.equalsIgnoreCase("content-length")
                                && !header.equalsIgnoreCase("transfer-encoding")
                                && !containsControl(value)) {
                                http.append(header).append(": ").append(value).append('\n');
                            }
                        }

                        String body = r.path("bodyText").asText();
                        if (!body.isEmpty()) {
                            try {
                                JsonNode parsed = Util.JSON.readTree(body);
                                if (parsed != null) {
                                    http.append('\n').append(compact(parsed)).append('\n');
                                }
                            } catch (Exception var11) {
                                http.append("# 请求体无法解析，未输出\n");
                            }
                        }

                        http.append('\n');
                        if (Util.bytes(http.toString()).length > 5242880) {
                            throw new IllegalArgumentException("requests.http超过5MiB budget");
                        }
                    } else {
                        http.append("# HTTP方法不可用\n\n");
                    }
                } else {
                    http.append("# REVIEW_REQUIRED：仅说明，不提供可自动发送的负例或不完整请求。\n\n");
                }
            }

            files.put("requests.http", Util.bytes(http.toString()));
            files.put(
                "README.md",
                Util.bytes(
                    "# 离线合成Mock与契约用例\n\n所有数据按类型、枚举和边界合成，忽略example/default；不表示真实客户数据。仅解析当前文件本地引用。\n\nreport.json包含覆盖问题、可用响应与请求；responses.json包含所有显式100至599响应。默认响应为首个已声明2xx，不自动补造错误码；default/通配状态需人工核对。HEAD、1xx、204、205、304无响应体；TRACE不可用。仅PASS且无生成问题的响应可提供Mock。\n\ncases.json的NORMAL仅声明契约，CONTRACT_ONLY说明响应变体；所有负例为REVIEW_REQUIRED，不预设HTTP错误码。requests.http仅指向http://127.0.0.1:8790/mock/REPLACE_PROFILE_UUID，请人工替换已创建的配置UUID；负例仅以注释展示。\n\nMock返回静态合成响应，不校验请求，不模拟鉴权、交易、幂等性，不自动执行银行后台。不读取契约servers。请求参数object/array、特殊序列化及未支持Schema会明确标记覆盖不完整。请人工补充业务期望及检查断言。\n"
                )
            );
            return ZipFiles.write(files);
        }
    }

    public static String requestPath(JsonNode request) {
        StringBuilder path = new StringBuilder(safePath(request.path("path").asText()));
        boolean first = true;

        for (String key : keys(request.path("query"))) {
            JsonNode value = request.path("query").get(key);
            List<JsonNode> values = new ArrayList<>();
            if (value.isArray()) {
                value.forEach(values::add);
            } else {
                values.add(value);
            }

            for (JsonNode part : values) {
                path.append((char)(first ? '?' : '&'));
                first = false;
                path.append(encode(key)).append('=').append(encode(part.asText()));
            }
        }

        return path.toString();
    }

    private static JsonNode emptyValidation(JsonNode doc) {
        Set<String> issues = new LinkedHashSet<>();

        for (JsonNode x : doc.path("x-coverage").path("issues")) {
            issues.add(x.asText());
        }

        ObjectNode r = Util.obj();
        r.put("verdict", issues.isEmpty() ? "PASS" : "INCOMPLETE");
        r.putArray("findings");
        ObjectNode c = r.putObject("coverage");
        c.put("complete", issues.isEmpty());
        c.set("issues", array(issues));
        r.put("scope", "HEAD/304按HTTP语义无响应体；不验证请求或业务行为");
        return r;
    }

    private static JsonNode incomplete(Set<String> issues) {
        ObjectNode r = Util.obj();
        r.put("verdict", "INCOMPLETE");
        r.putArray("findings");
        ObjectNode c = r.putObject("coverage");
        c.put("complete", false);
        c.set("issues", array(issues));
        return r;
    }

    private static List<String> keys(JsonNode n) {
        List<String> keys = new ArrayList<>();
        if (n.isObject()) {
            Iterator<String> it = n.fieldNames();

            while (it.hasNext()) {
                keys.add(it.next());
            }
        }

        return keys;
    }

    private static ArrayNode array(Collection<String> values) {
        ArrayNode a = Util.JSON.createArrayNode();

        for (String s : values) {
            a.add(line(s));
        }

        return a;
    }

    private static String compact(JsonNode value) {
        try {
            return Util.JSON.writeValueAsString(value);
        } catch (Exception var2) {
            throw new IllegalArgumentException("JSON序列化失败", var2);
        }
    }

    private static String scalar(JsonNode n) {
        return n.isTextual() ? n.asText() : compact(n);
    }

    private static String repeat(int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, 'X');
        return new String(chars);
    }

    private static String join(Collection<String> issues) {
        StringBuilder b = new StringBuilder();

        for (String i : issues) {
            if (b.length() > 0) {
                b.append("；");
            }

            b.append(i);
            if (b.length() > 512) {
                break;
            }
        }

        return line(b.toString());
    }

    private static String line(String s) {
        StringBuilder b = new StringBuilder();

        for (int i = 0; i < s.length() && b.length() < 512; i++) {
            char c = s.charAt(i);
            b.append(!Character.isISOControl(c) && c != 8232 && c != 8233 ? c : ' ');
        }

        return b.toString();
    }

    private static boolean containsControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i)) || s.charAt(i) == 8232 || s.charAt(i) == 8233) {
                return true;
            }
        }

        return false;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception var2) {
            throw new IllegalStateException(var2);
        }
    }

    private static boolean safeContractPath(String p) {
        if (p.startsWith("/") && p.length() <= 4096 && !containsControl(p) && !p.contains("?") && !p.contains("#") && !p.contains("\\") && !p.contains("//")) {
            for (String part : p.split("/", -1)) {
                if (part.replaceAll("(?i)%2e", ".").equals(".") || part.replaceAll("(?i)%2e", ".").equals("..")) {
                    return false;
                }
            }

            return true;
        } else {
            return false;
        }
    }

    private static String safePath(String p) {
        if (!safeContractPath(p)) {
            p = "/SYNTHETIC";
        }

        if (!p.startsWith("/")) {
            p = "/" + p;
        }

        StringBuilder out = new StringBuilder();
        int i = 0;

        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '/' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c);
                i++;
            } else if (c == '%' && i + 2 < p.length() && p.substring(i + 1, i + 3).matches("[0-9A-Fa-f]{2}")) {
                out.append(p.substring(i, i + 3));
                i += 3;
            } else {
                int cp = p.codePointAt(i);
                out.append(encode(new String(Character.toChars(cp))));
                i += Character.charCount(cp);
            }
        }

        String result = out.toString();
        if (result.startsWith("//")) {
            result = "/" + result.replaceFirst("^/+", "");
        }

        String var6 = result.replace("/../", "/%2E%2E/").replace("/./", "/%2E/");
        if (var6.endsWith("/..")) {
            var6 = var6.substring(0, var6.length() - 2) + "%2E%2E";
        }

        if (var6.endsWith("/.")) {
            var6 = var6.substring(0, var6.length() - 1) + "%2E";
        }

        return var6;
    }

    private static final class Param {
        String name;
        String in;
        boolean required;
        JsonNode schema;

        Param(String n, String i, boolean r, JsonNode s) {
            this.name = n;
            this.in = i;
            this.required = r;
            this.schema = s;
        }
    }

    private static final class RequestData {
        final ObjectNode request;
        final List<MockTestService.Param> params = new ArrayList<>();
        JsonNode bodySchema;
        JsonNode body;
        boolean bodyRequired;

        RequestData(ObjectNode r) {
            this.request = r;
        }
    }

    private static final class State {
        final JsonNode doc;
        final ObjectNode report = Util.obj();
        final ArrayNode operations = this.report.putArray("operations");
        final ArrayNode cases = this.report.putArray("cases");
        JsonNode currentInterface = Util.obj();
        final Set<String> issues = new LinkedHashSet<>();
        final int[] nodes = new int[]{0};
        long bytes = 262144L;
        boolean exhausted;
        int sequence;

        State(JsonNode d) {
            this.doc = d;
        }

        void issue(String s) {
            if (this.issues.size() < 120) {
                this.issues.add(MockTestService.line(s));
            } else {
                this.issues.add("覆盖问题超过120项，仅展示前120项");
            }
        }

        void budget(String s) {
            this.issue("生成budget达到限制：" + s);
            this.exhausted = true;
        }

        boolean add(ArrayNode target, ObjectNode value, int max) {
            if (this.exhausted) {
                return false;
            } else if (target.size() >= max) {
                this.budget(target == this.operations ? "operations 500" : "cases 5000");
                return false;
            } else {
                String pretty = Util.pretty(value);
                long length = Util.bytes(pretty).length + 32;

                for (int i = 0; i < pretty.length(); i++) {
                    if (pretty.charAt(i) == '\n') {
                        length += 8L;
                    }
                }

                if (this.bytes + length + 2L > 5242880L) {
                    this.budget("total serialized report 5MiB");
                    return false;
                } else {
                    this.bytes += length + 2L;
                    target.add(value);
                    return true;
                }
            }
        }

        void caseAdd(String kind, String assertion, String note, ObjectNode request, Integer status, boolean available) {
            if (!this.exhausted) {
                ObjectNode c = Util.obj();
                c.put("id", String.format(Locale.ROOT, "CASE-%04d", ++this.sequence));
                c.put("operationKey", this.currentInterface.path("operationKey").asText());
                c.put("summary", this.currentInterface.path("summary").asText());
                c.put("operationId", this.currentInterface.path("operationId").asText());
                c.put("kind", kind);
                c.put("assertion", assertion);
                c.put("note", MockTestService.line(note));
                c.set("request", request.deepCopy());
                if (status != null) {
                    c.put("expectedStatus", status);
                }

                c.put("mockAvailable", available);
                this.add(this.cases, c, 5000);
            }
        }
    }
}
