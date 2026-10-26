package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map.Entry;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

public final class GeneratorService {
    public static final String TOOL_VERSION = "2.1.1-rebuild.8-java8";
    public static final String TEMPLATE_VERSION = "bank-crud-java8-sb2718-rebuild-v19";
    private static final String TARGET_JAVA = "8";
    private static final String TARGET_BOOT = "2.7.18";
    private static final String TARGET_MYBATIS = "2.3.2";
    private static final String VALIDATION_PACKAGE = "javax.validation";
    private final Configuration configuration;
    private final Map<String, String> templateHashes = new TreeMap<>();

    public GeneratorService() {
        this.configuration = new Configuration(Configuration.VERSION_2_3_34);
        this.configuration.setClassLoaderForTemplateLoading(this.getClass().getClassLoader(), "templates");
        this.configuration.setDefaultEncoding("UTF-8");
        this.configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        this.configuration.setLogTemplateExceptions(false);
        this.configuration.setNumberFormat("computer");
        this.configuration.setInterpolationSyntax(21);
        String directory = System.getProperty("tool.template-dir");
        if (directory != null) {
            try {
                this.configuration.setDirectoryForTemplateLoading(new File(directory));
            } catch (IOException var3) {
                throw new IllegalArgumentException("模板目录不存在", var3);
            }
        }
    }

