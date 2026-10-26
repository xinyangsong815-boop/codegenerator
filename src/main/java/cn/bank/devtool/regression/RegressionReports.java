package cn.bank.devtool.regression;

import cn.bank.devtool.Util;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;

/** Reports include both business execution and cleanup; names are safe for XML 1.0. */
public final class RegressionReports {
    private RegressionReports() { }

    /** Preserve the business verdict while exposing cleanup and completion to consumers. */
    public static String overall(JsonNode run) {
        if (!run.path("state").asText().equals("FINISHED")) return "INCOMPLETE";
        if (run.path("resourceTrackingIncomplete").asBoolean()) return "INCOMPLETE";
        String business = run.path("verdict").asText("INCOMPLETE");
        if (!business.equals("PASS")) return business;
        for (JsonNode step : run.path("cleanupSteps")) {
            if (step.path("verdict").asText().equals("FAIL")) return "FAIL";
            if (step.path("verdict").asText().equals("ERROR")) return "ERROR";
            if (!step.path("state").asText().equals("FINISHED") || !step.path("verdict").asText().equals("PASS")) return "INCOMPLETE";
        }
        String cleanup = run.path("cleanupStatus").asText();
        if (cleanup.equals("FAILED")) return "FAIL";
        if (!run.path("steps").isArray() || run.path("steps").isEmpty()) return "INCOMPLETE";
        for (JsonNode step : run.path("steps")) {
            if (step.path("verdict").asText().equals("FAIL")) return "FAIL";
            if (step.path("verdict").asText().equals("ERROR")) return "ERROR";
            if (!step.path("state").asText().equals("FINISHED") || !step.path("verdict").asText().equals("PASS")) return "INCOMPLETE";
        }
        if (run.path("scenarioSnapshot").path("steps").isArray()
            && run.path("scenarioSnapshot").path("steps").size() != run.path("steps").size()) return "INCOMPLETE";
        // DONE claims automatic cleanup completed. A truncated or missing result array
        // cannot prove that every declared cleanup step executed. Manual confirmation
        // and NOT_REQUIRED remain separate, explicit lifecycle outcomes.
        if (cleanup.equals("DONE") && run.path("scenarioSnapshot").path("cleanupSteps").isArray()
            && (!run.path("cleanupSteps").isArray()
                || run.path("scenarioSnapshot").path("cleanupSteps").size() != run.path("cleanupSteps").size())) return "INCOMPLETE";
        return Arrays.asList("NOT_REQUIRED", "DONE", "MANUALLY_CONFIRMED").contains(cleanup) ? "PASS" : "INCOMPLETE";
    }

    public static byte[] html(JsonNode run) {
        run = ResourcePrivacy.sanitize(run);
        StringBuilder out = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>业务回归报告</title><style>body{font:16px sans-serif;max-width:1100px;margin:32px auto;padding:20px;color:#213047}table{border-collapse:collapse;width:100%}td,th{padding:12px;border:1px solid #ddd;text-align:left}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f4f6fa;padding:16px}</style><h1>业务场景回归报告</h1>");
        out.append("<p>").append(Util.html(run.path("name").asText())).append(" · 运行 ").append(Util.html(run.path("id").asText()))
                .append("</p><p>整体结论：<b>").append(Util.html(overall(run))).append("</b>；业务断言：<b>").append(Util.html(run.path("verdict").asText())).append("</b>；数据处理：<b>")
                .append(Util.html(run.path("cleanupStatus").asText())).append("</b></p><p>断言通过不代表数据已清理。取消不会撤销已提交操作。报告保存受限的资源标识，不保存响应正文或运行凭证。</p>")
                .append("<table><tr><th>阶段</th><th>步骤</th><th>接口</th><th>结论</th><th>HTTP</th><th>耗时ms</th><th>副作用状态</th></tr>");
        for (String section : Arrays.asList("steps", "cleanupSteps")) {
            for (JsonNode step : run.path(section)) {
                out.append("<tr><td>").append(section.equals("steps") ? "主流程" : "清理").append("</td>");
                for (String value : Arrays.asList(step.path("name").asText(), step.path("method").asText() + " " + step.path("path").asText(),
                        step.path("verdict").asText(step.path("state").asText()), step.path("status").asText(),
                        step.path("durationMs").asText(), step.path("effectState").asText())) {
                    out.append("<td>").append(Util.html(value)).append("</td>");
                }
                out.append("</tr>");
            }
        }
        return Util.bytes(out.append("</table><h2>断言与执行记录</h2><pre>").append(Util.html(Util.pretty(run))).append("</pre></html>").toString());
    }

    public static byte[] junit(JsonNode run) {
        run = ResourcePrivacy.sanitize(run);
        int tests = 0, failures = 0, errors = 0, skipped = 0;
        StringBuilder cases = new StringBuilder();
        for (String section : Arrays.asList("steps", "cleanupSteps")) {
            for (JsonNode step : run.path(section)) {
                tests++;
                String verdict = step.path("verdict").asText();
                cases.append("<testcase name=\"").append(xml((section.equals("cleanupSteps") ? "清理 · " : "") + step.path("id").asText() + " · " + step.path("name").asText()))
                        .append("\" time=\"").append(Math.max(0.0, step.path("durationMs").asDouble()) / 1000.0).append("\">");
                if (step.path("state").asText().equals("SKIPPED")) { skipped++; cases.append("<skipped/>"); }
                else if (verdict.equals("FAIL")) { failures++; cases.append("<failure message=\"业务断言失败，详见脱敏报告\"/>"); }
                else if (!step.path("state").asText().equals("FINISHED") || !verdict.equals("PASS")) { errors++; cases.append("<error message=\"执行未完成或异常\"/>"); }
                cases.append("</testcase>");
            }
        }
        if (!Arrays.asList("NOT_REQUIRED", "DONE", "MANUALLY_CONFIRMED").contains(run.path("cleanupStatus").asText())) {
            tests++; failures++;
            cases.append("<testcase name=\"测试数据处理\"><failure message=\"测试数据清理或重置待核对\"/></testcase>");
        }
        if (run.path("resourceTrackingIncomplete").asBoolean() || !run.path("state").asText().equals("FINISHED") || !overall(run).equals("PASS") && failures == 0 && errors == 0) {
            tests++; errors++;
            cases.append("<testcase name=\"执行完成状态\"><error message=\"运行尚未结束或已中断\"/></testcase>");
        }
        return Util.bytes("<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"" + xml(run.path("name").asText())
                + "\" tests=\"" + tests + "\" failures=\"" + failures + "\" errors=\"" + errors + "\" skipped=\"" + skipped + "\">" + cases + "</testsuite>");
    }

    private static String xml(String value) {
        StringBuilder valid = new StringBuilder();
        value.codePoints().forEach(cp -> valid.appendCodePoint(cp == 9 || cp == 10 || cp == 13 || cp >= 0x20 && cp <= 0xD7FF
                || cp >= 0xE000 && cp <= 0xFFFD || cp >= 0x10000 && cp <= 0x10FFFF ? cp : 0xFFFD));
        return Util.html(valid.toString());
    }
}
