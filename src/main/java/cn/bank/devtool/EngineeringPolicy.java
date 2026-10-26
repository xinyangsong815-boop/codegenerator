package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.SourceVersion;

public final class EngineeringPolicy {
    private EngineeringPolicy() {
    }

    public static boolean existing(Spec spec) {
        return spec.getIntegration() != null && "EXISTING".equals(spec.getIntegration().getMode());
    }

    public static boolean externalPage(Spec spec) {
        return existing(spec) && !Util.isBlank(spec.getIntegration().getPageClass());
    }

    public static Map<String, Object> resolve(Spec spec) {
        Spec.Integration value = spec.getIntegration();
        String mode = value != null && value.getMode() != null ? value.getMode() : "STANDALONE";
        if (!Arrays.asList("STANDALONE", "EXISTING").contains(mode)) {
            fail("integration.mode 仅支持 STANDALONE 或 EXISTING");
        }

        boolean host = "EXISTING".equals(mode);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mode", mode);
        output.put("resultClass", host ? value.getResultClass() : spec.packageName() + ".common.Result");
        output.put("successMethod", host ? defaultValue(value.getSuccessMethod(), "success") : "success");
        output.put("exceptionClass", host ? value.getExceptionClass() : spec.packageName() + ".common.BusinessException");
        output.put("notFoundMethod", host ? defaultValue(value.getNotFoundMethod(), "notFound") : "notFound");
        output.put("pageClass", externalPage(spec) ? value.getPageClass() : spec.packageName() + ".common.PageResult");
        output.put("pageFactoryMethod", externalPage(spec) ? defaultValue(value.getPageFactoryMethod(), "") : "");
        output.put("dataProperty", host ? defaultValue(value.getDataProperty(), "data") : "data");
        output.put("codeProperty", host ? defaultValue(value.getCodeProperty(), "code") : "code");
        output.put("codeType", host ? defaultValue(value.getCodeType(), "INTEGER") : "INTEGER");
        output.put("successCode", host ? defaultValue(value.getSuccessCode(), "0") : "0");
        output.put("messageProperty", host ? defaultValue(value.getMessageProperty(), "message") : "message");
        output.put("recordsProperty", externalPage(spec) ? defaultValue(value.getRecordsProperty(), "records") : "records");
        output.put("totalProperty", externalPage(spec) ? defaultValue(value.getTotalProperty(), "total") : "total");
        if (!host && value != null) {
            JsonNode node = Util.JSON.valueToTree(value);
            node.fields().forEachRemaining(ex -> {
                if (!((String)ex.getKey()).equals("mode") && !((JsonNode)ex.getValue()).isNull()) {
                    fail("自定义公共组件仅用于 EXISTING 模式：" + (String)ex.getKey());
                }
            });
        }

        if (host) {
            className(value.getResultClass(), "resultClass");
            className(value.getExceptionClass(), "exceptionClass");
            identifier(output.get("successMethod").toString(), "successMethod");
            if (!output.get("notFoundMethod").equals("")) {
                identifier(output.get("notFoundMethod").toString(), "notFoundMethod");
            }

            if (externalPage(spec)) {
                className(value.getPageClass(), "pageClass");
                if (!output.get("pageFactoryMethod").equals("")) {
                    identifier(output.get("pageFactoryMethod").toString(), "pageFactoryMethod");
                }
            } else if (!Util.isBlank(value.getPageFactoryMethod()) || value.getRecordsProperty() != null || value.getTotalProperty() != null) {
                fail("分页工厂和字段映射需要先指定 pageClass");
            }

            for (String key : Arrays.asList("resultClass", "pageClass", "exceptionClass")) {
                String name = output.get(key).toString();
                if ((!key.equals("pageClass") || externalPage(spec)) && name.startsWith(spec.packageName() + ".")) {
                    fail("宿主公共类型应位于生成模块包名之外：" + key);
                }
            }
        }

        for (String keyx : Arrays.asList("dataProperty", "codeProperty", "messageProperty", "recordsProperty", "totalProperty")) {
            String name = output.get(keyx).toString();
            if ((!keyx.equals("codeProperty") && !keyx.equals("messageProperty") || !name.isEmpty()) && !name.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}")) {
                fail("响应属性名称不合法：" + keyx);
            }
        }

