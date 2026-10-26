<#import "_macros.ftl" as code>
package ${package}.vo;

<@code.imports values=viewImports />
/** ${description}接口输出，只包含允许对外展示的字段；字段裁剪不代替数据权限。 */
public class ${entity}VO {

    /** 数据库自动生成的主键。 */
<@code.numeric type="Long" />
    private Long id;

<#if optimisticLock>
<@code.numeric type="Long" />
    private Long version;

<@code.accessor type="Long" name="version" cap="Version" />
</#if>
<#list exposedFields as field>
<@code.field f=field validate=false />
</#list>
<@code.accessor type="Long" name="id" cap="Id" />
<#list exposedFields as field>
<@code.accessor type=field.type name=field.name cap=field.cap jsonExplicit=field.jsonExplicit />
</#list>
}
