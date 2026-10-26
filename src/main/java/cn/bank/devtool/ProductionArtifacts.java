package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Conservative migration planning and evidence binding. Never executes SQL or supplied commands. */
public final class ProductionArtifacts {
    private ProductionArtifacts() { }

    public static ObjectNode binding(Spec spec) {
        return Util.obj().put("specSha256", Util.sha(Util.bytes(Util.pretty(spec))))
            .put("toolVersion", GeneratorService.TOOL_VERSION).put("templateVersion", GeneratorService.TEMPLATE_VERSION);
    }

    public static ObjectNode evidenceTemplate(Spec spec, Map<String, byte[]> files) {
        ObjectNode result = binding(spec).put("schemaVersion", 1).put("deploymentReady", false);
        ObjectNode hashes = result.putObject("files");
        for (Map.Entry<String, byte[]> entry : files.entrySet()) hashes.put(entry.getKey(), Util.sha(entry.getValue()));
        ArrayNode rows = result.putArray("evidence");
        for (JsonNode c : AcceptancePlanning.cases(spec).path("cases")) {
            rows.addObject().put("id", c.path("id").asText()).put("status", c.path("status").asText())
                .put("environment", "").put("command", "").put("executedAt", "").put("artifactSha256", "")
                .put("resultSha256", "").put("reviewer", "");
        }
        result.put("scope", "记录由外部验收产生的证据；绑定匹配不等于执行通过，工具不执行填入的命令，也不据此放行生产");
        return result;
    }