    public synchronized Map<String, byte[]> generate(Spec spec) {
        ResolvedSpec resolved = ResolvedSpec.resolve(spec);
        this.templateHashes.clear();
        Map<String, Object> model = this.model(resolved);
        Map<String, byte[]> files = new LinkedHashMap<>();
        boolean existing = EngineeringPolicy.existing(spec);
        String javaRoot = "src/main/java/" + spec.packageName().replace('.', '/') + "/";
        if (!existing) {
            this.render(files, "pom.xml", "pom.xml.ftl", model);
            this.render(files, javaRoot + "Application.java", "Application.java.ftl", model);

            for (String common : Arrays.asList("Result", "PageResult", "BusinessException", "ApiErrors", "ValidationIssue", "RequestTraceFilter")) {
                this.render(files, javaRoot + "common/" + common + ".java", common + ".java.ftl", model);
            }
        } else if (!EngineeringPolicy.externalPage(spec) && spec.operations().contains("PAGE")) {
            this.render(files, javaRoot + "common/PageResult.java", "PageResult.java.ftl", model);
        }

        if (spec.operations().contains("PAGE")) {
            this.render(files, javaRoot + "common/PaginationLimitException.java", "PaginationLimitException.java.ftl", model);
        }

        if (spec.operations().contains("UPDATE")) {
            this.render(files, javaRoot + "common/RequestMismatchException.java", "RequestMismatchException.java.ftl", model);
            if (InterfacePolicy.partial(spec)) {
                for (String name : Arrays.asList("UpdateCheck", "UpdateCheckValidator")) {
                    this.render(files, javaRoot + "dto/" + spec.entityName() + name + ".java", name + ".java.ftl", model);
                }
            }
        }

        if (Boolean.TRUE.equals(model.get("hasRange")) && spec.operations().contains("PAGE")) {
            for (String name : Arrays.asList("QueryCheck", "QueryCheckValidator")) {
                this.render(files, javaRoot + "dto/" + spec.entityName() + name + ".java", name + ".java.ftl", model);
            }
        }

        if (ProductionPolicy.accessGuard(spec)) {
            this.render(files, javaRoot + "common/" + spec.entityName() + "AccessIdentity.java", "AccessIdentity.java.ftl", model);
        }

        if (ProductionPolicy.rowLock(spec)) {
            this.render(files, javaRoot + "integration/" + spec.entityName() + "Snapshots.java", "Snapshots.java.ftl", model);
        }

        if (ProductionPolicy.audit(spec)) {
            for (String name : Arrays.asList("AuditEvent", "AuditWriter")) {
                this.render(files, javaRoot + "integration/" + spec.entityName() + name + ".java", name + ".java.ftl", model);
            }

            if (!existing) {
                this.render(files, javaRoot + "integration/" + spec.entityName() + "JdbcAuditWriter.java", "JdbcAuditWriter.java.ftl", model);
            }
        }

        if (Boolean.TRUE.equals(model.get("hasContains"))) {
            this.render(files, javaRoot + "common/LikePattern.java", "LikePattern.java.ftl", model);
        }

        for (String operation : Arrays.asList("CREATE", "UPDATE", "PAGE")) {
            if (spec.operations().contains(operation)) {
                String dto = operation.equals("CREATE") ? "CreateDTO" : (operation.equals("UPDATE") ? "UpdateDTO" : "QueryDTO");
                this.render(files, javaRoot + "dto/" + spec.entityName() + dto + ".java", dto + ".java.ftl", model);
            }
        }

        for (String operationx : Arrays.asList("CREATE", "UPDATE", "PAGE")) {
            if (spec.operations().contains(operationx)) {
                Map<String, Object> api = (Map<String, Object>)model.get(operationx.toLowerCase(Locale.ROOT) + "Api");
                if (Boolean.TRUE.equals(api.get("wrapped"))) {
                    Map<String, Object> wrapperModel = new HashMap<>(model);
                    wrapperModel.put("api", api);
                    wrapperModel.put("dtoName", operationx.equals("CREATE") ? "CreateDTO" : (operationx.equals("UPDATE") ? "UpdateDTO" : "QueryDTO"));
                    String suffix = operationx.equals("CREATE") ? "CreateRequest" : (operationx.equals("UPDATE") ? "UpdateRequest" : "PageRequest");
                    wrapperModel.put("wrapperName", suffix);
                    this.render(files, javaRoot + "dto/" + spec.entityName() + suffix + ".java", "BodyRequest.java.ftl", wrapperModel);
                }
            }
        }

        if (Boolean.TRUE.equals(model.get("needsIdDTO"))) {
            this.render(files, javaRoot + "dto/" + spec.entityName() + "IdDTO.java", "IdDTO.java.ftl", model);
        }

        if (ProductionPolicy.deleteVersion(spec) && resolved.endpoints.get("deleteApi").get("idIn").equals("BODY")) {
            this.render(files, javaRoot + "dto/" + spec.entityName() + "DeleteDTO.java", "DeleteDTO.java.ftl", model);
        }

        if (ProductionPolicy.accessGuard(spec)) {
            this.render(files, javaRoot + "integration/" + spec.entityName() + "AccessGuard.java", "AccessGuard.java.ftl", model);
            this.render(files, javaRoot + "common/" + spec.entityName() + "AccessException.java", "AccessException.java.ftl", model);
        }

        this.render(files, javaRoot + "entity/" + spec.entityName() + ".java", "Entity.java.ftl", model);
        if (spec.operations().contains("DETAIL") || spec.operations().contains("PAGE")) {
            this.render(files, javaRoot + "vo/" + spec.entityName() + "VO.java", "VO.java.ftl", model);
        }

        this.render(files, javaRoot + "controller/" + spec.entityName() + "Controller.java", "Controller.java.ftl", model);
        this.render(files, javaRoot + "service/" + spec.entityName() + "Service.java", "Service.java.ftl", model);
        if (Boolean.TRUE.equals(model.get("hasAssembler"))) {
            this.render(files, javaRoot + "assembler/" + spec.entityName() + "Assembler.java", "Assembler.java.ftl", model);
            this.render(files, "src/test/java/" + spec.packageName().replace('.', '/') + "/AssemblerTest.java", "AssemblerTest.java.ftl", model);
        }
        this.render(files, javaRoot + "service/impl/" + spec.entityName() + "ServiceImpl.java", "ServiceImpl.java.ftl", model);
        this.render(files, javaRoot + "mapper/" + spec.entityName() + "Mapper.java", "Mapper.java.ftl", model);
        this.render(files, "src/main/resources/mapper/" + spec.entityName() + "Mapper.xml", "Mapper.xml.ftl", model);
        if (!existing) {
            this.render(files, "src/main/resources/application.properties", "application.properties.ftl", model);
            this.render(files, "src/main/resources/application-prod.properties", "production.properties.ftl", model);
            this.render(files, "sql/schema.sql", "schema.sql.ftl", model);
            this.render(files, "src/test/resources/application-test.properties", "test.properties.ftl", model);
            this.render(files, "src/test/resources/schema-test.sql", "schema-test.sql.ftl", model);
            files.put(
                "src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker",
                Util.bytes("org.mockito.internal.creation.bytebuddy.ByteBuddyMockMaker\n")
            );
            this.render(files, "src/test/java/" + spec.packageName().replace('.', '/') + "/CrudIntegrationTest.java", "CrudIntegrationTest.java.ftl", model);
        }

        if (spec.operations().contains("UPDATE")) {
            this.render(files, "src/test/java/" + spec.packageName().replace('.', '/') + "/ServiceUpdateTest.java", "ServiceUpdateTest.java.ftl", model);
        }

        if (!existing) {
            this.render(files, "README.md", "README.md.ftl", model);
            this.render(files, "production-runbook.md", "production-runbook.md.ftl", model);
            this.render(files, "deployment/preflight.sh", "preflight.sh.ftl", model);
            this.render(files, "src/test/java/" + spec.packageName().replace('.', '/') + "/RequestTraceFilterTest.java", "RequestTraceFilterTest.java.ftl", model);
            this.render(files, "src/test/java/" + spec.packageName().replace('.', '/') + "/MysqlAcceptanceTest.java", "MysqlAcceptanceTest.java.ftl", model);
            this.render(files, "src/test/java/" + spec.packageName().replace('.', '/') + "/MysqlCrudAcceptanceTest.java", "MysqlCrudAcceptanceTest.java.ftl", model);
            files.put(
                ".editorconfig",
                Util.bytes(
                    "root = true\n\n[*]\ncharset = utf-8\nend_of_line = lf\nindent_style = space\nindent_size = 4\ninsert_final_newline = true\ntrim_trailing_whitespace = true\n"
                )
            );
            files.put(
                "build-offline.sh",
                Util.bytes(
                    "#!/usr/bin/env bash\nset -euo pipefail\ncd -- \"$(dirname -- \"$0\")\"\n: \"${OFFLINE_MAVEN_REPO:?Set OFFLINE_MAVEN_REPO to a complete approved local repository}\"\nmvn -o \"-Dmaven.repo.local=$OFFLINE_MAVEN_REPO\" verify\n"
                )
            );
            files.put(
                "build-offline.cmd",
                Util.bytes(
                    "@echo off\r\nsetlocal\r\ncd /d \"%~dp0\"\r\nif not defined OFFLINE_MAVEN_REPO (\r\n  echo Set OFFLINE_MAVEN_REPO to a complete approved local repository.\r\n  exit /b 1\r\n)\r\ncall mvn -o \"-Dmaven.repo.local=%OFFLINE_MAVEN_REPO%\" verify\r\nexit /b %ERRORLEVEL%\r\n"
                )
            );
        } else {
            this.render(files, "README.md", "EngineeringREADME.md.ftl", model);
            this.render(files, "integration/application.properties.sample", "EngineeringProperties.ftl", model);
            this.render(files, "integration/reference-schema.sql", "schema.sql.ftl", model);
            files.put("integration-config.json", Util.bytes(Util.pretty(EngineeringPolicy.resolve(spec))));
            files.put("integration/verification-checklist.json", Util.bytes(Util.pretty(EngineeringPolicy.checklist(spec))));
        }

        if (resolved.stringNumbers) {
            this.render(files, javaRoot + "common/NumericWire.java", "NumericWire.java.ftl", model);
        }

        if (resolved.strictJson && ProductionPolicy.jsonScope(spec).equals("MODULE")) {
            this.render(files, javaRoot + "common/" + spec.entityName() + "StrictJsonConfig.java", "StrictJsonConfig.java.ftl", model);
        }

        if (spec.fields().stream().anyMatch(f -> f.javaType().equals("String"))) {
            this.render(files, javaRoot + "common/CodePointSize.java", "CodePointSize.java.ftl", model);
            this.render(files, javaRoot + "common/CodePointSizeValidator.java", "CodePointSizeValidator.java.ftl", model);
        }

        if (spec.fields().stream().anyMatch(f -> f.javaType().equals("LocalDate"))) {
            this.render(files, javaRoot + "common/DatabaseDateRange.java", "DatabaseDateRange.java.ftl", model);
            this.render(files, javaRoot + "common/DatabaseDateRangeValidator.java", "DatabaseDateRangeValidator.java.ftl", model);
        }

        if (spec.fields().stream().anyMatch(f -> f.javaType().equals("LocalDateTime"))) {
            this.render(files, javaRoot + "common/TemporalPrecision.java", "TemporalPrecision.java.ftl", model);
            this.render(files, javaRoot + "common/TemporalPrecisionValidator.java", "TemporalPrecisionValidator.java.ftl", model);
        }

        if (Boolean.TRUE.equals(model.get("hasWriteContext"))) {
            this.render(files, javaRoot + "integration/" + spec.entityName() + "WriteContext.java", "WriteContext.java.ftl", model);
        }

        if (ProductionPolicy.hooks(spec)) {
            this.render(files, javaRoot + "integration/" + spec.entityName() + "Outbox.java", "Outbox.java.ftl", model);
            this.render(files, javaRoot + "integration/" + spec.entityName() + "OutboxDispatcher.java", "OutboxDispatcher.java.ftl", model);
            this.render(files, "integration/outbox-schema.sql", "outbox-schema.sql.ftl", model);
            this.render(files, "integration/outbox-delivery-guide.md", "outbox-delivery-guide.md.ftl", model);
            if (!existing) this.render(files, "src/test/java/" + spec.packageName().replace('.', '/') + "/OutboxDeliveryTest.java", "OutboxDeliveryTest.java.ftl", model);
            this.render(files, javaRoot + "integration/" + spec.entityName() + "Hooks.java", "Hooks.java.ftl", model);
            this.render(files, javaRoot + "integration/" + spec.entityName() + "OperationContext.java", "OperationContext.java.ftl", model);
        }

        files.put("openapi.json", Util.bytes(Util.pretty(GeneratedContract.create(resolved))));
        JsonNode referenceDocument = Util.parse(files.get("openapi.json"));
        files.put("interface-reference.md", ContractReports.markdown(spec.description(), referenceDocument));
        files.put("interface-reference.html", ContractReports.html(spec.description(), referenceDocument));
        files.put("acceptance-cases.json", Util.bytes(Util.pretty(AcceptancePlanning.cases(spec))));
        files.put("acceptance-checklist.md", Util.bytes(AcceptancePlanning.guide(spec)));
        if (!existing && ProductionPolicy.accessGuard(spec)) {
            this.render(
                files, "src/test/java/" + spec.packageName().replace('.', '/') + "/SecurityIntegrationTest.java", "SecurityIntegrationTest.java.ftl", model
            );
        }

        this.render(files, "frontend/api.js", "frontend-api.js.ftl", model);
        this.render(files, "requests.http", "requests.http.ftl", model);
        files.put("request-examples.json", Util.bytes(Util.pretty(RequestExamples.bodies(resolved))));
        JsonNode production = new ProductionReadiness().inspect(spec);
        files.put("production-readiness.json", Util.bytes(Util.pretty(production)));
        files.put("production-readiness.html", Reports.html("生成工程生产检查", production));
        files.put("interface-config.json", Util.bytes(Util.pretty(resolved.endpoints)));
        files.put("generation-spec.json", Util.bytes(Util.pretty(spec)));
        files.put("production-evidence-template.json", Util.bytes(Util.pretty(ProductionArtifacts.evidenceTemplate(spec, files))));
        Map<String, String> hashes = new LinkedHashMap<>();

        for (Entry<String, byte[]> entry : files.entrySet()) {
            hashes.put(entry.getKey(), Util.sha(entry.getValue()));
        }

        files.put(
            "generation-manifest.json",
            Util.bytes(
                Util.pretty(
                    Util.mapOf(
                        "version",
                        "2.1.1-rebuild.8-java8",
                        "templateVersion",
                        "bank-crud-java8-sb2718-rebuild-v19",
                        "targetJava",
                        "8",
                        "targetBoot",
                        "2.7.18",
                        "schemaVersion",
                        ProductionPolicy.version(spec),
                        "templateHashes",
                        this.templateHashes,
                        "formatProfile",
                        "java-4spaces-utf8-lf-ast-v3",
                        "files",
                        hashes,
                        "spec",
                        spec,
                        "updateMode",
                        "candidate-only"
                    )
                )
            )
        );
        return files;
    }

