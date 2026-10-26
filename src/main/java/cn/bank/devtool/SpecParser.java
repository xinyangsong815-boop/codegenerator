package cn.bank.devtool;

import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException.Reference;
import java.io.IOException;

public final class SpecParser {
    public static final ObjectMapper JSON = Util.JSON
        .copy()
        .enable(new Feature[]{Feature.STRICT_DUPLICATE_DETECTION})
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(new MapperFeature[]{MapperFeature.ALLOW_COERCION_OF_SCALARS});

    private SpecParser() {
    }

    public static Spec read(byte[] bytes) {
        if (bytes != null && bytes.length <= 5242880) {
            try {
                JsonNode tree = JSON.readTree(bytes);
                if (tree != null) {
                    for (JsonNode f : tree.path("fields")) {
                        if (f.hasNonNull("nullable") && f.has("required") && f.path("required").asBoolean() == f.path("nullable").asBoolean()) {
                            throw new IllegalArgumentException("nullable 与 required 冲突：" + f.path("name").asText());
                        }
                    }
                }

                Spec result = (Spec)JSON.treeToValue(tree, Spec.class);
                new SpecValidator().validate(result);
                return result;
            } catch (JsonMappingException var5) {
                StringBuilder pointer = new StringBuilder();

                for (Reference part : var5.getPath()) {
                    pointer.append('/').append(part.getFieldName() == null ? part.getIndex() : Util.ptr(part.getFieldName()));
                }

                throw new IllegalArgumentException("规格字段类型错误 " + pointer + "：" + var5.getOriginalMessage(), var5);
            } catch (IOException var6) {
                throw new IllegalArgumentException("规格 JSON 无效：" + var6.getMessage(), var6);
            }
        } else {
            throw new IllegalArgumentException("规格 JSON 最大5MiB");
        }
    }
}
