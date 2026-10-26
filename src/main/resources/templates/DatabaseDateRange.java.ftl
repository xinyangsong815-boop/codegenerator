package ${package}.common;
import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import ${validationPackage}.Constraint;
import ${validationPackage}.Payload;
@Documented
@Constraint(validatedBy=DatabaseDateRangeValidator.class)
@Target({ElementType.FIELD,ElementType.METHOD,ElementType.PARAMETER,ElementType.ANNOTATION_TYPE,ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DatabaseDateRange {
    String message() default "日期超出数据库支持范围1000-01-01至9999-12-31";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
