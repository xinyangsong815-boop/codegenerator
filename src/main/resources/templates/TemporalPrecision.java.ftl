package ${package}.common;
import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import ${validationPackage}.Constraint;
import ${validationPackage}.Payload;
@Target({ElementType.FIELD,ElementType.METHOD,ElementType.PARAMETER,ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TemporalPrecisionValidator.class)
public @interface TemporalPrecision {
    int value() default 3;
    String message() default "日期时间超过数据库范围或小数秒精度";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
