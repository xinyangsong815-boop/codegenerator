package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;

public final class Reports {
    private Reports() {
    }

    public static byte[] html(String title, JsonNode report) {
        if (report.has("openapi") && report.path("paths").isObject()) return ContractReports.html(title, report);
        if (report.path("operations").isArray()) {
            return MockReports.html(title, report);
        } else {
            StringBuilder b = new StringBuilder(
                "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"
            );
            b.append(Util.html(title))
                .append(
                    "</title><style>body{font:15px/1.7 sans-serif;color:#203044;max-width:1200px;margin:36px auto;padding:0 24px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #cad4e0;padding:9px;text-align:left;vertical-align:top}th{background:#edf2f8}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f5f7fa;padding:18px}.FAIL{color:#b42318}.WARN,.INCOMPLETE{color:#9c6500}.PASS{color:#157347}</style><h1>"
                )
                .append(Util.html(title))
                .append("</h1><p>报告固定生成时的数据；样例、页面登记和覆盖缺口均不能替代业务回归测试。</p>");
            b.append("<h2 class=\"")
                .append(Util.html(report.path("verdict").asText()))
                .append("\">结论：")
                .append(Util.html(report.path("verdict").asText()))
                .append("</h2>");
            if (report.path("findings").isArray()) {
                b.append("<table><thead><tr><th>级别</th><th>接口</th><th>规则</th><th>字段与原因</th><th>关联页面</th></tr></thead><tbody>");

                for (JsonNode f : report.path("findings")) {
                    b.append("<tr><td>")
                        .append(Util.html(f.path("severity").asText()))
                        .append("</td><td>")
                        .append(Util.html(f.path("method").asText() + " " + f.path("path").asText()))
                        .append("</td><td>")
                        .append(Util.html(f.path("rule").asText()))
                        .append("</td><td>")
                        .append(Util.html(f.path("schemaPointer").asText()))
                        .append("<br>")
                        .append(Util.html(f.path("reason").asText()))
                        .append("</td><td>")
                        .append(Util.html(f.path("pages").toString()))
                        .append("</td></tr>");
                }

                b.append("</tbody></table>");
            }

            b.append("<h2>完整结果及覆盖范围</h2><pre>").append(Util.html(Util.pretty(report))).append("</pre></html>");
            return Util.bytes(b.toString());
        }
    }
}
