package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

final class MockSynthesizer {
    private static final int MAX_STRING = 4096;
    private static final int MAX_ARRAY = 100;
    private static final int MAX_OBJECT = 200;

    JsonNode sample(JsonNode s, Set<String> issues, int depth, int[] nodes) {
        if (depth <= 14 && ++nodes[0] <= 20000) {
            CapabilityRegistry.schema(s, "mock", "", issues);
            if (!issues.isEmpty()) {
                return NullNode.instance;
            } else if (s.has("enum")) {
                for (JsonNode v : s.path("enum")) {
                    if (Util.bytes(v.toString()).length <= 1048576 && this.fits(s, v)) {
                        return v.deepCopy();
                    }
                }

                return gap(issues, "enum没有同时满足类型、格式与约束的可合成值");
            } else {
                try {
                    String type = s.path("type").asText();
                    JsonNode result;
                    if (type.equals("object")) {
                        ObjectNode out = Util.obj();
                        int min = s.path("minProperties").asInt();
                        int max = Math.min(200, s.path("maxProperties").asInt(200));
                        if (min > 200 || s.path("required").size() > max) {
                            return gap(issues, "对象最小或必填字段数超过合成上限");
                        }

                        for (JsonNode key : s.path("required")) {
                            if (!s.path("properties").has(key.asText())) {
                                return gap(issues, "required字段未声明properties：" + key.asText());
                            }

                            out.set(key.asText(), this.sample(s.path("properties").path(key.asText()), issues, depth + 1, nodes));
                        }

                        if (SchemaConstraints.requiredAlternatives(s) && !SchemaConstraints.matchesRequiredAlternatives(s, out)) {
                            for (JsonNode alternative : s.path("anyOf")) {
                                int missing = 0;
                                for (JsonNode key : alternative.path("required")) if (!out.has(key.asText())) missing++;
                                if (out.size() + missing <= max) {
                                    for (JsonNode key : alternative.path("required")) {
                                        if (!out.has(key.asText())) out.set(key.asText(), this.sample(s.path("properties").path(key.asText()), issues, depth + 1, nodes));
                                    }
                                    break;
                                }
                            }
                            if (!SchemaConstraints.matchesRequiredAlternatives(s, out)) return gap(issues, "无法在字段预算内满足anyOf必填字段组合");
                        }
                        for (String key : keys(s.path("properties"))) {
                            if (!out.has(key) && out.size() < max) {
                                out.set(key, this.sample(s.path("properties").path(key), issues, depth + 1, nodes));
                                if (!issues.isEmpty()) {
                                    break;
                                }
                            }
                        }

                        for (int i = 0; out.size() < min; i++) {
                            if (s.path("additionalProperties").isBoolean() && !s.path("additionalProperties").asBoolean()) {
                                return gap(issues, "minProperties超过可用字段数");
                            }

                            String keyx = "synthetic" + i;
                            if (!out.has(keyx)) {
                                out.set(
                                    keyx,
                                    (JsonNode)(s.path("additionalProperties").isObject()
                                        ? this.sample(s.get("additionalProperties"), issues, depth + 1, nodes)
                                        : TextNode.valueOf("SYNTHETIC"))
                                );
                            }
                        }

                        result = out;
                    } else if (type.equals("array")) {
                        int count = Math.min(Math.max(1, s.path("minItems").asInt()), s.path("maxItems").asInt(100));
                        if (count > 100 || s.path("minItems").asInt() > 100) {
                            return gap(issues, "数组最小长度超过100项合成预算");
                        }

                        ArrayNode out = Util.JSON.createArrayNode();

                        for (int i = 0; i < count; i++) {
                            out.add(this.sample(s.path("items"), issues, depth + 1, nodes));
                        }

                        result = out;
                    } else if (type.equals("string")) {
                        result = TextNode.valueOf(this.string(s));
                    } else if (!type.equals("integer") && !type.equals("number")) {
                        if (!type.equals("boolean")) {
                            return gap(issues, "Schema缺少明确类型或类型不支持：" + type);
                        }

                        result = BooleanNode.TRUE;
                    } else {
                        BigDecimal n = this.number(s, false);
                        result = (JsonNode)(type.equals("integer") ? BigIntegerNode.valueOf(n.toBigIntegerExact()) : DecimalNode.valueOf(n));
                    }

                    if (!issues.isEmpty()) {
                        return NullNode.instance;
                    } else {
                        return !this.fits(s, result) ? gap(issues, "无法在合成预算内同时满足约束，请核对Schema并补充人工用例") : result;
                    }
                } catch (RuntimeException var12) {
                    return gap(issues, "约束不一致或无法在合成预算内表示");
                }
            }
        } else {
            return gap(issues, "合成值超过14层或20000节点预算");
        }
    }

