package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline, escaped interface documentation from the same prepared contract as other tools. */
public final class ContractReports {
    private ContractReports() { }
    public static byte[] html(String title, JsonNode document) { return render(title, document, true); }
    public static byte[] markdown(String title, JsonNode document) { return render(title, document, false); }

    private static byte[] render(String title, JsonNode input, boolean html) {
        ContractService service = new ContractService();
        JsonNode doc = service.prepare(input);
        JsonNode operations = service.index(doc);
        Page page = new Page(html, title);
        page.heading(1, title);
        page.text("契约版本：" + doc.path("info").path("version").asText("未填写") + "；接口数：" + operations.size());
        Set<String> issues = new LinkedHashSet<String>();
        for (JsonNode issue : doc.path("x-coverage").path("issues")) issues.add(issue.asText());
        for (JsonNode operation : operations) for (JsonNode issue : operation.path("issues")) issues.add(issue.asText());
        page.heading(2, issues.isEmpty() ? "覆盖范围：已覆盖声明结构" : "覆盖范围：检查不完整");
        page.text("本文记录静态契约；必填指所在父对象存在时的要求。认证是否启用、业务规则、实际响应及生产部署仍需运行验收。未声明的约束不代表允许任意行为。");
        page.text("字段约束同时保留原始关键字和值；总有效位 x-precision 与最大小数位 x-scale 联合限制整数位，整数位最多为两者之差。标有未识别或检查不完整的约束需要复核，展示不表示已经验证。");
        for (String issue : issues) page.text("需复核：" + issue);
        if (doc.path("components").has("securitySchemes")) {
            page.heading(2, "认证方式定义（components.securitySchemes）");
            page.text("以下保留认证类型、凭证位置、请求头名称和流程声明；文档不会验证真实身份系统是否启用。security 数组中的各项为可选方案，同一项内的多个认证方式须同时满足。");
            page.code(Util.pretty(doc.path("components").path("securitySchemes")));
        }
        List<String[]> overview = new ArrayList<String[]>();
        for (JsonNode row : operations) overview.add(new String[]{row.path("method").asText(), row.path("path").asText(), row.path("value").path("summary").asText(), row.path("value").path("operationId").asText()});
        page.table(new String[]{"方法", "路径", "说明", "operationId"}, overview);
        for (JsonNode row : operations) {
            JsonNode op = row.path("value");
            page.heading(2, row.path("method").asText() + " " + row.path("path").asText());
            if (op.has("summary")) page.text(op.path("summary").asText());
            if (op.has("description")) page.text(op.path("description").asText());
            if (op.has("x-source")) page.text("源码：" + op.path("x-source").path("file").asText() + ":" + op.path("x-source").path("line").asText());
            servers(page, service, doc, row, op);
            JsonNode security = op.has("security") ? op.path("security") : doc.path("security");
            if (!security.isMissingNode()) { page.text("认证声明（实际启用需验收）："); page.code(Util.pretty(security)); }
            page.heading(3, "参数");
            List<String[]> parameters = new ArrayList<String[]>();
            for (JsonNode p : op.path("parameters")) parameters.add(new String[]{p.path("in").asText(), p.path("name").asText(), kind(p.path("schema")), p.path("required").asBoolean() ? "是" : "否", p.path("description").asText(), constraints(p.path("schema"))});
            page.table(new String[]{"位置", "名称", "类型", "必填", "说明", "约束／默认值"}, parameters);
            // Query object/array details would otherwise be lost in a one-line parameter summary.
            for (JsonNode p : op.path("parameters")) if (p.path("schema").has("properties") || p.path("schema").has("items")) {
                page.heading(4, "参数结构：" + p.path("name").asText()); fields(page, p.path("schema"));
            }
            JsonNode body = op.path("requestBody");
            page.heading(3, "请求体");
            if (body.isMissingNode()) page.text("未声明请求体。");
            else { page.text(body.path("required").asBoolean() ? "请求体必填。" : "请求体可省略。"); media(page, body.path("content")); }
            page.heading(3, "响应");
            Iterator<Map.Entry<String,JsonNode>> responses = op.path("responses").fields();
            while (responses.hasNext()) {
                Map.Entry<String,JsonNode> entry = responses.next(); JsonNode response = entry.getValue();
                page.heading(4, entry.getKey() + " " + response.path("description").asText());
                List<String[]> headers = new ArrayList<String[]>();
                response.path("headers").fields().forEachRemaining(h -> headers.add(new String[]{h.getKey(), kind(h.getValue().path("schema")), h.getValue().path("required").asBoolean() ? "是" : "否", h.getValue().path("description").asText(), constraints(h.getValue().path("schema"))}));
                if (!headers.isEmpty()) page.table(new String[]{"响应头", "类型", "响应头必填", "说明", "约束"}, headers);
                if (response.path("content").isMissingNode() || response.path("content").isEmpty()) page.text("未声明响应体。HEAD、1xx、204、205、304 响应不得发送正文。");
                else media(page, response.path("content"));
            }
        }
        page.heading(2, "如何使用这份文档");
        page.text("OpenAPI JSON 是可供其他工具读取的完整契约。本说明对递归、超大字段表和超长示例有展示限制；请同时保留原始 openapi.json，并优先处理覆盖缺口。");
        return page.finish().getBytes(StandardCharsets.UTF_8);
    }

