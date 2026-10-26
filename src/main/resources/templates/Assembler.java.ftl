package ${package}.assembler;

<#if create>
import ${package}.dto.${entity}CreateDTO;
</#if>
<#if update && !partialUpdate>
import ${package}.dto.${entity}UpdateDTO;
</#if>
import ${package}.entity.${entity};
<#if detail || page>
import ${package}.vo.${entity}VO;
</#if>
import java.util.Objects;

/** ${description}的无状态字段转换；创建、完整更新、展示分别使用规格白名单。
 * 不执行数据库、权限、事务或 Bean Validation；调用方须先在受管服务入口校验请求。
 * 不使用反射批量复制；服务端字段、数据库默认值、主键和并发控制由 Service 与 Mapper 管理。
 * PARTIAL 更新仍由原 DTO 的 Present 标记驱动 SQL，禁止转成普通实体后丢失未传与显式 null 的区别。
 */
public final class ${entity}Assembler {
    private ${entity}Assembler() { }

<#if create>
    /** 将已校验新增请求中的 CLIENT 白名单字段复制到新实体；不填充 ID、版本及 SERVER/DATABASE 字段。
     * @param request 非空新增请求
     * @return 尚未持久化的实体；可空字段保留 null
     */
    public static ${entity} fromCreate(${entity}CreateDTO request) {
        Objects.requireNonNull(request, "新增请求不能为空");
        ${entity} entity = new ${entity}();
<#list createFields as field>
        entity.set${field.cap}(request.get${field.cap}());
</#list>
        return entity;
    }

</#if>
<#if update && !partialUpdate>
    /** 仅复制完整更新接口声明的可编辑 CLIENT 字段；包括允许清空的 null，未选字段不参与后续 SQL。
     * 不复制请求 ID 或 expectedVersion，Service 独立核对并设置主键和版本。
     * @param request 非空且已通过完整更新校验的请求
     * @return 仅含可编辑字段的新实体，不作为现有记录的完整快照
     */
    public static ${entity} fromFullUpdate(${entity}UpdateDTO request) {
        Objects.requireNonNull(request, "完整更新请求不能为空");
        ${entity} entity = new ${entity}();
<#list editableFields as field>
        entity.set${field.cap}(request.get${field.cap}());
</#list>
        return entity;
    }

</#if>
<#if detail || page>
    /** 将存在的持久化实体映射为对外视图，仅包含主键、已启用版本及 exposed 白名单字段。
     * @param entity 非空实体；不存在判断由 Service 在调用前处理
     * @return 独立视图对象，不暴露隐藏字段或软删除内部标记
     */
    public static ${entity}VO toView(${entity} entity) {
        Objects.requireNonNull(entity, "展示实体不能为空");
        ${entity}VO view = new ${entity}VO();
        view.setId(entity.getId());
<#if optimisticLock>
        view.setVersion(entity.getVersion());
</#if>
<#list exposedFields as field>
        view.set${field.cap}(entity.get${field.cap}());
</#list>
        return view;
    }
</#if>
}
