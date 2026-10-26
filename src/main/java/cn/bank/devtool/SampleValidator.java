package cn.bank.devtool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.chrono.ChronoLocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Map.Entry;
import java.util.regex.Pattern;

public final class SampleValidator {
    private static final ObjectMapper STRICT_JSON = Util.JSON
        .copy()
        .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
        .enable(new Feature[]{Feature.STRICT_DUPLICATE_DETECTION})
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public JsonNode validate(JsonNode doc, String method, String path, String status, String media, String body) {
        if (method == null || !Arrays.asList("get", "post", "put", "delete", "patch", "head", "options", "trace").contains(method.toLowerCase(Locale.ROOT))
            || path == null || !path.startsWith("/")) throw new IllegalArgumentException("必须提供有效HTTP方法和接口路径");
        if (status == null || !status.matches("[1-5][0-9]{2}")) {
            throw new IllegalArgumentException("响应状态码必须为100至599的三位数字，不得包含空格");
        }
        if (body != null && Util.bytes(body).length <= 1048576) {
            ObjectNode d = new ContractService().prepare(doc);
            Set<String> issues = new LinkedHashSet<>();
            ArrayNode errors = Util.JSON.createArrayNode();
            CapabilityRegistry.audit(d, "sample", issues);
            JsonNode op = new ContractService().schema(d.path("paths").path(path), d, issues).path(method.toLowerCase(Locale.ROOT));
            if (op.isMissingNode()) {
                throw new IllegalArgumentException("未找到接口");
            } else {
                JsonNode responses = op.path("responses");
                JsonNode response = responses.path(status);
                if (response.isMissingNode() && status.matches("[1-5][0-9]{2}")) {
                    response = responses.path(status.substring(0, 1) + "XX");
                }

                if (response.isMissingNode()) {
                    response = responses.path("default");
                }

                response = new ContractService().schema(response, d, issues);
                if (response.isMissingNode()) {
                    issues.add("没有匹配的响应状态码定义");
                } else if (!response.isObject() || response.has("content") && !response.path("content").isObject()
                    || !response.has("content") && !response.path("description").isTextual()) {
                    issues.add("响应定义或content结构不合法，不能确认无正文契约");
                } else if (!noResponseBody(method, status) && response.path("content").isObject() && !response.path("content").isEmpty()) {
                    String m = media == null ? "application/json" : ContentTypes.base(media);
                    JsonNode schema = ContentTypes.match(response.path("content"), m, issues).path("schema");
                    if (!ContentTypes.json(m)) {
                        issues.add("响应样例仅支持JSON媒体类型，当前类型未校验：" + m);
                    } else if (schema.isMissingNode()) {
                        issues.add("没有匹配的JSON媒体类型及Schema");
                    } else {
                        try {
                            JsonNode value = STRICT_JSON.readTree(body);
                            if (value == null) {
                                throw new IllegalArgumentException("空JSON");
                            }

                            this.check(new ContractService().schema(schema, d, issues), value, "", errors, issues, 0);
                        } catch (JsonProcessingException var18) {
                            this.error(errors, "", "INVALID_JSON", "合法JSON", "格式错误");
                        }
                    }
                } else if (!body.isEmpty()) {
                    this.error(errors, "", "UNEXPECTED_BODY", "无响应体", "存在响应体");
                }

                for (JsonNode x : d.path("x-coverage").path("issues")) {
                    issues.add(x.asText());
                }

                ObjectNode r = Util.obj();
                r.put("verdict", !errors.isEmpty() ? "FAIL" : (!issues.isEmpty() ? "INCOMPLETE" : "PASS"));
                r.set("findings", errors);
                ObjectNode coverage = r.putObject("coverage");
                coverage.put("complete", issues.isEmpty());
                ArrayNode arr = coverage.putArray("issues");

                for (String issue : issues) {
                    arr.add(issue);
                }

                r.put("scope", "当前样例及已覆盖约束；原文未持久保存");
                r.set("interface", InterfaceDescriptor.describe(d, method, path));
                r.put("status", status);
                r.put("media", media);
                return r;
            }
        } else {
            throw new IllegalArgumentException("响应样例最大1MiB");
        }
    }

