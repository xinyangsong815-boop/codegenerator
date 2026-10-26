package ${package}.integration;
/** 必需生产权限适配接口，不生成允许全部访问的生产实现。
 * 在 Mapper 调用前读取可信身份并检查操作授权，返回非空、最多128字符且无控制字符的操作人。
 * 不信任请求中的机构／操作人；scopeField 另行实现统一 SQL 范围过滤。
 * 拒绝访问使用 ${package}.common.${entity}AccessException；宿主对齐401／403。
 */
public interface ${entity}AccessGuard {
    /** 每次 CRUD 调用 Mapper 前执行。使用可信身份验证 operation/id/scope 的操作权限，返回认证操作人；未认证或拒绝时抛 AccessException，禁止从请求正文读取操作人。 */
    String authorize(String operation, Long id, Object scope);
<#if recordAccessCheck>
    /** 当前记录为不可变标量快照，含授权所需服务端字段，不能直接返回或打印。
     * 更新／删除在同一事务锁定当前记录后调用；新增失败会回滚。
     */
    void checkRecord(String operation, Long id, Object scope, java.util.Map<String, Object> record);
<#if page>
    /** 必须保证整个 SQL 范围及总数均可读；部分获授权时拒绝，或改造宿主权限 SQL。
     * 不能仅检查当前页，再返回包含未授权记录的总数。
     */
    void checkPageScope(Object scope);
</#if></#if>
}
