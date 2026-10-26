package ${package}.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigDecimal;

/** STRING_NUMBERS 模式的无损 JSON 数值适配；传输层只接受十进制字符串，Java 领域对象仍使用 Long/BigDecimal。 */
public final class NumericWire {
    private NumericWire() { }
    /** 将规范十进制字符串解析成 Long；拒绝 JSON 数字、指数、前导加号及 Long 越界，避免浏览器大整数舍入后静默写入。 */
    public static final class LongReader extends JsonDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING || !parser.getText().matches("-?(0|[1-9][0-9]*)"))
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            try { return Long.valueOf(parser.getText()); }
            catch (NumberFormatException error) { return (Long) context.handleWeirdStringValue(Long.class, parser.getText(), "Long out of range"); }
        }
    }
    /** 将 BigDecimal 以无指数的字符串写入 JSON，保留十进制值；不在此处进行业务金额范围校验。 */
    public static final class DecimalWriter extends JsonSerializer<BigDecimal> {
        @Override public void serialize(BigDecimal value, JsonGenerator output, SerializerProvider provider) throws IOException { output.writeString(value.toPlainString()); }
    }
    /** 只接受规范十进制字符串并构造 BigDecimal；精度和小数位由 DTO 的 Digits 校验，不自动四舍五入。 */
    public static final class DecimalReader extends JsonDeserializer<BigDecimal> {
        @Override public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING || !parser.getText().matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))
                return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
            return new BigDecimal(parser.getText());
        }
    }
}
