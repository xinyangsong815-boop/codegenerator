package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local immutable text snapshots. A branch moves only after all content has been persisted.
 * This is a single-process local repository, without Git remotes, staging, automatic merge,
 * encryption, permission management, garbage collection, or arbitrary disk checkout.
 */
@Component
public final class CodeRepository implements AutoCloseable, DisposableBean {
    private final Path root;
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean closed;
    public static final String MODE = "LOCAL_SNAPSHOT";

    @Autowired
    public CodeRepository(@Value("${tool.data-dir}") String dataDir) {
        this(Paths.get(dataDir).resolve("code-repositories"));
    }

    /** Opens this repository storage root and holds an exclusive OS lock until close(). */
    public CodeRepository(Path storageRoot) {
        root = storageRoot.toAbsolutePath().normalize();
        try {
            safeDirectory(root);
            Path lockPath = root.resolve(".lock");
            rejectLink(lockPath);
            lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { lock = lockChannel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new RepositoryConflict("代码版本库已由另一实例使用，请使用独立数据目录"); }
            if (lock == null) throw new RepositoryConflict("代码版本库已由另一实例使用，请使用独立数据目录");
            safeDirectory(root.resolve("projects"));
            safeDirectory(root.resolve("blobs"));
            safeDirectory(root.resolve("commits"));
        } catch (IOException failure) {
            close(); throw new IllegalStateException("代码版本库目录不可用", failure);
        } catch (RuntimeException failure) { close(); throw failure; }
    }

