<#import "_macros.ftl" as code>
package ${package}.dto;

import ${validationPackage}.constraints.NotNull;
import ${validationPackage}.constraints.Positive;

/** JSON 请求体中的资源标识，不接收额外业务字段。 */
public class ${entity}IdDTO {
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
}
