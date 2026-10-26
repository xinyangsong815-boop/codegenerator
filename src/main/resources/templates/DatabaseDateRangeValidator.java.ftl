package ${package}.common;
import java.time.LocalDate;
import ${validationPackage}.ConstraintValidator;
import ${validationPackage}.ConstraintValidatorContext;
/** 限定 DATE 输入在 MySQL 支持的1000至9999年；null 交给 NotNull 判断，不做时区或日期自动修正。 */
public final class DatabaseDateRangeValidator implements ConstraintValidator<DatabaseDateRange,LocalDate> {
    /** 由 Bean Validation 校验日期范围；返回 false 时拒绝请求，null 通过此范围约束。 */
    public boolean isValid(LocalDate value,ConstraintValidatorContext context){return valid(value);}
    /** 供服务端字段及校验器复用：null 或1000至9999年的日期返回 true，其余返回 false。 */
    public static boolean valid(LocalDate value){return value==null||value.getYear()>=1000&&value.getYear()<=9999;}
}
