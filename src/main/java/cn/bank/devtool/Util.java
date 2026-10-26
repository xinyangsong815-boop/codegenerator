package cn.bank.devtool;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Util {
    public static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            new DeserializationFeature[]{DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS}
        )
        .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    private Util() {
    }

    public static String sha(byte[] b) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder out = new StringBuilder(digest.length * 2);

            for (byte value : digest) {
                out.append(String.format(Locale.ROOT, "%02x", value & 255));
            }

            return out.toString();
        } catch (Exception var7) {
            throw new IllegalStateException(var7);
        }
    }

    public static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static JsonNode parse(byte[] b) {
        if (b != null && b.length <= 5242880) {
            try {
                return JSON.readTree(b);
            } catch (Exception var2) {
                throw new IllegalArgumentException("JSON格式错误：" + var2.getMessage());
            }
        } else {
            throw new IllegalArgumentException("JSON文件最大5MiB");
        }
    }

    public static String pretty(Object o) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception var2) {
            throw new IllegalStateException(var2);
        }
    }

    public static String html(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static ObjectNode obj() {
        return JSON.createObjectNode();
    }

    public static String ptr(String s) {
        return s.replace("~", "~0").replace("/", "~1");
    }

    public static String comment(String s) {
        return s == null ? "" : s.replace("\\", "＼").replace("\r", " ").replace("\n", " ").replace("*/", "* /");
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    @SafeVarargs
    public static <T> List<T> listOf(T... items) {
        return Arrays.asList(items);
    }

    @SafeVarargs
    public static <T> Set<T> setOf(T... items) {
        return new LinkedHashSet<>(Arrays.asList(items));
    }

    public static <K, V> Map<K, V> mapOf(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("mapOf requires key/value pairs");
        } else {
            Map<K, V> m = new LinkedHashMap<>();

            for (int i = 0; i < kv.length; i += 2) {
                m.put((K)kv[i], (V)kv[i + 1]);
            }

            return m;
        }
    }
}
