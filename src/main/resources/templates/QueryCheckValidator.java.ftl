package ${package}.dto;
import ${validationPackage}.ConstraintValidator;
import ${validationPackage}.ConstraintValidatorContext;
/** 起止都有值时校验范围，错误分别指向对应 From／To 接口字段。 */
public final class ${entity}QueryCheckValidator implements ConstraintValidator<${entity}QueryCheck, ${entity}QueryDTO> {
    @Override
    public boolean isValid(${entity}QueryDTO value, ConstraintValidatorContext context) {
        if (value == null) { return true; }
        context.disableDefaultConstraintViolation(); boolean valid = true;
<#list rangeFields as f>
        if (value.get${f.cap}From() != null && value.get${f.cap}To() != null && value.get${f.cap}From().compareTo(value.get${f.cap}To()) > 0) {
            context.buildConstraintViolationWithTemplate("INVALID_RANGE").addPropertyNode("${f.name}From").addConstraintViolation();
            context.buildConstraintViolationWithTemplate("INVALID_RANGE").addPropertyNode("${f.name}To").addConstraintViolation(); valid = false;
        }
</#list>
        return valid;
    }
}
