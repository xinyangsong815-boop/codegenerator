package cn.bank.devtool.regression;

import cn.bank.devtool.Util;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScenarioRules {
    private ScenarioRules() {
    }

    public static void check(JsonNode s) {
        if (Util.bytes(Util.pretty(s)).length > 1048576) {
            fail("场景最大1MiB");
        }

        keys(s, "schemaVersion,id,name,variables,failurePolicy,cleanupPolicy,steps,cleanupSteps,contractId,draft");
        if (!s.path("schemaVersion").isIntegralNumber() || !s.path("schemaVersion").canConvertToInt() || s.path("schemaVersion").intValue() != 1) {
            fail("schemaVersion必须为1");
        }

        rejectCredentialLiterals(s, 0);
        id(s.path("id").asText());
        text(s.path("name"), "场景名称", 100);
        if (s.path("draft").asBoolean()) {
            fail("草稿需完善预期结果并取消draft后才能执行");
        }

        if (!s.path("failurePolicy").asText("STOP").equals("STOP")) {
            fail("首版只支持STOP");
        }

        if (!Arrays.asList("NONE", "RESET_TEST_DATABASE", "HTTP_CLEANUP").contains(s.path("cleanupPolicy").asText("NONE"))) {
            fail("不支持的清理策略");
        }

        Set<String> vars = new HashSet<>();
        JsonNode initial = s.path("variables");
        if (!initial.isMissingNode() && !initial.isObject()) {
            fail("variables必须为对象");
        }

        initial.fields().forEachRemaining(e -> {
            id((String)e.getKey());
            vars.add((String)e.getKey());
            JsonNode v = (JsonNode)e.getValue();
            if (v.isObject()) {
                keys(v, "generator,prefix,maxLength");
                if (!v.path("generator").asText().equals("runUnique")) {
                    fail("未知变量生成器");
                }

                if (!v.path("prefix").asText("").matches("[A-Za-z0-9_-]{0,12}")) {
                    fail("变量前缀不合法");
                }

                if (v.has("maxLength") && (!v.path("maxLength").isIntegralNumber() || !v.path("maxLength").canConvertToInt())) fail("maxLength必须为32位整数");
                int n = v.path("maxLength").asInt(32);
                if (n < v.path("prefix").asText("").length() + 16 || n > 128) {
                    fail("生成编号长度至少需容纳16位运行标识");
                }
            } else if (!v.isValueNode()) {
                fail("初始变量必须是基本值或runUnique");
            }
        });
        JsonNode steps = s.path("steps");
        if (!steps.isArray() || steps.size() < 1 || steps.size() > 50) {
            fail("步骤数应为1至50");
        }

        Set<String> ids = new HashSet<>();

        for (JsonNode step : steps) {
            validateStep(step, vars, ids, false);
        }

        JsonNode clean = s.path("cleanupSteps");
        if (!clean.isMissingNode()) {
            if (!clean.isArray() || clean.size() > 20) {
                fail("清理步骤最多20个");
            }

            for (JsonNode c : clean) {
                validateStep(c, vars, ids, true);
            }
        }

        if (s.path("cleanupPolicy").asText().equals("HTTP_CLEANUP") && (!clean.isArray() || clean.size() == 0)) {
            fail("HTTP_CLEANUP需配置cleanupSteps");
        }
    }

    private static void rejectCredentialLiterals(JsonNode n, int depth) {
        if (depth > 30) {
            fail("JSON嵌套过深");
        }

        if (n.isObject()) {
            n.fields()
                .forEachRemaining(
                    e -> {
                        String key = ((String)e.getKey()).toLowerCase(Locale.ROOT);
                        JsonNode vx = (JsonNode)e.getValue();
                        if (key.matches(".*(password|passwd|secret|authorization|cookie|csrf|api[-_]?key|access[-_]?token|refresh[-_]?token).*")
                            && vx.isValueNode()
                            && !vx.isNull()
                            && !vx.asText().isEmpty()) {
                            fail("凭证必须通过本次运行参数提供，不能保存到场景");
                        }

                        rejectCredentialLiterals(vx, depth + 1);
                    }
                );
        } else if (n.isArray()) {
            for (JsonNode v : n) {
                rejectCredentialLiterals(v, depth + 1);
            }
        }
    }

    private static void validateStep(JsonNode step, Set<String> vars, Set<String> ids, boolean cleanup) {
        keys(step, "id,name,request,assertions,extract,resource");
        String sid = step.path("id").asText();
        id(sid);
        if (!ids.add(sid)) {
            fail("步骤ID重复");
        }

        text(step.path("name"), "步骤名称", 100);
        JsonNode r = step.path("request");
        keys(r, "method,path,contractPath,pathVariables,query,headers,body");
        if (!Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(r.path("method").asText())) {
            fail("HTTP方法不支持");
        }

        path(r.path("path").asText());
        if (r.has("contractPath")) {
            path(r.path("contractPath").asText());
        }

        refs(r, vars, 0);
        JsonNode pv = r.path("pathVariables");
        if (!pv.isMissingNode() && !pv.isObject()) fail("pathVariables必须为对象");
        Set<String> needed = new HashSet<>();
        Matcher m = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_-]{0,63})\\}").matcher(r.path("path").asText());

        while (m.find()) {
            needed.add(m.group(1));
        }

        Set<String> got = new HashSet<>();
        pv.fieldNames().forEachRemaining(got::add);
        if (!needed.equals(got)) {
            fail("路径变量与路径占位符不一致");
        }

        if (!r.path("headers").isMissingNode()) {
            if (!r.path("headers").isObject()) {
                fail("headers必须为对象");
            }

            r.path("headers").fieldNames().forEachRemaining(ScenarioRules::header);
            for (JsonNode value : r.path("headers")) if (!value.isTextual() && !(value.isObject() && value.size() == 1 && value.path("$var").isTextual())) fail("请求头值必须为字符串或变量");
        }

        if (!r.path("query").isMissingNode() && !r.path("query").isObject()) {
            fail("query必须为对象");
        }

        for (JsonNode value : r.path("query")) {
            if (value.isArray()) {
                if (value.size() < 1 || value.size() > 100) {
                    fail("query数组需1至100项");
                }

                for (JsonNode item : value) {
                    if (!queryScalar(item)) {
                        fail("query数组只允许基本值或变量");
                    }
                }
            } else if (!queryScalar(value)) {
                fail("query只允许基本值、变量或基本值数组");
            }
        }

        JsonNode assertions = step.path("assertions");
        if (!assertions.isArray() || assertions.size() < 1 || assertions.size() > 100) {
            fail("每一步需1至100个断言");
        }

        for (JsonNode a : assertions) {
            keys(a, "source,pointer,op,expected");
            source(a);
            pointer(a);
            if (!Arrays.asList("eq", "ne", "exists", "notExists", "notNull", "type", "contains", "size", "gt", "gte", "lt", "lte").contains(a.path("op").asText())) {
                fail("断言操作不支持");
            }

            if (!Arrays.asList("exists", "notExists", "notNull").contains(a.path("op").asText()) && !a.has("expected")) {
                fail("断言缺少expected");
            }

            JsonNode expected = a.path("expected");
            if (!(expected.isObject() && expected.has("$var"))) {
                String operator = a.path("op").asText();
                if (operator.equals("type")) typeName(expected.asText());
                if (operator.equals("size") && (!expected.isIntegralNumber() || !expected.canConvertToInt() || expected.asInt() < 0)) fail("size预期必须为非负32位整数");
                if (Arrays.asList("gt", "gte", "lt", "lte").contains(operator) && !expected.isNumber()) fail("数值比较预期必须为数字");
                if (Arrays.asList("exists", "notExists", "notNull").contains(operator) && !expected.isMissingNode() && !expected.isBoolean()) fail("存在性断言预期必须为布尔值");
            }

            refs(a, vars, 0);
        }

        JsonNode extract = step.path("extract");
        if (!extract.isMissingNode() && (!extract.isArray() || extract.size() > 30)) {
            fail("extract应为数组，最多30项");
        }

        for (JsonNode e : extract) {
            keys(e, "name,source,pointer,type,required");
            source(e);
            pointer(e);
            typeName(e.path("type").asText());
            if (e.has("required") && !e.path("required").isBoolean()) fail("extract.required 必须为布尔值");
            id(e.path("name").asText());
            if (!vars.add(e.path("name").asText())) {
                fail("变量不能覆盖已有变量");
            }
        }

        if (step.has("resource")) {
            JsonNode h = step.path("resource");
            keys(h, "pointer,type");
            if (!h.path("pointer").isTextual() || !h.path("pointer").asText().startsWith("/") || h.path("pointer").asText().matches(".*~(?![01]).*")) {
                fail("资源ID指针不合法");
            }

            if (h.has("type") && !h.path("type").isTextual()
                || !Arrays.asList("integer", "string").contains(h.path("type").asText("integer"))) {
                fail("资源标识只允许integer或string，不能保存响应对象或数组");
            }
            String field = h.path("pointer").asText().substring(h.path("pointer").asText().lastIndexOf('/') + 1);
            if (!field.matches("(?i)(id|uuid|[a-z][a-z0-9_]*_id|[a-z][a-z0-9_]*Id)")) fail("资源指针必须指向id、uuid或以Id/_id结尾的标识字段");
        }
    }

    private static boolean queryScalar(JsonNode n) {
        return n.isValueNode() && !n.isNull() || n.isObject() && n.size() == 1 && n.path("$var").isTextual();
    }

    private static void source(JsonNode n) {
        if (!Arrays.asList("status", "body").contains(n.path("source").asText())) {
            fail("source只支持status/body");
        }
    }

    private static void pointer(JsonNode n) {
        if (n.path("source").asText().equals("body")) {
            String p = n.path("pointer").asText();
            if (!n.path("pointer").isTextual() || !p.isEmpty() && !p.startsWith("/") || p.matches(".*~(?![01]).*")) {
                fail("JSON Pointer不合法");
            }
        }
    }

    public static void typeName(String t) {
        if (!Arrays.asList("integer", "number", "string", "boolean", "array", "object", "null").contains(t)) {
            fail("类型不支持");
        }
    }

    public static void keys(JsonNode n, String allowed) {
        if (!n.isObject()) {
            fail("需要JSON对象");
        }

        Set<String> a = new HashSet<>(Arrays.asList(allowed.split(",")));
        n.fieldNames().forEachRemaining(k -> {
            if (!a.contains(k)) {
                fail("不支持字段：" + k);
            }
        });
    }

    public static void id(String id) {
        if (!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
            fail("ID必须是字母开头、最多64字符");
        }
    }

    private static void text(JsonNode n, String label, int max) {
        if (!n.isTextual() || n.asText().trim().isEmpty() || n.asText().length() > max) {
            fail(label + "长度不合法");
        }
    }

    public static void path(String p) {
        if (!p.startsWith("/")
            || p.startsWith("//")
            || p.contains("\\")
            || p.contains("?")
            || p.contains("#")
            || p.contains("%")
            || p.contains(":")
            || p.length() > 2048
            || p.matches(".*[\\x00-\\x20].*")
            || p.matches(".*(?:^|/)\\.\\.?(?:/|$).*")) {
            fail("只允许安全相对路径，参数单独配置");
        }
    }

    public static void header(String h) {
        if (!h.matches("[A-Za-z0-9-]{1,80}")
            || Arrays.asList("host", "content-length", "transfer-encoding", "connection", "proxy-authorization", "proxy-connection", "authorization", "cookie")
                .contains(h.toLowerCase(Locale.ROOT))) {
            fail("禁止设置该请求头");
        }
    }

    public static void refs(JsonNode n, Set<String> vars, int depth) {
        if (depth > 30) {
            fail("JSON嵌套过深");
        }

        if (n.isObject() && n.has("$var")) {
            if (n.size() != 1 || !n.path("$var").isTextual() || !vars.contains(n.path("$var").asText())) {
                fail("变量不存在或引用顺序错误");
            }
        } else {
            for (JsonNode c : n) {
                refs(c, vars, depth + 1);
            }
        }
    }

    public static JsonNode resolve(JsonNode n, Map<String, JsonNode> vars) {
        if (n.isObject() && n.has("$var")) {
            JsonNode v = vars.get(n.path("$var").asText());
            if (v == null) {
                fail("执行时变量缺失");
            }

            return v.deepCopy();
        } else if (n.isObject()) {
            ObjectNode o = Util.obj();
            n.fields().forEachRemaining(e -> o.set((String)e.getKey(), resolve((JsonNode)e.getValue(), vars)));
            return o;
        } else if (!n.isArray()) {
            return n.deepCopy();
        } else {
            ArrayNode a = Util.JSON.createArrayNode();

            for (JsonNode c : n) {
                a.add(resolve(c, vars));
            }

            return a;
        }
    }

    public static boolean type(JsonNode n, String t) {
        switch (t) {
            case "integer":
                return n.isIntegralNumber();
            case "number":
                return n.isNumber();
            case "string":
                return n.isTextual();
            case "boolean":
                return n.isBoolean();
            case "array":
                return n.isArray();
            case "object":
                return n.isObject();
            case "null":
                return n.isNull();
            default:
                return false;
        }
    }

    private static boolean same(JsonNode a, JsonNode b) {
        return cn.bank.devtool.SchemaConstraints.equal(a, b);
    }

    public static boolean assertValue(JsonNode actual, String op, JsonNode expected) {
        if (Arrays.asList("exists", "notExists", "notNull").contains(op) && !expected.isMissingNode() && !expected.isBoolean()) return false;
        if (op.equals("exists")) {
            return !actual.isMissingNode() == expected.asBoolean(true);
        } else if (op.equals("notExists")) {
            return actual.isMissingNode() == expected.asBoolean(true);
        } else if (op.equals("notNull")) {
            return (!actual.isMissingNode() && !actual.isNull()) == expected.asBoolean(true);
        } else if (actual.isMissingNode()) {
            return false;
        } else {
            switch (op) {
                case "eq":
                    return same(actual, expected);
                case "ne":
                    return !same(actual, expected);
                case "type":
                    return type(actual, expected.asText());
                case "size":
                    return (actual.isContainerNode() || actual.isTextual())
                        && expected.isIntegralNumber()
                        && expected.canConvertToInt() && expected.asInt() >= 0
                        && (actual.isTextual() ? actual.asText().codePointCount(0, actual.asText().length()) : actual.size()) == expected.asInt();
                case "contains":
                    if (actual.isTextual() && expected.isTextual()) {
                        return actual.asText().contains(expected.asText());
                    } else {
                        if (actual.isArray()) {
                            for (JsonNode c : actual) {
                                if (same(c, expected)) {
                                    return true;
                                }
                            }
                        }

                        return false;
                    }
                default:
                    if (actual.isNumber() && expected.isNumber()) {
                        int cx = actual.decimalValue().compareTo(expected.decimalValue());
                        return op.equals("gt") ? cx > 0 : (op.equals("gte") ? cx >= 0 : (op.equals("lt") ? cx < 0 : op.equals("lte") && cx <= 0));
                    } else {
                        return false;
                    }
            }
        }
    }

    static void fail(String s) {
        throw new IllegalArgumentException(s);
    }
}