    /** Informational, reset/no-content and conditional-cache responses cannot contain content. */
    private static boolean noResponseBody(String method, String status) {
        return method.equalsIgnoreCase("HEAD") || status.charAt(0) == '1'
            || status.equals("204") || status.equals("205") || status.equals("304");
    }

    public JsonNode validateSchema(JsonNode s, JsonNode value) {
        Set<String> issues = new LinkedHashSet<>();
        ArrayNode errors = Util.JSON.createArrayNode();
        this.check(s, value, "", errors, issues, 0);
        ObjectNode r = Util.obj().put("verdict", !errors.isEmpty() ? "FAIL" : (!issues.isEmpty() ? "INCOMPLETE" : "PASS"));
        r.set("findings", errors);
        return r;
    }

    private void check(JsonNode s, JsonNode v, String p, ArrayNode errors, Set<String> issues, int depth) {
        if (depth > 64) {
            issues.add("样例嵌套深度超过64");
        } else if (errors.size() <= 200) {
            CapabilityRegistry.schema(s, "sample", p, issues);
            if (s.has("enum")) {
                boolean match = false;

                for (JsonNode e : s.path("enum")) {
                    if (SchemaConstraints.equal(e, v)) {
                        match = true;
                        break;
                    }
                }

                if (!match) {
                    this.error(errors, p, "ENUM_MISMATCH", "声明的枚举", this.actual(v));
                }
            }

            if (v.isNull()) {
                if (!s.path("nullable").asBoolean()) {
                    this.error(errors, p, "NULL_NOT_ALLOWED", "非null", "null");
                }
            } else {
                String type = s.path("type").asText();
                boolean valid = true;
                if ("object".equals(type)) {
                    valid = v.isObject();
                } else if ("array".equals(type)) {
                    valid = v.isArray();
                } else if ("string".equals(type)) {
                    valid = v.isTextual();
                } else if ("integer".equals(type)) {
                    valid = v.isIntegralNumber();
                } else if ("number".equals(type)) {
                    valid = v.isNumber();
                } else if ("boolean".equals(type)) {
                    valid = v.isBoolean();
                }

                if (!valid) {
                    this.error(errors, p, "TYPE_MISMATCH", type, this.actual(v));
                } else {
                    if (type.isEmpty()) {
                        issues.add("缺少明确类型的Schema：" + p);
                    }

                    for (String k : Arrays.asList("allOf", "oneOf", "anyOf", "discriminator", "not")) {
                        if (s.has(k) && !(k.equals("anyOf") && SchemaConstraints.requiredAlternatives(s))) {
                            issues.add("样例校验未支持：" + k);
                        }
                    }

                    if (v.isObject()) {
                        if (SchemaConstraints.requiredAlternatives(s) && !SchemaConstraints.matchesRequiredAlternatives(s, v)) {
                            this.error(errors, p, "ANY_OF_REQUIRED", "至少提供一组业务字段", "未满足任一必填字段组合");
                        }
                        this.bounds(v.size(), s, "minProperties", "maxProperties", p, errors);

                        for (JsonNode f : s.path("required")) {
                            if (!v.has(f.asText())) {
                                this.error(errors, p + "/" + Util.ptr(f.asText()), "REQUIRED_MISSING", "必填字段", "缺失");
                            }
                        }

                        Iterator<Entry<String, JsonNode>> it = v.fields();

                        while (it.hasNext()) {
                            Entry<String, JsonNode> ex = it.next();
                            if (s.path("properties").has(ex.getKey())) {
                                this.check(s.path("properties").get(ex.getKey()), ex.getValue(), p + "/" + Util.ptr(ex.getKey()), errors, issues, depth + 1);
                            } else if (s.has("additionalProperties")
                                && s.path("additionalProperties").isBoolean()
                                && !s.path("additionalProperties").asBoolean()) {
                                this.error(errors, p + "/" + Util.ptr(ex.getKey()), "EXTRA_FIELD", "已声明字段", "额外字段");
                            } else if (s.path("additionalProperties").isObject()) {
                                this.check(s.get("additionalProperties"), ex.getValue(), p + "/" + Util.ptr(ex.getKey()), errors, issues, depth + 1);
                            }
                        }
                    }

                    if (v.isArray()) {
                        for (int i = 0; i < v.size(); i++) {
                            this.check(s.path("items"), v.get(i), p + "/" + i, errors, issues, depth + 1);
                        }

                        this.bounds(v.size(), s, "minItems", "maxItems", p, errors);
                    }

                    if (v.isTextual()) {
                        if (s.has("pattern")) {
                            String pattern = s.path("pattern").asText();
                            if (SchemaConstraints.patternSupported(pattern)) {
                                if (!Pattern.compile(pattern).matcher(v.asText()).find()) {
                                    this.error(errors, p, "PATTERN_MISMATCH", pattern, "string");
                                }
                            } else {
                                issues.add("pattern尚未支持：" + p);
                            }
                        }

                        if (s.has("x-numeric-minimum") || s.has("x-numeric-maximum") || s.has("x-precision")) {
                            try {
                                BigDecimal number = new BigDecimal(v.asText());
                                if (s.has("x-numeric-minimum") && number.compareTo(new BigDecimal(s.path("x-numeric-minimum").asText())) < 0) {
                                    this.error(errors, p, "MINIMUM", "数值下界", "string");
                                }

                                if (s.has("x-numeric-maximum") && number.compareTo(new BigDecimal(s.path("x-numeric-maximum").asText())) > 0) {
                                    this.error(errors, p, "MAXIMUM", "数值上界", "string");
                                }

                                if (s.has("x-precision")
                                    && (
                                        Math.max(0, number.scale()) > s.path("x-scale").asInt()
                                            || Math.max(0, number.precision() - number.scale()) > s.path("x-precision").asInt() - s.path("x-scale").asInt()
                                    )) {
                                    this.error(errors, p, "DECIMAL_PRECISION", "金额精度", "string");
                                }
                            } catch (NumberFormatException var14) {
                                this.error(errors, p, "INVALID_NUMBER", "十进制字符串", "string");
                            }
                        }

                        this.bounds(v.asText().length(), s, "x-utf16-min-length", "x-utf16-max-length", p, errors);
                        this.bounds(v.asText().codePointCount(0, v.asText().length()), s, "minLength", "maxLength", p, errors);
                        String fx = s.path("format").asText();
                        if (fx.equals("date")) {
                            try {
                                this.dateBounds(LocalDate.parse(v.asText()), s, p, errors);
                            } catch (Exception var13) {
                                this.error(errors, p, "FORMAT_MISMATCH", "date", "string");
                            }
                        } else if (fx.equals("date-time")) {
                            try {
                                OffsetDateTime.parse(v.asText());
                            } catch (Exception var12) {
                                this.error(errors, p, "FORMAT_MISMATCH", "带时区date-time", "string");
                            }
                        } else if (fx.equals("local-date-time")) {
                            try {
                                LocalDateTime dt = LocalDateTime.parse(v.asText());
                                this.dateBounds(dt.toLocalDate(), s, p, errors);
                                if (s.has("x-temporal-precision")
                                    && s.path("x-temporal-precision").asInt() >= 0
                                    && s.path("x-temporal-precision").asInt() <= 6
                                    && dt.getNano() % (int)Math.pow(10.0, 9 - s.path("x-temporal-precision").asInt()) != 0) {
                                    this.error(errors, p, "TEMPORAL_PRECISION", "时间精度", "string");
                                }
                            } catch (Exception var11) {
                                this.error(errors, p, "FORMAT_MISMATCH", "本地日期时间", "string");
                            }
                        } else if (!fx.isEmpty()) {
                            issues.add("未校验格式：" + fx);
                        }
                    }

                    if (v.isNumber()) {
                        if (s.path("format").asText().equals("float") && Float.isInfinite(v.floatValue())
                            || s.path("format").asText().equals("double") && Double.isInfinite(v.doubleValue())) {
                            this.error(errors, p, "FORMAT_RANGE", "有限浮点范围", this.actual(v));
                        }

                        String format = s.path("format").asText();
                        if (format.equals("int32") && (!v.isIntegralNumber() || !v.canConvertToInt())) {
                            this.error(errors, p, "FORMAT_RANGE", "int32范围", this.actual(v));
                        }

                        if (format.equals("int64") && (!v.isIntegralNumber() || !v.canConvertToLong())) {
                            this.error(errors, p, "FORMAT_RANGE", "int64范围", this.actual(v));
                        }

                        if (s.has("x-precision")
                            && (
                                Math.max(0, v.decimalValue().scale()) > s.path("x-scale").asInt()
                                    || Math.max(0, v.decimalValue().precision() - v.decimalValue().scale())
                                        > s.path("x-precision").asInt() - s.path("x-scale").asInt()
                            )) {
                            this.error(errors, p, "DECIMAL_PRECISION", "金额精度", "number");
                        }

                        if (s.has("minimum") && v.decimalValue().compareTo(s.get("minimum").decimalValue()) < (s.path("exclusiveMinimum").asBoolean() ? 1 : 0)) {
                            this.error(errors, p, "MINIMUM", "最小值约束", "number");
                        }

                        if (s.has("maximum") && v.decimalValue().compareTo(s.get("maximum").decimalValue()) > (s.path("exclusiveMaximum").asBoolean() ? -1 : 0)
                            )
                         {
                            this.error(errors, p, "MAXIMUM", "最大值约束", "number");
                        }

                        if (s.has("multipleOf")) {
                            BigDecimal unit = s.path("multipleOf").decimalValue();
                            if (unit.signum() <= 0) {
                                issues.add("multipleOf必须为正数");
                            } else if (v.decimalValue().remainder(unit).signum() != 0) {
                                this.error(errors, p, "MULTIPLE_OF", "金额最小单位", "number");
                            }
                        }
                    }
                }
            }
        }
    }

