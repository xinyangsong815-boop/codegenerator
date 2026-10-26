package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

final class MockReports {
    private MockReports() {
    }

    static byte[] html(String title, JsonNode report) {
        StringBuilder b = new StringBuilder(
            "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"
        );
        b.append(h(title))
            .append(
                "</title><style>body{font:15px/1.7 sans-serif;max-width:1200px;margin:30px auto;padding:0 20px;color:#203044}article{border:1px solid #cad4e0;border-radius:10px;padding:20px;margin:24px 0}table{border-collapse:collapse;width:100%}td,th{border:1px solid #cad4e0;padding:8px;text-align:left;vertical-align:top}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f5f7fa;padding:14px}h2{margin-bottom:4px}.PASS{color:#157347}.INCOMPLETE{color:#9c6500}.FAIL{color:#b42318}</style><h1>"
            )
            .append(h(title))
            .append("</h1><p>来源契约：")
            .append(h(report.path("sourceTitle").asText()))
            .append(" · ")
            .append(h(report.path("sourceContractId").asText()))
            .append("</p><p>结论：<strong>")
            .append(h(report.path("verdict").asText()))
            .append("</strong> · ")
            .append(h(report.path("metrics").toString()))
            .append("</p><p>")
            .append(h(report.path("scope").asText()))
            .append("</p>");
        b.append("<details open><summary>覆盖问题</summary><ul>");

        for (JsonNode issue : report.path("coverage").path("issues")) {
            b.append("<li>").append(h(issue.asText())).append("</li>");
        }

        b.append("</ul></details>");
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();

        for (JsonNode op : report.path("operations")) {
            String key = op.path("operationKey").asText(op.path("method").asText() + " " + op.path("path").asText());
            if (!groups.containsKey(key)) {
                groups.put(key, new ArrayList<>());
            }

            groups.get(key).add(op);
        }

        for (Entry<String, List<JsonNode>> entry : groups.entrySet()) {
            JsonNode info = entry.getValue().get(0).path("interface");
            b.append("<article><h2>")
                .append(h(info.path("summary").asText()))
                .append("</h2><p><strong>")
                .append(h(entry.getKey()))
                .append("</strong> · ")
                .append(h(info.path("operationId").asText()))
                .append("</p><p>")
                .append(h(info.path("description").asText()))
                .append("</p>");
            if (info.has("source")) {
                b.append("<p>源码：").append(h(info.path("source").toString())).append("</p>");
            }

            b.append("<details><summary>请求参数、字段及约束</summary><pre>")
                .append(h(Util.pretty(info.path("requestDefinition"))))
                .append("</pre></details><table><tr><th>响应</th><th>校验</th><th>请求覆盖</th><th>可访问</th></tr>");

            for (JsonNode op : entry.getValue()) {
                b.append("<tr><td>HTTP ")
                    .append(op.path("status").asInt())
                    .append(" · ")
                    .append(h(op.path("responseDescription").asText()))
                    .append("</td><td>")
                    .append(h(op.path("validation").path("verdict").asText()))
                    .append("</td><td>")
                    .append(op.path("requestComplete").asBoolean() ? "已合成" : "需补充")
                    .append("</td><td>")
                    .append(op.path("available").asBoolean() ? "是" : "否")
                    .append("</td></tr>");
            }

            b.append("</table>");

            for (JsonNode op : entry.getValue()) {
                b.append("<details><summary>HTTP ")
                    .append(op.path("status").asInt())
                    .append(" 请求、响应与诊断</summary><pre>")
                    .append(h(Util.pretty(op)))
                    .append("</pre></details>");
            }

            b.append("<details><summary>此接口测试用例</summary><table><tr><th>用例 / 类型</th><th>证据范围</th><th>内容</th></tr>");

            for (JsonNode c : report.path("cases")) {
                if (c.path("operationKey").asText().equals(entry.getKey())) {
                    b.append("<tr><td>")
                        .append(h(c.path("id").asText() + " · " + c.path("kind").asText()))
                        .append("</td><td>")
                        .append(h(c.path("assertion").asText()))
                        .append("</td><td>")
                        .append(h(c.path("note").asText()))
                        .append("<details><summary>输入</summary><pre>")
                        .append(h(Util.pretty(c.path("request"))))
                        .append("</pre></details></td></tr>");
                }
            }

            b.append("</table></details></article>");
        }

        return Util.bytes(b.append("</html>").toString());
    }

    private static String h(String s) {
        return Util.html(s);
    }
}
