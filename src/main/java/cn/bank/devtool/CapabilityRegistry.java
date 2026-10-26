package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Map.Entry;

public final class CapabilityRegistry {
    private static final Set<String> KNOWN = new HashSet<>(
        Arrays.asList(
            "type",
            "format",
            "nullable",
            "enum",
            "properties",
            "required",
            "items",
            "additionalProperties",
            "minimum",
            "maximum",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "multipleOf",
            "minLength",
            "maxLength",
            "minItems",
            "maxItems",
            "minProperties",
            "maxProperties",
            "x-utf16-min-length",
            "x-utf16-max-length",
            "pattern",
            "default",
            "title",
            "description",
            "example",
            "examples",
            "deprecated",
            "$ref",
            "x-java-type",
            "x-precision",
            "x-scale",
            "x-numeric-minimum",
            "x-numeric-maximum",
            "x-temporal-precision",
            "x-date-minimum",
            "x-date-maximum",
            "x-utf16-min-length",
            "x-utf16-max-length",
            "x-update-semantics",
            "x-host-contract-verification",
            "x-business-semantics"
        )
    );

    private CapabilityRegistry() {
    }

    /** Readable labels live beside the capability audit used by import, compare and sample validation. */
    public static java.util.Map<String, String> readableConstraints() {
        java.util.Map<String, String> labels = new java.util.LinkedHashMap<String, String>();
        String[][] entries = {
            {"minimum", "数值下界"}, {"maximum", "数值上界"}, {"exclusiveMinimum", "不含下界"}, {"exclusiveMaximum", "不含上界"},
            {"multipleOf", "最小单位/倍数"}, {"minLength", "最少Unicode码点"}, {"maxLength", "最多Unicode码点"},
            {"minItems", "最少元素"}, {"maxItems", "最多元素"}, {"uniqueItems", "元素唯一"},
            {"minProperties", "最少属性"}, {"maxProperties", "最多属性"}, {"pattern", "模式"}, {"enum", "枚举"},
            {"default", "默认值"}, {"required", "父对象内必填字段"}, {"additionalProperties", "额外属性"}, {"anyOf", "任一组合"}, {"oneOf", "唯一组合"}, {"allOf", "全部组合"},
            {"readOnly", "只读"}, {"writeOnly", "只写"}, {"deprecated", "已弃用"}, {"x-java-type", "Java类型"},
            {"x-precision", "总有效位"}, {"x-scale", "最大小数位"}, {"x-numeric-minimum", "十进制字符串下界"}, {"x-numeric-maximum", "十进制字符串上界"},
            {"x-temporal-precision", "小数秒位数"}, {"x-date-minimum", "日期下界(含)"}, {"x-date-maximum", "日期上界(含)"},
            {"x-utf16-min-length", "最少UTF-16单元"}, {"x-utf16-max-length", "最多UTF-16单元"},
            {"x-update-semantics", "更新语义"}, {"x-host-contract-verification", "宿主契约验收"}, {"x-business-semantics", "业务语义声明(需复核)"}
        };
        for (String[] entry : entries) labels.put(entry[0], entry[1]);
        return java.util.Collections.unmodifiableMap(labels);
    }

    public static void audit(JsonNode doc, String consumer, Set<String> issues) {
        document(doc, doc, consumer, issues, 0);
    }

    private static void document(JsonNode node, JsonNode root, String consumer, Set<String> issues, int depth) {
        if (depth <= 80) {
            if (node.isObject()) {
                Iterator<Entry<String, JsonNode>> fields = node.fields();

                while (fields.hasNext()) {
                    Entry<String, JsonNode> field = fields.next();
                    String key = field.getKey();
                    if (!key.equals("example") && !key.equals("examples")) {
                        if (key.equals("schema")) {
                            tree(new ContractService().schema(field.getValue(), root, issues), consumer, "/schema", issues, 0);
                        } else if (key.equals("schemas") && field.getValue().isObject()) {
                            for (JsonNode definition : field.getValue()) {
                                tree(new ContractService().schema(definition, root, issues), consumer, "/components/schemas", issues, 0);
                            }
                        } else {
                            document(field.getValue(), root, consumer, issues, depth + 1);
                        }
                    }
                }
            } else if (node.isArray()) {
                for (JsonNode value : node) {
                    document(value, root, consumer, issues, depth + 1);
                }
            }
        }
    }