    public static ObjectNode inspectEvidence(Spec spec, JsonNode evidence, Map<String, byte[]> files) {
        ObjectNode report = binding(spec).put("deploymentReady", false).put("verdict", "INCOMPLETE");
        ArrayNode findings = report.putArray("findings");
        if (evidence == null || !evidence.isObject()) throw new IllegalArgumentException("验收证据必须是对象");
        if (!evidence.path("schemaVersion").isIntegralNumber() || !evidence.path("schemaVersion").canConvertToInt()
                || evidence.path("schemaVersion").intValue() != 1) findings.add("UNSUPPORTED_EVIDENCE_SCHEMA");
        ObjectNode expected = binding(spec);
        for (String key : new String[]{"specSha256", "toolVersion", "templateVersion"}) {
            if (!expected.path(key).equals(evidence.path(key))) findings.add("STALE_BINDING:" + key);
        }
        if (!evidence.path("files").isObject() || evidence.path("files").size() != files.size()) findings.add("FILE_SET_MISMATCH");
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            if (!Util.sha(entry.getValue()).equals(evidence.path("files").path(entry.getKey()).asText())) findings.add("STALE_FILE:" + entry.getKey());
        }
        Map<String, JsonNode> supplied = new LinkedHashMap<String, JsonNode>();
        if (!evidence.path("evidence").isArray()) findings.add("MISSING_EVIDENCE_ROWS");
        if (evidence.path("evidence").isArray()) {
            int index = 0;
            for (JsonNode row : evidence.path("evidence")) {
                if (!row.isObject() || !row.path("id").isTextual() || Util.isBlank(row.path("id").asText())) {
                    findings.add("INVALID_EVIDENCE_ROW:" + index);
                } else {
                    String id = row.path("id").asText();
                    if (supplied.put(id, row) != null) findings.add("DUPLICATE_EVIDENCE:" + id);
                }
                index++;
            }
        }
        ArrayNode rows = report.putArray("checks");
        for (JsonNode c : AcceptancePlanning.cases(spec).path("cases")) {
            String id = c.path("id").asText();
            JsonNode row = supplied.remove(id);
            String status = row == null ? "MISSING" : row.path("status").asText("MISSING");
            if (row != null && !java.util.Arrays.asList("PASS", "FAIL", "SKIPPED", "NOT_RUN", "MANUAL_REVIEW", "NOT_APPLICABLE").contains(status)) status = "INVALID";
            if (c.path("evidenceRequired").asBoolean() && status.equals("NOT_APPLICABLE")) status = "INVALID";
            if (status.equals("PASS")) {
                boolean complete = true;
                for (String k : new String[]{"environment", "command", "reviewer"}) complete &= row.path(k).isTextual() && !Util.isBlank(row.path(k).asText());
                for (String k : new String[]{"artifactSha256", "resultSha256"}) complete &= row.path(k).isTextual() && row.path(k).asText().matches("[a-f0-9]{64}");
                try { complete &= !java.time.Instant.parse(row.path("executedAt").asText()).isAfter(java.time.Instant.now()); }
                catch (Exception ex) { complete = false; }
                status = complete ? "ATTESTED_PENDING_REVIEW" : "INCOMPLETE_EVIDENCE";
            }
            rows.addObject().put("id", id).put("status", status);
        }
        for (String id : supplied.keySet()) findings.add("UNKNOWN_EVIDENCE:" + id);
        report.put("bindingMatched", findings.isEmpty());
        report.put("scope", "仅核对规格、模板、文件摘要和证据字段；未核验外部结果真实性，仍需发布负责人复核");
        return report;
    }

    public static ObjectNode migration(Spec previous, Spec current) {
        new SpecValidator().validate(previous);
        new SpecValidator().validate(current);
        ObjectNode result = binding(current).put("previousSpecSha256", Util.sha(Util.bytes(Util.pretty(previous))))
            .put("executionStatus", "NOT_RUN").put("autoExecutable", false).put("verdict", "REVIEW_REQUIRED");
        ArrayNode changes = result.putArray("changes");
        boolean sameTable = previous.tableName().equals(current.tableName());
        if (!sameTable) change(changes, "TABLE_CHANGED", "", "BLOCKED", "表名变化需人工确认重命名或新表迁移", "");
        Map<String, Spec.Field> old = new LinkedHashMap<String, Spec.Field>();
        for (Spec.Field field : previous.fields()) old.put(field.column().toLowerCase(Locale.ROOT), field);
        for (Spec.Field field : current.fields()) {
            Spec.Field before = old.remove(field.column().toLowerCase(Locale.ROOT));
            if (before == null) {
                boolean candidate = sameTable && !field.required() && field.getDefaultValue() == null;
                change(changes, "ADD_COLUMN", field.column(), candidate ? "SQL_CANDIDATE" : "BLOCKED",
                    candidate ? "可空新增列仍需检查锁表、数据量、数据库版本和部署顺序" : "先完成回填、默认值与可空性策略；禁止直接执行",
                    candidate ? "ALTER TABLE `" + current.tableName() + "` ADD COLUMN `" + field.column() + "` " + sqlType(field) + " NULL;" : "");
            } else if (!columnShape(before).equals(columnShape(field))) {
                change(changes, "ALTER_COLUMN", field.column(), "BLOCKED", "类型、长度、精度、默认值或可空性变化需检查存量值和回退方案", "");
            } else if (!before.column().equals(field.column())) {
                change(changes, "COLUMN_CASE_CHANGED", field.column(), "REVIEW_REQUIRED", "MySQL列名不区分大小写；仅大小写变化不生成新增或删除SQL，请保持命名一致并核对实际列名", "");
            }
        }
        for (Spec.Field field : old.values()) change(changes, "DROP_COLUMN", field.column(), "BLOCKED", "删除或重命名均不自动输出破坏性SQL", "");
        if (!Util.JSON.valueToTree(previous.getIndexes()).equals(Util.JSON.valueToTree(current.getIndexes())))
            change(changes, "INDEX_CHANGED", "", "BLOCKED", "检查存量重复值、唯一范围、锁表与EXPLAIN", "");
        if (ProductionPolicy.locking(previous) != ProductionPolicy.locking(current) || ProductionPolicy.softDelete(previous) != ProductionPolicy.softDelete(current)
            || ProductionPolicy.audit(previous) != ProductionPolicy.audit(current) || ProductionPolicy.hooks(previous) != ProductionPolicy.hooks(current)
            || !Util.JSON.valueToTree(ProductionPolicy.database(previous)).equals(Util.JSON.valueToTree(ProductionPolicy.database(current))))
            change(changes, "STORAGE_POLICY_CHANGED", "", "BLOCKED", "版本、删除、审计、Outbox或数据库配置变化需专门迁移", "");
        result.put("review", "此计划只比较两份规格，不读取真实数据库；保留备份、回填、发布顺序、锁超时和回退演练证据。禁止将候选SQL自动应用到业务库。");
        return result;
    }

    /** Include template storage changes; a matching business spec does not prove matching auxiliary tables. */
    public static ObjectNode migration(Spec previous, Spec current, String previousTemplateVersion) {
        ObjectNode report = migration(previous, current);
        boolean provided = !Util.isBlank(previousTemplateVersion);
        boolean sameOutboxStorage = GeneratorService.TEMPLATE_VERSION.equals(previousTemplateVersion)
            || "bank-crud-java8-sb2718-rebuild-v18".equals(previousTemplateVersion)
            || "bank-crud-java17-sb35-rebuild-v18".equals(previousTemplateVersion);
        report.put("previousTemplateVersion", provided ? previousTemplateVersion : "UNKNOWN");
        report.put("templateStorageChecked", provided);
        if (ProductionPolicy.hooks(previous) && ProductionPolicy.hooks(current)
                && !sameOutboxStorage) {
            String table = current.tableName();
            String base = table.length() <= 50 ? table : table.substring(0, 50);
            String outbox = base + "_" + Util.sha(Util.bytes(table)).substring(0, 6) + "_outbox";
            boolean knownV6 = provided && previousTemplateVersion.endsWith("rebuild-v17") && previous.tableName().equals(table);
            String sql = knownV6 ? "ALTER TABLE `" + outbox + "` ADD COLUMN `lease_token` VARCHAR(36) NULL, "
                + "ADD COLUMN `leased_until` TIMESTAMP NULL, ADD COLUMN `delivered_at` TIMESTAMP NULL;" : "";
            change((ArrayNode) report.path("changes"), "OUTBOX_TEMPLATE_MIGRATION", outbox, "BLOCKED",
                knownV6 ? "新版Outbox投递器需要三列租约/完成信息；先核对现有列、InnoDB、备份和发布顺序，再评审候选ALTER；不得重复添加已有列"
                    : "旧模板未知或不是v17，必须人工比较Outbox表是否存在、引擎和租约列；不能按相同业务规格跳过辅助表迁移", sql);
        }
        return report;
    }

    public static String migrationMarkdown(JsonNode report) {
        StringBuilder out = new StringBuilder("# 增量迁移评估\n\n状态：REVIEW_REQUIRED；未执行数据库操作。\n\n");
        for (JsonNode row : report.path("changes")) {
            out.append("- ").append(row.path("code").asText()).append(" / ").append(row.path("column").asText())
                .append(" / ").append(row.path("status").asText()).append("：").append(row.path("action").asText()).append('\n');
            if (!row.path("sql").asText().isEmpty()) out.append("\n```sql\n").append(row.path("sql").asText()).append("\n```\n\n");
        }
        return out.append('\n').append(report.path("review").asText()).append('\n').toString();
    }

    private static JsonNode columnShape(Spec.Field f) {
        ObjectNode value = Util.obj().put("type", f.javaType()).put("required", f.required())
            .put("length", f.maxLength()).put("precision", f.precision()).put("scale", f.scale())
            .put("temporalPrecision", ProductionPolicy.temporalPrecision(f));
        value.set("default", f.getDefaultValue() == null ? com.fasterxml.jackson.databind.node.NullNode.instance : f.getDefaultValue());
        return value;
    }

    private static String sqlType(Spec.Field f) {
        if (f.javaType().equals("String")) return "VARCHAR(" + f.maxLength() + ")";
        if (f.javaType().equals("Long")) return "BIGINT";
        if (f.javaType().equals("Integer")) return "INT";
        if (f.javaType().equals("Boolean")) return "BOOLEAN";
        if (f.javaType().equals("BigDecimal")) return "DECIMAL(" + f.precision() + "," + f.scale() + ")";
        if (f.javaType().equals("LocalDate")) return "DATE";
        if (f.javaType().equals("LocalDateTime")) return "DATETIME(" + ProductionPolicy.temporalPrecision(f) + ")";
        throw new IllegalArgumentException("不支持迁移列类型");
    }

    private static void change(ArrayNode rows, String code, String column, String status, String action, String sql) {
        rows.addObject().put("code", code).put("column", column).put("status", status).put("action", action).put("sql", sql);
    }
}
