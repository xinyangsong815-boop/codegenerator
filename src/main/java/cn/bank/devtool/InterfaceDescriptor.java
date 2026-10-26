package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

public final class InterfaceDescriptor {
    private InterfaceDescriptor() {
    }

    public static ObjectNode describe(JsonNode doc, String method, String path) {
        Set<String> issues = new LinkedHashSet<>();
        JsonNode item = new ContractService().schema(doc.path("paths").path(path), doc, issues);
        JsonNode op = item.path(method.toLowerCase(Locale.ROOT));
        String verb = method.toUpperCase(Locale.ROOT);
        String name = op.path("summary").asText().trim();
        if (name.isEmpty()) {
            name = op.path("operationId").asText().trim();
        }

        if (name.isEmpty()) {
            name = verb + " " + path;
        }

        ObjectNode out = Util.obj()
            .put("operationKey", verb + " " + path)
            .put("method", verb)
            .put("path", path)
            .put("summary", line(name, 300))
            .put("operationId", line(op.path("operationId").asText(), 200))
            .put("description", line(op.path("description").asText(), 1200));
        ArrayNode tags = out.putArray("tags");

        for (JsonNode tag : op.path("tags")) {
            if (tags.size() < 12) {
                tags.add(line(tag.asText(), 100));
            }
        }

        if (op.path("x-source").isObject()) {
            out.set("source", op.get("x-source").deepCopy());
        }

        ObjectNode request = out.putObject("requestDefinition");
        ArrayNode params = request.putArray("parameters");
        Map<String, JsonNode> merged = new LinkedHashMap<>();

        for (JsonNode list : Arrays.asList(item.path("parameters"), op.path("parameters"))) {
            for (JsonNode p : list) {
                merged.put(ContractService.parameterKey(p), p);
            }
        }

        for (JsonNode p : merged.values()) {
            if (params.size() >= 80) {
                break;
            }

            params.addObject()
                .put("name", line(p.path("name").asText(), 100))
                .put("in", p.path("in").asText())
                .put("required", p.path("required").asBoolean())
                .put("type", p.path("schema").path("type").asText())
                .put("description", line(p.path("description").asText(p.path("schema").path("description").asText()), 300));
        }

        JsonNode body = op.path("requestBody");
        request.put("bodyRequired", body.path("required").asBoolean());
        flatten(ContentTypes.match(body.path("content"), "application/json", issues).path("schema"), "", false, request.putArray("bodyFields"), 0);
        return out;
    }

    private static void flatten(JsonNode s, String pointer, boolean required, ArrayNode rows, int depth) {
        if (depth <= 6 && rows.size() < 80 && !s.isMissingNode()) {
            if (!s.path("type").asText().equals("object")) {
                ObjectNode row = rows.addObject()
                    .put("pointer", pointer)
                    .put("type", s.path("type").asText())
                    .put("required", required)
                    .put("description", line(s.path("description").asText(), 300));

                for (String key : Arrays.asList("format", "pattern", "minLength", "maxLength", "minimum", "maximum", "x-java-type", "x-precision", "x-scale")) {
                    if (s.has(key)) {
                        row.set(key, s.get(key));
                    }
                }
            } else {
                Iterator<Entry<String, JsonNode>> it = s.path("properties").fields();

                while (it.hasNext()) {
                    Entry<String, JsonNode> e = it.next();
                    boolean needed = false;

                    for (JsonNode keyx : s.path("required")) {
                        if (keyx.asText().equals(e.getKey())) {
                            needed = true;
                        }
                    }

                    flatten(e.getValue(), pointer + "/" + Util.ptr(e.getKey()), needed, rows, depth + 1);
                }
            }
        }
    }

    private static String line(String value, int max) {
        String clean = value.replaceAll("[\\p{Cntrl}]", " ");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }
}
