package ${package}.dto;
import ${validationPackage}.ConstraintValidator;
import ${validationPackage}.ConstraintValidatorContext;
/** 部分修改区分未传与显式 null；空修改绑定请求整体，非空列错误绑定业务字段。 */
public final class ${entity}UpdateCheckValidator implements ConstraintValidator<${entity}UpdateCheck, ${entity}UpdateDTO> {
    @Override
    public boolean isValid(${entity}UpdateDTO value, ConstraintValidatorContext context) {
        if (value == null) { return true; }
        context.disableDefaultConstraintViolation();
        boolean present = <#list editableFields as f>value.is${f.cap}Present()<#sep> || </#sep></#list>;
        if (!present) { context.buildConstraintViolationWithTemplate("EMPTY_UPDATE").addConstraintViolation(); return false; }
        boolean valid = true;
<#list editableFields as f><#if f.nonNull>
        if (value.is${f.cap}Present() && (value.get${f.cap}() == null<#if f.type == 'String'> || value.get${f.cap}().trim().isEmpty()</#if>)) {
            context.buildConstraintViolationWithTemplate("REQUIRED_IF_PRESENT").addPropertyNode("${f.name}").addConstraintViolation(); valid = false;
        }
</#if></#list>
        return valid;
    }
}
