<#import "_macros.ftl" as code>
package ${package}.dto;

<@code.imports values=updateImports />
/** ${description}<#if partialUpdate>部分<#else>完整</#if>更新输入，字段白名单由接口规格确定。 */
<#if partialUpdate>@${entity}UpdateCheck
</#if>public class ${entity}UpdateDTO {

    /** 请求体 ID；有路径／查询／外层 ID 时必须一致。 */
<#if updateBodyId>
    @NotNull
</#if>
    @Positive
<@code.numeric type="Long" />
    private Long id;

<@code.accessor type="Long" name="id" cap="Id" />
<#if optimisticLock>
    @NotNull
    @PositiveOrZero
<@code.numeric type="Long" />
    private Long expectedVersion;

<@code.accessor type="Long" name="expectedVersion" cap="ExpectedVersion" />
</#if>
<#list editableFields as field>
<@code.field f=field validate=true />
<#if partialUpdate>
    private boolean ${field.name}Present;

</#if>
</#list>
<#list editableFields as field>
<#if partialUpdate>
<#if field.jsonExplicit>
    @JsonProperty("${field.name}")
</#if>
    public ${field.type} get${field.cap}() {
        return ${field.name};
    }

<#if field.jsonExplicit>
    @JsonProperty("${field.name}")
</#if>
    /** 接收显式传入的${field.description}并标记 Present=true；包括显式 null，是否允许清空由更新校验器决定，不直接写入数据库。 */
    public void set${field.cap}(${field.type} value) {
        this.${field.name} = value;
        this.${field.name}Present = true;
    }

    @JsonIgnore
    public boolean is${field.cap}Present() {
        return ${field.name}Present;
    }

<#else>
<@code.accessor type=field.type name=field.name cap=field.cap jsonExplicit=field.jsonExplicit />
</#if>
</#list>
}
