package ${package}.common;
import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import ${validationPackage}.Constraint;
import ${validationPackage}.Payload;
@Documented @Constraint(validatedBy=CodePointSizeValidator.class)
@Target({ElementType.FIELD,ElementType.METHOD,ElementType.PARAMETER,ElementType.ANNOTATION_TYPE,ElementType.TYPE_USE}) @Retention(RetentionPolicy.RUNTIME)
public @interface CodePointSize {int min() default 0;int max() default Integer.MAX_VALUE;String message() default "字符数量超出允许范围";Class<?>[] groups() default {};Class<? extends Payload>[] payload() default {};}
