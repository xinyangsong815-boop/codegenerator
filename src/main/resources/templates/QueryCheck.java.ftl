package ${package}.dto;
import ${validationPackage}.Constraint;
import ${validationPackage}.Payload;
import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
/** 业务组合约束；违规绑定到正式接口字段，禁止暴露内部计算属性。 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ${entity}QueryCheckValidator.class)
public @interface ${entity}QueryCheck {
    String message() default "组合约束不满足";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
