package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

public final class RequirementImporter {
    public Spec load(byte[] bytes, String fileName) {
        if (bytes.length > 5000000) {
            throw new IllegalArgumentException("需求文件最大5MB");
        } else {
            try {
                String name = fileName.toLowerCase(Locale.ROOT);
                Spec s;
                if (name.endsWith(".json")) {
                    s = SpecParser.read(bytes);
                } else if (name.endsWith(".xlsx")) {
                    Workbook w = WorkbookFactory.create(new ByteArrayInputStream(bytes));

                    try {
                        Sheet c = w.getSheet("模块配置");
                        Sheet f = w.getSheet("字段配置");
                        if (c == null || f == null) {
                            throw new IllegalArgumentException("Excel必须包含模块配置和字段配置工作表");
                        }

                        s = this.tables(this.rows(c), this.rows(f));
                    } catch (Throwable var10) {
                        if (w != null) {
                            try {
                                w.close();
                            } catch (Throwable var9) {
                                var10.addSuppressed(var9);
                            }
                        }

                        throw var10;
                    }

                    if (w != null) {
                        w.close();
                    }
                } else {
                    if (!name.endsWith(".docx")) {
                        throw new IllegalArgumentException("仅支持标准JSON、XLSX、DOCX模板");
                    }

                    XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(bytes));

                    try {
                        if (d.getTables().size() != 2) {
                            throw new IllegalArgumentException("Word模板必须含模块配置、字段配置两张表，不解释自由文本");
                        }

                        s = this.tables(this.docRows((XWPFTable)d.getTables().get(0)), this.docRows((XWPFTable)d.getTables().get(1)));
                    } catch (Throwable var11) {
                        try {
                            d.close();
                        } catch (Throwable var8) {
                            var11.addSuppressed(var8);
                        }

                        throw var11;
                    }

                    d.close();
                }

                new SpecValidator().validate(s);
                return s;
            } catch (IllegalArgumentException var12) {
                throw var12;
            } catch (Exception var13) {
                throw new IllegalArgumentException("需求导入失败：" + var13.getMessage(), var13);
            }
        }
    }

    private List<List<String>> rows(Sheet s) {
        List<List<String>> result = new ArrayList<>();
        DataFormatter fmt = new DataFormatter(Locale.ROOT);

        for (Row r : s) {
            List<String> row = new ArrayList<>();

            for (int i = 0; i < Math.max(14, r.getLastCellNum()); i++) {
                row.add(fmt.formatCellValue(r.getCell(i)).trim());
            }

            result.add(row);
        }

        return result;
    }

    private List<List<String>> docRows(XWPFTable t) {
        List<List<String>> result = new ArrayList<>();

        for (XWPFTableRow r : t.getRows()) {
            List<String> row = new ArrayList<>();

            for (XWPFTableCell c : r.getTableCells()) {
                row.add(c.getText().trim());
            }

            result.add(row);
        }

        return result;
    }

    private Spec tables(List<List<String>> config, List<List<String>> rows) {
        Map<String, String> c = new LinkedHashMap<>();
        Set<String> configKeys = Util.setOf(
            "schemaVersion",
            "packageName",
            "artifactId",
            "entityName",
            "tableName",
            "description",
            "basePath",
            "operations",
            "apiProfile",
            "updateMode",
            "endpoints",
            "integration",
            "options",
            "indexes"
        );
        boolean legacyNotes = false;

        for (int i = 1; i < config.size(); i++) {
            List<String> row = config.get(i);
            if (row.size() > 1 && !Util.isBlank(row.get(0))) {
                String key = row.get(0);
                if (key.equals("填写说明")) {
                    legacyNotes = true;
                } else if (!legacyNotes || configKeys.contains(key) || !Util.isBlank(row.get(1))) {
                    if (!configKeys.contains(key)) {
                        throw new IllegalArgumentException("模块配置不支持字段：" + key + "（第" + (i + 1) + "行），不会忽略或降级");
                    }

                    if (c.put(key, row.get(1)) != null) {
                        throw new IllegalArgumentException("模块配置键重复：" + key);
                    }
                }
            }
        }

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("缺少字段表");
        } else {
            if (!rows.get(0).isEmpty() && rows.get(0).get(0).equals("字段属性")) {
                List<List<String>> transposed = new ArrayList<>();
                List<String> header = new ArrayList<>();

                for (int ix = 1; ix < rows.size(); ix++) {
                    header.add(rows.get(ix).get(0));
                }

                transposed.add(header);

                for (int col = 1; col < rows.get(0).size(); col++) {
                    List<String> field = new ArrayList<>();

                    for (int ix = 1; ix < rows.size(); ix++) {
                        field.add(col < rows.get(ix).size() ? rows.get(ix).get(col) : "");
                    }

                    transposed.add(field);
                }

                rows = transposed;
            }

            List<String> h = new ArrayList<>(rows.get(0));

            while (!h.isEmpty() && Util.isBlank(h.get(h.size() - 1))) {
                h.remove(h.size() - 1);
            }

            if (!h.containsAll(Util.listOf("name", "column", "javaType", "description", "required", "editable", "exposed", "query"))) {
                throw new IllegalArgumentException("字段表头必须使用模板的英文列名");
            } else {
                Set<String> allowed = Util.setOf(
                    "name",
                    "column",
                    "javaType",
                    "description",
                    "required",
                    "editable",
                    "exposed",
                    "query",
                    "maxLength",
                    "precision",
                    "scale",
                    "temporalPrecision",
                    "source",
                    "nullable",
                    "defaultValue",
                    "temporalPrecision",
                    "serverUpdate"
                );
                if (new HashSet<>(h).size() == h.size() && allowed.containsAll(h)) {
                    ObjectNode result = Util.obj();

                    for (Entry<String, String> entry : c.entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        if (!Util.isBlank(value)) {
                            if (key.equals("operations")) {
                                ArrayNode ops = result.putArray(key);

                                for (String op : value.split("[,，]")) {
                                    if (!op.trim().isEmpty()) {
                                        ops.add(op.trim());
                                    }
                                }
                            } else if (key.equals("schemaVersion")) {
                                result.put(key, this.num(value));
                            } else if (Util.setOf("endpoints", "integration", "options", "indexes").contains(key)) {
                                result.set(key, this.jsonCell(value, key));
                            } else {
                                result.put(key, value);
                            }
                        }
                    }

                    ArrayNode fields = result.putArray("fields");

                    for (int ix = 1; ix < rows.size(); ix++) {
                        List<String> row = rows.get(ix);
                        if (!Util.isBlank(this.get(h, row, "name"))) {
                            ObjectNode field = fields.addObject();

                            for (String key : h) {
                                String value = this.get(h, row, key);
                                if (!Util.isBlank(value)) {
                                    if (Util.setOf("required", "editable", "exposed", "nullable", "serverUpdate").contains(key)) {
                                        field.put(key, this.bool(value));
                                    } else if (Util.setOf("maxLength", "precision", "scale", "temporalPrecision").contains(key)) {
                                        field.put(key, this.num(value));
                                    } else if (key.equals("defaultValue")) {
                                        field.set(key, this.jsonCell(value, "字段第" + (ix + 1) + "行 defaultValue"));
                                    } else {
                                        field.put(key, value);
                                    }
                                }
                            }
                        }
                    }

                    return SpecParser.read(Util.bytes(Util.pretty(result)));
                } else {
                    throw new IllegalArgumentException("字段表头存在重复或不支持的属性");
                }
            }
        }
    }

    private JsonNode jsonCell(String value, String location) {
        try {
            return SpecParser.JSON.readTree(value);
        } catch (Exception var4) {
            throw new IllegalArgumentException(location + " 必须为合法 JSON：" + var4.getMessage(), var4);
        }
    }

    private String get(List<String> h, List<String> r, String k) {
        int i = h.indexOf(k);
        return i >= 0 && i < r.size() ? r.get(i).trim() : "";
    }

    private boolean bool(String s) {
        if (Util.setOf("是", "true", "TRUE", "1").contains(s)) {
            return true;
        } else if (Util.setOf("否", "false", "FALSE", "0").contains(s)) {
            return false;
        } else {
            throw new IllegalArgumentException("布尔列请填是/否或true/false");
        }
    }

    private Integer num(String s) {
        if (Util.isBlank(s)) {
            return null;
        } else {
            try {
                return Integer.valueOf(s);
            } catch (Exception var3) {
                throw new IllegalArgumentException("长度/精度/小数位须为整数：" + s);
            }
        }
    }
}