    private Map<String, Object> model(ResolvedSpec resolved) {
        Spec spec = resolved.spec;
        Map<String, Object> model = new HashMap<>();
        model.putAll(EngineeringPolicy.model(spec));
        model.put("package", spec.packageName());
        model.put("artifact", spec.artifactId());
        model.put("entity", spec.entityName());
        model.put("table", spec.tableName());
        String outboxBase = spec.tableName().length() <= 50 ? spec.tableName() : spec.tableName().substring(0, 50);
        model.put("outboxTable", outboxBase + "_" + Util.sha(Util.bytes(spec.tableName())).substring(0, 6) + "_outbox");
        model.put("description", Util.html(Util.comment(spec.description())));
        model.put("basePath", spec.basePath());
        Map<String, Map<String, Object>> api = resolved.endpoints;
        model.putAll(api);
        model.put("apis", new ArrayList<>(api.values()));
        model.put("partialUpdate", InterfacePolicy.partial(spec));
        model.put("hasAssembler", spec.operations().contains("CREATE")
            || spec.operations().contains("DETAIL") || spec.operations().contains("PAGE")
            || spec.operations().contains("UPDATE") && !InterfacePolicy.partial(spec));
        model.put(
            "needsIdDTO",
            api.values().stream().anyMatch(e -> e.get("idIn").equals("BODY") && (e.get("operation").equals("DETAIL") || e.get("operation").equals("DELETE")))
        );
        model.put(
            "updateBodyId",
            api.containsKey("updateApi") && api.get("updateApi").get("idIn").equals("BODY") && !Boolean.TRUE.equals(api.get("updateApi").get("wrapped"))
        );
        model.put("targetJava", "8");
        model.put("validationPackage", "javax.validation");
        model.put("targetBoot", "2.7.18");
        model.put("targetMybatis", "2.3.2");
        model.put("modern", false);
        model.put("templateVersion", "bank-crud-java8-sb2718-rebuild-v19");

        for (String operation : Arrays.asList("CREATE", "UPDATE", "DETAIL", "PAGE", "DELETE")) {
            model.put(operation.toLowerCase(Locale.ROOT), spec.operations().contains(operation));
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        List<Map<String, Object>> editable = new ArrayList<>();
        List<Map<String, Object>> exposed = new ArrayList<>();
        List<Map<String, Object>> query = new ArrayList<>();
        List<Map<String, Object>> ranges = new ArrayList<>();
        List<Map<String, Object>> contains = new ArrayList<>();

        for (ResolvedSpec.FieldRule rule : resolved.fields) {
            Spec.Field field = rule.field;
            Map<String, Object> value = new HashMap<>();
            value.put("source", rule.source);
            value.put("serverUpdate", field.isServerUpdate());
            value.put("defaultSql", rule.defaultSql);
            value.put("stringNumber", rule.stringNumber);
            value.put("name", field.name());
            value.put("cap", Character.toUpperCase(field.name().charAt(0)) + field.name().substring(1));
            value.put("column", field.column());
            value.put("type", field.javaType());
            value.put("description", Util.html(Util.comment(field.description())));
            value.put("required", field.required());
            value.put("editable", field.editable());
            value.put("exposed", field.exposed());
            value.put("query", field.query());
            value.put("length", field.maxLength() == null ? 0 : field.maxLength());
            value.put("precision", field.precision() == null ? 0 : field.precision());
            value.put("scale", field.scale() == null ? 0 : field.scale());
            value.put("sqlType", this.sqlType(field));
            value.put("temporalPrecision", ProductionPolicy.temporalPrecision(field));
            value.put("jsonExplicit", field.name().length() > 1 && Character.isUpperCase(field.name().charAt(1)));
            value.put("sample", this.sample(field, false));
            value.put("updatedSample", this.sample(field, true));
            fields.add(value);
            if (field.editable()) {
                editable.add(value);
            }

            if (field.exposed()) {
                exposed.add(value);
            }

            if (!field.query().equals("NONE")) {
                query.add(value);
            }

            if (field.query().equals("RANGE")) {
                ranges.add(value);
            }

            if (field.query().equals("CONTAINS")) {
                contains.add(value);
            }

            if (field.required() && field.javaType().equals("String")) {
                model.putIfAbsent("firstRequiredString", value);
            }

            if (field.javaType().equals("String")) {
                model.putIfAbsent("firstString", value);
            }

            if (field.javaType().equals("BigDecimal")) {
                model.putIfAbsent("firstDecimal", value);
            }

            if (field.editable() && !field.required()) {
                model.putIfAbsent("firstNullableEditable", value);
            }
        }

        List<Map<String, Object>> createFields = InterfacePolicy.select(fields, api.get("createApi"));
        editable = InterfacePolicy.select(fields, api.get("updateApi"));
        query = InterfacePolicy.select(fields, api.get("pageApi"));
        ranges.clear();
        contains.clear();

        for (Map<String, Object> f : query) {
            if (f.get("query").equals("RANGE")) {
                ranges.add(f);
            }

            if (f.get("query").equals("CONTAINS")) {
                contains.add(f);
            }
        }

        for (String key : Arrays.asList("firstRequiredString", "firstString", "firstDecimal", "firstNullableEditable")) {
            model.remove(key);
        }

        for (Map<String, Object> f : createFields) {
            if (f.get("type").equals("String")) {
                model.putIfAbsent("firstString", f);
                if (Boolean.TRUE.equals(f.get("required"))) {
                    model.putIfAbsent("firstRequiredString", f);
                }
            }

            if (f.get("type").equals("BigDecimal")) {
                model.putIfAbsent("firstDecimal", f);
            }
        }

        for (Map<String, Object> f : editable) {
            if (!Boolean.TRUE.equals(f.get("nonNull")) && !Boolean.TRUE.equals(f.get("required"))) {
                model.putIfAbsent("firstNullableEditable", f);
            }
        }

        model.put("fields", fields);
        model.put("createFields", createFields);
        List<Map<String, Object>> serverFields = new ArrayList<>();
        List<Map<String, Object>> insertFields = new ArrayList<>(createFields);

        for (Map<String, Object> fx : fields) {
            if (fx.get("source").equals("SERVER")) {
                serverFields.add(fx);
                if (spec.operations().contains("CREATE")) {
                    insertFields.add(fx);
                }
            }
        }

        model.put("serverFields", serverFields);
        model.put("insertFields", insertFields);
        List<Map<String, Object>> serverUpdateFields = new ArrayList<>();

        for (Map<String, Object> fxx : serverFields) {
            if (Boolean.TRUE.equals(fxx.get("serverUpdate"))) {
                serverUpdateFields.add(fxx);
            }
        }

        model.put("serverUpdateFields", serverUpdateFields);
        model.put("hasServerUpdate", !serverUpdateFields.isEmpty() && spec.operations().contains("UPDATE"));
        model.put(
            "hasWriteContext",
            !serverFields.isEmpty() && spec.operations().contains("CREATE")
                || !serverUpdateFields.isEmpty() && spec.operations().contains("UPDATE")
                || ProductionPolicy.scope(spec) != null
        );
        model.put("stringNumbers", resolved.stringNumbers);
        model.put("strictJson", resolved.strictJson);
        model.put("requestExamplesText", RequestExamples.render(resolved));
        model.put("recordAccessCheck", ProductionPolicy.recordAccess(spec));
        model.put("transactionalAudit", ProductionPolicy.audit(spec));
        model.put("rowLock", ProductionPolicy.rowLock(spec));
        List<Map<String, Object>> auditFields = new ArrayList<>();
        if (spec.getOptions() != null && spec.getOptions().getAuditFields() != null) {
            for (Map<String, Object> fieldx : fields) {
                if (spec.getOptions().getAuditFields().contains(fieldx.get("name"))) {
                    auditFields.add(fieldx);
                }
            }
        }

        model.put("auditFields", auditFields);
        model.put("accessGuard", ProductionPolicy.accessGuard(spec));
        model.put("deleteVersionCheck", ProductionPolicy.deleteVersion(spec));
        model.put("optimisticLock", ProductionPolicy.locking(spec));
        model.put("softDelete", ProductionPolicy.softDelete(spec));
        model.put("hooks", ProductionPolicy.hooks(spec));
        Spec.Field scope = ProductionPolicy.scope(spec);
        model.put("scoped", scope != null);
        if (scope != null) {
            model.put("scopeName", scope.name());
            model.put("scopeColumn", scope.column());
            model.put("scopeType", scope.javaType());
            model.put("scopeCap", Character.toUpperCase(scope.name().charAt(0)) + scope.name().substring(1));
        }

        List<Map<String, Object>> indexes = new ArrayList<>();
        if (spec.getIndexes() != null) {
            for (Spec.Index index : spec.getIndexes()) {
                List<String> columns = new ArrayList<>();

                for (String name : index.getFields()) {
                    for (Spec.Field fxxx : spec.fields()) {
                        if (fxxx.name().equals(name)) {
                            columns.add(fxxx.column());
                        }
                    }
                }

                indexes.add(Util.mapOf("name", index.getName(), "unique", index.isUnique(), "columns", columns));
            }
        }

        model.put("indexes", indexes);
        Spec.DatabaseProfile db = ProductionPolicy.database(spec);
        model.put("dbCharset", db.getCharset());
        model.put("dbCollation", db.getCollation());
        model.put("dbRowFormat", db.getRowFormat());
        model.put("jsonScope", ProductionPolicy.jsonScope(spec));
        Set<String> uniqueSeeds = new HashSet<>();
        boolean canSeedMultiple = true;
        if (spec.getIndexes() != null) {
            for (Spec.Index index : spec.getIndexes()) {
                if (index.isUnique()) {
                    String selected = null;

                    for (String name : index.getFields()) {
                        if (scope == null || !scope.name().equals(name)) {
                            selected = name;
                            break;
                        }
                    }

                    if (selected == null) {
                        canSeedMultiple = false;
                    } else {
                        uniqueSeeds.add(selected);
                    }
                }
            }
        }

        for (Map<String, Object> fxxxx : fields) {
            String sample = fxxxx.get("sample").toString();
            String type = fxxxx.get("type").toString();
            if (uniqueSeeds.contains(fxxxx.get("name"))) {
                if (type.equals("String")) {
                    sample = "Character.toString((char)('A' + seedNumber))";
                } else if (type.equals("Long")) {
                    sample = "(1L + seedNumber)";
                } else if (type.equals("Integer")) {
                    sample = "(1 + seedNumber)";
                } else if (type.equals("Boolean")) {
                    sample = "(seedNumber % 2 == 0)";
                } else if (type.equals("BigDecimal")) {
                    sample = sample + ".multiply(BigDecimal.valueOf(seedNumber + 1L))";
                } else {
                    sample = sample + ".plusDays(seedNumber)";
                }
            }

            fxxxx.put("seedSample", sample);
        }

        model.put("canSeedMultiple", canSeedMultiple);
        model.put("editableFields", editable);
        model.put("exposedFields", exposed);
        model.put("queryFields", query);
        model.put("rangeFields", ranges);
        model.put("hasRange", !ranges.isEmpty());
        model.put("hasContains", !contains.isEmpty());
        if (!contains.isEmpty()) {
            model.put("firstContains", contains.get(0));
        }

        model.put("hasRequired", createFields.stream().anyMatch(fxxxx -> Boolean.TRUE.equals(fxxxx.get("required"))));
        model.put("createImports", this.fieldImports(createFields, true));
        Set<String> updateImports = new TreeSet<>(this.fieldImports(editable, true));
        if (InterfacePolicy.partial(spec)) {
            updateImports.add("com.fasterxml.jackson.annotation.JsonIgnore");
            updateImports.add("javax.validation.constraints.AssertTrue");
        }

        if (ProductionPolicy.locking(spec)) {
            updateImports.add("javax.validation.constraints.NotNull");
            updateImports.add("javax.validation.constraints.PositiveOrZero");
        }

        if (spec.operations().contains("UPDATE")) {
            updateImports.add("javax.validation.constraints.NotNull");
            updateImports.add("javax.validation.constraints.Positive");
        }

        model.put("updateImports", new ArrayList<>(updateImports));
        model.put("entityImports", this.fieldImports(fields, false));
        model.put("viewImports", this.fieldImports(exposed, false));
        Set<String> queryImports = new TreeSet<>(this.fieldImports(query, true));
        queryImports.add("com.fasterxml.jackson.annotation.JsonIgnore");

        for (String constraint : Arrays.asList("NotNull", "Min", "Max")) {
            queryImports.add("javax.validation.constraints." + constraint);
        }

        if (!ranges.isEmpty()) {
            queryImports.add("javax.validation.constraints.AssertTrue");
        }

        for (Map<String, Object> fieldxx : query) {
            if (fieldxx.get("type").equals("String")) {
                queryImports.add("javax.validation.constraints.Size");
            }

            if (fieldxx.get("type").equals("BigDecimal")) {
                queryImports.add("javax.validation.constraints.Digits");
            }

            if (fieldxx.get("query").equals("IN")) {
                queryImports.add("java.util.List");
                queryImports.add("javax.validation.constraints.Size");
            }
        }

        if (!contains.isEmpty()) {
            queryImports.add(spec.packageName() + ".common.LikePattern");
        }

        if (query.stream().anyMatch(fxxxx -> fxxxx.get("type").toString().startsWith("Local"))) {
            queryImports.add("org.springframework.format.annotation.DateTimeFormat");
        }

        model.put("queryImports", new ArrayList<>(queryImports));
        model.put("testTypeImports", this.typeImports(fields));
        String firstBody = "";

        for (String op : Arrays.asList("CREATE", "UPDATE", "PAGE", "DETAIL", "DELETE")) {
            Map<String, Object> endpoint = api.get(op.toLowerCase(Locale.ROOT) + "Api");
            if (endpoint != null && Boolean.TRUE.equals(endpoint.get("body"))) {
                firstBody = op;
                break;
            }
        }

        model.put("firstBody", firstBody);
        if (!firstBody.isEmpty()) {
            model.put("firstBodyApi", api.get(firstBody.toLowerCase(Locale.ROOT) + "Api"));
        }

        String firstId = spec.operations().contains("DETAIL") ? "detailApi" : (spec.operations().contains("DELETE") ? "deleteApi" : "updateApi");
        if (api.containsKey(firstId)) {
            model.put("firstIdApi", api.get(firstId));
        }

        model.put("hasId", api.containsKey("detailApi") || api.containsKey("updateApi") || api.containsKey("deleteApi"));
        Map<String, Object> unsupported = api.values().iterator().next();
        model.put("unsupportedPath", unsupported.get("url").toString().replace("{id}", "1"));
        model.put("unsupportedMethod", "PUT".equals(unsupported.get("method")) ? "PATCH" : "PUT");
        Set<String> occupied = new HashSet<>();

        for (Map<String, Object> e : api.values()) {
            if (e.get("url").toString().replace("{id}", "1").equals(model.get("unsupportedPath"))) {
                occupied.add(e.get("method").toString());
            }
        }

        for (String m : Arrays.asList("PATCH", "PUT", "DELETE", "POST", "GET")) {
            if (!occupied.contains(m)) {
                model.put("unsupportedMethod", m);
                break;
            }
        }

        return model;
    }

    private List<String> fieldImports(List<Map<String, Object>> fields, boolean validation) {
        Set<String> imports = new TreeSet<>(this.typeImports(fields));

        for (Map<String, Object> field : fields) {
            if (Boolean.TRUE.equals(field.get("jsonExplicit"))) {
                imports.add("com.fasterxml.jackson.annotation.JsonProperty");
            }

            if (validation) {
                String type = field.get("type").toString();
                if (Boolean.TRUE.equals(field.get("required"))) {
                    imports.add("javax.validation.constraints." + (type.equals("String") ? "NotBlank" : "NotNull"));
                }

                if (type.equals("String")) {
                    imports.add("javax.validation.constraints.Size");
                }

                if (type.equals("BigDecimal")) {
                    imports.add("javax.validation.constraints.Digits");
                }
            }
        }

        return new ArrayList<>(imports);
    }

    private List<String> typeImports(List<Map<String, Object>> fields) {
        Set<String> imports = new TreeSet<>();

        for (Map<String, Object> field : fields) {
            String type = field.get("type").toString();
            if (type.equals("BigDecimal")) {
                imports.add("java.math.BigDecimal");
            }

            if (type.equals("LocalDate") || type.equals("LocalDateTime")) {
                imports.add("java.time." + type);
            }
        }

        return new ArrayList<>(imports);
    }

    private void render(Map<String, byte[]> files, String path, String template, Map<String, Object> model) {
        try {
            StringWriter writer = new StringWriter();
            Template loaded = this.configuration.getTemplate(template);
            this.templateHashes.put(template, Util.sha(Util.bytes(loaded.toString())));
            if (template.endsWith(".java.ftl")) {
                this.templateHashes.put("_macros.ftl", Util.sha(Util.bytes(this.configuration.getTemplate("_macros.ftl").toString())));
            }

            if (template.equals("schema.sql.ftl") || template.equals("schema-test.sql.ftl")) {
                this.templateHashes.put("_audit.ftl", Util.sha(Util.bytes(this.configuration.getTemplate("_audit.ftl").toString())));
            }

            loaded.process(model, writer);
            String text = writer.toString()
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .replaceAll("[ \\t]+(?=\\n)", "")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim()
                    .replace("\n\n}", "\n}")
                + "\n";
            if (path.endsWith(".java")) {
                text = GeneratedJavaStyle.format(this.formatImports(text));
            } else if (path.endsWith(".xml")) {
                this.checkXml(text);
            }

            files.put(path, Util.bytes(text));
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception var8) {
            throw new IllegalStateException("模板生成失败：" + template, var8);
        }
    }

    private void checkXml(String source) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        builder.parse(new InputSource(new StringReader(source)));
    }

