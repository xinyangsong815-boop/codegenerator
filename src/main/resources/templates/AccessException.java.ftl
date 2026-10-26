package ${package}.common;
/** 权限适配器用于中止请求的受控异常；仅表达401未认证或403拒绝，不能附带凭据、权限策略或私密对象信息。 */
public final class ${entity}AccessException extends RuntimeException {
    private final int status;
    private ${entity}AccessException(int status){super(status==401?"身份认证失败":"没有操作或数据权限");this.status=status;}
    public int getStatus(){return status;}
    /** 创建未认证异常；AccessGuard 无可信登录身份时使用 throw unauthorized()，交给全局错误处理映射401。 */
    public static ${entity}AccessException unauthorized(){return new ${entity}AccessException(401);}
    /** 创建已认证但无权操作的异常；对象或数据范围检查拒绝时抛出，交给全局错误处理映射403。 */
    public static ${entity}AccessException forbidden(){return new ${entity}AccessException(403);}
}
