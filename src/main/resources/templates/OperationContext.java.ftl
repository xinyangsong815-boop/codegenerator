package ${package}.integration;
/** 单次服务调用的钩子上下文：operation 为操作类型，id 为当前主键（新增前和分页时为 null）。
 * actor 来自授权适配器或可信认证上下文；scope 来自 WriteContext。request 仅供业务前置检查，不是授权结果，也不包含修改前后的数据库快照。
 * 禁止通过 request 修改已校验参数，禁止保存或打印完整请求及敏感身份信息。
 */
public final class ${entity}OperationContext {
    private final String operation,actor;
    private final Long id;
    private final Object request,scope;
    public ${entity}OperationContext(String operation,Long id,Object request,String actor,Object scope){this.operation=operation;this.id=id;this.request=request;this.actor=actor;this.scope=scope;}
    public String getOperation(){return operation;} public Long getId(){return id;}
    public Object getRequest(){return request;} public String getActor(){return actor;} public Object getScope(){return scope;}
}
