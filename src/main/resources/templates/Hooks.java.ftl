package ${package}.integration;

/** hooks=true 时必须由宿主注入的业务扩展接口。before/after 均位于服务事务提交前，异常会中止本次调用。
 * 写操作中可通过同数据源 Outbox 保存经审查的事件，由独立投递器在提交后发送；禁止在钩子中执行不可回滚的远程副作用。
 * afterCommit 回调不能单独解决崩溃后漏投；消费者还需按 eventId 去重。
 * 数据范围隔离需配置 scopeField 与 SQL，before 钩子本身不能实现行级隔离。
 */
public interface ${entity}Hooks {
    /** 返回可信认证上下文中的操作人；默认 null 表示宿主未提供，启用 accessGuard 后以授权接口返回的操作人为准。 */
    default String currentActor(){return null;}
    /** 接收包含 operation/id/request/actor/scope 的上下文；默认转发给双参数 before，新增时 id 尚未生成，禁止修改请求以绕过已执行的参数校验。 */
    default void before(${entity}OperationContext context){before(context.getOperation(),context.getId());}
    /** 业务 SQL 执行后、事务提交前调用；默认转发给双参数 after，可保存同事务事件，不能将此回调视为已经提交成功。 */
    default void after(${entity}OperationContext context){after(context.getOperation(),context.getId());}
    /** 在操作 SQL 前检查业务前置规则；拒绝时抛异常，写操作随当前事务回滚。operation 为 CREATE/UPDATE/DETAIL/PAGE/DELETE，批量查询及新增前 id 为 null。 */
    void before(String operation, Long id);
    /** 在操作 SQL 后、提交前执行可回滚扩展；id 为当前记录主键或 PAGE 时为 null，异常向服务传播。 */
    void after(String operation, Long id);
}
