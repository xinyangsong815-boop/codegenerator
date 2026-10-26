package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.javadoc.Javadoc;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;

public final class SourceContractService {
    private static final Set<String> ADVICE = set("ControllerAdvice", "RestControllerAdvice");
    private static final Set<String> BINDINGS = set(
        "RequestBody", "RequestParam", "PathVariable", "RequestHeader", "CookieValue", "ModelAttribute", "RequestPart"
    );
    private static final Set<String> SIMPLE_BINDINGS = set("RequestParam", "PathVariable", "RequestHeader", "CookieValue");
    private static final Set<String> CONTEXT_TYPES = set("HttpServletRequest", "HttpServletResponse", "Principal", "Authentication", "BindingResult");
    private static final Set<String> MAPPING_CONDITIONS = set("headers", "params", "consumes", "produces");
    private static final Set<String> MAPPINGS = set("RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");
    private static final Set<String> HTTP_METHODS = set("get", "post", "put", "delete", "patch", "head", "options");

    public JsonNode scan(byte[] zip) {
        Map<String, byte[]> files = ZipFiles.read(zip);
        JsonNode verified = this.verifiedGenerated(files);
        if (verified != null) {
            return verified;
        } else {
            Set<String> issues = new LinkedHashSet<>();
            Map<String, TypeDeclaration<?>> types = new LinkedHashMap<>();
            Map<TypeDeclaration<?>, String> source = new IdentityHashMap<>();

            for (Entry<String, byte[]> e : files.entrySet()) {
                if (e.getKey().matches(".*application[^/]*\\.(properties|yml|yaml)")) {
                    String config = new String(e.getValue(), StandardCharsets.UTF_8);
                    if (config.contains("context-path")
                        || config.contains("servlet-path")
                        || config.contains("spring.mvc.servlet.path")
                        || config.contains("pathmatch")) {
                        issues.add("运行时路径配置未应用，请在文档中补齐context-path等设置：" + e.getKey());
                    }
                }
            }

            JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_17));
            int javaCount = 0;

            for (Entry<String, byte[]> ex : files.entrySet()) {
                if (ex.getKey().endsWith(".java") && !ex.getKey().contains("/src/test/") && !ex.getKey().startsWith("src/test/")) {
                    if (++javaCount > 1000) {
                        throw new IllegalArgumentException("最多扫描1000个Java文件");
                    }

                    ParseResult<CompilationUnit> parsed = parser.parse(new String(ex.getValue(), StandardCharsets.UTF_8));
                    if (parsed.isSuccessful() && parsed.getResult().isPresent()) {
                        CompilationUnit cu = (CompilationUnit)parsed.getResult().get();

                        for (TypeDeclaration<?> t : cu.getTypes()) {
                            String name = t.getFullyQualifiedName().orElse(t.getNameAsString());
                            if (types.containsKey(name)) {
                                issues.add("重复类名：" + name);
                            } else {
                                types.put(name, t);
                            }

                            source.put(t, ex.getKey());
                        }
                    } else {
                        issues.add("Java语法不能解析：" + ex.getKey());
                    }
                }
            }