    private String string(JsonNode s) {
        int min = Math.max(s.path("minLength").asInt(), s.path("x-utf16-min-length").asInt());
        int max = Math.min(Math.min(4096, s.path("maxLength").asInt(4096)), s.path("x-utf16-max-length").asInt(4096));
        if (min > max) {
            throw new IllegalArgumentException();
        } else {
            String value = "SYNTHETIC";
            String format = s.path("format").asText();
            String pattern = s.path("pattern").asText();
            if (format.equals("date") || format.equals("local-date-time")) {
                LocalDate date = LocalDate.of(2000, 1, 1);
                if (s.has("x-date-minimum") && date.isBefore(LocalDate.parse(s.get("x-date-minimum").asText()))) {
                    date = LocalDate.parse(s.get("x-date-minimum").asText());
                }

                if (s.has("x-date-maximum") && date.isAfter(LocalDate.parse(s.get("x-date-maximum").asText()))) {
                    date = LocalDate.parse(s.get("x-date-maximum").asText());
                }

                value = date.toString() + (format.equals("date") ? "" : "T00:00:00");
            } else if (format.equals("date-time")) {
                value = "2000-01-01T00:00:00Z";
            } else if (s.has("x-numeric-minimum") || s.has("x-numeric-maximum") || s.has("x-precision") || !pattern.isEmpty() && !pattern.equals("\\S")) {
                value = this.number(s, true).toPlainString();
                if (value.length() < min && min <= 38) {
                    ObjectNode c = (ObjectNode)s.deepCopy();
                    BigDecimal candidate = BigDecimal.TEN.pow(min - 1);
                    BigDecimal original = this.bound(s, "x-numeric-minimum");
                    if (original != null) {
                        candidate = candidate.max(original);
                    }

                    c.put("x-numeric-minimum", candidate.toPlainString());
                    value = this.number(c, true).toPlainString();
                }

                if (value.length() >= min && value.length() <= max) {
                    return value;
                } else {
                    throw new IllegalArgumentException();
                }
            }

            if (!format.isEmpty()) {
                if (value.length() >= min && value.length() <= max) {
                    return value;
                } else {
                    throw new IllegalArgumentException();
                }
            } else {
                if (value.length() > max) {
                    value = value.substring(0, max);
                }

                StringBuilder out = new StringBuilder(value);

                while (out.length() < min) {
                    out.append('X');
                }

                return out.toString();
            }
        }
    }

