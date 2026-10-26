package ${package}.common;
/** 安全字段反馈。Never include rejected values or raw exception messages.
 * field 为公开接口路径，$ 表示请求整体；message 只使用固定中文字典。
 */
public final class ValidationIssue {
    private final String field;
    private final String code;
    private final String message;
    public ValidationIssue(String field, String code) {
        this.field = field == null || field.isEmpty() ? "$" : field.substring(0, Math.min(field.length(), 256));
        this.code = code != null && code.matches("[a-zA-Z0-9_]{1,80}") ? code : "Validation";
        this.message = describe(this.code);
    }
    /** 仅接受跨字段约束白名单中的模板码，否则使用验证注解的 fallback 名称；不把任意校验消息当作公开错误码。 */
    public static String safeCode(String template, String fallback) {
        return "EMPTY_UPDATE".equals(template) || "REQUIRED_IF_PRESENT".equals(template) || "INVALID_RANGE".equals(template) ? template : fallback;
    }
    private static String describe(String code) {
        switch (code) {
            case "ID_MISMATCH": return "请求体 ID 与接口 ID 必须一致";
            case "EMPTY_UPDATE": return "至少提供一个允许修改的业务字段";
            case "INVALID_RANGE": return "范围起始值不能大于结束值";
            case "NotNull": case "NotBlank": case "required": case "REQUIRED_IF_PRESENT": return "字段不能为空";
            case "Positive": return "字段必须为正数";
            case "PositiveOrZero": return "字段必须为非负数";
            case "Digits": return "数值精度超出允许范围";
            case "typeMismatch": return "字段类型或格式不正确";
            case "Size": case "CodePointSize": return "字段长度或列表数量超出允许范围";
            case "Min": case "Max": return "字段数值超出允许范围";
            default: return "字段不符合接口约束";
        }
    }
    public String getField() { return field; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
}