    private String formatImports(String source) {
        CompilationUnit unit = StaticJavaParser.parse(source);
        Set<String> names = new HashSet<>();
        unit.findAll(ClassOrInterfaceType.class).forEach(type -> names.add(type.getNameAsString()));
        unit.findAll(NameExpr.class).forEach(name -> names.add(name.getNameAsString()));
        unit.findAll(AnnotationExpr.class).forEach(annotation -> names.add(annotation.getName().getIdentifier()));
        List<String> imports = new ArrayList<>();
        Map<String, String> importedTypes = new HashMap<>();

        for (ImportDeclaration declaration : unit.getImports()) {
            if (declaration.isStatic() || declaration.isAsterisk() || names.contains(declaration.getName().getIdentifier())) {
                if (!declaration.isStatic() && !declaration.isAsterisk()) {
                    String simple = declaration.getName().getIdentifier();
                    String qualified = declaration.getNameAsString();
                    String previous = importedTypes.put(simple, qualified);
                    if (previous != null && !previous.equals(qualified)) {
                        throw new IllegalArgumentException("实体名与生成代码的基础类型重名：" + simple + "，请更换业务实体名后重新生成");
                    }
                }
                imports.add(declaration.toString().trim());
            }
        }

        imports.sort((left, right) -> {
            boolean leftStatic = left.startsWith("import static ");
            boolean rightStatic = right.startsWith("import static ");
            if (leftStatic != rightStatic) {
                return leftStatic ? 1 : -1;
            } else {
                return left.compareTo(right);
            }
        });
        String withoutImports = source.replaceAll("(?m)^import [^\\n]+;\\n", "").replaceAll("\\n{3,}", "\n\n");
        if (imports.isEmpty()) {
            return withoutImports;
        } else {
            StringBuilder block = new StringBuilder();
            boolean staticStarted = false;

            for (String value : imports) {
                if (value.startsWith("import static ") && !staticStarted) {
                    if (block.length() > 0) {
                        block.append('\n');
                    }

                    staticStarted = true;
                }

                block.append(value).append('\n');
            }

            int packageEnd = withoutImports.indexOf(59) + 1;
            return withoutImports.substring(0, packageEnd) + "\n\n" + block + "\n" + withoutImports.substring(packageEnd).trim() + "\n";
        }
    }

