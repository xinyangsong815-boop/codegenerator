package cn.bank.devtool;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.lang.model.SourceVersion;

public final class SpecValidator {
    private static final Set<String> ENTITY_RESERVED = set(
        "String",
        "Long",
        "Integer",
        "Boolean",
        "BigDecimal",
        "LocalDate",
        "LocalDateTime",
        "Object",
        "Class",
        "Void",
        "List",
        "Map",
        "Collection",
        "Set",
        "Optional",
        "ArrayList",
        "Arrays",
        "Comparator",
        "HashMap",
        "HashSet",
        "Iterator",
        "LinkedHashMap",
        "LinkedHashSet",
        "TreeMap",
        "TreeSet",
        "UUID",
        "Math",
        "Objects",
        "System",
        "Result",
        "PageResult",
        "BusinessException",
        "ApiErrors",
        "Service",
        "Transactional",
        "Test",
        "SpringBootTest",
        "ActiveProfiles",
        "Autowired",
        "Param",
        "Application",
        "SpringApplication",
        "SpringBootApplication",
        "MapperScan",
        "RuntimeException",
        "Exception",
        "IllegalArgumentException",
        "Assertions",
        "LikePattern",
        "Logger",
        "LoggerFactory",
        "JsonIgnore",
        "JsonProperty",
        "Positive",
        "NotNull",
        "Valid",
        "Validated",
        "HttpMethod",
        "HttpStatus",
        "ResponseEntity",
        "Override",
        "Collections",
        "Collectors",
        "RequestMethod",
        "BindingResult",
        "BindException",
        "WebDataBinder",
        "InitBinder",
        "DateTimeFormat",
        "ValidationIssue",
        "RequestMismatchException",
        "NumericWire",
        "CodePointSize",
        "CodePointSizeValidator"
    );
    private static final Set<String> OPS = set("CREATE", "UPDATE", "DETAIL", "PAGE", "DELETE");
    private static final Set<String> FIELD_RESERVED = set("id", "class", "serialVersionUID", "emptyUpdate", "updatedFields");
    private static final Set<String> TYPES = set("String", "Long", "Integer", "BigDecimal", "LocalDate", "LocalDateTime", "Boolean");
    private static final Set<String> QUERIES = set("NONE", "EQ", "CONTAINS", "RANGE", "IN");
    private static final Set<String> NEWER_KEYWORDS = set("record", "var", "yield", "sealed", "permits");

    public void validate(Spec s) {
        if (s == null) {
            fail("规格不能为空");
        }

        if (s.packageName() == null || !s.packageName().matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
            fail("packageName必须是合法包名，例如com.bank.contract");
        }

        for (String p : s.packageName().split("\\.")) {
            this.id(p, "包名");
        }

        if (s.artifactId() == null || !s.artifactId().matches("[a-z][a-z0-9-]{0,63}")) {
            fail("artifactId必须为小写英文、数字和短横线");
        }

        this.id(s.entityName(), "实体名");
        if (!Character.isUpperCase(s.entityName().charAt(0))) {
            fail("实体名须以大写字母开头");
        }

        if (ENTITY_RESERVED.contains(s.entityName())) {
            fail("实体名与模板引用的基础或公共类型冲突，请使用业务名称：" + s.entityName());
        }

        if (s.tableName() == null || !s.tableName().matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}")) {
            fail("非法数据库表名");
        }

        if (s.basePath() == null || !s.basePath().matches("/[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*")) {
            fail("basePath使用静态路径，例如/contracts");
        }

        if (Util.isBlank(s.description()) || s.description().length() > 200) {
            fail("请填写200字以内的模块说明");
        }

        if (s.operations() == null || s.operations().isEmpty() || new HashSet<>(s.operations()).size() != s.operations().size()) {
            fail("操作列表为空、重复或含不支持的操作");
        }

        for (String op : s.operations()) {
            if (op == null || !OPS.contains(op)) {
                fail("操作列表为空、重复或含不支持的操作");
            }
        }