            if (javaCount == 0) {
                throw new IllegalArgumentException("ZIP中没有Java源文件");
            } else {
                SchemaResolver resolver = new SchemaResolver(types, issues);
                ObjectNode doc = Util.obj();
                doc.put("openapi", "3.0.3");
                doc.putObject("info").put("title", "源码接口文档").put("version", "source");
                ObjectNode paths = doc.putObject("paths");
                int operations = 0;

                for (TypeDeclaration<?> t : types.values()) {
                    for (AnnotationExpr a : t.getAnnotations()) {
                        if (ADVICE.contains(a.getName().getIdentifier())) {
                            issues.add("存在全局异常或响应处理器，状态码和响应包裹需人工核对：" + t.getNameAsString());
                            break;
                        }
                    }

                    boolean rest = hasAnnotation(t, "RestController");
                    boolean mvc = rest || hasAnnotation(t, "Controller");
                    if (mvc) {
                        if (t.isClassOrInterfaceDeclaration() && (!t.asClassOrInterfaceDeclaration().getExtendedTypes().isEmpty() || !t.asClassOrInterfaceDeclaration().getImplementedTypes().isEmpty())) {
                            issues.add("Controller 继承或实现类型，继承映射及注解尚未解析：" + t.getNameAsString());
                        }
                        this.checkMapping(t.getAnnotations(), issues);
                        List<String> prefixes = this.paths(t.getAnnotations(), issues);
                        if (prefixes.isEmpty()) {
                            prefixes = Collections.singletonList("");
                        }

                        for (MethodDeclaration method : t.getMethods()) {
                            this.checkMapping(method.getAnnotations(), issues);
                            Optional<AnnotationExpr> mapping = this.findMapping(method.getAnnotations());
                            if (mapping.isPresent()) {
                                if (!rest && !hasAnnotation(method, "ResponseBody")) {
                                    issues.add("MVC视图响应不作为JSON接口解析：" + method.getNameAsString());
                                } else if (!hasAnnotation(t, "RequestMapping") || !this.paths(t.getAnnotations(), issues).isEmpty()) {
                                    List<String> suffixes = this.paths(method.getAnnotations(), issues);
                                    if (!suffixes.isEmpty()) {
                                        List<String> verbs = this.verbs(mapping.get(), issues);

                                        for (String prefix : prefixes) {
                                            for (String suffix : suffixes) {
                                                for (String verb : verbs) {
                                                    String path = (prefix + "/" + suffix).replaceAll("/+", "/");
                                                    if (path.length() > 1 && path.endsWith("/")) {
                                                        path = path.substring(0, path.length() - 1);
                                                    }

                                                    ObjectNode item = paths.has(path) ? (ObjectNode)paths.get(path) : paths.putObject(path);
                                                    if (item.has(verb)) {
                                                        issues.add("重复接口映射：" + verb + " " + path);
                                                    } else {
                                                        ObjectNode op = item.putObject(verb);
                                                        op.put("operationId", t.getNameAsString() + "_" + method.getNameAsString() + "_" + operations++);
                                                        op.put(
                                                            "summary",
                                                            method.getJavadoc().isPresent()
                                                                ? ((Javadoc)method.getJavadoc().get()).getDescription().toText()
                                                                : method.getNameAsString()
                                                        );
                                                        op.putArray("tags").add(t.getNameAsString());
                                                        ObjectNode xSource = op.putObject("x-source");
                                                        xSource.put("file", source.get(t));
                                                        xSource.put("class", t.getFullyQualifiedName().orElse(t.getNameAsString()));
                                                        xSource.put("method", method.getNameAsString());
                                                        xSource.put("line", method.getBegin().isPresent() ? ((Position)method.getBegin().get()).line : 0);
                                                        ObjectNode authorization = op.putObject("x-authorization");

                                                        for (NodeWithAnnotations<?> node : Arrays.asList(t, method)) {
                                                            for (AnnotationExpr ann : node.getAnnotations()) {
                                                                if (Arrays.asList(
                                                                        "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "PermitAll", "DenyAll"
                                                                    )
                                                                    .contains(ann.getName().getIdentifier())) {
                                                                    authorization.put(
                                                                        (node == t ? "class:" : "method:") + ann.getName().getIdentifier(), ann.toString()
                                                                    );
                                                                    issues.add("权限声明已记录，实际启用与执行语义需复核：" + path);
                                                                }
                                                            }
                                                        }

                                                        if (authorization.isEmpty()) {
                                                            op.remove("x-authorization");
                                                        }

                                                        ArrayNode params = op.putArray("parameters");
                                                        int bodies = 0;

                                                        for (Parameter p : method.getParameters()) {
                                                            NodeList<AnnotationExpr> pa = p.getAnnotations();
                                                            String bind = "";

                                                            for (AnnotationExpr annx : pa) {
                                                                String n = annx.getName().getIdentifier();
                                                                if (BINDINGS.contains(n)) {
                                                                    bind = n;
                                                                    break;
                                                                }
                                                            }

                                                            if (bind.equals("RequestBody")) {
                                                                if (++bodies > 1) {
                                                                    issues.add("多个RequestBody：" + path);
                                                                }

                                                                ObjectNode body = op.putObject("requestBody");
                                                                body.put("required", !"false".equals(SchemaResolver.annotationValue(pa, bind, "required")));
                                                                body.putObject("content")
                                                                    .putObject("application/json")
                                                                    .set(
                                                                        "schema",
                                                                        resolver.resolve(p.getType(), Collections.emptyMap(), new HashSet<>(), "request")
                                                                    );
                                                            } else if (SIMPLE_BINDINGS.contains(bind)) {
                                                                String name = SchemaResolver.annotationValue(pa, bind, "name");
                                                                if (name == null) {
                                                                    name = SchemaResolver.annotationValue(pa, bind, "value");
                                                                }

                                                                if (Util.isBlank(name)) {
                                                                    name = p.getNameAsString();
                                                                }

                                                                ObjectNode param = params.addObject();
                                                                param.put("name", name);
                                                                param.put("in", this.bindingLocation(bind));
                                                                String def = SchemaResolver.annotationValue(pa, bind, "defaultValue");
                                                                param.put(
                                                                    "required",
                                                                    bind.equals("PathVariable")
                                                                        || !"false".equals(SchemaResolver.annotationValue(pa, bind, "required")) && def == null
                                                                );
                                                                ObjectNode schema = resolver.resolve(
                                                                    p.getType(), Collections.emptyMap(), new HashSet<>(), "request"
                                                                );
                                                                resolver.constraints(pa, schema);
                                                                if (pa.stream()
                                                                        .anyMatch(
                                                                            x -> Arrays.asList(
                                                                                    "Min",
                                                                                    "Max",
                                                                                    "Size",
                                                                                    "Positive",
                                                                                    "PositiveOrZero",
                                                                                    "Negative",
                                                                                    "NegativeOrZero",
                                                                                    "Digits",
                                                                                    "NotNull",
                                                                                    "NotBlank",
                                                                                    "NotEmpty"
                                                                                )
                                                                                .contains(x.getName().getIdentifier())
                                                                        )
                                                                    && !hasAnnotation(t, "Validated")) {
                                                                    issues.add("Controller参数校验是否启用需复核：" + path);
                                                                }

                                                                if (def != null) {
                                                                    typedDefault(schema, def, issues, path + " " + name);
                                                                }

                                                                param.set("schema", schema);
                                                            } else if (!bind.equals("ModelAttribute")) {
                                                                if (CONTEXT_TYPES.contains(p.getType().asString())) {
                                                                    issues.add("请求含上下文参数，权限语义需复核：" + path);
                                                                } else {
                                                                    issues.add("未支持的参数绑定（请使用显式RequestBody/RequestParam等）：" + path + " " + p.getNameAsString());
                                                                }
                                                            } else {
                                                                ObjectNode query = resolver.resolve(
                                                                    p.getType(), Collections.emptyMap(), new HashSet<>(), "request"
                                                                );
                                                                Iterator<Entry<String, JsonNode>> fields = query.path("properties").fields();

                                                                while (fields.hasNext()) {
                                                                    Entry<String, JsonNode> field = fields.next();
                                                                    ObjectNode paramx = params.addObject();
                                                                    paramx.put("name", field.getKey()).put("in", "query");
                                                                    boolean required = false;

                                                                    for (JsonNode r : query.path("required")) {
                                                                        if (r.asText().equals(field.getKey())) {
                                                                            required = true;
                                                                        }
                                                                    }

                                                                    paramx.put("required", required && !field.getValue().has("default"))
                                                                        .set("schema", field.getValue());
                                                                    if (field.getValue().path("type").asText().equals("array")) {
                                                                        paramx.put("style", "form").put("explode", true);
                                                                    }
                                                                }

                                                                if (!query.path("type").asText().equals("object")) {
                                                                    issues.add("ModelAttribute 非静态对象：" + path);
                                                                }
                                                            }
                                                        }

                                                        if (params.isEmpty()) {
                                                            op.remove("parameters");
                                                        }

                                                        String declared = declaredStatus(method.getAnnotations());
                                                        if (declared == null) declared = declaredStatus(t.getAnnotations());
                                                        String status = declared == null ? "200" : this.status(declared, issues);
                                                        if (SchemaResolver.annotationValue(method.getAnnotations(), "ResponseStatus", "reason") != null || SchemaResolver.annotationValue(t.getAnnotations(), "ResponseStatus", "reason") != null) {
                                                            issues.add("ResponseStatus reason 可能触发容器错误响应，需运行验收：" + path);
                                                        }

                                                        ObjectNode response = op.putObject("responses").putObject(status);
                                                        response.put("description", "声明的响应结构；业务错误需补充核对");
                                                        if (!method.getType().isVoidType() && !status.equals("204")) {
                                                            response.putObject("content")
                                                                .putObject("application/json")
                                                                .set(
                                                                    "schema",
                                                                    resolver.resolve(method.getType(), Collections.emptyMap(), new HashSet<>(), "response")
                                                                );
                                                        }

                                                        String produces = SchemaResolver.annotationValue(
                                                            method.getAnnotations(), mapping.get().getName().getIdentifier(), "produces"
                                                        );
                                                        if (produces != null && !produces.equals("application/json")) {
                                                            issues.add("自定义媒体类型需复核：" + path + " " + produces);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (operations == 0) {
                    issues.add("没有解析到标准Spring MVC JSON接口，不能作为检查通过依据");
                }

                ObjectNode coverage = doc.putObject("x-coverage");
                coverage.put("complete", issues.isEmpty());
                coverage.put("javaFiles", javaCount);
                coverage.put("operations", operations);
                ArrayNode arr = coverage.putArray("issues");

                for (String issue : issues) {
                    arr.add(issue);
                }

                return new ContractService().prepare(doc);
            }
        }
    }

    private JsonNode verifiedGenerated(Map<String, byte[]> files) {
        byte[] manifestBytes = files.get("generation-manifest.json");
        if (manifestBytes != null && System.getProperty("tool.template-dir") == null) {
            try {
                JsonNode manifest = Util.parse(manifestBytes);
                JsonNode hashes = manifest.path("files");
                if (manifest.path("templateVersion").asText().equals("bank-crud-java8-sb2718-rebuild-v19") && hashes.isObject()) {
                    for (Entry<String, byte[]> file : files.entrySet()) {
                        String path = file.getKey();
                        if (!path.equals("generation-manifest.json") && (!hashes.has(path) || !Util.sha(file.getValue()).equals(hashes.path(path).asText()))) {
                            return null;
                        }
                    }

                    Iterator<String> names = hashes.fieldNames();

                    while (names.hasNext()) {
                        if (!files.containsKey(names.next())) {
                            return null;
                        }
                    }

                    Spec spec = SpecParser.read(files.get("generation-spec.json"));
                    if (!SchemaConstraints.equal(Util.JSON.valueToTree(spec), manifest.path("spec"))) {
                        return null;
                    } else {
                        Map<String, byte[]> reproduced = new GeneratorService().generate(spec);

                        for (Entry<String, byte[]> filex : reproduced.entrySet()) {
                            if (!filex.getKey().equals("generation-manifest.json") && !Arrays.equals(filex.getValue(), files.get(filex.getKey()))) {
                                return null;
                            }
                        }

                        ObjectNode doc = GeneratedContract.create(spec);
                        if (!SchemaConstraints.equal(doc, Util.parse(files.get("openapi.json")))) {
                            return null;
                        } else {
                            ObjectNode coverage = doc.putObject("x-coverage");
                            coverage.put("complete", true);
                            coverage.putArray("issues");
                            coverage.put("mode", "verified-generated-contract");
                            doc.put(
                                "x-source-verification",
                                "All manifest files and regenerated contract match; edits/additions require AST scan. Host/runtime behavior still requires acceptance."
                            );
                            return doc;
                        }
                    }
                } else {
                    return null;
                }
            } catch (RuntimeException var10) {
                return null;
            }
        } else {
            return null;
        }
    }

    private static boolean hasAnnotation(NodeWithAnnotations<?> node, String name) {
        for (AnnotationExpr a : node.getAnnotations()) {
            if (a.getName().getIdentifier().equals(name)) {
                return true;
            }
        }

        return false;
    }

    private Optional<AnnotationExpr> findMapping(NodeList<AnnotationExpr> annotations) {
        for (AnnotationExpr a : annotations) {
            if (isMapping(a.getName().getIdentifier())) {
                return Optional.of(a);
            }
        }

        return Optional.empty();
    }

    private void checkMapping(NodeList<AnnotationExpr> annotations, Set<String> issues) {
        for (AnnotationExpr a : annotations) {
            if (isMapping(a.getName().getIdentifier()) && a.isNormalAnnotationExpr()) {
                for (MemberValuePair pair : a.asNormalAnnotationExpr().getPairs()) {
                    if (MAPPING_CONDITIONS.contains(pair.getNameAsString())) {
                        issues.add("映射条件或媒体类型需人工复核：" + a);
                    }
                }
            }
        }
    }

    private static boolean isMapping(String n) {
        return MAPPINGS.contains(n);
    }

    private List<String> paths(NodeList<AnnotationExpr> annotations, Set<String> issues) {
        Optional<AnnotationExpr> a = this.findMapping(annotations);
        if (!a.isPresent()) {
            return Collections.singletonList("");
        } else {
            Expression e = null;
            if (a.get().isSingleMemberAnnotationExpr()) {
                e = a.get().asSingleMemberAnnotationExpr().getMemberValue();
            } else if (a.get().isNormalAnnotationExpr()) {
                for (MemberValuePair p : a.get().asNormalAnnotationExpr().getPairs()) {
                    if (p.getNameAsString().equals("value") || p.getNameAsString().equals("path")) {
                        e = p.getValue();
                    }
                }
            }

            if (e == null) {
                return Collections.singletonList("");
            } else {
                NodeList<Expression> list = e.isArrayInitializerExpr() ? e.asArrayInitializerExpr().getValues() : new NodeList(new Expression[]{e});
                List<String> result = new ArrayList<>();

                for (Expression v : list) {
                    if (v.isStringLiteralExpr() && !v.asStringLiteralExpr().asString().contains("${")) {
                        result.add(v.asStringLiteralExpr().asString());
                    } else {
                        issues.add("动态路径未解析：" + v);
                    }
                }

                return result;
            }
        }
    }

    private List<String> verbs(AnnotationExpr a, Set<String> issues) {
        String n = a.getName().getIdentifier();
        if (!n.equals("RequestMapping")) {
            return Collections.singletonList(n.substring(0, n.length() - 7).toLowerCase(Locale.ROOT));
        } else {
            String v = SchemaResolver.annotationValue(new NodeList(new AnnotationExpr[]{a}), n, "method");
            if (v == null) {
                issues.add("RequestMapping未限定HTTP方法，需人工核对");
                return new ArrayList<>(HTTP_METHODS);
            } else {
                List<String> result = new ArrayList<>();

                for (String x : v.replace("{", "").replace("}", "").split(",")) {
                    String verb = x.trim().replace("RequestMethod.", "").toLowerCase(Locale.ROOT);
                    if (HTTP_METHODS.contains(verb)) {
                        result.add(verb);
                    } else {
                        issues.add("未知HTTP方法：" + x);
                    }
                }

                return result;
            }
        }
    }

    private String bindingLocation(String bind) {
        if (bind.equals("PathVariable")) {
            return "path";
        } else if (bind.equals("RequestHeader")) {
            return "header";
        } else {
            return bind.equals("CookieValue") ? "cookie" : "query";
        }
    }

    private static String declaredStatus(NodeList<AnnotationExpr> annotations) {
        String value = SchemaResolver.annotationValue(annotations, "ResponseStatus", "code");
        return value == null ? SchemaResolver.annotationValue(annotations, "ResponseStatus", "value") : value;
    }

    private static void typedDefault(ObjectNode schema, String value, Set<String> issues, String location) {
        try {
            if (value.contains("${") || value.contains("#{")) throw new IllegalArgumentException();
            String type = schema.path("type").asText();
            if (type.equals("string")) schema.put("default", value);
            else if (type.equals("integer")) {
                java.math.BigInteger number = new java.math.BigInteger(value.trim());
                if (schema.path("format").asText().equals("int32") && number.bitLength() > 31 || schema.path("format").asText().equals("int64") && number.bitLength() > 63) throw new IllegalArgumentException();
                schema.put("default", number);
            } else if (type.equals("number")) schema.put("default", new java.math.BigDecimal(value.trim()));
            else if (type.equals("boolean") && (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))) schema.put("default", Boolean.parseBoolean(value));
            else throw new IllegalArgumentException();
        } catch (RuntimeException error) {
            issues.add("参数默认值无法按声明类型静态解析，未写入有效默认值：" + location);
            schema.remove("default");
        }
    }

    private String status(String text, Set<String> issues) {
        try {
            String name = text.trim();
            int separator = name.lastIndexOf('.');
            return String.valueOf(org.springframework.http.HttpStatus.valueOf(name.substring(separator + 1)).value());
        } catch (IllegalArgumentException unknown) {
            issues.add("响应状态未识别，使用 default 占位而不猜测 200：" + text);
            return "default";
        }
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
