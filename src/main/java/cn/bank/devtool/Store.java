package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Store {
    private final Path root;
    private final java.util.Set<String> unquarantined = new java.util.HashSet<String>();

    public Store(Path root) {
        this.root = root.toAbsolutePath().normalize();

        try {
            Files.createDirectories(this.root);
        } catch (IOException var3) {
            throw new IllegalStateException("数据目录不可写", var3);
        }
    }

    public synchronized JsonNode save(String kind, String label, Map<String, byte[]> files) {
        if (!Util.isBlank(label) && label.length() <= 1000) {
            String id = UUID.randomUUID().toString();
            Path temp = this.root.resolve(".tmp-" + id);

            try {
                Files.createDirectory(temp);
                ObjectNode meta = Util.obj();
                meta.put("id", id);
                meta.put("kind", kind);
                meta.put("description", label);
                meta.put("label", label.length() > 100 ? label.substring(0, 97) + "…" : label);
                meta.put("createdAt", Instant.now().toString());
                ObjectNode hashes = meta.putObject("files");

                for (Entry<String, byte[]> e : files.entrySet()) {
                    this.fileName(e.getKey());
                    Files.write(temp.resolve(e.getKey()), e.getValue(), StandardOpenOption.CREATE_NEW);
                    hashes.put(e.getKey(), Util.sha(e.getValue()));
                }

                Files.write(temp.resolve("metadata.json"), Util.bytes(Util.pretty(meta)), StandardOpenOption.CREATE_NEW);

                try {
                    Files.move(temp, this.root.resolve(id), StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException var10) {
                    Files.move(temp, this.root.resolve(id));
                }

                return meta;
            } catch (IOException var11) {
                this.cleanup(temp);
                throw new IllegalStateException("数据保存失败", var11);
            }
        } else {
            throw new IllegalArgumentException("记录说明必须为1至1000字");
        }
    }

    private void cleanup(Path p) {
        if (Files.exists(p)) {
            try (Stream<Path> walk = Files.walk(p)) {
                for (Path x : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                    try {
                        Files.deleteIfExists(x);
                    } catch (IOException var8) {
                    }
                }
            } catch (IOException var10) {
            }
        }
    }

    public byte[] read(String id, String file) {
        this.id(id);
        this.fileName(file);
        Path p = this.root.resolve(id).resolve(file);
        if (!Files.isRegularFile(p)) {
            throw new NoSuchElementException("文件不存在");
        } else {
            try {
                return Files.readAllBytes(p);
            } catch (IOException var5) {
                throw new IllegalStateException("读取失败", var5);
            }
        }
    }

    public synchronized List<JsonNode> list(String kind) {
        unquarantined.clear();
        List<JsonNode> rows = new java.util.ArrayList<JsonNode>();
        try (Stream<Path> entries = Files.list(this.root)) {
            for (Path p : entries.collect(Collectors.toList())) {
                String name = p.getFileName().toString();
                if (!name.matches("[0-9a-f-]{36}") || Files.isSymbolicLink(p)) continue;
                try {
                    Path meta = p.resolve("metadata.json");
                    if (Files.isSymbolicLink(meta) || !Files.isRegularFile(meta) || Files.size(meta) > 5242880) throw new IOException("Invalid metadata");
                    JsonNode m = Util.parse(Files.readAllBytes(meta));
                    if (m == null || !m.isObject() || !name.equals(m.path("id").asText()) || !m.path("files").isObject()
                        || !m.path("kind").isTextual()) throw new IOException("Invalid metadata");
                    Instant.parse(m.path("createdAt").asText());
                    if (Util.isBlank(kind) || m.path("kind").asText().equals(kind)) rows.add(m);
                } catch (IOException | IllegalArgumentException | java.time.DateTimeException damaged) {
                    try {
                        Path quarantine = Files.createDirectories(this.root.resolve(".quarantine"));
                        Files.move(p, quarantine.resolve(name + "-" + UUID.randomUUID()));
                    } catch (IOException unavailable) {
                        // Leave the original bytes intact; another healthy record must remain readable.
                        unquarantined.add(name);
                    }
                }
            }
        } catch (IOException ex) { throw new IllegalStateException("读取历史记录失败", ex); }
        rows.sort(Comparator.<JsonNode, String>comparing(m -> m.path("createdAt").asText()).reversed());
        return rows;
    }

    public synchronized JsonNode diagnostics() {
        long count = 0;
        Path quarantine = this.root.resolve(".quarantine");
        if (Files.isDirectory(quarantine)) {
            try (Stream<Path> entries = Files.list(quarantine)) { count = entries.count(); }
            catch (IOException ex) { throw new IllegalStateException("读取隔离状态失败", ex); }
        }
        return Util.obj().put("quarantinedRecords", count).put("unquarantinedRecords", unquarantined.size()).put("recovery", "损坏记录原样保留在数据目录.quarantine；备份后由开发人员修复，勿直接覆盖正常记录");
    }

    private void id(String s) {
        if (s == null || !s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("非法记录ID");
        }
    }

    private void fileName(String s) {
        if (s == null || !s.matches("[a-zA-Z0-9_.-]{1,80}") || s.startsWith(".") || s.equals("metadata.json")) {
            throw new IllegalArgumentException("非法文件名");
        }
    }
}