    /** Show the effective access information, preserving operation/path/document override rules. */
    private static void servers(Page page, ContractService service, JsonNode doc, JsonNode row, JsonNode operation) {
        Set<String> issues = new LinkedHashSet<String>();
        JsonNode item = service.schema(doc.path("paths").path(row.path("path").asText()), doc, issues);
        JsonNode values;
        String source;
        if (operation.has("servers")) { values = operation.path("servers"); source = "Operation.servers"; }
        else if (item.has("servers")) { values = item.path("servers"); source = "Path Item.servers"; }
        else { values = doc.path("servers"); source = "文档 servers"; }
        page.heading(3, "有效服务地址（来源：" + source + "）");
        if (values.isMissingNode() || values.isArray() && values.isEmpty()) {
            page.text("未配置服务地址，按 OpenAPI 默认相对地址 / 解释；调用时需由宿主或部署配置提供实际原点。");
        } else {
            page.text("接口路径追加到以下服务 URL；变量使用 default 或所列 enum 值，实际地址由项目部署配置确认。相对 URL 需结合契约文档的来源地址解析。");
            page.code(Util.pretty(values));
        }
        for (String issue : issues) page.text("服务地址定义需复核：" + issue);
    }

    private static void media(Page page, JsonNode content) {
        Iterator<Map.Entry<String,JsonNode>> media = content.fields();
        while (media.hasNext()) {
            Map.Entry<String,JsonNode> entry = media.next(); page.text("媒体类型：" + entry.getKey());
            JsonNode schema = entry.getValue().path("schema");
            if (schema.isMissingNode()) page.text("未声明 Schema，字段检查不完整。");
            else fields(page, schema);
            if (entry.getValue().has("example")) { page.text("文档示例："); page.code(Util.pretty(entry.getValue().path("example"))); }
            entry.getValue().path("examples").fields().forEachRemaining(example -> {
                page.text("文档示例：" + example.getKey());
                if (example.getValue().has("value")) page.code(Util.pretty(example.getValue().path("value")));
                else page.text("示例值未内嵌；请核对 OpenAPI 中的引用或外部示例声明。");
            });
            if (schema.has("example")) { page.text("Schema 示例："); page.code(Util.pretty(schema.path("example"))); }
        }
    }

    private static void fields(Page page, JsonNode schema) {
        List<String[]> rows = new ArrayList<String[]>(); boolean[] truncated = {false};
        flatten(schema, "$", false, rows, 0, truncated);
        page.table(new String[]{"字段", "类型", "父对象内必填", "可为 null", "说明", "约束／默认值"}, rows);
        if (truncated[0]) page.text("字段展示达到 8 层或 500 行限制；其余定义请查看完整 OpenAPI JSON。");
    }

    private static void flatten(JsonNode schema, String path, boolean required, List<String[]> rows, int depth, boolean[] truncated) {
        if (rows.size() >= 500 || depth > 8) { truncated[0] = true; return; }
        rows.add(new String[]{path, kind(schema), path.equals("$") ? "—" : required ? "是" : "否", schema.path("nullable").asBoolean() ? "是" : "否", schema.path("description").asText(), constraints(schema)});
        Iterator<Map.Entry<String,JsonNode>> children = schema.path("properties").fields();
        while (children.hasNext()) {
            Map.Entry<String,JsonNode> child = children.next(); boolean needed = false;
            for (JsonNode name : schema.path("required")) if (name.asText().equals(child.getKey())) needed = true;
            flatten(child.getValue(), path + "/" + Util.ptr(child.getKey()), needed, rows, depth + 1, truncated);
            if (rows.size() >= 500) { if (children.hasNext()) truncated[0] = true; break; }
        }
        if (schema.has("items")) flatten(schema.path("items"), path + "/*", false, rows, depth + 1, truncated);
        if (schema.path("additionalProperties").isObject()) flatten(schema.path("additionalProperties"), path + "/{key}", false, rows, depth + 1, truncated);
    }

    private static String kind(JsonNode schema) {
        String type = schema.path("type").asText("未明确声明");
        if (schema.has("format")) type += " (" + schema.path("format").asText() + ")";
        return type;
    }