        if (s.fields() == null || s.fields().isEmpty() || s.fields().size() > 100) {
            fail("字段数量必须为1至100");
        }

        Set<String> names = new HashSet<>();
        Set<String> columns = new HashSet<>();

        for (Spec.Field f : s.fields()) {
            if (f == null) {
                fail("字段不能为空");
            }

            this.id(f.name(), "字段名");
            if (!f.name().matches("[a-z][a-zA-Z0-9_]*")) {
                fail("字段名须以小写字母开头，避免生成getter冲突：" + f.name());
            }

            if (FIELD_RESERVED.contains(f.name()) || !names.add(f.name())) {
                fail("字段重复或保留：" + f.name());
            }

            if (f.column() == null
                || !f.column().matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}")
                || f.column().equalsIgnoreCase("id")
                || !columns.add(f.column().toLowerCase(Locale.ROOT))) {
                fail("列名重复、非法或为id：" + f.column());
            }

            if (f.javaType() == null || !TYPES.contains(f.javaType())) {
                fail("不支持的Java类型：" + f.javaType());
            }

            if (Util.isBlank(f.description()) || f.description().length() > 200) {
                fail("请填写字段中文说明：" + f.name());
            }

            if (f.query() == null || !QUERIES.contains(f.query())) {
                fail("未知查询方式：" + f.name());
            }

            if (f.query().equals("CONTAINS") && !f.javaType().equals("String")) {
                fail("CONTAINS仅支持String");
            }

            if (f.query().equals("RANGE") && (f.javaType().equals("String") || f.javaType().equals("Boolean"))) {
                fail("RANGE仅支持数值或日期");
            }

            if (f.javaType().equals("String") && (f.maxLength() == null || f.maxLength() < 1 || f.maxLength() > 2000)) {
                fail("String长度必须为1至2000：" + f.name());
            }

            if (f.javaType().equals("BigDecimal")
                && (f.precision() == null || f.scale() == null || f.precision() < 1 || f.precision() > 38 || f.scale() < 0 || f.scale() > f.precision())) {
                fail("BigDecimal需明确precision和scale：" + f.name());
            }
        }

        for (Spec.Field f : s.fields()) {
            if (names.contains(f.name() + "Present") || names.contains(f.name() + "ValueValid")) {
                fail("部分更新派生名称重名：" + f.name());
            }
        }

        for (Spec.Field fx : s.fields()) {
            if (!fx.query().equals("NONE")) {
                if (fx.query().equals("RANGE") && (names.contains(fx.name() + "From") || names.contains(fx.name() + "To"))) {
                    fail("区间查询派生字段重名：" + fx.name());
                }

                if (fx.query().equals("CONTAINS") && names.contains(fx.name() + "Like")) {
                    fail("模糊查询派生字段重名：" + fx.name());
                }

                if (fx.query().equals("RANGE") && names.contains(fx.name() + "RangeValid")) {
                    fail("范围校验派生字段重名：" + fx.name());
                }

                if (set("page", "size", "offset").contains(fx.name())) {
                    fail("查询字段与分页参数重名：" + fx.name());
                }
            }
        }

        ProductionPolicy.validate(s);
        InterfacePolicy.resolve(s);
        EngineeringPolicy.resolve(s);
        if (s.operations().contains("UPDATE")) {
            boolean editable = false;

            for (Spec.Field fxx : s.fields()) {
                if (fxx.editable()) {
                    editable = true;
                    break;
                }
            }

            if (!editable) {
                fail("UPDATE需要至少一个可修改字段");
            }
        }
    }

    private void id(String v, String label) {
        if (v == null
            || v.length() > 64
            || !v.matches("[a-zA-Z_][a-zA-Z0-9_]*")
            || !SourceVersion.isIdentifier(v)
            || SourceVersion.isKeyword(v)
            || NEWER_KEYWORDS.contains(v)) {
            fail(label + "不合法：" + v);
        }
    }

    private static void fail(String m) {
        throw new IllegalArgumentException(m);
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
