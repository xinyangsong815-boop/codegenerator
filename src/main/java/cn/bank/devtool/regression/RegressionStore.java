package cn.bank.devtool.regression;

import cn.bank.devtool.Util;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class RegressionStore implements Closeable {
    private static final List<String> KINDS = Arrays.asList("environments", "scenarios", "runs");
    private final Path root;
    private final FileChannel channel;
    private final FileLock lock;

    public RegressionStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        FileChannel opened = null;
        FileLock acquired = null;
        try {
            Files.createDirectories(this.root);
            opened = FileChannel.open(this.root.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            acquired = opened.tryLock();
            if (acquired == null) throw new IllegalStateException("回归目录正在被其他进程使用");
            this.channel = opened;
            this.lock = acquired;
            for (String kind : KINDS) Files.createDirectories(this.root.resolve(kind));
            // Isolate malformed records before serving requests. Never replay interrupted runs.
            for (String kind : KINDS) {
                for (JsonNode n : this.list(kind)) {
                    if (!kind.equals("runs")) continue;
                    ObjectNode v = ResourcePrivacy.sanitize(n);
                    String state = n.path("state").asText();
                    if (Arrays.asList("QUEUED", "RUNNING", "STOPPING").contains(state)) {
                        v.put("state", "INTERRUPTED").put("verdict", "INCOMPLETE")
                            .put("cleanupStatus", "MANUAL_REQUIRED").put("interruptionReason", "进程退出，未自动重放请求");
                        for (String section : Arrays.asList("steps", "cleanupSteps")) for (JsonNode step : v.path(section)) {
                            if (step.isObject() && Arrays.asList("PREPARING", "DISPATCHING").contains(step.path("state").asText())) {
                                if (step.path("state").asText().equals("DISPATCHING")) ((ObjectNode)step).put("effectState", "UNKNOWN_EFFECT");
                                ((ObjectNode)step).put("state", "INTERRUPTED").put("verdict", "INCOMPLETE");
                            }
                        }
                        v.put("overallVerdict", "INCOMPLETE");
                    }
                    if (!v.equals(n)) this.save("runs", v.path("id").asText(), v);
                }
            }
        } catch (IOException | RuntimeException ex) {
            if (acquired != null) try { acquired.release(); } catch (IOException suppressed) { ex.addSuppressed(suppressed); }
            if (opened != null) try { opened.close(); } catch (IOException suppressed) { ex.addSuppressed(suppressed); }
            throw new IllegalStateException("回归数据目录不可用或已被其他进程使用", ex);
        }
    }

    private void kind(String kind) {
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("记录类型不合法");
    }

    private Path file(String kind, String id) {
        this.kind(kind);
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("记录ID不合法");
        return this.root.resolve(kind).resolve(id + ".json");
    }

    public synchronized void save(String kind, String id, JsonNode n) {
        Path f = this.file(kind, id);
        if (n == null || !n.isObject() || !n.path("id").isTextual() || !id.equals(n.path("id").asText())) throw new IllegalArgumentException("记录内容与ID不一致");
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try {
            byte[] data = Util.bytes(Util.pretty(kind.equals("runs") ? ResourcePrivacy.sanitize(n) : n));
            try (FileChannel c = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer b = ByteBuffer.wrap(data);
                while (b.hasRemaining()) c.write(b);
                c.force(true);
            }
            try { Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ex) { throw new IllegalStateException("保存回归记录失败", ex); }
    }

    private JsonNode read(String kind, Path f) throws IOException {
        JsonNode n = Util.JSON.readTree(f.toFile());
        String expected = f.getFileName().toString().replaceFirst("\\.json$", "");
        if (n == null || !n.isObject() || !n.path("id").isTextual() || !expected.equals(n.path("id").asText())
            || !expected.matches("[A-Za-z0-9_-]{1,64}")) throw new IOException("记录对象或ID无效");
        return n;
    }

    private void quarantine(String kind, Path f) {
        try {
            Path dir = this.root.resolve("quarantine").resolve(kind);
            Files.createDirectories(dir);
            Files.move(f, dir.resolve(UUID.randomUUID() + "--" + f.getFileName() + ".bad"));
        } catch (IOException ex) { throw new IllegalStateException("隔离损坏回归记录失败，原文件已保留", ex); }
    }

    public synchronized JsonNode get(String kind, String id) {
        Path f = this.file(kind, id);
        if (!Files.isRegularFile(f)) throw new NoSuchElementException();
        try {
            JsonNode n = this.read(kind, f);
            return kind.equals("runs") ? ResourcePrivacy.sanitize(n) : n;
        } catch (IOException ex) {
            this.quarantine(kind, f);
            throw new IllegalStateException("回归记录损坏，已隔离；请查看存储诊断并人工恢复", ex);
        }
    }

    public synchronized List<JsonNode> list(String kind) {
        this.kind(kind);
        try {
            List<Path> paths;
            try (Stream<Path> s = Files.list(this.root.resolve(kind))) {
                paths = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json")).collect(Collectors.toList());
            }
            List<JsonNode> values = new ArrayList<>();
            for (Path p : paths) {
                try {
                    JsonNode value = this.read(kind, p);
                    if (kind.equals("runs")) {
                        ObjectNode safe = ResourcePrivacy.sanitize(value);
                        if (!safe.equals(value)) this.save(kind, safe.path("id").asText(), safe);
                        value = safe;
                    }
                    values.add(value);
                }
                catch (IOException ex) { this.quarantine(kind, p); }
            }
            values.sort(Comparator.<JsonNode, String>comparing(n -> n.path("updatedAt").asText()).reversed()
                .thenComparing(n -> n.path("id").asText()));
            return values;
        } catch (IOException ex) { throw new IllegalStateException("读取回归记录失败", ex); }
    }

    /** Diagnostics contain location metadata only. Corrupted payloads are never returned to the browser. */
    public synchronized ObjectNode diagnostics() {
        ObjectNode result = Util.obj();
        ArrayNode records = result.putArray("quarantinedRecords");
        for (String kind : KINDS) {
            Path dir = this.root.resolve("quarantine").resolve(kind);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".bad") || !name.contains("--")) continue;
                    records.addObject().put("kind", kind).put("file", name.substring(name.indexOf("--") + 2, name.length() - 4))
                        .put("recoveryFile", "quarantine/" + kind + "/" + name)
                        .put("reason", "JSON损坏或记录ID不一致；未自动重放请求");
                }
            } catch (IOException ex) { throw new IllegalStateException("读取隔离记录诊断失败", ex); }
        }
        result.put("quarantinedCount", records.size()).put("healthy", records.size() == 0)
            .put("recovery", "先备份隔离文件，在停机且持有写锁的进程退出后修正JSON和ID，再移回原目录；损坏运行需人工核对副作用，禁止直接重放");
        return result;
    }

    public synchronized void delete(String kind, String id) {
        try { if (!Files.deleteIfExists(this.file(kind, id))) throw new NoSuchElementException(); }
        catch (IOException ex) { throw new IllegalStateException("删除失败", ex); }
    }

    @Override
    public synchronized void close() throws IOException {
        try { if (this.lock.isValid()) this.lock.release(); }
        finally { this.channel.close(); }
    }
}