    private static void tree(JsonNode node, String consumer, String path, Set<String> issues, int depth) {
        if (depth > 30) {
            issues.add(consumer + " 约束嵌套过深：" + path);
        } else {
            schema(node, consumer, path, issues);

            for (String key : Arrays.asList("properties")) {
                if (node.path(key).isObject()) {
                    Iterator<Entry<String, JsonNode>> fields = node.path(key).fields();

                    while (fields.hasNext()) {
                        Entry<String, JsonNode> field = fields.next();
                        tree(field.getValue(), consumer, path + "/" + field.getKey(), issues, depth + 1);
                    }
                }
            }

            for (String keyx : Arrays.asList("items", "additionalProperties")) {
                if (node.path(keyx).isObject()) {
                    tree(node.get(keyx), consumer, path + "/" + keyx, issues, depth + 1);
                }
            }
        }
    }

    public static void schema(JsonNode s, String consumer, String pointer, Set<String> issues) {
        if (!s.isObject()) {
            issues.add(consumer + " Schema必须是对象：" + pointer);
        } else {
            if (s.has("type")
                && (
                    !s.path("type").isTextual()
                        || !Arrays.asList("object", "array", "string", "number", "integer", "boolean").contains(s.path("type").asText())
                )) {
                issues.add(consumer + " type声明不合法：" + pointer);
            }

            for (String key : Arrays.asList("nullable", "exclusiveMinimum", "exclusiveMaximum", "deprecated")) {
                if (s.has(key) && !s.path(key).isBoolean()) {
                    issues.add(consumer + " " + key + "必须为布尔值：" + pointer);
                }
            }

            for (String keyx : Arrays.asList("minimum", "maximum")) {
                if (s.has(keyx) && !s.path(keyx).isNumber()) {
                    issues.add(consumer + " " + keyx + "必须为数值：" + pointer);
                }
            }

            for (String keyxx : Arrays.asList(
                "minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties", "x-utf16-min-length", "x-utf16-max-length"
            )) {
                if (s.has(keyxx) && (!s.path(keyxx).isIntegralNumber() || !s.path(keyxx).canConvertToInt() || s.path(keyxx).asInt() < 0)) {
                    issues.add(consumer + " " + keyxx + "必须为非负int：" + pointer);
                }
            }

            for (String[] pair : new String[][]{
                {"minimum", "maximum"}, {"minLength", "maxLength"}, {"minItems", "maxItems"}, {"minProperties", "maxProperties"}
            }) {
                if (s.path(pair[0]).isNumber() && s.path(pair[1]).isNumber() && s.path(pair[0]).decimalValue().compareTo(s.path(pair[1]).decimalValue()) > 0) {
                    issues.add(consumer + " 上下界倒置：" + pointer);
                }
            }

            for (String keyxxx : Arrays.asList("pattern", "format", "title", "description")) {
                if (s.has(keyxxx) && !s.path(keyxxx).isTextual()) {
                    issues.add(consumer + " " + keyxxx + "必须为字符串：" + pointer);
                }
            }

            if (s.has("properties") && !s.path("properties").isObject()) {
                issues.add(consumer + " properties必须是对象：" + pointer);
            }

            if (s.has("items") && !s.path("items").isObject() || s.path("type").asText().equals("array") && !s.path("items").isObject()) {
                issues.add(consumer + " 数组必须声明对象形式items：" + pointer);
            }

            if (s.has("additionalProperties") && !s.path("additionalProperties").isObject() && !s.path("additionalProperties").isBoolean()) {
                issues.add(consumer + " additionalProperties声明不合法：" + pointer);
            }

            if (s.has("required")) {
                if (!s.path("required").isArray() || s.path("required").isEmpty()) {
                    issues.add(consumer + " required必须是非空字符串数组：" + pointer);
                }

                Set<String> fields = new HashSet<>();

                for (JsonNode field : s.path("required")) {
                    if (!field.isTextual() || !fields.add(field.asText())) {
                        issues.add(consumer + " required字段非法或重复：" + pointer);
                    }
                }
            }

            if (s.has("enum") && (!s.path("enum").isArray() || s.path("enum").isEmpty())) {
                issues.add(consumer + " enum必须是非空数组：" + pointer);
            }

            if (s.has("x-scale") && !s.has("x-precision")) {
                issues.add(consumer + " x-scale缺少x-precision：" + pointer);
            }

            for (String keyxxxx : Arrays.asList("x-numeric-minimum", "x-numeric-maximum")) {
                if (s.has(keyxxxx)) {
                    try {
                        if (!s.path(keyxxxx).isTextual() && !s.path(keyxxxx).isNumber()) {
                            throw new IllegalArgumentException();
                        }

                        new BigDecimal(s.path(keyxxxx).asText());
                    } catch (Exception var9) {
                        issues.add(consumer + " 数值扩展边界不合法：" + pointer + "/" + keyxxxx);
                    }
                }
            }

            for (String keyxxxxx : Arrays.asList("x-date-minimum", "x-date-maximum")) {
                if (s.has(keyxxxxx)) {
                    try {
                        if (!s.path(keyxxxxx).isTextual()) {
                            throw new IllegalArgumentException();
                        }

                        LocalDate.parse(s.path(keyxxxxx).asText());
                    } catch (Exception var8) {
                        issues.add(consumer + " 日期扩展边界不合法：" + pointer + "/" + keyxxxxx);
                    }
                }
            }

            Iterator<String> keys = s.fieldNames();

            while (keys.hasNext()) {
                String k = keys.next();
                if (!KNOWN.contains(k) && !(k.equals("anyOf") && SchemaConstraints.requiredAlternatives(s))) {
                    issues.add(consumer + " 未支持 Schema 约束：" + pointer + "/" + k);
                }
            }

            String f = s.path("format").asText();
            if (!f.isEmpty() && !Arrays.asList("int32", "int64", "float", "double", "date", "date-time", "local-date-time").contains(f)) {
                issues.add(consumer + " 未支持 format：" + pointer + "/" + f);
            }

            if (s.has("x-temporal-precision")
                && (
                    !s.path("x-temporal-precision").isIntegralNumber()
                        || !s.path("x-temporal-precision").canConvertToInt()
                        || s.path("x-temporal-precision").asInt() < 0
                        || s.path("x-temporal-precision").asInt() > 6
                )) {
                issues.add(consumer + " 时间精度必须为0至6：" + pointer);
            }

            if (s.has("x-precision")
                && (
                    !s.path("x-precision").isIntegralNumber()
                        || !s.path("x-precision").canConvertToInt()
                        || s.path("x-precision").asInt() < 1
                        || s.path("x-precision").asInt() > 38
                        || !s.path("x-scale").isIntegralNumber()
                        || !s.path("x-scale").canConvertToInt()
                        || s.path("x-scale").asInt() < 0
                        || s.path("x-scale").asInt() > s.path("x-precision").asInt()
                )) {
                issues.add(consumer + " 数字精度约束不合法：" + pointer);
            }

            if ((s.has("x-date-minimum") || s.has("x-date-maximum")) && !Arrays.asList("date", "local-date-time").contains(f)) {
                issues.add(consumer + " 日期扩展仅支持date/local-date-time：" + pointer);
            }

            if (s.has("x-temporal-precision") && !f.equals("local-date-time")) {
                issues.add(consumer + " 时间精度仅支持local-date-time：" + pointer);
            }

            if (Arrays.asList("int32", "int64", "float", "double").contains(f) && !Arrays.asList("integer", "number").contains(s.path("type").asText())) {
                issues.add(consumer + " 数值格式的类型未支持：" + pointer);
            }

            if (s.has("pattern") && !SchemaConstraints.patternSupported(s.path("pattern").asText())) {
                issues.add(consumer + " 未支持 pattern：" + pointer);
            }

            if (s.has("multipleOf") && (!s.path("multipleOf").isNumber() || s.path("multipleOf").decimalValue().signum() <= 0)) {
                issues.add(consumer + " multipleOf 必须是正数：" + pointer);
            }
        }
    }
}
