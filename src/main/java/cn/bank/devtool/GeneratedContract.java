package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GeneratedContract {
    private GeneratedContract() {
    }

    public static ObjectNode create(Spec spec) {
        return create(ResolvedSpec.resolve(spec));
    }

    public static ObjectNode create(ResolvedSpec resolved) {
        Spec spec = resolved.spec;
        Map<String, Object> integration = resolved.engineering;
        boolean existing = EngineeringPolicy.existing(spec);
        ObjectNode document = Util.JSON.createObjectNode();
        document.put("openapi", "3.0.3");
        document.putObject("info").put("title", spec.description()).put("version", "1.0.0");
        ObjectNode paths = document.putObject("paths");
        ObjectNode schemas = document.putObject("components").putObject("schemas");
        ObjectNode view = object();
        view.with("properties").set("id", idSchema(spec));
        view.putArray("required").add("id");
        if (ProductionPolicy.locking(spec)) {
            view.with("properties").set("version", versionSchema(spec));
            view.withArray("required").add("version");
        }

        for (Spec.Field f : spec.fields()) {
            if (f.exposed()) {
                view.with("properties").set(f.name(), fieldSchema(spec, f, !f.required()));
                if (f.required()) {
                    view.withArray("required").add(f.name());
                }
            }
        }

        schemas.set(spec.entityName() + "VO", view);

        for (Map<String, Object> api : resolved.endpoints.values()) {
            String op = api.get("operation").toString();
            String suffix = op.equals("CREATE")
                ? "CreateDTO"
                : (
                    op.equals("UPDATE")
                        ? "UpdateDTO"
                        : (op.equals("PAGE") ? "QueryDTO" : (op.equals("DELETE") && ProductionPolicy.deleteVersion(spec) ? "DeleteDTO" : "IdDTO"))
                );
            ObjectNode dto = object();
            ObjectNode properties = dto.with("properties");
            ArrayNode required = dto.putArray("required");
            if (op.equals("PAGE")) {
                properties.set("page", Util.JSON.valueToTree(Util.mapOf("type", "integer", "minimum", 1, "maximum", 100000, "default", 1)));
                properties.set("size", Util.JSON.valueToTree(Util.mapOf("type", "integer", "minimum", 1, "maximum", 100, "default", 20)));
            }

            List<String> chosen = (List<String>)api.get("fields");
            List<String> requiredNames = (List<String>)api.get("requiredFields");

            for (Spec.Field fx : spec.fields()) {
                if (chosen.contains(fx.name())) {
                    boolean nullable = !fx.required() && !requiredNames.contains(fx.name());
                    if (op.equals("PAGE") && fx.query().equals("RANGE")) {
                        properties.set(fx.name() + "From", fieldSchema(spec, fx, !requiredNames.contains(fx.name())));
                        properties.set(fx.name() + "To", fieldSchema(spec, fx, !requiredNames.contains(fx.name())));
                        if (requiredNames.contains(fx.name())) {
                            required.add(fx.name() + "From");
                            required.add(fx.name() + "To");
                        }
                    } else if (op.equals("PAGE") && fx.query().equals("IN")) {
                        ObjectNode list = Util.JSON
                            .createObjectNode()
                            .put("type", "array")
                            .put("minItems", 1)
                            .put("maxItems", 100)
                            .put("nullable", !requiredNames.contains(fx.name()));
                        list.set("items", fieldSchema(spec, fx, false));
                        properties.set(fx.name(), list);
                        if (requiredNames.contains(fx.name())) {
                            required.add(fx.name());
                        }
                    } else {
                        ObjectNode field = fieldSchema(spec, fx, op.equals("PAGE") ? !requiredNames.contains(fx.name()) : nullable);
                        if (fx.javaType().equals("String") && (requiredNames.contains(fx.name()) || Boolean.TRUE.equals(api.get("partial")) && fx.required())) {
                            field.put("minLength", 1).put("pattern", "\\S");
                        }

                        properties.set(fx.name(), field);
                        if (requiredNames.contains(fx.name())) {
                            required.add(fx.name());
                        }
                    }
                }
            }

            if (op.equals("UPDATE") && ProductionPolicy.locking(spec)
                || op.equals("DELETE") && ProductionPolicy.deleteVersion(spec) && api.get("idIn").equals("BODY")) {
                properties.set("expectedVersion", versionSchema(spec));
                required.add("expectedVersion");
            }

            if (op.equals("UPDATE")) {
                properties.set("id", idSchema(spec).put("description", "请求体ID可省略；提供时必须与接口ID一致"));
            }

            if (Boolean.TRUE.equals(api.get("partial"))) {
                dto.put("minProperties", ProductionPolicy.locking(spec) ? 2 : 1);
                dto.put("x-update-semantics", "Only explicitly supplied business fields are updated; empty updates rejected.");
                ArrayNode alternatives = dto.putArray("anyOf");
                for (Object businessField : chosen) {
                    alternatives.addObject().putArray("required").add(businessField.toString());
                }
            }

            if (required.isEmpty()) {
                dto.remove("required");
            }

            boolean wrapped = Boolean.TRUE.equals(api.get("wrapped"));
            if (api.get("idIn").equals("BODY") && !wrapped) {
                properties.set("id", idSchema(spec));
                dto.withArray("required").add("id");
                if (Boolean.TRUE.equals(api.get("partial"))) {
                    dto.put("minProperties", ProductionPolicy.locking(spec) ? 3 : 2);
                }
            }

            String dtoName = spec.entityName() + suffix;
            if (api.get("requestIn").equals("BODY") || api.get("idIn").equals("BODY")) {
                schemas.set(dtoName, dto);
            }

            ObjectNode wireSchema = ref(dtoName);
            if (wrapped) {
                ObjectNode envelope = object();
                String property = api.get("bodyProperty").toString();
                envelope.with("properties").set(property, ref(dtoName));
                envelope.putArray("required").add(property);
                if (api.get("idIn").equals("BODY")) {
                    envelope.with("properties").set("id", idSchema(spec));
                    envelope.withArray("required").add("id");
                }

                String envelopeName = spec.entityName() + (op.equals("PAGE") ? "Page" : (op.equals("CREATE") ? "Create" : "Update")) + "Request";
                schemas.set(envelopeName, envelope);
                wireSchema = ref(envelopeName);
            }

            ObjectNode item = paths.has(api.get("url").toString())
                ? (ObjectNode)paths.get(api.get("url").toString())
                : paths.putObject(api.get("url").toString());
            ObjectNode operation = item.putObject(api.get("method").toString().toLowerCase(Locale.ROOT));
            String action = op.equals("CREATE")
                ? "新增"
                : (
                    op.equals("UPDATE")
                        ? (Boolean.TRUE.equals(api.get("partial")) ? "部分修改" : "完整修改")
                        : (op.equals("DETAIL") ? "查询详情" : (op.equals("PAGE") ? "分页查询" : "删除"))
                );
            operation.put("operationId", op.toLowerCase(Locale.ROOT) + spec.entityName()).put("summary", spec.description() + " · " + action);
            operation.putArray("tags").add(spec.description());
            operation.put(
                "description",
                action
                    + "；业务字段位置："
                    + api.get("requestIn")
                    + "；ID位置："
                    + api.get("idIn")
                    + (Boolean.TRUE.equals(api.get("partial")) ? "。仅修改显式提供字段，未传字段保留，空更新拒绝。" : "。参数与必填要求见字段定义。")
            );
            operation.put("x-operation", op);
            if (op.equals("UPDATE")) {
                operation.put("x-id-consistency", "MUST_MATCH_IF_PRESENT");
            }

            ArrayNode parameters = operation.putArray("parameters");
            if (api.get("idIn").equals("PATH") || api.get("idIn").equals("QUERY")) {
                addParam(parameters, "id", api.get("idIn").toString().toLowerCase(Locale.ROOT), idSchema(spec), true);
            }

            if (op.equals("DELETE") && ProductionPolicy.deleteVersion(spec) && !api.get("idIn").equals("BODY")) {
                addParam(parameters, "expectedVersion", "query", versionSchema(spec), true);
            }

            if (api.get("requestIn").equals("QUERY")) {
                properties.fields()
                    .forEachRemaining(e -> addParam(parameters, (String)e.getKey(), "query", (ObjectNode)e.getValue(), contains(required, (String)e.getKey())));
            }

            if (parameters.isEmpty()) {
                operation.remove("parameters");
            }

            if (Boolean.TRUE.equals(api.get("body"))) {
                operation.putObject("requestBody").put("required", true).putObject("content").putObject("application/json").set("schema", wireSchema);
            }

            ObjectNode response = object();
            response.putArray("required").add(integration.get("dataProperty").toString());
            if (existing) {
                response.put("additionalProperties", true).put("x-host-contract-verification", "REQUIRED");
            }

            String codeProperty = integration.get("codeProperty").toString();
            String messageProperty = integration.get("messageProperty").toString();
            if (!codeProperty.isEmpty()) {
                ObjectNode codeSchema = Util.JSON.createObjectNode().put("type", integration.get("codeType").equals("STRING") ? "string" : "integer");
                codeSchema.putArray("enum").add(Util.JSON.valueToTree(EngineeringPolicy.successCode(spec)));
                response.with("properties").set(codeProperty, codeSchema);
                response.withArray("required").add(codeProperty);
            }

            if (!messageProperty.isEmpty()) {
                response.with("properties").set(messageProperty, Util.JSON.valueToTree(Util.mapOf("type", "string")));
                response.withArray("required").add(messageProperty);
            }

            ObjectNode data = Util.JSON.createObjectNode().put("type", "object").put("nullable", true);
            data.putArray("enum").addNull();
            if (op.equals("CREATE")) {
                data = idSchema(spec);
            }

            if (op.equals("DETAIL")) {
                data = ref(spec.entityName() + "VO");
            }

            if (op.equals("PAGE")) {
                data = object();
                data.putArray("required").add(integration.get("totalProperty").toString()).add(integration.get("recordsProperty").toString());
                data.with("properties").set(integration.get("totalProperty").toString(), versionSchema(spec));
                data.with("properties")
                    .putObject(integration.get("recordsProperty").toString())
                    .put("type", "array")
                    .set("items", ref(spec.entityName() + "VO"));
            }

            if (op.equals("PAGE") && EngineeringPolicy.externalPage(spec)) {
                data.put("additionalProperties", true);
            }

            response.with("properties").set(integration.get("dataProperty").toString(), data);
            operation.putObject("responses")
                .putObject("200")
                .put("description", "成功")
                .putObject("content")
                .putObject("application/json")
                .set("schema", response);
            if (!existing) operation.with("responses").putObject("406").put("description", "调用方不接受 application/json；在业务调用前拒绝，不返回响应体");
            if (existing) {
                operation.with("responses").putObject("default").put("description", "错误状态与响应结构由宿主全局异常处理器定义，须完成接入验收");
            } else {
                for (String code : ProductionPolicy.accessGuard(spec)
                    ? new String[]{"400", "401", "403", "404", "405", "409", "415", "500"}
                    : new String[]{"400", "404", "405", "409", "415", "500"}) {
                    ObjectNode error = object();
                    error.putArray("required").add("code").add("message").add("data");
                    error.with("properties")
                        .set("code", Util.JSON.valueToTree(Util.mapOf("type", "integer", "enum", Collections.singletonList(Integer.parseInt(code)))));
                    error.with("properties").putObject("message").put("type", "string");
                    ObjectNode empty = Util.JSON.createObjectNode().put("type", "object").put("nullable", true);
                    empty.putArray("enum").addNull();
                    error.with("properties").set("data", empty);
                    if (code.equals("400")) {
                        ObjectNode issue = object();
                        issue.with("properties").putObject("field").put("type", "string");
                        issue.with("properties").putObject("code").put("type", "string");
                        issue.with("properties").putObject("message").put("type", "string");
                        issue.putArray("required").add("field").add("code").add("message");
                        error.with("properties").putObject("errors").put("type", "array").put("maxItems", 20).set("items", issue);
                    }

                    operation.with("responses")
                        .putObject(code)
                        .put(
                            "description",
                            code.equals("401")
                                ? "未认证或身份上下文缺失"
                                : (
                                    code.equals("403")
                                        ? "没有操作或数据访问权限"
                                        : (
                                            code.equals("400")
                                                ? "参数或请求校验失败"
                                                : (
                                                    code.equals("404")
                                                        ? "目标记录不存在"
                                                        : (
                                                            code.equals("405")
                                                                ? "HTTP方法不支持"
                                                                : (code.equals("409") ? "数据完整性或版本冲突" : (code.equals("415") ? "请求媒体类型不支持" : "内部错误，详情不对外暴露"))
                                                        )
                                                )
                                        )
                                )
                        )
                        .putObject("content")
                        .putObject("application/json")
                        .set("schema", error);
                }
            }
        }

        if (!existing) for (JsonNode pathItem : paths) for (JsonNode op : pathItem) {
            for (JsonNode response : op.path("responses")) {
                ((ObjectNode) response).with("headers").putObject("X-Request-Id")
                        .put("description", "同步请求关联编号；不是认证或幂等凭据")
                        .putObject("schema").put("type", "string").put("minLength", 1).put("maxLength", 64);
            }
        }
        document.set("x-engineering-profile", Util.JSON.valueToTree(integration));
        document.put("x-spec-schema-version", ProductionPolicy.version(spec));
        document.set("x-production-options", Util.JSON.valueToTree(spec.getOptions()));
        document.put("x-required-access-adapter", ProductionPolicy.accessGuard(spec));
        document.put(
            "x-generation-note",
            "Generated CRUD contract; authentication, authorization and business workflow are not implemented. Cross-field range constraints require runtime tests."
        );
        return document;
    }

    private static ObjectNode object() {
        ObjectNode value = Util.JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
        value.putObject("properties");
        return value;
    }

    private static ObjectNode ref(String name) {
        return Util.JSON.createObjectNode().put("$ref", "#/components/schemas/" + name);
    }

    private static ObjectNode idSchema(Spec spec) {
        return ProductionPolicy.strings(spec)
            ? Util.JSON
                .createObjectNode()
                .put("type", "string")
                .put("pattern", "^[1-9][0-9]*$")
                .put("x-java-type", "Long")
                .put("x-numeric-minimum", "1")
                .put("x-numeric-maximum", Long.toString(Long.MAX_VALUE))
            : Util.JSON.createObjectNode().put("type", "integer").put("format", "int64").put("minimum", 1).put("maximum", Long.MAX_VALUE);
    }

    private static ObjectNode versionSchema(Spec spec) {
        return ProductionPolicy.strings(spec)
            ? Util.JSON
                .createObjectNode()
                .put("type", "string")
                .put("pattern", "^(0|[1-9][0-9]*)$")
                .put("x-java-type", "Long")
                .put("x-numeric-minimum", "0")
                .put("x-numeric-maximum", Long.toString(Long.MAX_VALUE))
            : Util.JSON.createObjectNode().put("type", "integer").put("format", "int64").put("minimum", 0).put("maximum", Long.MAX_VALUE);
    }

    private static ObjectNode fieldSchema(Spec spec, Spec.Field f, boolean nullable) {
        ObjectNode value = Util.JSON.createObjectNode();
        if (f.javaType().equals("String")) {
            value.put("type", "string").put("maxLength", f.maxLength());
        } else if (f.javaType().equals("Boolean")) {
            value.put("type", "boolean");
        } else if (f.javaType().equals("BigDecimal")) {
            value.put("type", ProductionPolicy.strings(spec) ? "string" : "number");
            value.put("x-precision", f.precision());
            value.put("x-scale", f.scale());
            BigDecimal unit = BigDecimal.ONE.movePointLeft(f.scale());
            BigDecimal max = BigDecimal.TEN.pow(f.precision() - f.scale()).subtract(unit);
            if (ProductionPolicy.strings(spec)) {
                value.put("pattern", "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$")
                    .put("x-numeric-maximum", max.toPlainString())
                    .put("x-numeric-minimum", max.negate().toPlainString());
            } else {
                value.put("multipleOf", unit).put("maximum", max).put("minimum", max.negate());
            }
        } else if (f.javaType().startsWith("Local")) {
            value.put("type", "string").put("format", f.javaType().equals("LocalDate") ? "date" : "local-date-time");
            value.put("x-date-minimum", "1000-01-01").put("x-date-maximum", "9999-12-31");
            if (f.javaType().equals("LocalDateTime")) {
                value.put("x-temporal-precision", ProductionPolicy.temporalPrecision(f));
            }
        } else if (f.javaType().equals("Long") && ProductionPolicy.strings(spec)) {
            value.put("type", "string")
                .put("pattern", "^-?(0|[1-9][0-9]*)$")
                .put("x-java-type", "Long")
                .put("x-numeric-minimum", Long.toString(Long.MIN_VALUE))
                .put("x-numeric-maximum", Long.toString(Long.MAX_VALUE));
        } else {
            value.put("type", "integer").put("format", f.javaType().equals("Long") ? "int64" : "int32");
            value.put("minimum", f.javaType().equals("Long") ? Long.MIN_VALUE : Integer.MIN_VALUE);
            value.put("maximum", f.javaType().equals("Long") ? Long.MAX_VALUE : Integer.MAX_VALUE);
        }

        value.put("description", f.description()).put("nullable", nullable);
        return value;
    }

    private static boolean contains(ArrayNode array, String name) {
        for (JsonNode value : array) {
            if (value.asText().equals(name)) {
                return true;
            }
        }

        return false;
    }

    private static void addParam(ArrayNode params, String name, String in, ObjectNode schema, boolean required) {
        ObjectNode p = params.addObject().put("name", name).put("in", in).put("required", required);
        p.set("schema", schema);
        if (schema.path("type").asText().equals("array")) {
            p.put("style", "form").put("explode", true);
        }
    }
}
