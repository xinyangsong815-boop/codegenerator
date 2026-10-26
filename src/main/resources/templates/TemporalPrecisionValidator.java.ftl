package ${package}.common;
import java.time.LocalDateTime;
import ${validationPackage}.ConstraintValidator;
import ${validationPackage}.ConstraintValidatorContext;
/** 校验 MySQL DATETIME 日期范围与小数秒精度；超精度直接拒绝，禁止隐式舍入导致前后值不一致。 */
public final class TemporalPrecisionValidator implements ConstraintValidator<TemporalPrecision,LocalDateTime> {
    private int precision;
    /** Bean Validation 从注解读取已声明的0至6位小数秒精度，供后续 isValid 使用。 */
    public void initialize(TemporalPrecision rule){precision=rule.value();}
    /** 检查日期范围与初始化的小数秒精度；null 通过此约束，由非空约束单独判断。 */
    public boolean isValid(LocalDateTime value,ConstraintValidatorContext context){return valid(value,precision);}
    /** 供可信服务端字段复用；precision 须为0至6，null 或符合日期范围与精度的值返回 true。 */
    public static boolean valid(LocalDateTime value,int precision){
        return value==null||value.getYear()>=1000&&value.getYear()<=9999&&value.getNano()%(int)Math.pow(10,9-precision)==0;
    }
}
