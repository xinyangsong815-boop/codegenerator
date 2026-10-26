package ${package}.mapper;

<#if create || update || detail || page || rowLock>
import ${package}.entity.${entity};
<#if update && partialUpdate>
import ${package}.dto.${entity}UpdateDTO;
</#if>
</#if>
<#if page>
import ${package}.dto.${entity}QueryDTO;
import java.util.List;
</#if>
<#list testTypeImports as value>
import ${value};
</#list>
import org.apache.ibatis.annotations.Param;

/** ${description}持久化操作。SQL 参数必须使用 #{} 绑定，不拼接客户端表达式。 */
public interface ${entity}Mapper {

<#if create>
    /** 在调用方事务中插入 entity，返回影响行数并将数据库生成的主键写回 entity.id；调用方必须检查行数及主键。 */
    int insert(@Param("entity") ${entity} entity);

</#if>
<#if update>
    /** 按 id<#if scoped>及可信 scope</#if><#if optimisticLock>、预期版本</#if>更新，返回影响行数；0 表示未匹配或值未变，由服务进一步区分不存在与版本冲突。 */
    int update(@Param("id") Long id, <#if partialUpdate>@Param("request") ${entity}UpdateDTO request<#else>@Param("entity") ${entity} entity</#if><#if scoped>, @Param("scope") ${scopeType} scope</#if><#if hasServerUpdate>, @Param("server") ${entity} server</#if>);

</#if>
<#if update || deleteVersionCheck>
    /** 查询 id<#if scoped>在可信 scope 内</#if>是否仍可见，返回 0 或 1；仅用于写入结果诊断，不能代替写操作的并发条件。 */
    long existsById(@Param("id") Long id<#if scoped>, @Param("scope") ${scopeType} scope</#if>);

</#if>
<#if rowLock && (update || delete)>
    /** 写前锁定当前可见记录，权限与业务规则使用同一事务快照。 */
    ${entity} findByIdForUpdate(@Param("id") Long id<#if scoped>, @Param("scope") ${scopeType} scope</#if>);
</#if>
<#if transactionalAudit || (recordAccessCheck && create)>
    /** 写后内部快照，包含本次逻辑删除后的记录；不作为公开查询入口。 */
    ${entity} findByIdForAudit(@Param("id") Long id<#if scoped>, @Param("scope") ${scopeType} scope</#if>);
</#if>
<#if detail>
    /** 查询可见记录；id 不存在<#if scoped>或不属于 scope</#if>时返回 null，不在 Mapper 层转换业务异常。 */
    ${entity} findById(@Param("id") Long id<#if scoped>, @Param("scope") ${scopeType} scope</#if>);

</#if>
<#if page>
    /** 按已校验的 query 与固定 ID 倒序读取当前页；调用前必须完成权限校验，offset/size 由服务限制。 */
    List<${entity}> page(@Param("query") ${entity}QueryDTO query<#if scoped>, @Param("scope") ${scopeType} scope</#if>);

    /** 按与 page 相同的查询和数据范围统计全部匹配记录；结果不得包含未授权数据。 */
    long count(@Param("query") ${entity}QueryDTO query<#if scoped>, @Param("scope") ${scopeType} scope</#if>);

</#if>
<#if delete>
    /** 在调用方事务中删除 id 对应的记录，返回影响行数；服务必须检查 0 行结果并处理不存在<#if deleteVersionCheck>或版本冲突</#if>。 */
    int delete(@Param("id") Long id<#if deleteVersionCheck>, @Param("expectedVersion") Long expectedVersion</#if><#if scoped>, @Param("scope") ${scopeType} scope</#if>);

</#if>
}
