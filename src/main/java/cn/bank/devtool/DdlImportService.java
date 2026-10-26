package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DdlImportService {
    private static final int MAX_BYTES = 5242880;
    private static final int MAX_TOKENS = 200000;

    public JsonNode parse(String ddl, String packageName) {
        if (ddl != null && ddl.length() <= 5242880 && ddl.indexOf(0) < 0 && ddl.indexOf(65533) < 0) {
            for (int i = 0; i < ddl.length(); i++) {
                char ch = ddl.charAt(i);
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 >= ddl.length() || !Character.isLowSurrogate(ddl.charAt(++i))) {
                        throw bad("Unicode 文本编码无效");
                    }
                } else if (Character.isLowSurrogate(ch)) {
                    throw bad("Unicode 文本编码无效");
                }
            }

            if (ddl.getBytes(StandardCharsets.UTF_8).length > 5242880) {
                throw bad("DDL 文本最大 5MiB（UTF-8）");
            } else {
                List<DdlImportService.T> tokens = this.lex(ddl);
                ObjectNode out = Util.obj();
                ArrayNode tables = out.putArray("tables");
                ObjectNode coverage = out.putObject("coverage");
                ArrayNode issues = coverage.putArray("issues");
                Set<String> tableNames = new HashSet<>();
                int start = 0;
                int depth = 0;

                for (int ix = 0; ix <= tokens.size(); ix++) {
                    DdlImportService.T t = ix < tokens.size() ? tokens.get(ix) : null;
                    if (t != null && t.symbol("(")) {
                        depth++;
                    }

                    if (t != null && t.symbol(")")) {
                        depth--;
                    }

                    if (t == null || t.symbol(";") && depth == 0) {
                        if (ix > start) {
                            List<DdlImportService.T> statement = tokens.subList(start, ix);
                            if (statement.size() >= 2 && statement.get(0).kw("CREATE") && statement.get(1).kw("TABLE")) {
                                if (!this.hasColumnDefinitions(statement)) {
                                    issues.add("已跳过不支持的 CREATE TABLE 变体（仅支持显式字段定义）：" + render(statement));
                                    start = ix + 1;
                                    continue;
                                }

                                if (tables.size() >= 100) {
                                    throw bad("最多导入 100 张表");
                                }

                                ObjectNode table = this.readTable(statement, packageName, issues);
                                if (!tableNames.add(table.get("tableName").asText().toLowerCase(Locale.ROOT))) {
                                    throw bad("表名重复");
                                }

                                tables.add(table);
                            } else {
                                issues.add("已跳过不支持的 SQL 语句：" + render(statement));
                            }
                        }

                        start = ix + 1;
                    }
                }

                if (tables.size() == 0) {
                    throw bad("未找到支持的 CREATE TABLE 语句");
                } else {
                    ObjectNode parseCoverage = out.putObject("parseCoverage");
                    parseCoverage.put("complete", issues.size() == 0);
                    parseCoverage.set("issues", issues.deepCopy());

                    for (JsonNode table : tables) {
                        for (JsonNode blocker : table.path("blockers")) {
                            issues.add(table.path("tableName").asText() + "：" + blocker.asText());
                        }

                        for (JsonNode warning : table.path("warnings")) {
                            if (warning.asText().contains("不会复原") || warning.asText().contains("不能精确复原") || warning.asText().contains("取值范围")) {
                                issues.add(table.path("tableName").asText() + "：" + warning.asText());
                            }
                        }
                    }

                    coverage.put("complete", issues.size() == 0);
                    out.set("schemaFidelity", coverage.deepCopy());
                    out.put("scope", "离线读取 MySQL CREATE TABLE 并转成代码生成规格；生成前请检查警告和阻断项。本功能不执行 SQL，不保证完整复原数据库结构。");
                    return out;
                }
            }
        } else {
            throw bad("DDL 必须是有效文本，UTF-8 最大 5MiB");
        }
    }

    private boolean hasColumnDefinitions(List<DdlImportService.T> ts) {
        DdlImportService.C c = new DdlImportService.C(ts);
        c.take();
        c.take();
        if (c.accept("IF")) {
            c.require("NOT");
            c.require("EXISTS");
        }

        c.identifier();
        if (c.symbol(".")) {
            c.take();
            c.identifier();
        }

        return c.symbol("(");
    }

    private ObjectNode readTable(List<DdlImportService.T> ts, String packageName, ArrayNode issues) {
        DdlImportService.C c = new DdlImportService.C(ts);
        c.take();
        c.take();
        ObjectNode result = Util.obj();
        ArrayNode warnings = result.putArray("warnings");
        ArrayNode blockers = result.putArray("blockers");
        if (c.accept("IF")) {
            c.require("NOT");
            c.require("EXISTS");
            warnings.add("不复原 IF NOT EXISTS 行为");
        }

        String name = c.identifier();
        result.put("tableName", name);
        if (c.symbol(".")) {
            c.take();
            name = c.identifier();
            result.put("tableName", name);
            blockers.add("暂不支持带数据库前缀的表名");
            issues.add("表名包含数据库前缀：" + name);
        }

        c.requireSymbol("(");
        List<List<DdlImportService.T>> definitions = new ArrayList<>();
        int start = c.pos;
        int depth = 0;

        while (c.has()) {
            DdlImportService.T t = c.peek();
            if (t.symbol(")") && depth == 0) {
                if (c.pos == start) {
                    throw bad("字段为空或存在末尾逗号");
                }

                definitions.add(ts.subList(start, c.pos));
                c.take();
                break;
            }

            if (t.symbol(",") && depth == 0) {
                if (c.pos == start) {
                    throw bad("字段定义为空");
                }

                definitions.add(ts.subList(start, c.pos));
                c.take();
                start = c.pos;
            } else {
                if (t.symbol("(")) {
                    depth++;
                }

                if (t.symbol(")")) {
                    depth--;
                }

                c.take();
            }
        }

        if (!c.hasPrevious(")")) {
            throw bad("CREATE TABLE 缺少右括号");
        } else {
            Set<String> columns = new HashSet<>();
            List<Spec.Field> fields = new ArrayList<>();
            List<String> pk = new ArrayList<>();
            List<Spec.Index> indexes = new ArrayList<>();
            int pkDeclarations = 0;
            DdlImportService.Column id = null;
            int count = 0;

            for (List<DdlImportService.T> definition : definitions) {
                DdlImportService.C d = new DdlImportService.C(definition);
                if (d.kw("CONSTRAINT")) {
                    d.take();
                    if (d.has() && !d.kw("PRIMARY") && !d.kw("FOREIGN") && !d.kw("UNIQUE") && !d.kw("CHECK")) {
                        d.identifier();
                    }
                }

                if (d.kw("PRIMARY")) {
                    d.take();
                    d.require("KEY");
                    pkDeclarations++;
                    pk.addAll(this.primaryColumns(d, blockers));
                } else if (d.kw("KEY") || d.kw("INDEX") || d.kw("UNIQUE")) {
                    Spec.Index index = this.readIndexV2(d, blockers, issues, name);
                    if (index != null) {
                        indexes.add(index);
                    }
                } else if (!d.kw("FOREIGN") && !d.kw("CHECK") && !d.kw("FULLTEXT") && !d.kw("SPATIAL")) {
                    if (definition.get(0).kw("CONSTRAINT")) {
                        blockers.add("无法识别的表级约束：" + render(definition));
                        issues.add("表级约束无法识别，表：" + name);
                    } else {
                        if (++count > 100) {
                            throw bad("每张表最多 100 个字段（含 id）");
                        }

                        DdlImportService.Column col = this.column(d, warnings, blockers, issues, name);
                        if (!columns.add(col.name.toLowerCase(Locale.ROOT))) {
                            throw bad("字段名重复：" + col.name);
                        }

                        if (col.primary) {
                            pkDeclarations++;
                            pk.add(col.name);
                        }

                        if (col.name.equalsIgnoreCase("id")) {
                            id = col;
                        } else {
                            if (Arrays.asList("SMALLINT", "MEDIUMINT", "TINYINT").contains(col.sqlType)) {
                                warnings.add("原SQL类型 " + col.sqlType + " 的存储类型和取值范围不会复原为同一数据库约束：" + col.name);
                            }

                            fields.add(col.field());
                            if (col.auto) {
                                blockers.add("AUTO_INCREMENT 仅支持 id 字段：" + col.name);
                            }
                        }
                    }
                } else {
                    blockers.add("不支持的表级约束或索引：" + render(definition));
                }
            }

            if (pkDeclarations != 1 || pk.size() != 1 || !pk.get(0).equalsIgnoreCase("id")) {
                blockers.add("生成器要求且仅允许单列 PRIMARY KEY(id)，不支持缺失、复合或其他主键");
            }

            if (id == null || !id.name.equals("id") || !id.sqlType.equals("BIGINT") || id.unsigned || !id.auto) {
                blockers.add("生成器要求 id 为有符号 BIGINT AUTO_INCREMENT");
            }

            String description = name;

            while (c.has()) {
                if (c.accept("COMMENT")) {
                    c.optionalSymbol("=");
                    description = c.string();
                } else if (c.kw("ENGINE") || c.kw("CHARSET") || c.kw("COLLATE") || c.kw("AUTO_INCREMENT")) {
                    String option = c.take().text;
                    c.optionalSymbol("=");
                    if (!c.has()) {
                        throw bad("缺少表选项的值");
                    }

                    c.take();
                    warnings.add("表选项 " + option + " 仅作推断，不能精确复原");
                } else if (c.accept("DEFAULT")) {
                    if (c.accept("CHARACTER")) {
                        c.require("SET");
                        c.optionalSymbol("=");
                        c.identifier();
                        warnings.add("表的 DEFAULT CHARACTER SET 仅作推断，不能精确复原");
                    } else if (!c.kw("CHARSET") && !c.kw("COLLATE")) {
                        blockers.add("不支持的表级 DEFAULT 选项");
                        issues.add("表级 DEFAULT 选项无法识别，表：" + name);
                        if (c.has()) {
                            c.take();
                        }
                    } else {
                        String option = c.take().text;
                        c.optionalSymbol("=");
                        c.identifier();
                        warnings.add("表的 DEFAULT " + option + " 仅作推断，不能精确复原");
                    }
                } else if (c.accept("CHARACTER")) {
                    c.require("SET");
                    c.optionalSymbol("=");
                    c.identifier();
                    warnings.add("表的 CHARACTER SET 仅作推断，不能精确复原");
                } else {
                    blockers.add("不支持的表选项：" + c.peek().text);
                    issues.add("表选项无法识别，表：" + name + ": " + c.take().text);
                }
            }

            if (description.equals(name)) {
                warnings.add("未提供表注释，已用表名代替；请补充业务说明");
            }

            warnings.add("请开发者检查敏感属性、字段对外暴露/可编辑设置、DELETE 删除操作和访问权限；CRUD 操作仅作默认推断");
            String camel = camel(name);
            String entity = camel.isEmpty() ? "" : Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
            String artifact = name.toLowerCase(Locale.ROOT).replace('_', '-');
            Spec spec = new Spec(
                packageName, artifact, entity, name, description, "/" + artifact, Arrays.asList("CREATE", "UPDATE", "DETAIL", "PAGE", "DELETE"), fields
            );
            spec.setSchemaVersion(2);
            spec.setIndexes(indexes);

            try {
                new SpecValidator().validate(spec);
            } catch (IllegalArgumentException var25) {
                blockers.add("规格校验未通过：" + var25.getMessage());
            }

            result.set("spec", Util.JSON.valueToTree(spec));
            result.put("canGenerate", blockers.size() == 0);
            return result;
        }
    }

    private DdlImportService.Column column(DdlImportService.C c, ArrayNode warnings, ArrayNode blockers, ArrayNode issues, String table) {
        DdlImportService.Column col = new DdlImportService.Column();
        col.name = c.identifier();
        col.sqlType = c.takeWord().toUpperCase(Locale.ROOT);
        List<Integer> args = new ArrayList<>();
        if (c.symbol("(")) {
            c.take();
            List<DdlImportService.T> content = c.groupContent();
            boolean expected = true;

            for (DdlImportService.T t : content) {
                if (expected) {
                    try {
                        args.add(Integer.valueOf(t.text));
                    } catch (NumberFormatException var13) {
                        blockers.add("不支持的类型参数：" + col.name);
                    }

                    expected = false;
                } else {
                    if (!t.symbol(",")) {
                        blockers.add("不支持的类型参数：" + col.name);
                    }

                    expected = true;
                }
            }

            if (content.isEmpty() || expected) {
                blockers.add("类型参数格式错误：" + col.name);
            }
        }

        if (col.sqlType.equals("VARCHAR") || col.sqlType.equals("CHAR")) {
            col.type = "String";
            if (args.size() == 1) {
                col.length = args.get(0);
            } else {
                blockers.add("字符串类型必须明确长度：" + col.name);
            }
        } else if (col.sqlType.equals("BIGINT")) {
            col.type = "Long";
        } else if (Arrays.asList("INT", "INTEGER", "SMALLINT", "MEDIUMINT", "TINYINT").contains(col.sqlType)) {
            col.type = "Integer";
            if (col.sqlType.equals("TINYINT") && args.size() == 1 && args.get(0) == 1) {
                col.type = "Boolean";
                warnings.add("TINYINT(1) 已推断为 Boolean，请确认取值仅为 0/1：" + col.name);
            }
        } else if (col.sqlType.equals("BOOL") || col.sqlType.equals("BOOLEAN")) {
            col.type = "Boolean";
            warnings.add("BOOL/BOOLEAN 已推断为 Boolean，请确认取值仅为 0/1：" + col.name);
        } else if (col.sqlType.equals("DECIMAL") || col.sqlType.equals("NUMERIC")) {
            col.type = "BigDecimal";
            if (args.size() == 2) {
                col.precision = args.get(0);
                col.scale = args.get(1);
            } else {
                blockers.add("DECIMAL/NUMERIC 必须明确精度和小数位：" + col.name);
            }
        } else if (col.sqlType.equals("DATE")) {
            col.type = "LocalDate";
        } else if (!col.sqlType.equals("DATETIME") && !col.sqlType.equals("TIMESTAMP")) {
            col.type = "String";
            blockers.add("不支持的 SQL 类型 " + col.sqlType + ": " + col.name);
        } else {
            col.type = "LocalDateTime";
            col.temporalPrecision = args.isEmpty() ? 0 : args.get(0);
            if (col.sqlType.equals("TIMESTAMP")) {
                warnings.add("TIMESTAMP 时区和取值范围不会复原：" + col.name);
            }
        }

        if (!args.isEmpty() && !Arrays.asList("CHAR", "VARCHAR", "DECIMAL", "NUMERIC", "DATETIME", "TIMESTAMP").contains(col.sqlType)) {
            warnings.add("SQL 类型显示宽度/小数秒精度不会复原：" + col.name);
        }

        if (args.size() > 1 && !Arrays.asList("DECIMAL", "NUMERIC").contains(col.sqlType)) {
            blockers.add("不支持的 SQL 类型参数数量：" + col.name);
        }

        if (Arrays.asList("DATE", "BOOL", "BOOLEAN").contains(col.sqlType) && !args.isEmpty()) {
            blockers.add("此类型不支持参数：" + col.name);
        }

        if (Arrays.asList("TIMESTAMP", "DATETIME").contains(col.sqlType) && !args.isEmpty() && (args.get(0) < 0 || args.get(0) > 6)) {
            blockers.add("时间小数秒精度必须为 0 至 6：" + col.name);
        }

        while (c.has()) {
            if (c.accept("NOT")) {
                c.require("NULL");
                col.required = true;
            } else if (c.accept("NULL")) {
                col.required = false;
            } else if (c.accept("AUTO_INCREMENT")) {
                col.auto = true;
            } else if (c.accept("PRIMARY")) {
                c.require("KEY");
                col.primary = true;
                col.required = true;
            } else if (c.accept("COMMENT")) {
                col.description = c.string();
            } else if (c.accept("UNSIGNED")) {
                if (!numeric(col.sqlType)) {
                    blockers.add("此类型不能使用 UNSIGNED：" + col.name);
                }

                col.unsigned = true;
                if (col.sqlType.equals("BIGINT")) {
                    blockers.add("UNSIGNED BIGINT 超出 Long 取值范围：" + col.name);
                } else if (!col.sqlType.equals("INT") && !col.sqlType.equals("INTEGER")) {
                    warnings.add("UNSIGNED 取值范围约束不会复原：" + col.name);
                } else {
                    col.type = "Long";
                }
            } else if (c.accept("SIGNED")) {
                if (!numeric(col.sqlType)) {
                    blockers.add("此类型不能使用 SIGNED：" + col.name);
                }
            } else if (c.accept("DEFAULT")) {
                int begin = c.pos;
                this.consumeValue(c);
                col.defaultValue = this.defaultValue(c.ts.subList(begin, c.pos), col, blockers);
                if (col.defaultValue != null) {
                    warnings.add("DEFAULT 字面量已保留；字段来源暂设 CLIENT，请按创建接口语义选择 CLIENT 或 DATABASE：" + col.name);
                }
            } else if (c.accept("COLLATE")) {
                c.identifier();
                warnings.add("字段 COLLATE 排序规则不会复原：" + col.name);
            } else if (c.accept("CHARACTER")) {
                c.require("SET");
                c.identifier();
                warnings.add("字段 CHARACTER SET 字符集不会复原：" + col.name);
            } else if (c.accept("ZEROFILL")) {
                if (!numeric(col.sqlType)) {
                    blockers.add("此类型不能使用 ZEROFILL：" + col.name);
                }

                warnings.add("ZEROFILL 显示和取值范围语义不会复原：" + col.name);
            } else if (!c.kw("UNIQUE") && !c.kw("REFERENCES") && !c.kw("CHECK") && !c.kw("GENERATED") && !c.kw("AS") && !c.kw("ON")) {
                String unknown = c.take().text;
                blockers.add("无法识别的字段属性：" + col.name + " " + unknown);
                issues.add("字段属性无法识别，位置：" + table + "." + col.name + ": " + unknown);
            } else {
                blockers.add("不支持字段约束、生成列或 ON UPDATE 语义：" + col.name + " " + c.take().text);

                while (c.has()) {
                    c.take();
                }
            }
        }

        if (col.description == null) {
            col.description = col.name;
            warnings.add("缺少字段注释，已用字段名代替：" + col.name);
        }

        return col;
    }

    private void consumeValue(DdlImportService.C c) {
        if (!c.has()) {
            throw bad("缺少 DEFAULT 值");
        } else {
            if (c.symbol("+") || c.symbol("-")) {
                c.take();
            }

            if (!c.has()) {
                throw bad("缺少 DEFAULT 值");
            } else if (c.symbol("(")) {
                c.take();
                c.groupContent();
            } else {
                DdlImportService.T first = c.take();
                if (first.kind == 'w' && first.text.matches("[0-9]+") && c.symbol(".")) {
                    c.take();
                    if (!c.has() || !c.peek().text.matches("[0-9]+")) {
                        throw bad("DEFAULT 小数不完整");
                    }

                    c.take();
                }

                if (c.symbol("(")) {
                    c.take();
                    c.groupContent();
                }
            }
        }
    }

    private List<String> primaryColumns(DdlImportService.C c, ArrayNode blockers) {
        List<String> names = new ArrayList<>();
        c.requireSymbol("(");
        if (c.symbol(")")) {
            throw bad("PRIMARY KEY 内容为空");
        } else {
            while (true) {
                names.add(c.identifier());
                if (!c.symbol(",")) {
                    c.requireSymbol(")");
                    if (c.has()) {
                        blockers.add("不支持的 PRIMARY KEY 选项：" + render(c.ts.subList(c.pos, c.ts.size())));
                    }

                    return names;
                }

                c.take();
            }
        }
    }

    private void readIndex(DdlImportService.C c, ArrayNode blockers, ArrayNode issues, String table) {
        c.take();
        if (!c.symbol("(") && !c.kw("USING")) {
            c.identifier();
        }

        if (c.accept("USING")) {
            this.indexMethod(c, blockers);
        }

        c.requireSymbol("(");
        if (c.symbol(")")) {
            throw bad("索引字段为空");
        } else {
            while (true) {
                if (c.symbol("(")) {
                    c.take();
                    c.groupContent();
                    blockers.add("不支持函数或表达式索引");
                } else {
                    c.identifier();
                    if (c.symbol("(")) {
                        c.take();
                        List<DdlImportService.T> prefix = c.groupContent();
                        if (prefix.size() != 1 || !prefix.get(0).text.matches("[0-9]+")) {
                            blockers.add("不支持的索引字段表达式");
                        }
                    }
                }

                if (c.kw("ASC") || c.kw("DESC")) {
                    c.take();
                }

                if (!c.symbol(",")) {
                    c.requireSymbol(")");

                    while (c.has()) {
                        if (c.accept("USING")) {
                            this.indexMethod(c, blockers);
                        } else if (c.accept("COMMENT")) {
                            c.string();
                        } else if (c.accept("KEY_BLOCK_SIZE")) {
                            c.optionalSymbol("=");
                            DdlImportService.T t = c.take();
                            if (!t.text.matches("[0-9]+")) {
                                blockers.add("索引 KEY_BLOCK_SIZE 无效");
                            }
                        } else if (!c.kw("VISIBLE") && !c.kw("INVISIBLE")) {
                            String option = c.take().text;
                            blockers.add("无法识别的索引选项：" + option);
                            issues.add("索引选项无法识别，表：" + table + ": " + option);
                        } else {
                            c.take();
                        }
                    }

                    return;
                }

                c.take();
            }
        }
    }

    private JsonNode defaultValue(List<DdlImportService.T> tokens, DdlImportService.Column col, ArrayNode blockers) {
        if (tokens.size() == 1 && tokens.get(0).kw("NULL")) {
            return NullNode.instance;
        } else {
            String raw = "";

            for (DdlImportService.T token : tokens) {
                raw = raw + token.text;
            }

            try {
                if (tokens.size() == 1 && tokens.get(0).kind == 's') {
                    String text = tokens.get(0).text;
                    if (col.type.equals("LocalDateTime")) {
                        text = text.replace(' ', 'T');
                    }

                    if (Arrays.asList("String", "LocalDate", "LocalDateTime").contains(col.type)) {
                        return Util.JSON.getNodeFactory().textNode(text);
                    }

                    raw = text;
                }

                if (col.type.equals("Boolean") && (raw.equalsIgnoreCase("TRUE") || raw.equals("1"))) {
                    return BooleanNode.TRUE;
                }

                if (col.type.equals("Boolean") && (raw.equalsIgnoreCase("FALSE") || raw.equals("0"))) {
                    return BooleanNode.FALSE;
                }

                if (raw.matches("[+-]?[0-9]+(\\.[0-9]+)?")) {
                    BigDecimal number = new BigDecimal(raw);
                    if (col.type.equals("BigDecimal")) {
                        return Util.JSON.getNodeFactory().numberNode(number);
                    }

                    if (col.type.equals("Integer") || col.type.equals("Long")) {
                        return Util.JSON.getNodeFactory().numberNode(number.toBigIntegerExact());
                    }
                }
            } catch (RuntimeException var7) {
            }

            blockers.add("不支持的 DEFAULT 表达式或类型：" + col.name + "，不会丢弃后继续生成");
            return null;
        }
    }

    private Spec.Index readIndexV2(DdlImportService.C c, ArrayNode blockers, ArrayNode issues, String table) {
        Spec.Index index = new Spec.Index();
        boolean unique = c.accept("UNIQUE");
        index.setUnique(unique);
        if (c.kw("KEY") || c.kw("INDEX")) {
            c.take();
        }

        if (!c.symbol("(") && !c.kw("USING")) {
            index.setName(c.identifier());
        } else {
            blockers.add("索引必须有明确名称");
            index.setName("unnamed_index");
        }

        if (c.accept("USING")) {
            String method = c.takeWord();
            if (!method.equalsIgnoreCase("BTREE")) {
                blockers.add("只支持完整 BTREE 索引：" + index.getName());
            }
        }

        c.requireSymbol("(");
        List<String> fields = new ArrayList<>();

        while (true) {
            if (c.symbol("(")) {
                c.take();
                c.groupContent();
                blockers.add("不支持函数或表达式索引");
            } else {
                fields.add(camel(c.identifier()));
                if (c.symbol("(")) {
                    c.take();
                    c.groupContent();
                    blockers.add("前缀索引不能无损映射为完整索引");
                }
            }

            if (c.accept("DESC")) {
                blockers.add("降序索引不能无损还原");
            } else {
                c.accept("ASC");
            }

            if (!c.symbol(",")) {
                c.requireSymbol(")");

                while (c.has()) {
                    if (c.accept("USING")) {
                        String method = c.takeWord();
                        if (!method.equalsIgnoreCase("BTREE")) {
                            blockers.add("只支持 BTREE 索引");
                        }
                    } else if (c.accept("COMMENT")) {
                        c.string();
                        issues.add("索引注释不会复原，表：" + table);
                    } else if (!c.accept("VISIBLE")) {
                        blockers.add("索引选项不能无损还原：" + c.take().text);
                    }
                }

                index.setFields(fields);
                return index;
            }

            c.take();
        }
    }

    private void indexMethod(DdlImportService.C c, ArrayNode blockers) {
        String method = c.takeWord();
        if (!method.equalsIgnoreCase("BTREE") && !method.equalsIgnoreCase("HASH")) {
            blockers.add("不支持的索引 USING 方法：" + method);
        }
    }

    private static boolean numeric(String type) {
        return Arrays.asList("BIGINT", "INT", "INTEGER", "SMALLINT", "MEDIUMINT", "TINYINT", "BOOL", "BOOLEAN", "DECIMAL", "NUMERIC").contains(type);
    }

    private static String render(List<DdlImportService.T> ts) {
        StringBuilder b = new StringBuilder();

        for (DdlImportService.T t : ts) {
            if (b.length() > 0) {
                b.append(' ');
            }

            b.append(t.text);
            if (b.length() > 250) {
                b.append("...");
                break;
            }
        }

        return b.toString();
    }

    private static String camel(String name) {
        StringBuilder b = new StringBuilder();
        boolean upper = false;

        for (char ch : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch == '_') {
                upper = true;
            } else {
                b.append(upper ? Character.toUpperCase(ch) : ch);
                upper = false;
            }
        }

        return b.toString();
    }

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }

    private List<DdlImportService.T> lex(String sql) {
        List<DdlImportService.T> out = new ArrayList<>();
        int depth = 0;
        int i = 0;

        while (i < sql.length()) {
            char ch = sql.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
            } else if (ch != '#' && (ch != '-' || i + 2 >= sql.length() || sql.charAt(i + 1) != '-' || !Character.isWhitespace(sql.charAt(i + 2)))) {
                if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                    if (i + 2 < sql.length() && sql.charAt(i + 2) == '!') {
                        throw bad("不支持 MySQL 可执行注释 /*!...*/");
                    }

                    int end = sql.indexOf("*/", i + 2);
                    if (end < 0) {
                        throw bad("块注释未闭合");
                    }

                    i = end + 2;
                } else {
                    if (ch == '\'' || ch == '"' || ch == '`') {
                        char quote = ch;
                        StringBuilder b = new StringBuilder();
                        i++;
                        boolean closed = false;

                        while (i < sql.length()) {
                            ch = sql.charAt(i++);
                            if (ch == quote) {
                                if (i >= sql.length() || sql.charAt(i) != quote) {
                                    closed = true;
                                    break;
                                }

                                b.append(quote);
                                i++;
                            } else if (ch == '\\' && quote != '`') {
                                if (i >= sql.length()) {
                                    throw bad("转义符不完整");
                                }

                                char escaped = sql.charAt(i++);
                                if (escaped == '0') {
                                    throw bad("不支持 NUL 转义字符");
                                }

                                switch (escaped) {
                                    case 'n':
                                        b.append('\n');
                                        break;
                                    case 'r':
                                        b.append('\r');
                                        break;
                                    case 't':
                                        b.append('\t');
                                        break;
                                    default:
                                        b.append(escaped);
                                }
                            } else {
                                b.append(ch);
                            }
                        }

                        if (!closed) {
                            throw bad("引号未闭合");
                        }

                        out.add(new DdlImportService.T(b.toString(), (char)(quote == '`' ? 'i' : 's')));
                    } else if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '$') {
                        if (ch == '(') {
                            if (++depth > 128) {
                                throw bad("括号嵌套最多 128 层");
                            }
                        } else if (ch == ')') {
                            if (--depth < 0) {
                                throw bad("右括号没有匹配的左括号");
                            }
                        }

                        out.add(new DdlImportService.T(String.valueOf(ch), 'p'));
                        i++;
                    } else {
                        int start;
                        for (start = i++; i < sql.length(); i++) {
                            char x = sql.charAt(i);
                            if (!Character.isLetterOrDigit(x) && x != '_' && x != '$') {
                                break;
                            }
                        }

                        out.add(new DdlImportService.T(sql.substring(start, i), 'w'));
                    }

                    if (out.size() > 200000) {
                        throw bad("SQL 词法项最多 200000 个");
                    }
                }
            } else {
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
            }
        }

        if (depth != 0) {
            throw bad("括号未闭合");
        } else {
            return out;
        }
    }

    private static final class C {
        final List<DdlImportService.T> ts;
        int pos;

        C(List<DdlImportService.T> ts) {
            this.ts = ts;
        }

        boolean has() {
            return this.pos < this.ts.size();
        }

        DdlImportService.T peek() {
            if (!this.has()) {
                throw DdlImportService.bad("CREATE TABLE 意外结束");
            } else {
                return this.ts.get(this.pos);
            }
        }

        DdlImportService.T take() {
            DdlImportService.T t = this.peek();
            this.pos++;
            return t;
        }

        boolean kw(String s) {
            return this.has() && this.peek().kw(s);
        }

        boolean symbol(String s) {
            return this.has() && this.peek().symbol(s);
        }

        boolean accept(String s) {
            if (this.kw(s)) {
                this.take();
                return true;
            } else {
                return false;
            }
        }

        void require(String s) {
            if (!this.accept(s)) {
                throw DdlImportService.bad("预期为 " + s);
            }
        }

        void requireSymbol(String s) {
            if (!this.symbol(s)) {
                throw DdlImportService.bad("预期为 " + s);
            } else {
                this.take();
            }
        }

        void optionalSymbol(String s) {
            if (this.symbol(s)) {
                this.take();
            }
        }

        String identifier() {
            DdlImportService.T t = this.take();
            if (t.kind != 'w' && t.kind != 'i') {
                throw DdlImportService.bad("应为 SQL 标识符");
            } else {
                return t.text;
            }
        }

        String takeWord() {
            DdlImportService.T t = this.take();
            if (t.kind != 'w') {
                throw DdlImportService.bad("应为未加引号的 SQL 类型");
            } else {
                return t.text;
            }
        }

        String string() {
            DdlImportService.T t = this.take();
            if (t.kind != 's') {
                throw DdlImportService.bad("COMMENT 值应为带引号的字符串");
            } else {
                return t.text;
            }
        }

        boolean hasPrevious(String s) {
            return this.pos > 0 && this.ts.get(this.pos - 1).symbol(s);
        }

        List<DdlImportService.T> groupContent() {
            int start = this.pos;
            int depth = 0;

            while (this.has()) {
                DdlImportService.T t = this.take();
                if (t.symbol(")")) {
                    if (depth == 0) {
                        return this.ts.subList(start, this.pos - 1);
                    }

                    depth--;
                } else if (t.symbol("(")) {
                    depth++;
                }
            }

            throw DdlImportService.bad("括号未闭合");
        }
    }

    private static final class Column {
        String name;
        String sqlType;
        String type;
        String description;
        Integer length;
        Integer precision;
        Integer scale;
        Integer temporalPrecision;
        JsonNode defaultValue;
        boolean required;
        boolean auto;
        boolean primary;
        boolean unsigned;

        Spec.Field field() {
            Spec.Field f = new Spec.Field(
                DdlImportService.camel(this.name),
                this.name,
                this.type,
                this.description,
                this.required,
                true,
                true,
                "NONE",
                this.length,
                this.precision,
                this.scale
            );
            f.setNullable(!this.required);
            f.setSource("CLIENT");
            f.setDefaultValue(this.defaultValue);
            f.setTemporalPrecision(this.temporalPrecision);
            return f;
        }
    }

    private static final class T {
        final String text;
        final char kind;

        T(String text, char kind) {
            this.text = text;
            this.kind = kind;
        }

        boolean kw(String s) {
            return this.kind == 'w' && this.text.equalsIgnoreCase(s);
        }

        boolean symbol(String s) {
            return this.kind == 'p' && this.text.equals(s);
        }
    }
}
