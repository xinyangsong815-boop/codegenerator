package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.Iterator;

public final class SchemaConstraints {
    private SchemaConstraints() {
    }

    public static boolean patternSupported(String pattern) {
        return Arrays.asList("\\S", "^[1-9][0-9]*$", "^(0|[1-9][0-9]*)$", "^-?(0|[1-9][0-9]*)$", "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$").contains(pattern);
    }

    public static boolean equal(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        } else if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            } else {
                for (int i = 0; i < a.size(); i++) {
                    if (!equal(a.get(i), b.get(i))) {
                        return false;
                    }
                }

                return true;
            }
        } else if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            } else {
                Iterator<String> names = a.fieldNames();

                while (names.hasNext()) {
                    String key = names.next();
                    if (!b.has(key) || !equal(a.get(key), b.get(key))) {
                        return false;
                    }
                }

                return true;
            }
        } else {
            return a.equals(b);
        }
    }

    /** Supported anyOf subset: alternatives contain only declared required property names. */
    public static boolean requiredAlternatives(JsonNode schema) {
        JsonNode alternatives = schema.path("anyOf");
        if (!schema.path("type").asText().equals("object") || !schema.path("properties").isObject()
                || !alternatives.isArray() || alternatives.isEmpty() || alternatives.size() > 200) return false;
        for (JsonNode alternative : alternatives) {
            JsonNode names = alternative.path("required");
            if (!alternative.isObject() || alternative.size() != 1 || !names.isArray() || names.isEmpty() || names.size() > 200) return false;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (JsonNode name : names) {
                if (!name.isTextual() || !schema.path("properties").has(name.asText()) || !seen.add(name.asText())) return false;
            }
        }
        return true;
    }

    public static boolean matchesRequiredAlternatives(JsonNode schema, JsonNode value) {
        if (!value.isObject() || !requiredAlternatives(schema)) return false;
        for (JsonNode alternative : schema.path("anyOf")) {
            boolean present = true;
            for (JsonNode name : alternative.path("required")) present &= value.has(name.asText());
            if (present) return true;
        }
        return false;
    }
}
