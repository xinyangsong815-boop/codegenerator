package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

public final class SourceFiles {
    private SourceFiles() {
    }

    public static Map<String, byte[]> text(JsonNode files) {
        if (files != null && files.isObject() && files.size() >= 1 && files.size() <= 1000) {
            Map<String, byte[]> out = new LinkedHashMap<>();
            long total = 0L;
            Iterator<Entry<String, JsonNode>> iterator = files.fields();

            while (iterator.hasNext()) {
                Entry<String, JsonNode> e = iterator.next();
                path(e.getKey());
                if (!e.getValue().isTextual()) {
                    throw new IllegalArgumentException("文件正文必须为字符串：" + e.getKey());
                }

                byte[] bytes = e.getValue().asText().getBytes(StandardCharsets.UTF_8);
                total += bytes.length;
                if (bytes.length > 5242880 || total > 20971520L) {
                    throw new IllegalArgumentException("文本单文件最大5MiB，集合最大20MiB");
                }

                out.put(e.getKey(), bytes);
            }

            return out;
        } else {
            throw new IllegalArgumentException("文件集合必须为1至1000个相对路径与正文");
        }
    }

    public static void path(String p) {
        if (p == null
            || p.length() > 500
            || p.startsWith("/")
            || p.contains("\\")
            || p.contains(":")
            || p.matches(".*[\\x00-\\x1f].*")
            || Arrays.asList(p.split("/", -1)).contains("..")
            || Arrays.asList(p.split("/", -1)).contains(".")
            || Arrays.asList(p.split("/", -1)).contains("")) {
            throw new IllegalArgumentException("需要安全相对文件路径");
        }
    }

    public static String utf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
        } catch (Exception var2) {
            throw new IllegalArgumentException("源码必须为UTF-8");
        }
    }
}