        distinct(output, "dataProperty", "codeProperty", "messageProperty");
        distinct(output, "recordsProperty", "totalProperty");
        if (!Arrays.asList("STRING", "INTEGER").contains(output.get("codeType"))) {
            fail("codeType 仅支持 STRING 或 INTEGER");
        }

        String code = output.get("successCode").toString();
        if (output.get("codeType").equals("INTEGER")) {
            if (!code.matches("-?(0|[1-9][0-9]{0,18})")) {
                fail("successCode 应为整数文本");
            }

            try {
                Long.parseLong(code);
            } catch (NumberFormatException var8) {
                fail("successCode 超出 Long 范围");
            }
        } else if (code.length() > 64) {
            fail("successCode 长度不能超过64");
        }

        output.put("hostVerification", host ? "REQUIRED" : "NOT_APPLICABLE");
        return output;
    }

    public static Object successCode(Spec spec) {
        Map<String, Object> value = resolve(spec);
        return value.get("codeType").equals("STRING") ? value.get("successCode") : Long.valueOf(value.get("successCode").toString());
    }

    public static Map<String, Object> model(Spec spec) {
        Map<String, Object> resolved = resolve(spec);
        Map<String, Object> model = new LinkedHashMap<>();
        boolean host = existing(spec);
        String result = host ? resolved.get("resultClass").toString() : "Result";
        String page = externalPage(spec) ? resolved.get("pageClass").toString() : "PageResult";
        String exception = host ? resolved.get("exceptionClass").toString() : "BusinessException";
        String pageMethod = resolved.get("pageFactoryMethod").toString();
        String exceptionMethod = resolved.get("notFoundMethod").toString();
        model.put("existing", host);
        model.put("externalPage", externalPage(spec));
        model.put("resultType", result);
        model.put("pageType", page);
        model.put("exceptionType", exception);
        model.put("successMethod", resolved.get("successMethod"));
        model.put("notFoundPrefix", exceptionMethod.isEmpty() ? "new " + exception + "(" : exception + "." + exceptionMethod + "(");
        model.put("pageFactoryPrefix", pageMethod.isEmpty() ? "new " + page + "<" + spec.entityName() + "VO>(" : page + "." + pageMethod + "(");
        model.put("engineering", resolved);
        return model;
    }

    public static Map<String, Object> checklist(Spec spec) {
        List<Map<String, Object>> checks = new ArrayList<>();

        for (String item : Arrays.asList(
            "公共类型及泛型工厂签名",
            "返回字段与成功码及空值序列化",
            "宿主异常状态和响应协议",
            "Controller/Service 扫描及方法校验代理",
            "Mapper 扫描和 XML 加载",
            "JSON 未知字段及小数转整数拒绝",
            "数据源、事务管理器及 MySQL 驱动行为",
            "鉴权、操作权限和数据权限",
            "审计日志及敏感字段脱敏",
            "宿主工程离线编译与业务回归"
        )) {
            checks.add(Util.mapOf("item", item, "status", "NOT_VERIFIED"));
        }

        return Util.mapOf("mode", "EXISTING", "status", "NOT_VERIFIED", "checks", checks, "note", "生成成功和示例公共类测试均不代表宿主工程接入验收通过", "profile", resolve(spec));
    }

    private static String defaultValue(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static void className(String value, String key) {
        if (value == null || value.length() > 200 || !value.matches("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+")) {
            fail("请指定合法完整类名：" + key);
        }

        for (String part : value.split("\\.")) {
            identifier(part, key);
        }
    }

    private static void identifier(String value, String key) {
        if (!SourceVersion.isIdentifier(value)
            || SourceVersion.isKeyword(value)
            || Arrays.asList("record", "var", "yield", "sealed", "permits").contains(value)) {
            fail("Java 名称不合法：" + key);
        }
    }

    private static void distinct(Map<String, Object> values, String... keys) {
        Set<String> names = new HashSet<>();

        for (String key : keys) {
            if (!values.get(key).equals("") && !names.add(values.get(key).toString())) {
                fail("响应属性不能重复：" + key);
            }
        }
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }
}
