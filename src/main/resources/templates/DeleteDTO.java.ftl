<#import "_macros.ftl" as code>
package ${package}.dto;
import ${validationPackage}.constraints.NotNull;
import ${validationPackage}.constraints.Positive;
import ${validationPackage}.constraints.PositiveOrZero;
/** 删除请求：版本来自最近读取结果；删除前仍须进行权限和业务状态检查。 */
public class ${entity}DeleteDTO {
    @NotNull @Positive
<@code.numeric type="Long" />
    private Long id;
    @NotNull @PositiveOrZero
<@code.numeric type="Long" />
    private Long expectedVersion;
    public Long getId(){return id;}
    public void setId(Long value){id=value;}
    public Long getExpectedVersion(){return expectedVersion;}
    public void setExpectedVersion(Long value){expectedVersion=value;}
}
