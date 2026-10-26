package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Match an actual Content-Type to the most specific OpenAPI media range. */
final class ContentTypes {
    private ContentTypes() { }
    static String base(String media) { return media == null ? "" : media.split(";", 2)[0].trim().toLowerCase(Locale.ROOT); }
    static String normalize(String media) {
        int semicolon = media.indexOf(';');
        return semicolon < 0 ? base(media) : base(media) + ";" + media.substring(semicolon + 1).trim();
    }
    static Map<String, JsonNode> normalized(JsonNode content) {
        Map<String, JsonNode> values = new LinkedHashMap<String, JsonNode>();
        content.fields().forEachRemaining(e -> values.putIfAbsent(normalize(e.getKey()), e.getValue()));
        return values;
    }
    static boolean json(String media) {
        String type = base(media);
        return !type.contains("*") && (type.equals("application/json") || type.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+\\+json"));
    }
    static JsonNode match(JsonNode content, String actual, Set<String> issues) {
        String value = base(actual); JsonNode best = MissingNode.getInstance(); int specificity = -1;
        java.util.Iterator<Map.Entry<String, JsonNode>> entries = content.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next(); String declared = base(entry.getKey());
            int rank = declared.equals(value) ? 2 : declared.equals(value.split("/", 2)[0] + "/*") ? 1 : declared.equals("*/*") ? 0 : -1;
            if (rank > specificity) {
                best = entry.getValue(); specificity = rank;
                if (entry.getKey().contains(";")) issues.add("媒体类型参数尚未校验：" + entry.getKey());
            }
        }
        return best;
    }
}