    private static String constraints(JsonNode schema) {
        List<String> values = new ArrayList<String>();
        Map<String,String> labels = CapabilityRegistry.readableConstraints();
        for (Map.Entry<String,String> entry : labels.entrySet()) {
            String key = entry.getKey();
            if (schema.has(key) && !(key.equals("additionalProperties") && schema.path(key).isObject())) values.add(entry.getValue() + " " + key + "=" + schema.path(key).toString());
        }
        if (schema.has("x-precision") && schema.has("x-scale") && schema.path("x-precision").canConvertToInt() && schema.path("x-scale").canConvertToInt()) values.add("最大整数位=" + (schema.path("x-precision").asInt() - schema.path("x-scale").asInt()));
        Set<String> separate = new HashSet<String>(Arrays.asList("type", "format", "nullable", "required", "properties", "items", "title", "description", "example", "examples", "$ref"));
        schema.fields().forEachRemaining(field -> {
            if (!labels.containsKey(field.getKey()) && !separate.contains(field.getKey())) values.add("未识别约束(需复核) " + field.getKey() + "=" + field.getValue().toString());
        });
        return String.join("；", values);
    }

    private static final class Page {
        final boolean html; final StringBuilder out = new StringBuilder();
        Page(boolean html, String title) {
            this.html = html;
            if (html) out.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>").append(Util.html(title)).append("</title><style>body{font:15px/1.75 'Noto Sans CJK SC','Microsoft YaHei',sans-serif;margin:0;color:#24394e;background:#f3f6fa}main{max-width:1180px;margin:auto;background:white;padding:32px 36px;min-height:100vh}h1{font-size:26px}h2{font-size:20px;margin-top:36px;border-bottom:2px solid #dce6ef;padding-bottom:8px;overflow-wrap:anywhere}h3{font-size:17px;margin-top:28px}h4{font-size:15px;margin-bottom:12px}p{max-width:80ch;overflow-wrap:anywhere}table{border-collapse:collapse;width:100%;font-size:13px}th,td{text-align:left;vertical-align:top;border:1px solid #dce6ef;padding:10px;overflow-wrap:anywhere;min-width:65px;max-width:420px}th{background:#edf3f8;color:#17324d}.scroll{overflow:auto}pre{background:#f4f7fa;padding:16px;white-space:pre-wrap;overflow-wrap:anywhere}a:focus-visible{outline:3px solid #255d92}@media(max-width:650px){main{padding:18px 14px}h1{font-size:23px}table{min-width:620px}}</style></head><body><main data-contract-document=\"true\">");
        }
        void heading(int level, String text) { if (html) out.append("<h").append(level).append('>').append(Util.html(text)).append("</h").append(level).append('>'); else { for(int i=0;i<level;i++)out.append('#'); out.append(' ').append(md(text)).append("\n\n"); } }
        void text(String text) { if (html) out.append("<p>").append(Util.html(text)).append("</p>"); else out.append(md(text)).append("\n\n"); }
        void table(String[] headers, List<String[]> rows) {
            if (rows.isEmpty()) { text("无声明项。"); return; }
            if (html) out.append("<div class=\"scroll\"><table><thead><tr>");
            cells(headers, true);
            if (html) out.append("</tr></thead><tbody>"); else { out.append('|');for(int i=0;i<headers.length;i++)out.append(" --- |");out.append('\n'); }
            for(String[] row:rows) { if(html)out.append("<tr>");cells(row,false);if(html)out.append("</tr>"); }
            if(html)out.append("</tbody></table></div>");else out.append('\n');
        }
        void cells(String[] row,boolean heading) { if(!html)out.append('|');for(String cell:row){String value=limit(cell,1500);if(html)out.append(heading?"<th>":"<td>").append(Util.html(value)).append(heading?"</th>":"</td>");else out.append(' ').append(md(value)).append(" |");}if(!html)out.append('\n'); }
        void code(String value) { value=limit(value,12000);if(html)out.append("<pre><code>").append(Util.html(value)).append("</code></pre>");else { for(String line:value.split("\n",-1))out.append("    ").append(line).append('\n');out.append('\n'); } }
        String finish(){return html?out.append("</main></body></html>").toString():out.toString();}
        static String md(String value){return Util.html(value).replace("\\","\\\\").replace("|","\\|").replace("`","\\`").replace("[","\\[").replace("]","\\]").replace("*","\\*").replace("_","\\_").replace("#","\\#").replace("\r","").replace("\n"," ");}
        static String limit(String value,int max){return value.length()>max?value.substring(0,max)+"…（展示截断，完整内容见 OpenAPI JSON）":value;}
    }
}
