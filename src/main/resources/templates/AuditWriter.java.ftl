package ${package}.integration;
/** 独立工程提供同数据源 JDBC 实现；宿主必须实现并共享同一个业务事务。
 * 写入失败抛异常回滚业务；禁止 REQUIRES_NEW、吞异常及不可回滚远程副作用。
 * 本接口只留痕成功写操作；读取、拒绝访问、留存与防篡改由宿主安全审计负责。
 */
public interface ${entity}AuditWriter {
    /** 在业务 SQL 成功后、当前事务提交前保存一次不可变审计事件；event 含操作人和字段白名单快照，保存失败必须抛异常回滚业务。 */
    void write(${entity}AuditEvent event);
}