    private void dateBounds(LocalDate date, JsonNode schema, String p, ArrayNode errors) {
        for (String key : Arrays.asList("x-date-minimum", "x-date-maximum")) {
            if (schema.has(key)) {
                try {
                    int cmp = date.compareTo((ChronoLocalDate)LocalDate.parse(schema.path(key).asText()));
                    if (key.equals("x-date-minimum") ? cmp < 0 : cmp > 0) {
                        this.error(errors, p, "DATE_RANGE", "数据库日期范围", "string");
                    }
                } catch (DateTimeParseException var8) {
                }
            }
        }
    }

    private void bounds(int n, JsonNode s, String min, String max, String p, ArrayNode e) {
        if (s.has(min) && n < s.get(min).asInt()) {
            this.error(e, p, "MIN_LENGTH", min, "长度不足");
        }

        if (s.has(max) && n > s.get(max).asInt()) {
            this.error(e, p, "MAX_LENGTH", max, "长度超限");
        }
    }

    private String actual(JsonNode v) {
        if (v.isTextual()) {
            return "string";
        } else if (v.isIntegralNumber()) {
            return "integer";
        } else if (v.isNumber()) {
            return "number";
        } else if (v.isObject()) {
            return "object";
        } else if (v.isArray()) {
            return "array";
        } else {
            return v.isBoolean() ? "boolean" : "null";
        }
    }

    private void error(ArrayNode e, String p, String code, String expected, String actual) {
        e.addObject().put("pointer", p).put("code", code).put("expected", expected).put("actual", actual);
    }
}
