package ${package}.service;

<#if page>
<#if !existing || !externalPage>
import ${package}.common.PageResult;
</#if>
import ${package}.dto.${entity}QueryDTO;
</#if>
<#if create>
import ${package}.dto.${entity}CreateDTO;
</#if>
<#if update>
import ${package}.dto.${entity}UpdateDTO;
</#if>
<#if detail || page>
import ${package}.vo.${entity}VO;
</#if>
<#if create || update || page>
import ${validationPackage}.Valid;
</#if>
import ${validationPackage}.constraints.NotNull;
<#if update || detail || delete>
import ${validationPackage}.constraints.Positive;
</#if>

/** ${description}应用服务。约束在 Spring 管理的服务代理入口执行，禁止绕过代理直接 new。 */
public interface ${entity}Service {

<#if create>
    /** 新增记录，返回生成的 ID。必填、长度及小数精度需要满足规格。 */
    Long create(@NotNull @Valid ${entity}CreateDTO request);

</#if>
<#if update>
    /** <#if partialUpdate>部分<#else>完整</#if>更新接口声明的字段；不存在时抛业务异常。 */
    void update(@NotNull @Positive Long id, @NotNull @Valid ${entity}UpdateDTO request);

</#if>
<#if detail>
    /** 查询记录的对外视图；不存在时抛业务异常。 */
    ${entity}VO detail(@NotNull @Positive Long id);

</#if>
<#if page>
    /** 按固定 ID 倒序返回分页结果，空页仍保留 total。 */
    ${pageType}<${entity}VO> page(@NotNull @Valid ${entity}QueryDTO query);

</#if>
<#if delete>
    /** <#if softDelete>逻辑<#else>物理</#if>删除记录；关联限制和保留策略仍由业务定义。 */
    void delete(@NotNull @Positive Long id<#if deleteVersionCheck>, @NotNull @${validationPackage}.constraints.PositiveOrZero Long expectedVersion</#if>);

</#if>
}
