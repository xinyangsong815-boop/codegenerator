package cn.bank.devtool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ZipFiles {
    private ZipFiles() {
    }

    public static Map<String, byte[]> read(byte[] bytes) {
        if (bytes.length > 20971520) {
            throw new IllegalArgumentException("ZIP最大20MiB");
        } else {
            Map<String, byte[]> result = new LinkedHashMap<>();
            long total = 0L;
            int count = 0;

            ZipEntry e;
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                while ((e = z.getNextEntry()) != null) {
                    String n = e.getName().replace('\\', '/');
                    if (n.startsWith("/") || n.matches("^[A-Za-z]:.*") || Arrays.asList(n.split("/")).contains("..") || n.indexOf(0) >= 0) {
                        throw new IllegalArgumentException("ZIP含不安全路径");
                    }

                    if (++count > 3000) {
                        throw new IllegalArgumentException("ZIP文件数超过3000");
                    }

                    if (!e.isDirectory()) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] b = new byte[8192];

                        int k;
                        while ((k = z.read(b)) != -1) {
                            total += k;
                            if (total > 41943040L || out.size() + k > 5242880) {
                                throw new IllegalArgumentException("ZIP解压大小超过限制（总40MiB，单文件5MiB）");
                            }

                            out.write(b, 0, k);
                        }

                        if (result.containsKey(n)) {
                            throw new IllegalArgumentException("ZIP含重复文件名");
                        }

                        result.put(n, out.toByteArray());
                    }
                }
            } catch (IllegalArgumentException var13) {
                throw var13;
            } catch (IOException var14) {
                throw new IllegalArgumentException("ZIP读取失败", var14);
            }

            if (result.isEmpty()) {
                throw new IllegalArgumentException("ZIP为空或格式错误");
            } else {
                return result;
            }
        }
    }

    public static byte[] write(Map<String, byte[]> files) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            try (ZipOutputStream z = new ZipOutputStream(out)) {
                for (Entry<String, byte[]> e : files.entrySet()) {
                    ZipEntry entry = new ZipEntry(e.getKey());
                    entry.setTime(0L);
                    z.putNextEntry(entry);
                    z.write(e.getValue());
                    z.closeEntry();
                }
            }

            return out.toByteArray();
        } catch (IOException var8) {
            throw new IllegalStateException(var8);
        }
    }

    public static Map<String, byte[]> projectFiles(byte[] zip) {
        Map<String, byte[]> raw = read(zip);
        if (!raw.containsKey("pom.xml") && !raw.containsKey("generation-manifest.json")) {
            Set<String> roots = new HashSet<>();

            for (String k : raw.keySet()) {
                int i = k.indexOf(47);
                if (i < 0) {
                    return raw;
                }

                roots.add(k.substring(0, i + 1));
            }

            if (roots.size() != 1) {
                return raw;
            } else {
                String prefix = roots.iterator().next();
                Map<String, byte[]> out = new LinkedHashMap<>();

                for (Entry<String, byte[]> e : raw.entrySet()) {
                    out.put(e.getKey().substring(prefix.length()), e.getValue());
                }

                return out;
            }
        } else {
            return raw;
        }
    }
}
