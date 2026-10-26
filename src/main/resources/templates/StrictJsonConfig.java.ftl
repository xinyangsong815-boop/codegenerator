package ${package}.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为本模块白名单中的生成 DTO 注册严格反序列化；拒绝重复键、未知属性和不匹配类型，隔离树读取器保留金额数字及小数位，不改变宿主全局数值转换策略。 */
@Configuration("${package}.${entity}StrictJsonConfig")
public class ${entity}StrictJsonConfig {
    private static final Set<String> DTO_TYPES = new HashSet<String>(Arrays.asList(
        "${package}.dto.${entity}CreateDTO", "${package}.dto.${entity}UpdateDTO",
        "${package}.dto.${entity}QueryDTO", "${package}.dto.${entity}IdDTO", "${package}.dto.${entity}DeleteDTO",
        "${package}.dto.${entity}CreateRequest", "${package}.dto.${entity}UpdateRequest", "${package}.dto.${entity}PageRequest"));

    @Bean(name = "${package}.${entity}StrictJson")
    /** 由 Spring 自动装配调用，返回只对 DTO_TYPES 生效的 Jackson 定制器；其他宿主类型继续使用原反序列化器。 */
    public Jackson2ObjectMapperBuilderCustomizer ${entity?uncap_first}StrictJson() {
        return builder -> builder.postConfigurer(mapper -> {
            // An isolated reader preserves decimal digits and scale without changing the host mapper.
            ObjectReader treeReader = mapper.copy()
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
                    .readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            SimpleModule module = new SimpleModule("${package}.${entity}StrictDTO");
            module.setDeserializerModifier(new BeanDeserializerModifier() {
                @Override
                /** 仅在 bean 类型属于本模块 DTO 白名单时包装 delegate，否则原样返回；读取可反序列化属性及字符串数值注解用于后续严格检查。 */
                public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription bean, JsonDeserializer<?> delegate) {
                    if (!DTO_TYPES.contains(bean.getBeanClass().getName())) return delegate;
                    Map<String, JavaType> properties = new LinkedHashMap<String, JavaType>();
                    Set<String> stringNumbers = new HashSet<String>();
                    for (BeanPropertyDefinition property : bean.findProperties()) {
                        if (!property.couldDeserialize()) continue;
                        properties.put(property.getName(), property.getPrimaryType());
                        JsonDeserialize annotation = property.getPrimaryMember() == null ? null : property.getPrimaryMember().getAnnotation(JsonDeserialize.class);
                        if (annotation != null && (annotation.using().getName().startsWith("${package}.common.NumericWire$") || annotation.contentUsing().getName().startsWith("${package}.common.NumericWire$"))) stringNumbers.add(property.getName());
                    }
                    return new Reader(delegate, bean.getBeanClass(), properties, stringNumbers, treeReader);
                }
            });
            mapper.registerModule(module);
        });
    }

    /** 先用隔离读取器检查 JSON 结构、类型及重复键，再委托原 DTO 反序列化器构造对象；解析器临时选项在完成后恢复。 */
    private static final class Reader extends DelegatingDeserializer {
        private final ObjectReader treeReader;
        private final Class<?> target;
        private final Map<String, JavaType> properties;
        private final Set<String> stringNumbers;
        Reader(JsonDeserializer<?> delegate, Class<?> target, Map<String, JavaType> properties, Set<String> stringNumbers, ObjectReader treeReader) {
            super(delegate); this.target = target; this.properties = properties; this.stringNumbers = stringNumbers; this.treeReader = treeReader;
        }
        @Override protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> delegate) { return new Reader(delegate,target,properties,stringNumbers,treeReader); }
        @Override public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            boolean previous = parser.isEnabled(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            boolean root = parser.getParsingContext().inRoot() || parser.getParsingContext().getParent()!=null && parser.getParsingContext().getParent().inRoot();
            JsonNode value;
            try {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                value = treeReader.readTree(parser);
                if (root && parser.nextToken()!=null) context.reportInputMismatch(target,"Trailing JSON tokens are not allowed");
            } finally { if (!previous) parser.disable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION); }
            if (!value.isObject()) context.reportInputMismatch(target,"Expected JSON object");
            Iterator<Map.Entry<String,JsonNode>> fields=value.fields();
            while(fields.hasNext()) {
                Map.Entry<String,JsonNode> field=fields.next(); JavaType type=properties.get(field.getKey());
                if(type==null) context.reportInputMismatch(target,"Unknown or ignored field: %s",field.getKey());
                validate(field.getValue(),type,stringNumbers.contains(field.getKey()),field.getKey(),context);
            }
            try(JsonParser tree=value.traverse(parser.getCodec())) { tree.nextToken(); return _delegatee.deserialize(tree,context); }
        }
        private void validate(JsonNode node,JavaType type,boolean numberString,String name,DeserializationContext context) throws IOException {
            if(node.isNull()) return; // Bean Validation handles nullability and required/presence semantics.
            if(type.isCollectionLikeType()) {
                if(!node.isArray()) context.reportInputMismatch(target,"Expected array: %s",name);
                for(JsonNode item:node) validate(item,type.getContentType(),numberString,name,context); return;
            }
            Class<?> raw=type.getRawClass(); boolean valid=true;
            if(raw==String.class) valid=node.isTextual();
            else if(raw==Boolean.class||raw==boolean.class) valid=node.isBoolean();
            else if(raw==Integer.class||raw==int.class) valid=node.isIntegralNumber()&&node.canConvertToInt();
            else if(raw==Long.class||raw==long.class) valid=numberString?node.isTextual():node.isIntegralNumber()&&node.canConvertToLong();
            else if(raw==BigDecimal.class) valid=numberString?node.isTextual():node.isNumber();
            else if(raw==LocalDate.class||raw==LocalDateTime.class) {
                valid=node.isTextual();
                if(valid)try{if(raw==LocalDate.class)LocalDate.parse(node.asText());else LocalDateTime.parse(node.asText());}catch(RuntimeException error){valid=false;}
            }
            if(!valid) context.reportInputMismatch(target,"Wrong JSON type or range for field: %s",name);
        }
    }
}
