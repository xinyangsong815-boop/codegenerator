package ${package}.common;
/** 校验可信操作人标识，避免空身份、控制字符和超长数据进入授权／审计链路。 */
public final class ${entity}AccessIdentity {
    private ${entity}AccessIdentity() { }
    /** 校验 authorize 返回的 actor：非空白、最多128个Java字符且不含控制字符；不合格时抛401异常，此检查本身不验证身份真实性。 */
    public static void require(String actor) {
        if (actor == null || actor.trim().isEmpty() || actor.length() > 128) { throw ${entity}AccessException.unauthorized(); }
        for (int index = 0; index < actor.length(); index++) {
            if (Character.isISOControl(actor.charAt(index))) { throw ${entity}AccessException.unauthorized(); }
        }
    }
}
