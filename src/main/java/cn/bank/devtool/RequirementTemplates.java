package cn.bank.devtool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;

public final class RequirementTemplates {
    private static final String[][] CONFIG = new String[][]{
        {"key", "value"},
        {"schemaVersion", "2"},
        {"packageName", "com.bank.contract"},
        {"artifactId", "contract-demo"},
        {"entityName", "Contract"},
        {"tableName", "biz_contract"},
        {"description", "合同管理"},
        {"basePath", "/contracts"},
        {"operations", "CREATE,UPDATE,DETAIL,PAGE,DELETE"},
        {"apiProfile", "REST"},
        {"updateMode", "PARTIAL"},
        {"endpoints", "{}"},
        {"options", "{\"wireFormat\":\"STRING_NUMBERS\",\"jsonMode\":\"STRICT\"}"},
        {"indexes", "[]"},
        {"integration", "{\"mode\":\"STANDALONE\"}"}
    };
    private static final String[][] FIELDS = new String[][]{
        {
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
                "source",
                "nullable",
                "defaultValue",
                "temporalPrecision",
                "serverUpdate"
        },
        {"contractNo", "contract_no", "String", "合同编号", "true", "true", "true", "EQ", "50", "", "", "CLIENT", "", "", "", "false"},
        {"amount", "amount", "BigDecimal", "合同金额", "false", "true", "true", "RANGE", "", "18", "2", "CLIENT", "", "", "", "false"}
    };

    public byte[] create(String format) {
        try {
            byte[] var16;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if ("xlsx".equals(format)) {
                    XSSFWorkbook book = new XSSFWorkbook();

                    try {
                        this.sheet(book.createSheet("模块配置"), CONFIG);
                        this.sheet(book.createSheet("字段配置"), FIELDS);
                        book.write(out);
                    } catch (Throwable var11) {
                        try {
                            book.close();
                        } catch (Throwable var10) {
                            var11.addSuppressed(var10);
                        }

                        throw var11;
                    }

                    book.close();
                } else {
                    if (!"docx".equals(format)) {
                        throw new IllegalArgumentException("format 仅支持 xlsx/docx");
                    }

                    XWPFDocument doc = new XWPFDocument();

                    try {
                        doc.createParagraph().createRun().setText("代码生成规格 v2：复杂选项填写 JSON；字段来源为 CLIENT/SERVER/DATABASE。不要添加额外说明表。");
                        XWPFTable config = doc.createTable(CONFIG.length, 2);

                        for (int i = 0; i < CONFIG.length; i++) {
                            for (int j = 0; j < 2; j++) {
                                config.getRow(i).getCell(j).setText(CONFIG[i][j]);
                            }
                        }

                        XWPFTable fields = doc.createTable(FIELDS[0].length + 1, FIELDS.length);
                        fields.getRow(0).getCell(0).setText("字段属性");

                        for (int j = 1; j < FIELDS.length; j++) {
                            fields.getRow(0).getCell(j).setText("字段" + j);
                        }

                        for (int i = 0; i < FIELDS[0].length; i++) {
                            for (int j = 0; j < FIELDS.length; j++) {
                                fields.getRow(i + 1).getCell(j).setText(FIELDS[j][i]);
                            }
                        }

                        doc.write(out);
                    } catch (Throwable var12) {
                        try {
                            doc.close();
                        } catch (Throwable var9) {
                            var12.addSuppressed(var9);
                        }

                        throw var12;
                    }

                    doc.close();
                }

                var16 = out.toByteArray();
            }

            return var16;
        } catch (IOException var14) {
            throw new IllegalStateException("需求模板生成失败", var14);
        }
    }

    private void sheet(Sheet sheet, String[][] rows) {
        for (int i = 0; i < rows.length; i++) {
            Row row = sheet.createRow(i);

            for (int j = 0; j < rows[i].length; j++) {
                row.createCell(j).setCellValue(rows[i][j]);
            }
        }

        sheet.createFreezePane(0, 1);

        for (int j = 0; j < rows[0].length; j++) {
            sheet.setColumnWidth(j, j == 1 && rows[0].length == 2 ? 22000 : 4500);
        }
    }
}