    private BigDecimal number(JsonNode s, boolean wire) {
        String format = s.path("format").asText();
        String pattern = s.path("pattern").asText();
        boolean integer = s.path("type").asText().equals("integer")
            || wire
                && (
                    s.path("x-java-type").asText().matches("Long|Integer")
                        || Arrays.asList("^[1-9][0-9]*$", "^(0|[1-9][0-9]*)$", "^-?(0|[1-9][0-9]*)$").contains(pattern)
                );
        BigDecimal min = this.bound(s, wire ? "x-numeric-minimum" : "minimum");
        BigDecimal max = this.bound(s, wire ? "x-numeric-maximum" : "maximum");
        if (pattern.equals("^[1-9][0-9]*$")) {
            min = higher(min, BigDecimal.ONE);
        }

        if (pattern.equals("^(0|[1-9][0-9]*)$")) {
            min = higher(min, BigDecimal.ZERO);
        }

        if (format.equals("int32")) {
            min = higher(min, BigDecimal.valueOf(-2147483648L));
            max = lower(max, BigDecimal.valueOf(2147483647L));
        }

        if (format.equals("int64")) {
            min = higher(min, BigDecimal.valueOf(Long.MIN_VALUE));
            max = lower(max, BigDecimal.valueOf(Long.MAX_VALUE));
        }

        int scale = s.has("x-scale")
            ? s.path("x-scale").asInt()
            : Math.min(18, Math.max(6, Math.max(min == null ? 0 : min.scale(), max == null ? 0 : max.scale())));
        BigDecimal unit = s.has("multipleOf") ? this.bound(s, "multipleOf") : (integer ? BigDecimal.ONE : BigDecimal.ONE.scaleByPowerOfTen(-scale));
        if (unit != null && unit.signum() > 0) {
            if (integer && s.has("multipleOf")) {
                BigDecimal reduced = unit.stripTrailingZeros();
                BigInteger numerator = reduced.scale() < 0 ? reduced.toBigIntegerExact() : reduced.unscaledValue();
                BigInteger denominator = BigInteger.TEN.pow(Math.max(0, reduced.scale()));
                unit = new BigDecimal(numerator.divide(numerator.gcd(denominator)));
            }

            if (s.has("x-precision")) {
                BigDecimal limit = BigDecimal.TEN
                    .pow(s.path("x-precision").asInt() - s.path("x-scale").asInt())
                    .subtract(BigDecimal.ONE.scaleByPowerOfTen(-s.path("x-scale").asInt()));
                min = higher(min, limit.negate());
                max = lower(max, limit);
            }

            BigDecimal low = min == null ? null : min.divide(unit, 0, RoundingMode.CEILING).multiply(unit);
            BigDecimal high = max == null ? null : max.divide(unit, 0, RoundingMode.FLOOR).multiply(unit);
            if (!wire && min != null && s.path("exclusiveMinimum").asBoolean() && low.compareTo(min) == 0) {
                low = low.add(unit);
            }

            if (!wire && max != null && s.path("exclusiveMaximum").asBoolean() && high.compareTo(max) == 0) {
                high = high.subtract(unit);
            }

            BigDecimal value = BigDecimal.ONE.divide(unit, 0, RoundingMode.CEILING).multiply(unit);
            if (low != null && value.compareTo(low) < 0) {
                value = low;
            }

            if (high != null && value.compareTo(high) > 0) {
                value = high;
            }

            if ((low == null || value.compareTo(low) >= 0) && (high == null || value.compareTo(high) <= 0)) {
                return value.stripTrailingZeros();
            } else {
                throw new IllegalArgumentException();
            }
        } else {
            throw new IllegalArgumentException();
        }
    }

    private BigDecimal bound(JsonNode s, String key) {
        if (!s.has(key)) {
            return null;
        } else {
            BigDecimal n = new BigDecimal(s.get(key).asText());
            if (n.precision() <= 38 && Math.abs((long)n.scale()) <= 38L && n.abs().compareTo(new BigDecimal("1e38")) <= 0) {
                return n;
            } else {
                throw new IllegalArgumentException();
            }
        }
    }

    private static BigDecimal higher(BigDecimal a, BigDecimal b) {
        return a == null ? b : a.max(b);
    }

    private static BigDecimal lower(BigDecimal a, BigDecimal b) {
        return a == null ? b : a.min(b);
    }

    private boolean fits(JsonNode s, JsonNode v) {
        return new SampleValidator().validateSchema(s, v).path("verdict").asText().equals("PASS");
    }

    private static JsonNode gap(Set<String> issues, String text) {
        issues.add(text);
        return NullNode.instance;
    }

    private static List<String> keys(JsonNode n) {
        List<String> keys = new ArrayList<>();
        n.fieldNames().forEachRemaining(keys::add);
        Collections.sort(keys);
        return keys;
    }
}
