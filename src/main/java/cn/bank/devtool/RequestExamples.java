package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public final class RequestExamples {
    private RequestExamples() {
    }

    public static ObjectNode bodies(ResolvedSpec r) {
        ObjectNode rows = Util.obj();

        for (Map<String, Object> api : r.endpoints.values()) {
            rows.set(api.get("operation").toString(), body(r, api, false));
        }

        return rows;
    }

    private static ObjectNode body(ResolvedSpec r, Map<String, Object> api, boolean variables) {
        ObjectNode data = Util.obj();
        String op = api.get("operation").toString();
        List<?> selected = (List<?>)api.get("fields");
        if (op.equals("PAGE")) {
            data.put("page", 1).put("size", 20);
        }

        for (Spec.Field f : r.spec.fields()) {
            if (selected.contains(f.name())) {
                JsonNode value = value(r, f);
                if (op.equals("PAGE") && f.query().equals("RANGE")) {
                    data.set(f.name() + "From", value);
                    data.set(f.name() + "To", value);
                } else if (op.equals("PAGE") && f.query().equals("IN")) {
                    data.putArray(f.name()).add(value);
                } else {
                    data.set(f.name(), value);
                }
            }
        }

        if (op.equals("UPDATE") && ProductionPolicy.locking(r.spec)) {
            data.set("expectedVersion", numeric(r, "0"));
        }

        if (op.equals("DELETE") && ProductionPolicy.deleteVersion(r.spec) && api.get("idIn").equals("BODY")) {
            data.set("expectedVersion", numeric(r, "0"));
        }

        if (Boolean.TRUE.equals(api.get("wrapped"))) {
            ObjectNode outer = Util.obj();
            outer.set(api.get("bodyProperty").toString(), data);
            data = outer;
        }

        if (api.get("idIn").equals("BODY")) {
            data.set("id", numeric(r, "1"));
        }

        return data;
    }

    private static JsonNode numeric(ResolvedSpec r, String v) {
        return (JsonNode)(r.stringNumbers ? new TextNode(v) : Util.JSON.getNodeFactory().numberNode(Long.parseLong(v)));
    }

    private static JsonNode value(ResolvedSpec r, Spec.Field f) {
        String var2 = f.javaType();
        switch (var2) {
            case "String":
                return new TextNode("示例".substring(0, Math.min(2, f.maxLength())));
            case "BigDecimal":
                BigDecimal v = BigDecimal.ONE.movePointLeft(f.scale());
                return (JsonNode)(r.stringNumbers ? new TextNode(v.toPlainString()) : Util.JSON.getNodeFactory().numberNode(v));
            case "Long":
                return numeric(r, "1");
            case "Integer":
                return Util.JSON.getNodeFactory().numberNode(1);
            case "Boolean":
                return BooleanNode.TRUE;
            case "LocalDate":
                return new TextNode("2026-01-01");
            default:
                return new TextNode("2026-01-01T09:00:00");
        }
    }

    public static String render(ResolvedSpec r) {
        StringBuilder text = new StringBuilder(
            "@baseUrl = http://127.0.0.1:8080\n@recordId = REPLACE_WITH_CREATED_ID\n@expectedVersion = REPLACE_WITH_CURRENT_VERSION\n# 仅用于专用测试环境；ID和版本须替换为本次真实返回值。权限头／会话按宿主填写。\n\n"
        );

        for (Map<String, Object> api : r.endpoints.values()) {
            String op = api.get("operation").toString();
            String in = api.get("idIn").toString();
            ObjectNode data = body(r, api, true);
            List<String> query = new ArrayList<>();
            if (in.equals("QUERY")) {
                query.add("id={{recordId}}");
            }

            if (op.equals("DELETE") && ProductionPolicy.deleteVersion(r.spec) && !in.equals("BODY")) {
                query.add("expectedVersion={{expectedVersion}}");
            }

            if (api.get("requestIn").equals("QUERY")) {
                Iterator<Entry<String, JsonNode>> it = data.fields();

                while (it.hasNext()) {
                    Entry<String, JsonNode> e = it.next();
                    if (e.getValue().isArray()) {
                        for (JsonNode v : e.getValue()) {
                            query.add(e.getKey() + "=" + encode(v.asText()));
                        }
                    } else {
                        query.add(e.getKey() + "=" + encode(e.getValue().asText()));
                    }
                }
            }

            text.append("### ")
                .append(op)
                .append(" · ")
                .append(api.get("url"))
                .append('\n')
                .append(api.get("method"))
                .append(" {{baseUrl}}")
                .append(api.get("url").toString().replace("{id}", "{{recordId}}"));
            if (!query.isEmpty()) {
                text.append('?').append(String.join("&", query));
            }

            text.append('\n');
            if (Boolean.TRUE.equals(api.get("body"))) {
                String body = Util.pretty(data);
                if (in.equals("BODY")) {
                    body = body.replace(
                        r.stringNumbers ? "\"id\" : \"1\"" : "\"id\" : 1", r.stringNumbers ? "\"id\" : \"{{recordId}}\"" : "\"id\" : {{recordId}}"
                    );
                }

                if (op.equals("UPDATE") && ProductionPolicy.locking(r.spec) || op.equals("DELETE") && ProductionPolicy.deleteVersion(r.spec)) {
                    body = body.replace(
                        r.stringNumbers ? "\"expectedVersion\" : \"0\"" : "\"expectedVersion\" : 0",
                        r.stringNumbers ? "\"expectedVersion\" : \"{{expectedVersion}}\"" : "\"expectedVersion\" : {{expectedVersion}}"
                    );
                }

                text.append("Content-Type: application/json\n\n").append(body).append('\n');
            }

            text.append('\n');
        }

        return text.toString();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception var2) {
            throw new IllegalStateException(var2);
        }
    }
}