    private String sample(Spec.Field field, boolean updated) {
        String var3 = field.javaType();
        switch (var3) {
            case "String":
                return updated ? "\"B\"" : "\"A\"";
            case "Long":
                return updated ? "2L" : "1L";
            case "Integer":
                return updated ? "2" : "1";
            case "Boolean":
                return updated ? "false" : "true";
            case "BigDecimal":
                return field.scale() == 0
                    ? (updated ? "new BigDecimal(\"2\")" : "new BigDecimal(\"1\")")
                    : (updated ? "new BigDecimal(\"0.2\")" : "new BigDecimal(\"0.1\")");
            case "LocalDate":
                return updated ? "LocalDate.of(2026, 1, 2)" : "LocalDate.of(2026, 1, 1)";
            case "LocalDateTime":
                return updated ? "LocalDateTime.of(2026, 1, 2, 0, 0)" : "LocalDateTime.of(2026, 1, 1, 0, 0)";
            default:
                throw new IllegalArgumentException("未知字段类型");
        }
    }

    private String sqlType(Spec.Field field) {
        String var2 = field.javaType();
        switch (var2) {
            case "String":
                return "VARCHAR(" + field.maxLength() + ")";
            case "Long":
                return "BIGINT";
            case "Integer":
                return "INT";
            case "BigDecimal":
                return "DECIMAL(" + field.precision() + "," + field.scale() + ")";
            case "LocalDate":
                return "DATE";
            case "LocalDateTime":
                return "DATETIME(" + ProductionPolicy.temporalPrecision(field) + ")";
            case "Boolean":
                return "BOOLEAN";
            default:
                throw new IllegalArgumentException("未知字段类型");
        }
    }
}
