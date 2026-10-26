<#import "_macros.ftl" as code>
package ${package}.entity;

<@code.imports values=entityImports />
/** ${description}数据库持久化实体，不直接作为接口输入或输出。 */
public class ${entity} {

    /** 数据库自动生成的主键。 */
    private Long id;

<#if optimisticLock>
    private Long version;
<@code.accessor type="Long" name="version" cap="Version" />
</#if>
<#if softDelete>
    private Boolean deleted;
<@code.accessor type="Boolean" name="deleted" cap="Deleted" />
</#if>
<#list fields as field>
<@code.field f=field validate=false wire=false />
</#list>
<@code.accessor type="Long" name="id" cap="Id" />
<#list fields as field>
<@code.accessor type=field.type name=field.name cap=field.cap jsonExplicit=field.jsonExplicit />
</#list>
}
