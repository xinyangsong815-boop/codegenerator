package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.stream.Collectors;

final class SchemaResolver {
    private static final Set<String> ENUM_SERIAL = set("JsonValue", "JsonCreator", "JsonProperty");
    private static final Set<String> FIELD_SERIAL = set("JsonSerialize", "JsonDeserialize", "JsonFormat", "JsonInclude", "JsonUnwrapped");
    private static final Set<String> LOMBOK_GETTERS = set("Data", "Getter", "Value");
    private static final Set<String> CLASS_SERIAL = set("JsonSerialize", "JsonDeserialize", "JsonNaming", "JsonTypeInfo", "JsonInclude");
    private int nodes;
    private final Map<String, TypeDeclaration<?>> types;
    private final Set<String> issues;

    SchemaResolver(Map<String, TypeDeclaration<?>> types, Set<String> issues) {
        this.types = types;
        this.issues = issues;
    }

    ObjectNode resolve(Type t, Map<String, Type> bindings, Set<String> trail, String direction) {
        return this.resolve(t, bindings, trail, direction, 0);
    }

    private ObjectNode resolve(Type t, Map<String, Type> bindings, Set<String> trail, String direction, int depth) {
        String name = t.asString();
        ObjectNode s = Util.obj();
        if (++this.nodes > 7000) {
            this.issues.add("源码模型共享展开预算达到7000项，请缩小业务模块范围");
            return s;
        } else if (depth > 16) {
            this.issues.add("Schema嵌套深度超过16：" + name);
            return s;
        } else if (t.isArrayType()) {
            s.put("type", "array");
            s.set("items", this.resolve(t.asArrayType().getComponentType(), bindings, trail, direction, depth + 1));
            return s;
        } else {
            String simple = t.isClassOrInterfaceType() ? t.asClassOrInterfaceType().getNameAsString() : name;
            if (bindings.containsKey(simple) && !bindings.get(simple).asString().equals(name)) {
                return this.resolve(bindings.get(simple), Collections.emptyMap(), trail, direction, depth + 1);
            } else if (!simple.equals("String") && !simple.equals("char") && !simple.equals("Character")) {
                if (set("int", "Integer", "short", "Short", "byte", "Byte").contains(simple)) {
                    s.put("type", "integer");
                    s.put("format", "int32");
                    return s;
                } else if (set("long", "Long", "BigInteger").contains(simple)) {
                    s.put("type", "integer");
                    s.put("format", "int64");
                    if (simple.equals("BigInteger")) {
                        this.issues.add("BigInteger可能超出int64，需复核");
                    }

                    return s;
                } else if (set("double", "Double", "float", "Float", "BigDecimal").contains(simple)) {
                    s.put("type", "number");
                    if (simple.equals("BigDecimal")) {
                        s.put("x-java-type", "BigDecimal");
                    } else {
                        s.put("format", simple.toLowerCase(Locale.ROOT));
                    }

                    return s;
                } else if (simple.equals("boolean") || simple.equals("Boolean")) {
                    s.put("type", "boolean");
                    return s;
                } else if (simple.equals("LocalDate")) {
                    s.put("type", "string");
                    s.put("format", "date");
                    return s;
                } else if (set("Date", "Instant", "OffsetDateTime", "ZonedDateTime").contains(simple)) {
                    s.put("type", "string");
                    s.put("x-java-type", simple);
                    this.issues.add("日期序列化格式需项目确认：" + simple);
                    return s;
                } else if (simple.equals("LocalDateTime")) {
                    s.put("type", "string").put("format", "local-date-time");
                    return s;
                } else if (!simple.equals("void") && !simple.equals("Void")) {
                    if (set("List", "Set", "Collection", "Iterable").contains(simple)) {
                        s.put("type", "array");
                        Optional<NodeList<Type>> args = t.asClassOrInterfaceType().getTypeArguments();
                        if (args.isPresent() && !args.get().isEmpty()) {
                            s.set("items", this.resolve((Type)args.get().get(0), bindings, trail, direction, depth + 1));
                        } else {
                            this.issues.add("集合未声明泛型：" + name);
                            s.set("items", Util.obj());
                        }

                        return s;
                    } else if (set("ResponseEntity", "HttpEntity", "Optional").contains(simple)) {
                        Optional<NodeList<Type>> a = t.asClassOrInterfaceType().getTypeArguments();
                        if (a.isPresent() && !a.get().isEmpty()) {
                            return this.resolve((Type)a.get().get(0), bindings, trail, direction, depth + 1);
                        } else {
                            this.issues.add("包装类型未声明泛型：" + name);
                            return s;
                        }
                    } else if (set("Map", "HashMap", "Object").contains(simple)) {
                        this.issues.add("动态或未明确的类型：" + name);
                        s.put("type", "object");
                        s.put("additionalProperties", true);
                        return s;
                    } else {
                        TypeDeclaration<?> decl = this.find(t, simple);
                        if (decl == null) {
                            this.issues.add("找不到类型源码：" + name);
                            return s;
                        } else {
                            String qualified = decl.getFullyQualifiedName().orElse(simple);
                            if (!trail.add(qualified)) {
                                this.issues.add("递归模型未展开：" + qualified);
                                return s;
                            } else {
                                ObjectNode props;
                                try {
                                    if (!decl.isEnumDeclaration()) {
                                        Map<String, Type> params = new HashMap<>(bindings);
                                        if (decl instanceof ClassOrInterfaceDeclaration && t.isClassOrInterfaceType()) {
                                            ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration)decl;
                                            NodeList<Type> args = t.asClassOrInterfaceType().getTypeArguments().orElse(new NodeList());

                                            for (int i = 0; i < c.getTypeParameters().size(); i++) {
                                                if (i < args.size()) {
                                                    Type arg = (Type)args.get(i);
                                                    Type bound = bindings.containsKey(arg.asString()) ? bindings.get(arg.asString()) : arg;
                                                    params.put(((TypeParameter)c.getTypeParameters().get(i)).getNameAsString(), bound);
                                                }
                                            }
                                        }

                                        s.put("type", "object");
                                        props = s.putObject("properties");
                                        ArrayNode required = s.putArray("required");
                                        if (decl instanceof ClassOrInterfaceDeclaration) {
                                            ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) decl;
                                            for (ClassOrInterfaceType base : c.getExtendedTypes()) {
                                                ObjectNode inherited = this.resolve(base, params, new HashSet<>(trail), direction, depth + 1);
                                                if (inherited.path("properties").isObject()) {
                                                    Iterator<Entry<String, JsonNode>> it = inherited.path("properties").fields();

                                                    while (it.hasNext()) {
                                                        Entry<String, JsonNode> e = it.next();
                                                        props.set(e.getKey(), e.getValue());
                                                    }
                                                }

                                                if (inherited.path("required").isArray()) {
                                                    for (JsonNode x : inherited.path("required")) {
                                                        required.add(x);
                                                    }
                                                }
                                            }
                                        }

                                        for (FieldDeclaration f : decl.getFields()) {
                                            if (this.nodes > 7000) {
                                                break;
                                            }

                                            if (!f.isStatic() && !has(f, "JsonIgnore")) {
                                                boolean getterIgnored = false;

                                                for (VariableDeclarator variable : f.getVariables()) {
                                                    String cap = Character.toUpperCase(variable.getNameAsString().charAt(0))
                                                        + variable.getNameAsString().substring(1);

                                                    for (MethodDeclaration method : decl.getMethods()) {
                                                        if (method.getNameAsString().equals("get" + cap) || method.getNameAsString().equals("is" + cap)) {
                                                            for (AnnotationExpr annotation : method.getAnnotations()) {
                                                                if (annotation.getName().getIdentifier().equals("JsonIgnore")) {
                                                                    getterIgnored = true;
                                                                }
                                                            }
                                                        }
                                                    }
                                                }

                                                if (!getterIgnored) {
                                                    for (AnnotationExpr a : f.getAnnotations()) {
                                                        if (FIELD_SERIAL.contains(a.getName().getIdentifier()) && !this.knownSerialization(a)) {
                                                            this.issues.add("字段自定义序列化未解析：" + qualified + " " + f);
                                                            break;
                                                        }
                                                    }

                                                    boolean lombok = false;

                                                    for (AnnotationExpr ax : decl.getAnnotations()) {
                                                        if (LOMBOK_GETTERS.contains(ax.getName().getIdentifier())) {
                                                            lombok = true;
                                                            break;
                                                        }
                                                    }

                                                    if (f.isPrivate() && !has(f, "JsonProperty") && !lombok && !has(f, "Getter")) {
                                                        for (VariableDeclarator v : f.getVariables()) {
                                                            String cap = Character.toUpperCase(v.getNameAsString().charAt(0))
                                                                + v.getNameAsString().substring(1);
                                                            if (decl.getMethodsByName("get" + cap).isEmpty() && decl.getMethodsByName("is" + cap).isEmpty()) {
                                                                this.issues.add("私有字段无可识别getter，Jackson可见性需确认：" + qualified + "." + v.getNameAsString());
                                                            }
                                                        }
                                                    }

                                                    boolean writeOnly = false;
                                                    boolean readOnly = false;

                                                    for (AnnotationExpr axx : f.getAnnotations()) {
                                                        String text = axx.toString();
                                                        if (text.contains("WRITE_ONLY")) {
                                                            writeOnly = true;
                                                        }

                                                        if (text.contains("READ_ONLY")) {
                                                            readOnly = true;
                                                        }
                                                    }

                                                    if ((!writeOnly || !direction.equals("response")) && (!readOnly || !direction.equals("request"))) {
                                                        for (VariableDeclarator vx : f.getVariables()) {
                                                            String field = vx.getNameAsString();
                                                            NodeList<AnnotationExpr> annotations = new NodeList();
                                                            annotations.addAll(f.getAnnotations());
                                                            String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);

                                                            for (MethodDeclaration getter : decl.getMethods()) {
                                                                if (getter.getParameters().isEmpty()
                                                                    && (
                                                                        getter.getNameAsString().equals("get" + cap)
                                                                            || getter.getNameAsString().equals("is" + cap)
                                                                    )) {
                                                                    for (AnnotationExpr ann : getter.getAnnotations()) {
                                                                        annotations.add(ann);
                                                                        if (FIELD_SERIAL.contains(ann.getName().getIdentifier())
                                                                            && !this.knownSerialization(ann)) {
                                                                            this.issues.add("getter自定义序列化需复核：" + qualified + "." + getter.getNameAsString());
                                                                        }
                                                                    }
                                                                }
                                                            }

                                                            String rename = annotationValue(annotations, "JsonProperty", "value");
                                                            if (rename != null) {
                                                                field = rename;
                                                            }

                                                            ObjectNode fs = this.resolve(vx.getType(), params, new HashSet<>(trail), direction, depth + 1);
                                                            if (f.getJavadocComment().isPresent()) {
                                                                fs.put(
                                                                    "description",
                                                                    ((JavadocComment)f.getJavadocComment().get()).parse().getDescription().toText()
                                                                );
                                                            }

                                                            this.applySerialization(annotations, fs, direction);
                                                            this.constraints(annotations, fs);
                                                            boolean needed = annotations.stream()
                                                                    .anyMatch(
                                                                        x -> Arrays.asList("NotNull", "NotBlank", "NotEmpty")
                                                                            .contains(x.getName().getIdentifier())
                                                                    )
                                                                || vx.getType().isPrimitiveType();
                                                            if (!needed) {
                                                                fs.put("nullable", true);
                                                            }

                                                            if (vx.getInitializer().isPresent()) {
                                                                Expression init = (Expression)vx.getInitializer().get();
                                                                if (init.isIntegerLiteralExpr()) {
                                                                    try {
                                                                        fs.put("default", init.asIntegerLiteralExpr().asNumber().longValue());
                                                                    } catch (Exception var33) {
                                                                    }
                                                                } else if (init.isStringLiteralExpr()) {
                                                                    fs.put("default", init.asStringLiteralExpr().asString());
                                                                }
                                                            }

                                                            props.set(field, fs);
                                                            if (needed) {
                                                                required.add(field);
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        for (MethodDeclaration getterx : decl.getMethods()) {
                                            if (!getterx.isStatic()
                                                && getterx.isPublic()
                                                && getterx.getParameters().isEmpty()
                                                && !getterx.getType().isVoidType()) {
                                                String methodx = getterx.getNameAsString();
                                                String property = methodx.startsWith("get") && methodx.length() > 3
                                                    ? methodx.substring(3)
                                                    : (methodx.startsWith("is") && methodx.length() > 2 ? methodx.substring(2) : "");
                                                if (!property.isEmpty() && !property.equals("Class")) {
                                                    property = Character.toLowerCase(property.charAt(0)) + property.substring(1);
                                                    boolean backed = false;

                                                    for (FieldDeclaration field : decl.getFields()) {
                                                        for (VariableDeclarator vx : field.getVariables()) {
                                                            if (vx.getNameAsString().equals(property)) {
                                                                backed = true;
                                                            }
                                                        }
                                                    }

                                                    if (!backed
                                                        && !getterx.getAnnotations().stream().anyMatch(x -> x.getName().getIdentifier().equals("JsonIgnore"))) {
                                                        String renamex = annotationValue(getterx.getAnnotations(), "JsonProperty", "value");
                                                        if (renamex != null) {
                                                            property = renamex;
                                                        }

                                                        if (direction.equals("request")) {
                                                            this.issues.add("仅getter属性的反序列化可写性需复核：" + qualified + "." + property);
                                                        } else {
                                                            ObjectNode value = this.resolve(
                                                                getterx.getType(), params, new HashSet<>(trail), direction, depth + 1
                                                            );
                                                            this.constraints(getterx.getAnnotations(), value);
                                                            this.applySerialization(getterx.getAnnotations(), value, direction);
                                                            props.set(property, value);
                                                            this.issues.add("计算getter返回值和可空性需运行时复核：" + qualified + "." + property);
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        if (decl instanceof RecordDeclaration) {
                                            RecordDeclaration rec = (RecordDeclaration) decl;
                                            for (Parameter p : rec.getParameters()) {
                                                props.set(p.getNameAsString(), this.resolve(p.getType(), params, new HashSet<>(trail), direction, depth + 1));
                                            }
                                        }

                                        for (AnnotationExpr axx : decl.getAnnotations()) {
                                            if (CLASS_SERIAL.contains(axx.getName().getIdentifier())) {
                                                this.issues.add("类级自定义序列化未解析：" + qualified);
                                                break;
                                            }
                                        }

                                        if (required.isEmpty()) {
                                            s.remove("required");
                                        }

                                        return s;
                                    }

                                    s.put("type", "string");
                                    ArrayNode axxx = s.putArray("enum");

                                    for (EnumConstantDeclaration e : decl.asEnumDeclaration().getEntries()) {
                                        axxx.add(e.getNameAsString());
                                    }

                                    for (AnnotationExpr ae : decl.findAll(AnnotationExpr.class)) {
                                        if (ENUM_SERIAL.contains(ae.getName().getIdentifier())) {
                                            this.issues.add("枚举自定义序列化需复核：" + qualified);
                                            break;
                                        }
                                    }

                                    props = s;
                                } finally {
                                    trail.remove(qualified);
                                }

                                return props;
                            }
                        }
                    }
                } else {
                    s.put("type", "object");
                    s.put("nullable", true);
                    return s;
                }
            } else {
                s.put("type", "string");
                return s;
            }
        }
    }

    private TypeDeclaration<?> find(Type t, String simple) {
        if (this.types.containsKey(t.asString())) {
            return this.types.get(t.asString());
        } else {
            Optional<CompilationUnit> unit = t.findCompilationUnit();
            if (unit.isPresent()) {
                for (ImportDeclaration i : unit.get().getImports()) {
                    if (!i.isAsterisk() && i.getName().getIdentifier().equals(simple) && this.types.containsKey(i.getNameAsString())) {
                        return this.types.get(i.getNameAsString());
                    }
                }

                String pkg = unit.get().getPackageDeclaration().isPresent()
                    ? ((PackageDeclaration)unit.get().getPackageDeclaration().get()).getNameAsString() + "."
                    : "";
                if (this.types.containsKey(pkg + simple)) {
                    return this.types.get(pkg + simple);
                }
            }

            List<TypeDeclaration<?>> matches = this.types.values().stream().filter(x -> x.getNameAsString().equals(simple)).collect(Collectors.toList());
            if (matches.size() == 1) {
                return matches.get(0);
            } else {
                if (matches.size() > 1) {
                    this.issues.add("类型名称歧义：" + simple);
                }

                return null;
            }
        }
    }

    static boolean has(FieldDeclaration f, String name) {
        for (AnnotationExpr a : f.getAnnotations()) {
            if (a.getName().getIdentifier().equals(name)) {
                return true;
            }
        }

        return false;
    }

    static String annotationValue(NodeList<AnnotationExpr> annotations, String name, String key) {
        for (AnnotationExpr a : annotations) {
            if (a.getName().getIdentifier().equals(name)) {
                if (a.isSingleMemberAnnotationExpr() && key.equals("value")) {
                    Expression e = a.asSingleMemberAnnotationExpr().getMemberValue();
                    if (e.isStringLiteralExpr()) {
                        return e.asStringLiteralExpr().asString();
                    }

                    return e.toString();
                }

                if (a.isNormalAnnotationExpr()) {
                    for (MemberValuePair p : a.asNormalAnnotationExpr().getPairs()) {
                        if (p.getNameAsString().equals(key)) {
                            Expression e = p.getValue();
                            if (e.isStringLiteralExpr()) {
                                return e.asStringLiteralExpr().asString();
                            }

                            return e.toString();
                        }
                    }
                }
            }
        }

        return null;
    }

    void constraints(NodeList<AnnotationExpr> a, ObjectNode s) {
        for (String[] rule : new String[][]{
            {"Size", "min", "minLength"},
            {"Size", "max", "maxLength"},
            {"Min", "value", "minimum"},
            {"Max", "value", "maximum"},
            {"DecimalMin", "value", "minimum"},
            {"DecimalMax", "value", "maximum"}
        }) {
            String v = annotationValue(a, rule[0], rule[1]);
            if (v != null) {
                try {
                    BigDecimal number = new BigDecimal(v);
                    if (rule[0].equals("Size")) {
                        s.put(rule[2], number.intValueExact());
                    } else {
                        s.put(rule[2], number);
                    }
                } catch (Exception var14) {
                    this.issues.add("约束值不是静态数字：" + rule[0]);
                }
            }
        }

        if (s.path("type").asText().equals("string") && a.stream().anyMatch(xx -> xx.getName().getIdentifier().equals("Size"))) {
            if (s.has("minLength")) {
                s.set("x-utf16-min-length", s.remove("minLength"));
            }

            if (s.has("maxLength")) {
                s.set("x-utf16-max-length", s.get("maxLength"));
            }
        }

        for (String[] rulex : new String[][]{
            {"Positive", "minimum", "0"}, {"PositiveOrZero", "minimum", "0"}, {"Negative", "maximum", "0"}, {"NegativeOrZero", "maximum", "0"}
        }) {
            if (a.stream().anyMatch(xx -> xx.getName().getIdentifier().equals(rulex[0]))) {
                s.put(rulex[1], Integer.parseInt(rulex[2]));
                if (rulex[0].equals("Positive")) {
                    s.put("exclusiveMinimum", true);
                }

                if (rulex[0].equals("Negative")) {
                    s.put("exclusiveMaximum", true);
                }
            }
        }

        for (String bound : Arrays.asList("DecimalMin", "DecimalMax")) {
            if ("false".equals(annotationValue(a, bound, "inclusive"))) {
                s.put(bound.equals("DecimalMin") ? "exclusiveMinimum" : "exclusiveMaximum", true);
            }
        }

        for (String key : Arrays.asList("min", "max")) {
            String length = annotationValue(a, "CodePointSize", key);
            if (length != null) {
                try {
                    s.put(key.equals("min") ? "minLength" : "maxLength", Integer.parseInt(length));
                } catch (Exception var13) {
                    this.issues.add("CodePointSize参数需复核");
                }
            }
        }

        if (s.path("type").asText().equals("array")) {
            if (s.has("minLength")) {
                s.set("minItems", s.remove("minLength"));
            }

            if (s.has("maxLength")) {
                s.set("maxItems", s.remove("maxLength"));
            }
        }

        if (a.stream().anyMatch(xx -> xx.getName().getIdentifier().equals("NotBlank")) && s.path("type").asText().equals("string")) {
            s.put("minLength", Math.max(1, s.path("minLength").asInt())).put("pattern", "\\S");
        }

        if (a.stream().anyMatch(xx -> xx.getName().getIdentifier().equals("NotEmpty"))) {
            if (s.path("type").asText().equals("string")) {
                s.put("minLength", Math.max(1, s.path("minLength").asInt()));
            }

            if (s.path("type").asText().equals("array")) {
                s.put("minItems", Math.max(1, s.path("minItems").asInt()));
            }
        }

        String integer = annotationValue(a, "Digits", "integer");
        String fraction = annotationValue(a, "Digits", "fraction");
        if (integer != null && fraction != null) {
            try {
                s.put("x-precision", Integer.parseInt(integer) + Integer.parseInt(fraction));
                s.put("x-scale", Integer.parseInt(fraction));
            } catch (NumberFormatException var12) {
                this.issues.add("Digits 参数不是静态整数");
            }
        }

        if (a.stream().anyMatch(xx -> xx.getName().getIdentifier().equals("DatabaseDateRange") || xx.getName().getIdentifier().equals("TemporalPrecision"))) {
            s.put("x-date-minimum", "1000-01-01").put("x-date-maximum", "9999-12-31");
        }

        String precision = annotationValue(a, "TemporalPrecision", "value");
        if (precision != null) {
            try {
                s.put("x-temporal-precision", Integer.parseInt(precision));
            } catch (NumberFormatException var11) {
                this.issues.add("时间精度不是静态整数");
            }
        }

        for (AnnotationExpr x : a) {
            String annotation = x.getName().getIdentifier();
            if (Arrays.asList("AssertTrue", "AssertFalse", "Email", "Past", "PastOrPresent", "Future", "FutureOrPresent").contains(annotation)) {
                this.issues.add("未支持的校验语义需复核：" + annotation);
            }

            for (TypeDeclaration<?> type : this.types.values()) {
                if (type.getNameAsString().equals(annotation)
                    && type.getAnnotations().stream().anyMatch(ann -> ann.getName().getIdentifier().equals("Constraint"))
                    && !Arrays.asList("CodePointSize", "DatabaseDateRange", "TemporalPrecision").contains(annotation)) {
                    this.issues.add("自定义校验语义需复核：" + annotation);
                }
            }
        }

        for (AnnotationExpr x : a) {
            if (x.getName().getIdentifier().equals("Pattern")) {
                this.issues.add("Java正则约束未转换，需要复核");
            }

            if (x.toString().contains("groups")) {
                this.issues.add("校验分组需复核");
            }
        }
    }

    private boolean knownSerialization(AnnotationExpr a) {
        String n = a.getName().getIdentifier();
        String text = a.toString();
        return !n.equals("JsonSerialize") && !n.equals("JsonDeserialize")
            ? n.equals("JsonFormat") && text.contains("Shape.STRING") && !text.contains("pattern") && !text.contains("timezone")
            : text.contains("NumericWire.") || text.contains("ToStringSerializer.class") || a.isMarkerAnnotationExpr();
    }

    private void applySerialization(NodeList<AnnotationExpr> annotations, ObjectNode s, String direction) {
        String name = direction.equals("request") ? "JsonDeserialize" : "JsonSerialize";
        String serializer = annotationValue(annotations, name, "using");
        String content = annotationValue(annotations, name, "contentUsing");
        ObjectNode target = content != null && s.path("items").isObject() ? (ObjectNode)s.get("items") : s;
        String value = content != null ? content : serializer;
        if (value != null && (value.contains("NumericWire.") || value.contains("ToStringSerializer.class"))) {
            boolean decimal = value.contains("Decimal");
            target.put("type", "string");
            target.remove("format");
            target.put("x-java-type", decimal ? "BigDecimal" : "Long");
            target.put("pattern", decimal ? "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$" : "^-?(0|[1-9][0-9]*)$");
            if (!decimal) {
                target.put("x-numeric-minimum", Long.toString(Long.MIN_VALUE));
                target.put("x-numeric-maximum", Long.toString(Long.MAX_VALUE));
            }
        }
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
