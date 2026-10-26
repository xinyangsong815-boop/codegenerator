package cn.bank.devtool.regression;

import cn.bank.devtool.Util;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Resource tracking stores bounded identifiers, never response documents. */
public final class ResourcePrivacy {
    private ResourcePrivacy() { }

    public static boolean identifier(JsonNode value) {
        return value.isIntegralNumber() && value.asText().length() <= 128
            || value.isTextual() && value.asText().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    /** Also protects exports of records created by earlier versions. Original business verdict stays intact. */
    public static ObjectNode sanitize(JsonNode run) {
        ObjectNode safe = (ObjectNode)run.deepCopy();
        ArrayNode resources = Util.JSON.createArrayNode();
        boolean removed = run.has("resources") && !run.path("resources").isArray();
        for (JsonNode resource : run.path("resources")) {
            if (!resource.isObject() || !identifier(resource.path("id"))) { removed = true; continue; }
            ObjectNode item = resources.addObject();
            item.put("stepId", resource.path("stepId").asText());
            item.set("id", resource.path("id").deepCopy());
            if (resource.size() != 2) removed = true;
        }
        if (run.has("resources")) safe.set("resources", resources);
        if (removed) safe.put("resourceTrackingIncomplete", true)
            .put("resourceTrackingWarning", "旧版或不安全的资源字段已移除；需人工核对测试数据清理")
            .put("overallVerdict", "INCOMPLETE");
        return safe;
    }
}