    /** Returns healthy projects and explicit diagnostics for unreadable metadata, without hiding other projects. */
    public synchronized ObjectNode list() {
        ensureOpen();
        ObjectNode result = Util.obj().put("mode", MODE).put("scope", "本地轻量文本快照；不提供完整Git、远程协作或磁盘检出");
        ArrayNode repositories = result.putArray("repositories"), problems = result.putArray("problems");
        try (Stream<Path> entries = Files.list(root.resolve("projects"))) {
            List<Path> paths = entries.sorted().collect(Collectors.toList());
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (!name.matches("[0-9a-f-]{36}\\.json")) continue;
                String id = name.substring(0, 36);
                try { repositories.add(project(id)); }
                catch (RepositoryCorruption | IllegalArgumentException ex) { problems.addObject().put("id", id).put("message", "项目元数据损坏；请从备份恢复，原始记录保留"); }
            }
        } catch (IOException ex) { throw storageError(ex); }
        return result;
    }

    /** Creates a project whose main branch is empty; the first commit must send expectedHead="". */
    public synchronized ObjectNode create(String name, String description) {
        ensureOpen(); label(name, 100, "项目名称"); label(description == null ? "" : description, 1000, "项目说明", true);
        ObjectNode listing = list();
        if (listing.path("repositories").size() + listing.path("problems").size() >= 1000) throw new IllegalArgumentException("最多创建1000个本地项目");
        String id = UUID.randomUUID().toString();
        ObjectNode project = Util.obj().put("schemaVersion", 1).put("id", id).put("name", name.trim())
            .put("description", description == null ? "" : description.trim()).put("createdAt", Instant.now().toString()).put("mode", MODE);
        project.putObject("branches").putNull("main");
        replace(projectPath(id), json(project)); return project;
    }

    public synchronized ObjectNode branches(String id) {
        ObjectNode project = project(id), result = Util.obj().put("repositoryId", id);
        ArrayNode rows = result.putArray("branches");
        project.path("branches").fields().forEachRemaining(e -> rows.addObject().put("name", e.getKey()).set("head", e.getValue()));
        return result;
    }

    /** Creates an independent branch at an existing commit, or an empty branch when fromCommit is blank. */
    public synchronized ObjectNode createBranch(String id, String name, String fromCommit) {
        branchName(name); ObjectNode project = project(id); ObjectNode refs = (ObjectNode)project.path("branches");
        if (refs.has(name)) throw new RepositoryConflict("该分支已存在");
        if (refs.size() >= 100) throw new IllegalArgumentException("每个项目最多100个分支");
        if (!Util.isBlank(fromCommit)) { commit(id, fromCommit); refs.put(name, fromCommit); }
        else refs.putNull(name);
        replace(projectPath(id), json(project));
        return Util.obj().put("name", name).set("head", refs.path(name));
    }

    /** Lists first-parent history in pages; hasMore/nextOffset explicitly indicate remaining history. */
    public synchronized ObjectNode history(String id, String branch, int offset, int limit) {
        if (offset < 0 || offset > 100000 || limit < 1 || limit > 500) throw new IllegalArgumentException("历史分页范围错误：offset为0至100000，limit为1至500");
        String head = head(project(id), branch), current = head;
        ObjectNode out = Util.obj().put("repositoryId", id).put("branch", branch);
        nullable(out, "head", head); ArrayNode rows = out.putArray("commits");
        Set<String> seen = new HashSet<>(); int index = 0;
        while (current != null && index < offset + limit) {
            if (!seen.add(current)) throw corrupt();
            ObjectNode entry;
            try { entry = commit(id, current); } catch (NoSuchElementException missing) { throw corrupt(); }
            if (index++ >= offset) rows.add(summary(current, entry));
            current = nullText(entry.get("parent"));
        }
        out.put("hasMore", current != null);
        if (current == null) out.putNull("nextOffset"); else out.put("nextOffset", offset + rows.size());
        return out;
    }

    /** Saves the complete file set, including deletions by omission; never changes files on the caller's disk. */
    public synchronized ObjectNode commitText(String id, String branch, String expectedHead, String message, JsonNode files) {
        Map<String, byte[]> textFiles = SourceFiles.text(files);
        files.fields().forEachRemaining(e -> { if (!SourceFiles.utf8(textFiles.get(e.getKey())).equals(e.getValue().asText())) throw new IllegalArgumentException("文本含无效Unicode字符"); });
        return save(id, branch, expectedHead, message, textFiles, null);
    }

    /** Imports a complete UTF-8 source snapshot; binary files, credentials and unsafe paths are rejected as a whole. */
    public synchronized ObjectNode importZip(String id, String branch, String expectedHead, String message, byte[] bytes) {
        return importZip(id, branch, expectedHead, message, bytes, false);
    }

    /** Preserves all archive paths by default; wrapper removal is an explicit, validated user choice. */
    public synchronized ObjectNode importZip(String id, String branch, String expectedHead, String message, byte[] bytes, boolean stripSingleRoot) {
        Map<String, byte[]> files = ZipFiles.read(bytes);
        // Check before wrapper stripping, otherwise a ZIP containing only .git/config would become config.
        for (String path : files.keySet()) sourcePath(path);
        if (stripSingleRoot) {
            String prefix = null;
            for (String path : files.keySet()) {
                int slash = path.indexOf('/');
                if (slash < 1) throw new IllegalArgumentException("剥除ZIP顶层目录需要所有文件位于同一个顶层目录；ZIP含根目录文件");
                String candidate = path.substring(0, slash + 1);
                if (prefix == null) prefix = candidate;
                else if (!prefix.equals(candidate)) throw new IllegalArgumentException("剥除ZIP顶层目录需要唯一顶层目录；ZIP含多个顶层目录");
            }
            Map<String, byte[]> stripped = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> file : files.entrySet()) stripped.put(file.getKey().substring(prefix.length()), file.getValue());
            files = stripped;
        }
        return save(id, branch, expectedHead, message, files, null);
    }

    /** Restores by making a new commit; the previous branch head and all historical objects remain intact. */
    public synchronized ObjectNode restore(String id, String branch, String expectedHead, String commitId, String message) {
        ObjectNode original = commit(id, commitId);
        return save(id, branch, expectedHead, message, contents(original), commitId);
    }

    public synchronized ObjectNode details(String id, String hash) {
        ObjectNode commit = commit(id, hash), out = summary(hash, commit); ArrayNode rows = out.putArray("files");
        commit.path("files").fields().forEachRemaining(e -> rows.addObject().put("path", e.getKey()).put("sha256", e.getValue().asText()).put("size", blob(e.getValue().asText()).length));
        return out;
    }

    public synchronized String file(String id, String hash, String path) {
        sourcePath(path); ObjectNode commit = commit(id, hash);
        if (!commit.path("files").has(path)) throw new NoSuchElementException("快照中文件不存在");
        return SourceFiles.utf8(blob(commit.path("files").path(path).asText()));
    }

    public synchronized byte[] archive(String id, String hash) { return ZipFiles.write(contents(commit(id, hash))); }

    /** Includes every changed path; text previews are capped and clearly marked, full originals remain downloadable. */
    public synchronized ObjectNode diff(String id, String baseHash, String targetHash) {
        ObjectNode before = commit(id, baseHash), after = commit(id, targetHash);
        Map<String, byte[]> a = contents(before), b = contents(after);
        ObjectNode out = Util.obj().put("baseCommit", baseHash).put("targetCommit", targetHash);
        ArrayNode rows = out.putArray("files"); Set<String> paths = new TreeSet<>(a.keySet()); paths.addAll(b.keySet());
        int added = 0, modified = 0, deleted = 0; long budget = 0; boolean complete = true;
        for (String path : paths) {
            String oldHash = before.path("files").path(path).asText(null), newHash = after.path("files").path(path).asText(null);
            if (Objects.equals(oldHash, newHash)) continue;
            String status = oldHash == null ? "ADDED" : newHash == null ? "DELETED" : "MODIFIED";
            if (oldHash == null) added++; else if (newHash == null) deleted++; else modified++;
            ObjectNode row = rows.addObject().put("path", path).put("status", status);
            nullable(row, "baseSha256", oldHash); nullable(row, "targetSha256", newHash);
            long size = (a.containsKey(path) ? a.get(path).length : 0) + (b.containsKey(path) ? b.get(path).length : 0);
            if (size <= 262144 && budget + size <= 1048576) {
                nullable(row, "before", a.containsKey(path) ? SourceFiles.utf8(a.get(path)) : null);
                nullable(row, "after", b.containsKey(path) ? SourceFiles.utf8(b.get(path)) : null);
                row.put("contentIncluded", true); budget += size;
            } else { row.put("contentIncluded", false); complete = false; }
        }
        return out.put("added", added).put("modified", modified).put("deleted", deleted).put("contentIncluded", complete)
            .put("scope", "文件完整新增、删除、修改清单；仅比较文本，不证明接口或业务兼容。单文件对预览256KiB，总预览1MiB，超限正文可单独查看或下载");
    }

    private ObjectNode save(String id, String branch, String expectedHead, String message, Map<String, byte[]> input, String restoredFrom) {
        label(message, 1000, "提交说明"); ObjectNode project = project(id); String previous = head(project, branch);
        if (expectedHead == null) throw new IllegalArgumentException("请提供expectedHead；空分支传空字符串");
        if (!Objects.equals(previous, expectedHead.isEmpty() ? null : expectedHead)) throw new RepositoryConflict("分支已有新提交，请刷新历史后重新确认提交");
        if (previous != null) { try { commit(id, previous); } catch (NoSuchElementException missing) { throw corrupt(); } }
        Map<String, byte[]> files = validated(input);
        ObjectNode record = Util.obj().put("schemaVersion", 1).put("repositoryId", id).put("message", message.trim()).put("createdAt", Instant.now().toString());
        nullable(record, "parent", previous); nullable(record, "restoredFrom", restoredFrom); ObjectNode hashes = record.putObject("files");
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            String hash = Util.sha(file.getValue()); immutable(root.resolve("blobs").resolve(hash), file.getValue()); hashes.put(file.getKey(), hash);
        }
        byte[] bytes = json(record); String commitHash = Util.sha(bytes); immutable(root.resolve("commits").resolve(commitHash + ".json"), bytes);
        ((ObjectNode)project.path("branches")).put(branch, commitHash); replace(projectPath(id), json(project));
        return Util.obj().put("branch", branch).put("head", commitHash).set("commit", summary(commitHash, record));
    }

    private ObjectNode project(String id) {
        ensureOpen(); projectId(id); Path path = projectPath(id);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchElementException("代码项目不存在");
        try {
            JsonNode value = Util.parse(read(path, 5242880));
            if (!value.isObject() || !value.path("schemaVersion").canConvertToInt() || value.path("schemaVersion").asInt() != 1 || !value.path("schemaVersion").isIntegralNumber()
                || !id.equals(value.path("id").asText()) || !value.path("branches").isObject() || value.path("branches").size() < 1 || value.path("branches").size() > 100) throw corrupt();
            if (!value.path("name").isTextual() || !value.path("description").isTextual() || !value.path("createdAt").isTextual()) throw corrupt();
            label(value.path("name").asText(null), 100, "项目名称"); label(value.path("description").asText(null), 1000, "项目说明", true);
            Instant.parse(value.path("createdAt").asText());
            value.path("branches").fields().forEachRemaining(e -> { branchName(e.getKey()); if (!e.getValue().isNull()) { if (!e.getValue().isTextual()) throw corrupt(); hash(e.getValue().asText()); } });
            return (ObjectNode)value;
        } catch (RepositoryCorruption ex) { throw ex; }
        catch (RuntimeException ex) { throw corrupt(); }
    }

    private ObjectNode commit(String id, String hash) {
        projectId(id); hash(hash); project(id); Path path = root.resolve("commits").resolve(hash + ".json");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchElementException("代码提交不存在");
        byte[] bytes = read(path, 5242880); if (!hash.equals(Util.sha(bytes))) throw corrupt();
        try {
            JsonNode record = Util.parse(bytes);
            if (!record.isObject() || !id.equals(record.path("repositoryId").asText()) || !record.path("files").isObject()
                || !record.path("schemaVersion").isIntegralNumber() || !record.path("schemaVersion").canConvertToInt() || record.path("schemaVersion").asInt() != 1) throw corrupt();
            if (!record.has("parent") || !record.has("restoredFrom")) throw corrupt();
            if (!record.path("parent").isNull()) { if (!record.path("parent").isTextual()) throw corrupt(); hash(record.path("parent").asText()); }
            if (!record.path("restoredFrom").isNull()) { if (!record.path("restoredFrom").isTextual()) throw corrupt(); hash(record.path("restoredFrom").asText()); }
            if (!record.path("message").isTextual() || !record.path("createdAt").isTextual()) throw corrupt();
            label(record.path("message").asText(null), 1000, "提交说明"); Instant.parse(record.path("createdAt").asText());
            Map<String, byte[]> files = new LinkedHashMap<>();
            record.path("files").fields().forEachRemaining(e -> { if (!e.getValue().isTextual()) throw corrupt(); hash(e.getValue().asText()); files.put(e.getKey(), blob(e.getValue().asText())); });
            validated(files); return (ObjectNode)record;
        } catch (RepositoryCorruption ex) { throw ex; }
        catch (RuntimeException ex) { throw corrupt(); }
    }

    private byte[] blob(String hash) {
        hash(hash); byte[] bytes = read(root.resolve("blobs").resolve(hash), 5242880);
        if (!hash.equals(Util.sha(bytes))) throw corrupt(); return bytes;
    }

    private Map<String, byte[]> contents(ObjectNode record) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        record.path("files").fields().forEachRemaining(e -> files.put(e.getKey(), blob(e.getValue().asText()))); return files;
    }

    private Map<String, byte[]> validated(Map<String, byte[]> files) {
        if (files == null || files.isEmpty() || files.size() > 1000) throw new IllegalArgumentException("快照必须包含1至1000个文本文件");
        long total = 0; Set<String> names = new HashSet<>(); Map<String, byte[]> sorted = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            sourcePath(entry.getKey()); byte[] bytes = entry.getValue();
            if (bytes == null || bytes.length > 5242880 || (total += bytes.length) > 20971520L) throw new IllegalArgumentException("快照单文件最大5MiB，总正文最大20MiB");
            String text = SourceFiles.utf8(bytes); if (text.indexOf(0) >= 0) throw new IllegalArgumentException("快照仅支持UTF-8文本，请移除二进制文件");
            if (!names.add(entry.getKey().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("快照含大小写冲突路径");
            sorted.put(entry.getKey(), bytes.clone());
        }
        for (String path : names) { int slash = path.indexOf('/'); while (slash >= 0) { if (names.contains(path.substring(0, slash))) throw new IllegalArgumentException("快照含文件与目录冲突"); slash = path.indexOf('/', slash + 1); } }
        return sorted;
    }

    private static void sourcePath(String path) {
        SourceFiles.path(path);
        if (!SourceFiles.utf8(Util.bytes(path)).equals(path)) throw new IllegalArgumentException("文件路径含无效Unicode字符");
        for (String segment : path.split("/")) {
            String name = segment.toLowerCase(Locale.ROOT);
            if (name.endsWith(".") || name.endsWith(" ") || name.matches(".*[<>\"|?*\\x7f].*")
                || name.matches("(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) throw new IllegalArgumentException("文件路径不兼容Windows/Linux");
            if (name.equals(".git") || name.equals(".ssh") || name.equals(".aws") || name.equals(".azure") || name.equals(".kube")
                || name.equals(".env") || (name.startsWith(".env.") && !name.equals(".env.example") && !name.equals(".env.template"))
                || name.matches("\\.(npmrc|pypirc|netrc|git-credentials|docker)") || name.matches("(id_rsa|id_dsa|id_ecdsa|id_ed25519)(\\..*)?")
                || name.equals("credentials") || name.equals("credentials.json") || name.equals("service-account.json")
                || name.matches(".*\\.(key|pem|p12|pfx|jks|keystore)")) throw new IllegalArgumentException("快照拒绝版本库内部目录或常见凭据文件：" + path);
        }
    }

    private ObjectNode summary(String id, ObjectNode commit) {
        ObjectNode out = Util.obj().put("id", id).put("message", commit.path("message").asText()).put("createdAt", commit.path("createdAt").asText()).put("fileCount", commit.path("files").size());
        out.set("parent", commit.path("parent")); out.set("restoredFrom", commit.path("restoredFrom")); return out;
    }
    private String head(ObjectNode project, String branch) {
        branchName(branch); if (!project.path("branches").has(branch)) throw new NoSuchElementException("代码分支不存在");
        return nullText(project.path("branches").get(branch));
    }
    private static String nullText(JsonNode node) { return node == null || node.isNull() ? null : node.asText(); }
    private static void nullable(ObjectNode node, String key, String value) { if (value == null) node.putNull(key); else node.put(key, value); }
    private static void branchName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}") || name.contains("..") || name.endsWith(".") || name.endsWith(".lock")) throw new IllegalArgumentException("分支名须为1至80位字母、数字、点、短横线或下划线，且不得含连续点");
    }
    private static void projectId(String id) { if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw new IllegalArgumentException("非法代码项目ID"); }
    private static void hash(String hash) { if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("非法代码提交或内容哈希"); }
    private static void label(String value, int max, String field) { label(value, max, field, false); }
    private static void label(String value, int max, String field, boolean empty) {
        if (value == null || (!empty && value.trim().isEmpty()) || value.length() > max || value.matches("(?s).*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f].*")) throw new IllegalArgumentException(field + "长度或格式错误（最大" + max + "字）");
    }
    private Path projectPath(String id) { return root.resolve("projects").resolve(id + ".json"); }
    private byte[] json(ObjectNode value) { return Util.bytes(Util.pretty(value)); }

    private byte[] read(Path path, long max) {
        ensureOpen();
        try {
            rejectLink(path); rejectLink(path.getParent());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > max) throw corrupt();
            return Files.readAllBytes(path);
        } catch (IOException ex) { throw corrupt(); }
    }
    private void immutable(Path destination, byte[] bytes) {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (!java.util.Arrays.equals(read(destination, Math.max(bytes.length, 1)), bytes)) throw corrupt(); return;
        }
        persist(destination, bytes, false);
    }
    private void replace(Path destination, byte[] bytes) { persist(destination, bytes, true); }
    private void persist(Path destination, byte[] bytes, boolean replace) {
        ensureOpen(); Path temp = destination.getParent().resolve(".tmp-" + UUID.randomUUID());
        try {
            rejectLink(destination.getParent()); rejectLink(destination);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            try {
                if (replace) Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Refuse to advance a branch if this storage cannot guarantee atomic metadata replacement.
                throw new IllegalStateException("版本库所在文件系统不支持原子写入，请迁移到本地受支持文件系统", unsupported);
            }
        } catch (IOException ex) { throw storageError(ex); }
        finally { try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* A crash-safe orphan temp file is not a published commit. */ } }
    }
    private static void safeDirectory(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) rejectLink(current);
        Files.createDirectories(path); rejectLink(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a directory");
    }
    private static void rejectLink(Path path) { if (path != null && Files.isSymbolicLink(path)) throw new RepositoryCorruption("版本库拒绝符号链接，请恢复安全的本地目录"); }
    private static RepositoryCorruption corrupt() { return new RepositoryCorruption("代码版本库内容缺失或完整性校验失败；未覆盖历史，请从可信备份恢复"); }
    private static IllegalStateException storageError(IOException cause) { return new IllegalStateException("代码版本库读写失败；请检查本地存储和备份", cause); }
    private void ensureOpen() { if (closed || lock == null || !lock.isValid()) throw new IllegalStateException("代码版本库未打开"); }
    @Override public synchronized void destroy() { close(); }
    @Override public synchronized void close() {
        closed = true;
        try { if (lock != null && lock.isValid()) lock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
    }
    public static final class RepositoryConflict extends RuntimeException { public RepositoryConflict(String message) { super(message); } }
    public static final class RepositoryCorruption extends RuntimeException { public RepositoryCorruption(String message) { super(message); } }
}
