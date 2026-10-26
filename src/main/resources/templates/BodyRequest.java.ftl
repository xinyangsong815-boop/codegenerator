<#import "_macros.ftl" as code>
package ${package}.dto;

import ${validationPackage}.Valid;
import ${validationPackage}.constraints.NotNull;
import ${validationPackage}.constraints.Positive;
import com.fasterxml.jackson.annotation.JsonProperty;

/** ${description}请求封装；嵌套输入必须通过级联校验。 */
public class ${entity}${wrapperName} {

<#if api.idIn == 'BODY'>
    @NotNull
    @Positive
<@code.numeric type="Long" />
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

</#if>
    @NotNull
    @Valid
    private ${entity}${dtoName} ${api.bodyProperty};

    @JsonProperty("${api.bodyProperty}")
    public ${entity}${dtoName} get${api.wrapperCap}() {
        return ${api.bodyProperty};
    }

    @JsonProperty("${api.bodyProperty}")
    public void set${api.wrapperCap}(${entity}${dtoName} value) {
        this.${api.bodyProperty} = value;
    }
}
