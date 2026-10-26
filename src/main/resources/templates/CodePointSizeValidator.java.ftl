package ${package}.common;
import ${validationPackage}.ConstraintValidator;
import ${validationPackage}.ConstraintValidatorContext;
/** Unicode 码点计长，与接口长度一致；null 交由非空约束处理。 */
public final class CodePointSizeValidator implements ConstraintValidator<CodePointSize,CharSequence>{private int min,max;public void initialize(CodePointSize annotation){min=annotation.min();max=annotation.max();}public boolean isValid(CharSequence value,ConstraintValidatorContext context){if(value==null)return true;String text=value.toString();int count=text.codePointCount(0,text.length());return count>=min&&count<=max;}}
