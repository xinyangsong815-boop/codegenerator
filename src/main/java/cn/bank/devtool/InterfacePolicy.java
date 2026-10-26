package cn.bank.devtool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.lang.model.SourceVersion;

public final class InterfacePolicy {
    private InterfacePolicy() {
    }

    public static boolean partial(Spec spec) {
        return "PARTIAL".equals(spec.getUpdateMode());
    }

    public static Map<String, Map<String, Object>> resolve(Spec spec) {
        String profile = spec.getApiProfile() == null ? "LEGACY" : spec.getApiProfile();
        require(Arrays.asList("LEGACY", "REST", "BANK_POST").contains(profile), "未知 apiProfile");
        require(spec.getUpdateMode() == null || Arrays.asList("FULL", "PARTIAL").contains(spec.getUpdateMode()), "未知 updateMode");
        require(spec.operations().contains("UPDATE") || !partial(spec), "PARTIAL 需要启用 UPDATE");
        Map<String, Spec.Endpoint> overrides = spec.getEndpoints() == null ? Collections.emptyMap() : spec.getEndpoints();

        for (String key : overrides.keySet()) {
            require(spec.operations().contains(key), "接口配置对应未启用操作：" + key);
        }

        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Set<String> mappings = new HashSet<>();

        for (String op : spec.operations()) {
            Spec.Endpoint custom = overrides.get(op);
            require(!overrides.containsKey(op) || custom != null, "接口配置不能为空：" + op);
            boolean id = Arrays.asList("UPDATE", "DETAIL", "DELETE").contains(op);
            boolean dto = Arrays.asList("CREATE", "UPDATE", "PAGE").contains(op);
            boolean bank = profile.equals("BANK_POST");
            String defaultMethod = bank
                ? "POST"
                : (
                    op.equals("DETAIL")
                        ? "GET"
                        : (
                            op.equals("DELETE")
                                ? "DELETE"
                                : (op.equals("UPDATE") ? (partial(spec) ? "PATCH" : "PUT") : (op.equals("PAGE") && profile.equals("REST") ? "GET" : "POST"))
                        )
                );
            String defaultPath = bank ? "/" + op.toLowerCase(Locale.ROOT) : (id ? "/{id}" : (op.equals("PAGE") ? (profile.equals("REST") ? "" : "/page") : ""));
            String method = value(custom == null ? null : custom.getMethod(), defaultMethod);
            String path = value(custom == null ? null : custom.getPath(), defaultPath);
            String requestIn = value(custom == null ? null : custom.getRequestIn(), dto ? (method.equals("GET") ? "QUERY" : "BODY") : "NONE");
            String idIn = value(custom == null ? null : custom.getIdIn(), id ? (bank ? "BODY" : "PATH") : "NONE");
            String wrapper = value(custom == null ? null : custom.getBodyProperty(), "");
            require(path.length() <= 180 && (path.isEmpty() || path.matches("/(?:[A-Za-z0-9_-]+|\\{id\\})(?:/(?:[A-Za-z0-9_-]+|\\{id\\}))*")), "非法相对接口路径：" + op);
            require(!path.contains("{id}") || id && idIn.equals("PATH") && path.indexOf("{id}") == path.lastIndexOf("{id}"), "路径 ID 与 idIn 不一致：" + op);
            require(!idIn.equals("PATH") || path.contains("{id}"), "PATH 参数必须含 {id}：" + op);
            require(id ? Arrays.asList("PATH", "QUERY", "BODY").contains(idIn) : idIn.equals("NONE"), "无效 idIn：" + op);
            require(dto ? Arrays.asList("BODY", "QUERY").contains(requestIn) : requestIn.equals("NONE"), "无效 requestIn：" + op);
            if (op.equals("CREATE")) {
                require(method.equals("POST") && requestIn.equals("BODY"), "CREATE 仅支持 POST JSON");
            }

            if (op.equals("UPDATE")) {
                require(
                    (method.equals("POST") || method.equals(partial(spec) ? "PATCH" : "PUT")) && requestIn.equals("BODY"),
                    "UPDATE 方法需匹配 FULL/ PARTIAL，且使用 JSON"
                );
            }

            if (op.equals("PAGE") || op.equals("DETAIL")) {
                require(Arrays.asList("GET", "POST").contains(method), "查询仅支持 GET/POST");
            }

            if (op.equals("DELETE")) {
                require(Arrays.asList("DELETE", "POST").contains(method), "DELETE 仅支持 DELETE/POST");
            }

            require(!method.equals("GET") || !requestIn.equals("BODY") && !idIn.equals("BODY"), "GET 不允许请求体");
            require(!method.equals("DELETE") || !idIn.equals("BODY"), "DELETE 请求体请改用 POST 动作接口");
            require(!requestIn.equals("QUERY") || op.equals("PAGE"), "QUERY DTO 仅支持 PAGE");
            require(
                wrapper.isEmpty()
                    || dto
                        && requestIn.equals("BODY")
                        && wrapper.matches("[a-z][a-zA-Z0-9_]{0,63}")
                        && !Arrays.asList("id", "class", "serialVersionUID").contains(wrapper)
                        && !SourceVersion.isKeyword(wrapper),
                "非法 bodyProperty"
            );
            List<String> fields = new ArrayList<>();

            for (Spec.Field f : spec.fields()) {
                if (op.equals("CREATE") && ProductionPolicy.client(f)
                    || op.equals("UPDATE") && f.editable() && ProductionPolicy.client(f)
                    || op.equals("PAGE") && !f.query().equals("NONE")) {
                    fields.add(f.name());
                }
            }

            if (custom != null && custom.getFields() != null) {
                require(
                    dto && new HashSet<>(custom.getFields()).size() == custom.getFields().size() && fields.containsAll(custom.getFields()),
                    "未知、重复或不可接收的字段：" + op
                );
                fields = new ArrayList<>(custom.getFields());
            }

            require(!op.equals("UPDATE") || !fields.isEmpty(), "UPDATE 至少选择一个可修改字段");

            for (Spec.Field fx : spec.fields()) {
                if (op.equals("CREATE") && fx.required() && ProductionPolicy.client(fx)) {
                    require(fields.contains(fx.name()), "新增不可遗漏数据库必填字段：" + fx.name());
                }
            }

            List<String> required = new ArrayList<>();

            for (Spec.Field fxx : spec.fields()) {
                if (fields.contains(fxx.name()) && !op.equals("PAGE") && !partialFor(op, spec) && fxx.required()) {
                    required.add(fxx.name());
                }
            }

            if (custom != null && custom.getRequiredFields() != null) {
                require(
                    dto
                        && fields.containsAll(custom.getRequiredFields())
                        && new HashSet<>(custom.getRequiredFields()).size() == custom.getRequiredFields().size(),
                    "requiredFields 必须属于选中的 fields"
                );
                require(!partialFor(op, spec) || custom.getRequiredFields().isEmpty(), "PARTIAL 不支持每次必须传的业务字段；已传非空约束由表字段 required 决定");
                require(custom.getRequiredFields().containsAll(required), "不能放宽已选数据库非空字段的完整写入约束");
                required = new ArrayList<>(custom.getRequiredFields());
            }

            if (!dto) {
                require(custom == null || custom.getFields() == null && custom.getRequiredFields() == null, "ID 操作不支持业务字段配置");
            }

            Map<String, Object> endpoint = new LinkedHashMap<>();
            endpoint.put("operation", op);
            endpoint.put("method", method);
            endpoint.put("path", path);
            endpoint.put("url", spec.basePath() + path);
            endpoint.put("requestIn", requestIn);
            endpoint.put("idIn", idIn);
            endpoint.put("bodyProperty", wrapper);
            endpoint.put("wrapperCap", wrapper.isEmpty() ? "" : Character.toUpperCase(wrapper.charAt(0)) + wrapper.substring(1));
            endpoint.put("wrapped", !wrapper.isEmpty());
            endpoint.put("fields", fields);
            endpoint.put("requiredFields", required);
            endpoint.put("partial", partialFor(op, spec));
            endpoint.put("body", requestIn.equals("BODY") || idIn.equals("BODY"));
            String normalized = path.replace("{id}", "{}");
            require(mappings.add(method + " " + normalized), "接口方法和路径重复：" + method + " " + path);
            result.put(op.toLowerCase(Locale.ROOT) + "Api", endpoint);
        }

        return result;
    }

    public static List<Map<String, Object>> select(List<Map<String, Object>> fields, Map<String, Object> endpoint) {
        List<Map<String, Object>> selected = new ArrayList<>();
        if (endpoint == null) {
            return selected;
        } else {
            for (Map<String, Object> field : fields) {
                if (((List)endpoint.get("fields")).contains(field.get("name"))) {
                    Map<String, Object> copy = new LinkedHashMap<>(field);
                    copy.put("required", ((List)endpoint.get("requiredFields")).contains(field.get("name")));
                    copy.put("nonNull", field.get("required"));
                    selected.add(copy);
                }
            }

            return selected;
        }
    }

    private static boolean partialFor(String op, Spec spec) {
        return op.equals("UPDATE") && partial(spec);
    }

    private static String value(String input, String fallback) {
        return input == null ? fallback : input;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
