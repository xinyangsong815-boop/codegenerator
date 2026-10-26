<#import "_macros.ftl" as code>
package ${package}.dto;

<@code.imports values=createImports />
/** ${description}新增输入。必填及长度规则在 Controller 和 Spring Service 入口校验。 */
<#if createFields?size == 0>
@com.fasterxml.jackson.databind.annotation.JsonSerialize
</#if>
public class ${entity}CreateDTO {

<#list createFields as field>
<@code.field f=field validate=true />
</#list>
<#list createFields as field>
<@code.accessor type=field.type name=field.name cap=field.cap jsonExplicit=field.jsonExplicit />
</#list>
}
