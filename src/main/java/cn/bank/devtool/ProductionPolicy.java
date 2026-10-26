package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class ProductionPolicy {
    private ProductionPolicy() {
    }

    public static int version(Spec s) {
        return s.getSchemaVersion() == null ? 1 : s.getSchemaVersion();
    }

    public static String source(Spec.Field f) {
        return f.getSource() == null ? "CLIENT" : f.getSource();
    }

    public static boolean client(Spec.Field f) {
        return source(f).equals("CLIENT");
    }

    public static boolean strings(Spec s) {
        String v = s.getOptions() == null ? null : s.getOptions().getWireFormat();
        return "STRING_NUMBERS".equals(v) || v == null && version(s) == 2;
    }

    public static boolean strict(Spec s) {
        String v = s.getOptions() == null ? null : s.getOptions().getJsonMode();
        return "STRICT".equals(v) || v == null && version(s) == 2;
    }

    public static String jsonScope(Spec s) {
        return s.getOptions() != null && s.getOptions().getJsonScope() != null ? s.getOptions().getJsonScope() : "MODULE";
    }

    public static int temporalPrecision(Spec.Field f) {
        return f.getTemporalPrecision() == null ? 3 : f.getTemporalPrecision();
    }

    public static Spec.DatabaseProfile database(Spec s) {
        return s.getOptions() != null && s.getOptions().getDatabase() != null ? s.getOptions().getDatabase() : new Spec.DatabaseProfile();
    }

    public static boolean locking(Spec s) {
        return s.getOptions() != null && s.getOptions().isOptimisticLock();
    }

    public static boolean recordAccess(Spec s) {
        return s.getOptions() != null && s.getOptions().isRecordAccessCheck();
    }

    public static boolean audit(Spec s) {
        return s.getOptions() != null && s.getOptions().isTransactionalAudit();
    }

    public static boolean rowLock(Spec s) {
        return recordAccess(s) || audit(s);
    }

    public static boolean accessGuard(Spec s) {
        return s.getOptions() != null && s.getOptions().isAccessGuard();
    }

    public static boolean deleteVersion(Spec s) {
        return s.operations().contains("DELETE") && s.getOptions() != null && s.getOptions().isDeleteVersionCheck();
    }

    public static boolean softDelete(Spec s) {
        return s.getOptions() != null && s.getOptions().isSoftDelete();
    }

    public static boolean hooks(Spec s) {
        return s.getOptions() != null && s.getOptions().isHooks();
    }

    public static Spec.Field scope(Spec s) {
        String name = s.getOptions() == null ? null : s.getOptions().getScopeField();
        if (name == null) {
            return null;
        } else {
            for (Spec.Field f : s.fields()) {
                if (f.name().equals(name)) {
                    return f;
                }
            }

            throw new IllegalArgumentException("options.scopeField 不存在：" + name);
        }
    }

    public static void validate(Spec s) {
        require(version(s) == 1 || version(s) == 2, "schemaVersion 仅支持 1、2；未知版本不能自动降级");
        require(!deleteVersion(s) || locking(s), "删除版本校验需要启用 optimisticLock");
        require(!recordAccess(s) || accessGuard(s), "记录权限检查需要 accessGuard");
        require(!audit(s) || accessGuard(s), "同事务审计需要 accessGuard 提供可信操作人");
        require(!audit(s) || s.tableName().length() <= 58, "审计表名加 _audit 后不能超过64个字符");
        require(!audit(s) || s.operations().stream().anyMatch(op -> Arrays.asList("CREATE", "UPDATE", "DELETE").contains(op)), "同事务审计至少需要一个写操作");
        require(!audit(s) || !s.operations().contains("UPDATE") || locking(s), "审计更新必须启用 optimisticLock");
        require(!audit(s) || !s.operations().contains("DELETE") || deleteVersion(s), "审计删除必须启用 deleteVersionCheck");
        Spec.Options o = s.getOptions();
        require(Arrays.asList("MODULE", "HOST").contains(jsonScope(s)), "jsonScope 仅支持 MODULE/HOST");
        require(!jsonScope(s).equals("HOST") || EngineeringPolicy.existing(s), "HOST JSON策略仅用于已有宿主模式");
        Spec.DatabaseProfile db = database(s);
        require(Arrays.asList("8.0", "8.4").contains(db.getVersion()), "database.version 仅支持已验证限制的 MySQL 8.0/8.4");
        require(Arrays.asList("utf8mb4", "utf8mb3", "latin1", "ascii").contains(db.getCharset()), "未知数据库字符集");
        require(
            db.getCollation() != null && db.getCollation().matches("[a-zA-Z0-9_]+") && db.getCollation().startsWith(db.getCharset() + "_"),
            "排序规则必须匹配字符集且为安全标识符"
        );
        require(Arrays.asList("DYNAMIC", "COMPACT", "REDUNDANT").contains(db.getRowFormat()), "不支持数据库行格式");
        require(Arrays.asList(4096, 8192, 16384, 32768, 65536).contains(db.getPageSize()), "不支持 InnoDB 页大小");
        if (o != null) {
            require(o.getWireFormat() == null || Arrays.asList("NATIVE", "STRING_NUMBERS").contains(o.getWireFormat()), "未知 options.wireFormat");
            require(o.getJsonMode() == null || Arrays.asList("COMPATIBLE", "STRICT").contains(o.getJsonMode()), "未知 options.jsonMode");
        }

        Set<String> names = new HashSet<>();

        for (Spec.Field f : s.fields()) {
            names.add(f.name());
            require(!f.isServerUpdate() || source(f).equals("SERVER"), "serverUpdate 仅允许 SERVER 字段：" + f.name());
            if (f.javaType().equals("BigDecimal")) {
                require(f.scale() <= 30, "MySQL DECIMAL scale 不能超过30：" + f.name());
            }

            require(f.getTemporalPrecision() == null || f.javaType().equals("LocalDateTime"), "temporalPrecision 只用于 LocalDateTime：" + f.name());
            require(temporalPrecision(f) >= 0 && temporalPrecision(f) <= 6, "时间精度必须为0至6：" + f.name());
            require(Arrays.asList("CLIENT", "SERVER", "DATABASE").contains(source(f)), "未知字段来源：" + f.name());
            require(client(f) || !f.editable(), "SERVER/DATABASE 字段不能由客户端修改：" + f.name());
            require(!source(f).equals("SERVER") || f.getDefaultValue() == null, "SERVER 字段不使用数据库默认值：" + f.name());
            if (f.getDefaultValue() != null) {
                defaultSql(f);
            }

            if (source(f).equals("DATABASE") && f.required() && !EngineeringPolicy.existing(s)) {
                require(f.getDefaultValue() != null && !f.getDefaultValue().isNull(), "独立工程数据库非空字段需要明确默认值：" + f.name());
            }

            if (locking(s)) {
                require(
                    !f.name().equals("version") && !f.name().equals("expectedVersion") && !f.column().equalsIgnoreCase("version"),
                    "乐观锁保留 version/expectedVersion 名称"
                );
            }

            if (softDelete(s)) {
                require(!f.name().equals("deleted") && !f.column().equalsIgnoreCase("deleted"), "逻辑删除保留 deleted 名称");
            }
        }

        if (o != null && o.getAuditFields() != null) {
            require(audit(s) || o.getAuditFields().isEmpty(), "auditFields 仅用于开启 transactionalAudit 的规格");
            require(
                o.getAuditFields().size() <= 100
                    && new HashSet<>(o.getAuditFields()).size() == o.getAuditFields().size()
                    && names.containsAll(o.getAuditFields()),
                "auditFields 必须是不重复的已定义字段"
            );
        }

        long rowBytes = 8 + (locking(s) ? 8 : 0) + (softDelete(s) ? 1 : 0);
        int nullable = 0;

        for (Spec.Field field : s.fields()) {
            rowBytes += storageBytes(field, db);
            if (!field.required()) {
                nullable++;
            }
        }

        rowBytes += (nullable + 7) / 8;
        require(rowBytes <= 65535L, "声明字段的 MySQL 最大行长度 " + rowBytes + " 字节超过65535；请拆分字段，不能直接生成不可建表的 VARCHAR 结构");
        Spec.Field scope = scope(s);
        if (scope != null) {
            require(
                source(scope).equals("SERVER") && scope.required() && !scope.editable() && scope.query().equals("NONE"),
                "scopeField 必须为 SERVER、非空、不可编辑、NONE 查询字段"
            );
        }

        if (scope != null) {
            require(!scope.isServerUpdate(), "数据范围字段不能在 UPDATE 改写");
        }

        if (s.getIndexes() != null) {
            require(s.getIndexes().size() <= 20, "最多配置20个索引");
            Set<String> indexes = new HashSet<>();

            for (Spec.Index index : s.getIndexes()) {
                require(
                    index != null
                        && index.getName() != null
                        && index.getName().matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}")
                        && !index.getName().equalsIgnoreCase("PRIMARY")
                        && indexes.add(index.getName().toLowerCase(Locale.ROOT)),
                    "索引名非法或重复"
                );
                require(
                    index.getFields() != null
                        && !index.getFields().isEmpty()
                        && index.getFields().size() <= 8
                        && new HashSet<>(index.getFields()).size() == index.getFields().size()
                        && names.containsAll(index.getFields()),
                    "索引字段必须为不重复的已定义字段：" + index.getName()
                );
                int bytes = 0;

                for (String name : index.getFields()) {
                    for (Spec.Field f : s.fields()) {
                        if (name.equals(f.name())) {
                            if (f.javaType().equals("String")) {
                                bytes += f.maxLength() * (db.getCharset().equals("utf8mb4") ? 4 : (db.getCharset().equals("utf8mb3") ? 3 : 1));
                            } else if (f.javaType().equals("BigDecimal")) {
                                bytes += 18;
                            } else {
                                bytes += 8;
                            }
                        }
                    }
                }

                int limit = db.getRowFormat().equals("DYNAMIC") ? Math.min(3072, db.getPageSize() * 3 / 16) : 767;
                require(bytes <= limit, "索引 " + index.getName() + " 最大编码长度 " + bytes + " 字节超过 " + limit + "；请调整字段/索引，禁止自动截短唯一键");
            }
        }
    }

    public static String defaultSql(Spec.Field f) {
        JsonNode value = f.getDefaultValue();
        if (value == null) {
            return "";
        } else if (value.isNull()) {
            require(!f.required(), "非空字段不能 DEFAULT NULL：" + f.name());
            return "NULL";
        } else {
            String var2 = f.javaType();
            switch (var2) {
                case "String":
                    require(
                        value.isTextual()
                            && value.asText().codePointCount(0, value.asText().length()) <= f.maxLength()
                            && !value.asText().contains("\\")
                            && !value.asText().contains("\u0000")
                            && !value.asText().contains("\r")
                            && !value.asText().contains("\n"),
                        "字符串默认值类型、长度或字符不支持：" + f.name()
                    );
                    return "'" + value.asText().replace("'", "''") + "'";
                case "Long":
                case "Integer":
                    require(
                        value.isIntegralNumber() && (f.javaType().equals("Long") ? value.canConvertToLong() : value.canConvertToInt()), "整数默认值不合法：" + f.name()
                    );
                    return value.asText();
                case "BigDecimal":
                    require(value.isNumber() || value.isTextual() && value.asText().matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"), "金额默认值不合法：" + f.name());
                    BigDecimal d = new BigDecimal(value.asText());
                    require(Math.max(0, d.scale()) <= f.scale() && Math.max(0, d.precision() - d.scale()) <= f.precision() - f.scale(), "金额默认值超过精度：" + f.name());
                    return d.toPlainString();
                case "Boolean":
                    require(value.isBoolean(), "布尔默认值不合法：" + f.name());
                    return value.asBoolean() ? "TRUE" : "FALSE";
                case "LocalDate":
                    require(value.isTextual(), "日期默认值应为字符串");
                    LocalDate date = LocalDate.parse(value.asText());
                    require(date.getYear() >= 1000 && date.getYear() <= 9999, "DATE 默认值超出 MySQL 支持范围：" + f.name());
                    return "'" + value.asText() + "'";
                case "LocalDateTime":
                    require(value.isTextual(), "日期时间默认值应为字符串");
                    LocalDateTime dt = LocalDateTime.parse(value.asText());
                    require(
                        dt.getNano() % (int)Math.pow(10.0, 9 - temporalPrecision(f)) == 0 && dt.getYear() >= 1000 && dt.getYear() <= 9999,
                        "DATETIME 默认值超过时间精度或数据库日期范围：" + f.name()
                    );
                    return "'" + dt.toString().replace('T', ' ') + "'";
                default:
                    throw new IllegalArgumentException("不支持默认值类型");
            }
        }
    }

    private static int decimalBytes(int digits) {
        int[] tail = new int[]{0, 1, 1, 2, 2, 3, 3, 4, 4};
        return digits / 9 * 4 + tail[digits % 9];
    }

    private static long storageBytes(Spec.Field f, Spec.DatabaseProfile db) {
        String var2 = f.javaType();
        switch (var2) {
            case "String":
                long bytes = (long)f.maxLength().intValue() * (db.getCharset().equals("utf8mb4") ? 4 : (db.getCharset().equals("utf8mb3") ? 3 : 1));
                return bytes + (bytes > 255L ? 2 : 1);
            case "Long":
                return 8L;
            case "Integer":
                return 4L;
            case "Boolean":
                return 1L;
            case "LocalDate":
                return 3L;
            case "LocalDateTime":
                return 5 + (temporalPrecision(f) + 1) / 2;
            case "BigDecimal":
                return decimalBytes(f.precision() - f.scale()) + decimalBytes(f.scale());
            default:
                throw new IllegalArgumentException("不支持数据库字段类型");
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
