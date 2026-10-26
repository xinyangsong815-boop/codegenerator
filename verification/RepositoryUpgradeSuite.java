package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Executes real persistence, corruption, branch, concurrency and ZIP checks on the distribution's actual JDK. */
public final class RepositoryUpgradeSuite {
    private static final List<Map<String, Object>> results = new ArrayList<>();
    private static Path output;
    interface Check { void run() throws Exception; }
    static void check(String name, Check test) {
        try { test.run(); results.add(Util.mapOf("name", name, "passed", true)); }
        catch (Throwable error) { results.add(Util.mapOf("name", name, "passed", false, "error", error.toString())); error.printStackTrace(); }
    }
    static void require(boolean value) { if (!value) throw new AssertionError("Requirement failed"); }
    static void fails(Class<? extends Throwable> type, Check action) throws Exception {
        try { action.run(); } catch (Throwable failure) { if (type.isInstance(failure)) return; throw new AssertionError("Unexpected " + failure, failure); }
        throw new AssertionError("Expected " + type.getName());
    }
    static Path area(String name) throws Exception { return Files.createTempDirectory(output, name + "-"); }
    static String project(CodeRepository repo) { return repo.create("合同模块", "本地测试").path("id").asText(); }
    static ObjectNode files(String... pairs) { ObjectNode node = Util.obj(); for (int i = 0; i < pairs.length; i += 2) node.put(pairs[i], pairs[i + 1]); return node; }
    static String save(CodeRepository repo, String id, String expected, ObjectNode files) { return repo.commitText(id, "main", expected, "修改说明", files).path("head").asText(); }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--lock-probe")) {
            try (CodeRepository ignored = new CodeRepository(Paths.get(args[1]))) { System.exit(0); }
            catch (CodeRepository.RepositoryConflict expected) { System.exit(73); }
            return;
        }
        String platform = args[0]; output = Paths.get(args[1]).toAbsolutePath(); Files.createDirectories(output);
        check("empty-main-project-and-local-snapshot-scope", () -> {
            try (CodeRepository repo = new CodeRepository(area("create"))) {
                String id = project(repo); require(repo.list().path("mode").asText().equals("LOCAL_SNAPSHOT"));
                require(repo.branches(id).path("branches").get(0).path("head").isNull());
                require(repo.history(id, "main", 0, 100).path("commits").isEmpty());
            }
        });
        check("immutable-history-persists-after-close-and-reopen", () -> {
            Path path = area("restart"); String id, first, second;
            try (CodeRepository repo = new CodeRepository(path)) {
                id = project(repo); first = save(repo, id, "", files("src/X.java", "public class X {}\n"));
                second = save(repo, id, first, files("src/X.java", "public class X { int x; }\n"));
            }
            try (CodeRepository repo = new CodeRepository(path)) {
                JsonNode history = repo.history(id, "main", 0, 100); require(history.path("commits").size() == 2);
                require(history.path("head").asText().equals(second)); require(repo.file(id, first, "src/X.java").equals("public class X {}\n"));
            }
        });
        check("branch-changes-are-independent", () -> {
            try (CodeRepository repo = new CodeRepository(area("branch"))) {
                String id = project(repo), first = save(repo, id, "", files("X.java", "A")); repo.createBranch(id, "feature-test", first);
                String feature = repo.commitText(id, "feature-test", first, "功能分支", files("X.java", "B")).path("head").asText();
                require(!feature.equals(first)); require(repo.history(id, "main", 0, 100).path("head").asText().equals(first));
                require(repo.history(id, "feature-test", 0, 100).path("commits").size() == 2);
                fails(CodeRepository.RepositoryConflict.class, () -> repo.createBranch(id, "feature-test", first));
            }
        });
        check("stale-and-absent-head-preconditions-do-not-overwrite", () -> {
            try (CodeRepository repo = new CodeRepository(area("head"))) {
                String id = project(repo), first = save(repo, id, "", files("X.java", "A"));
                fails(CodeRepository.RepositoryConflict.class, () -> save(repo, id, "", files("X.java", "B")));
                fails(IllegalArgumentException.class, () -> save(repo, id, null, files("X.java", "B")));
                require(repo.history(id, "main", 0, 100).path("head").asText().equals(first));
            }
        });
        check("concurrent-expected-head-allows-exactly-one-writer", () -> {
            try (CodeRepository repo = new CodeRepository(area("concurrent"))) {
                final String id = project(repo), head = save(repo, id, "", files("X.java", "A")); CountDownLatch start = new CountDownLatch(1);
                ExecutorService executor = Executors.newFixedThreadPool(2);
                try {
                    List<Future<Boolean>> answers = new ArrayList<>();
                    for (int i = 0; i < 2; i++) { final String text = "change-" + i; answers.add(executor.submit(() -> {
                        start.await(); try { save(repo, id, head, files("X.java", text)); return true; }
                        catch (CodeRepository.RepositoryConflict expected) { return false; }
                    })); }
                    start.countDown(); int winners = 0; for (Future<Boolean> answer : answers) if (answer.get(20, TimeUnit.SECONDS)) winners++;
                    require(winners == 1); require(repo.history(id, "main", 0, 100).path("commits").size() == 2);
                } finally { executor.shutdownNow(); }
            }
        });
        check("operating-system-lock-blocks-a-second-jvm-and-releases", () -> {
            Path path = area("process-lock");
            try (CodeRepository repo = new CodeRepository(path)) {
                fails(CodeRepository.RepositoryConflict.class, () -> new CodeRepository(path));
                String executable = Paths.get(System.getProperty("java.home"), "bin", "java" + (System.getProperty("os.name").contains("Windows") ? ".exe" : "")).toString();
                Process child = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"), RepositoryUpgradeSuite.class.getName(), "--lock-probe", path.toString())
                    .redirectErrorStream(true).redirectOutput(output.resolve("lock-child.log").toFile()).start();
                require(child.waitFor(20, TimeUnit.SECONDS)); require(child.exitValue() == 73);
            }
            try (CodeRepository repo = new CodeRepository(path)) { require(repo.list().path("repositories").isEmpty()); }
        });
        check("restore-is-new-commit-and-preserves-old-history", () -> {
            try (CodeRepository repo = new CodeRepository(area("restore"))) {
                String id = project(repo), first = save(repo, id, "", files("X.java", "A", "old.txt", "keep"));
                String second = save(repo, id, first, files("X.java", "B", "new.txt", "added"));
                String restored = repo.restore(id, "main", second, first, "恢复初版").path("head").asText();
                JsonNode details = repo.details(id, restored); require(details.path("parent").asText().equals(second)); require(details.path("restoredFrom").asText().equals(first));
                require(repo.file(id, restored, "old.txt").equals("keep")); require(repo.file(id, second, "X.java").equals("B"));
                require(repo.history(id, "main", 0, 100).path("commits").size() == 3);
                require(ZipFiles.read(repo.archive(id, restored)).keySet().equals(ZipFiles.read(repo.archive(id, first)).keySet()));
            }
        });
        check("diff-covers-added-deleted-modified-and-unchanged", () -> {
            try (CodeRepository repo = new CodeRepository(area("diff"))) {
                String id = project(repo), a = save(repo, id, "", files("same.txt", "same", "change.txt", "before", "remove.txt", "remove"));
                String b = save(repo, id, a, files("same.txt", "same", "change.txt", "after", "add.txt", "add"));
                JsonNode diff = repo.diff(id, a, b); require(diff.path("added").asInt() == 1 && diff.path("modified").asInt() == 1 && diff.path("deleted").asInt() == 1);
                require(diff.path("files").size() == 3); require(diff.path("contentIncluded").asBoolean());
                require(diff.path("files").get(1).path("before").asText().equals("before"));
            }
        });
        check("diff-keeps-full-path-list-when-preview-is-too-large", () -> {
            try (CodeRepository repo = new CodeRepository(area("preview"))) {
                String large = repeat('x', 300000), id = project(repo), a = save(repo, id, "", files("large.txt", large));
                String b = save(repo, id, a, files("large.txt", large + "y", "small.txt", "new"));
                JsonNode diff = repo.diff(id, a, b); require(diff.path("files").size() == 2); require(!diff.path("contentIncluded").asBoolean());
                require(!diff.path("files").get(0).path("contentIncluded").asBoolean()); require(diff.path("files").get(1).path("contentIncluded").asBoolean());
                require(repo.file(id, b, "large.txt").equals(large + "y"));
            }
        });
        check("history-pagination-is-explicit-and-complete", () -> {
            try (CodeRepository repo = new CodeRepository(area("pagination"))) {
                String id = project(repo), a = save(repo, id, "", files("X.java", "A")); String b = save(repo, id, a, files("X.java", "B")); save(repo, id, b, files("X.java", "C"));
                JsonNode page1 = repo.history(id, "main", 0, 2), page2 = repo.history(id, "main", 2, 2);
                require(page1.path("hasMore").asBoolean() && page1.path("nextOffset").asInt() == 2); require(page2.path("commits").size() == 1 && !page2.path("hasMore").asBoolean());
                require(page2.path("commits").get(0).path("id").asText().equals(a));
            }
        });
        check("text-path-traversal-and-platform-aliases-are-rejected", () -> {
            try (CodeRepository repo = new CodeRepository(area("paths"))) {
                String id = project(repo);
                for (String path : Arrays.asList("../escape.java", "/root.java", "C:/escape.java", "a\\b.java", "a/./b", "a//b", "a/../b", "a\u0000.java", "NUL.txt", "a./x", "a /x", "dir/a?.txt", "\ud800.txt"))
                    fails(IllegalArgumentException.class, () -> save(repo, id, "", files(path, "x")));
                require(repo.history(id, "main", 0, 100).path("head").isNull());
            }
        });
        check("case-collision-and-file-directory-conflict-are-rejected", () -> {
            try (CodeRepository repo = new CodeRepository(area("collisions"))) {
                String id = project(repo);
                fails(IllegalArgumentException.class, () -> save(repo, id, "", files("src/X.java", "A", "src/x.java", "B")));
                fails(IllegalArgumentException.class, () -> save(repo, id, "", files("src", "A", "src/X.java", "B")));
            }
        });
        check("git-internal-and-common-credential-files-are-rejected", () -> {
            try (CodeRepository repo = new CodeRepository(area("credentials"))) {
                String id = project(repo);
                for (String path : Arrays.asList(".git/config", "wrapper/.git/index", ".env", ".env.prod", ".ssh/id_rsa", ".aws/credentials", "keys/server.key", "cert.pem", "a.p12", "key.jks", ".npmrc", ".git-credentials", ".docker/config.json", "credentials.json"))
                    fails(IllegalArgumentException.class, () -> save(repo, id, "", files(path, "synthetic")));
                save(repo, id, "", files(".gitignore", "target/", ".env.example", "PASSWORD=replace-me"));
            }
        });
        check("zip-import-validates-before-project-wrapper-stripping", () -> {
            try (CodeRepository repo = new CodeRepository(area("zip-internal"))) {
                String id = project(repo); Map<String, byte[]> data = new LinkedHashMap<>(); data.put(".git/config", Util.bytes("synthetic"));
                fails(IllegalArgumentException.class, () -> repo.importZip(id, "main", "", "禁止内部目录", ZipFiles.write(data)));
                fails(IllegalArgumentException.class, () -> repo.importZip(id, "main", "", "禁止剥根绕过内部目录", ZipFiles.write(data), true));
                data.clear(); data.put("wrapper/.env", Util.bytes("synthetic"));
                fails(IllegalArgumentException.class, () -> repo.importZip(id, "main", "", "禁止环境文件", ZipFiles.write(data)));
                fails(IllegalArgumentException.class, () -> repo.importZip(id, "main", "", "禁止剥根绕过环境文件", ZipFiles.write(data), true));
            }
        });
        check("zip-import-roundtrips-nested-text-files-and-deletions", () -> {
            try (CodeRepository repo = new CodeRepository(area("zip-roundtrip"))) {
                String id = project(repo), first = save(repo, id, "", files("old.txt", "old")); Map<String, byte[]> data = new LinkedHashMap<>();
                data.put("project/pom.xml", Util.bytes("<project/>")); data.put("project/src/X.java", Util.bytes("// 中文\nclass X {}"));
                String hash = repo.importZip(id, "main", first, "显式剥除项目包裹目录", ZipFiles.write(data), true).path("head").asText();
                Map<String, byte[]> roundtrip = ZipFiles.read(repo.archive(id, hash)); require(roundtrip.size() == 2 && !roundtrip.containsKey("old.txt"));
                require(Arrays.equals(roundtrip.get("src/X.java"), data.get("project/src/X.java"))); require(repo.diff(id, first, hash).path("deleted").asInt() == 1);
            }
        });
        check("single-source-directory-export-import-preserves-all-relative-paths", () -> {
            try (CodeRepository repo = new CodeRepository(area("zip-source-directory"))) {
                String id = project(repo), first = save(repo, id, "", files("src/X.java", "class X {}"));
                byte[] exported = repo.archive(id, first);
                String second = repo.importZip(id, "main", first, "重新导入原快照", exported).path("head").asText();
                require(repo.file(id, second, "src/X.java").equals("class X {}"));
                require(repo.diff(id, first, second).path("files").isEmpty());
                require(Arrays.equals(exported, repo.archive(id, second)));
            }
        });
        check("default-import-preserves-multiple-directories-and-root-files", () -> {
            try (CodeRepository repo = new CodeRepository(area("zip-multiple-roots"))) {
                String id = project(repo); Map<String, byte[]> data = new LinkedHashMap<>();
                data.put("src/X.java", Util.bytes("class X {}")); data.put("docs/readme.md", Util.bytes("# docs")); data.put("pom.xml", Util.bytes("<project/>"));
                String hash = repo.importZip(id, "main", "", "默认保留路径", ZipFiles.write(data)).path("head").asText();
                require(ZipFiles.read(repo.archive(id, hash)).keySet().equals(data.keySet()));
                require(repo.file(id, hash, "src/X.java").equals("class X {}"));
            }
        });
        check("explicit-strip-rejects-multiple-roots-and-root-files-without-publishing", () -> {
            try (CodeRepository repo = new CodeRepository(area("zip-bad-strip"))) {
                String id = project(repo); Map<String, byte[]> data = new LinkedHashMap<>();
                data.put("src/X.java", Util.bytes("class X {}")); data.put("docs/readme.md", Util.bytes("# docs"));
                fails(IllegalArgumentException.class, () -> repo.importZip(id, "main", "", "多个目录不可剥根", ZipFiles.write(data), true));
                data.clear(); data.put("wrapper/src/X.java", Util.bytes("class X {}")); data.put("pom.xml", Util.bytes("<project/>"));
                fails(IllegalArgumentException.class, () -> repo.importZip(id, "main", "", "根文件不可剥根", ZipFiles.write(data), true));
                require(repo.history(id, "main", 0, 100).path("head").isNull());
            }
        });
        check("zip-traversal-invalid-utf8-and-binary-text-are-rejected", () -> {
            try (CodeRepository repo = new CodeRepository(area("bad-zip"))) {
                String id = project(repo); Map<String, byte[]> data = new LinkedHashMap<>(); data.put("../escape", Util.bytes("x"));
                fails(IllegalArgumentException.class, () -> repo.importZip(id, "main", "", "越界", ZipFiles.write(data)));
                data.clear(); data.put("bad.java", new byte[]{(byte)0xc3, (byte)0x28});
                fails(IllegalArgumentException.class, () -> repo.importZip(id, "main", "", "无效编码", ZipFiles.write(data)));
                fails(IllegalArgumentException.class, () -> save(repo, id, "", files("binary.txt", "a\u0000b")));
                fails(IllegalArgumentException.class, () -> save(repo, id, "", files("bad.txt", "\ud800")));
            }
        });
        check("file-count-and-size-limits-do-not-publish-a-head", () -> {
            try (CodeRepository repo = new CodeRepository(area("limits"))) {
                String id = project(repo); ObjectNode many = Util.obj(); for (int i = 0; i < 1001; i++) many.put("f" + i + ".txt", "x");
                fails(IllegalArgumentException.class, () -> save(repo, id, "", many));
                fails(IllegalArgumentException.class, () -> save(repo, id, "", files("big.txt", repeat('x', 5242881))));
                fails(IllegalArgumentException.class, () -> save(repo, id, "", Util.obj()));
                require(repo.history(id, "main", 0, 100).path("head").isNull());
            }
        });
        check("blob-tampering-is-explicit-and-blocks-export-and-restore", () -> {
            Path path = area("blob-corrupt");
            try (CodeRepository repo = new CodeRepository(path)) {
                String id = project(repo), first = save(repo, id, "", files("X.java", "original"));
                Files.write(path.resolve("blobs").resolve(Util.sha(Util.bytes("original"))), Util.bytes("tampered"));
                fails(CodeRepository.RepositoryCorruption.class, () -> repo.file(id, first, "X.java"));
                fails(CodeRepository.RepositoryCorruption.class, () -> repo.archive(id, first));
                fails(CodeRepository.RepositoryCorruption.class, () -> repo.restore(id, "main", first, first, "不可恢复坏版本"));
            }
        });
        check("commit-tampering-is-detected-after-restart", () -> {
            Path path = area("commit-corrupt"); String id, first;
            try (CodeRepository repo = new CodeRepository(path)) { id = project(repo); first = save(repo, id, "", files("X.java", "original")); }
            Files.write(path.resolve("commits").resolve(first + ".json"), Util.bytes("{}"));
            try (CodeRepository repo = new CodeRepository(path)) { fails(CodeRepository.RepositoryCorruption.class, () -> repo.history(id, "main", 0, 100)); }
        });
        check("missing-referenced-commit-is-not-a-successful-history", () -> {
            Path path = area("commit-missing");
            try (CodeRepository repo = new CodeRepository(path)) {
                String id = project(repo), first = save(repo, id, "", files("X.java", "original"));
                Files.delete(path.resolve("commits").resolve(first + ".json"));
                fails(CodeRepository.RepositoryCorruption.class, () -> repo.history(id, "main", 0, 100));
                fails(CodeRepository.RepositoryCorruption.class, () -> save(repo, id, first, files("X.java", "next")));
            }
        });
        check("broken-metadata-does-not-hide-healthy-projects", () -> {
            Path path = area("metadata");
            try (CodeRepository repo = new CodeRepository(path)) {
                String damaged = project(repo), healthy = project(repo); Files.write(path.resolve("projects").resolve(damaged + ".json"), Util.bytes("{"));
                JsonNode list = repo.list(); require(list.path("repositories").size() == 1 && list.path("problems").size() == 1);
                require(list.path("repositories").get(0).path("id").asText().equals(healthy));
                fails(CodeRepository.RepositoryCorruption.class, () -> repo.branches(damaged));
            }
        });
        check("metadata-schema-version-does-not-wrap-large-integers", () -> {
            Path path = area("metadata-integer");
            try (CodeRepository repo = new CodeRepository(path)) {
                String id = project(repo); Path metadata = path.resolve("projects").resolve(id + ".json");
                JsonNode value = Util.parse(Files.readAllBytes(metadata)); ((ObjectNode)value).put("schemaVersion", 4294967297L); Files.write(metadata, Util.bytes(Util.pretty(value)));
                fails(CodeRepository.RepositoryCorruption.class, () -> repo.branches(id));
            }
        });
        check("cross-project-commit-access-is-rejected", () -> {
            try (CodeRepository repo = new CodeRepository(area("cross-project"))) {
                String a = project(repo), b = project(repo), hash = save(repo, a, "", files("X.java", "A"));
                fails(CodeRepository.RepositoryCorruption.class, () -> repo.archive(b, hash));
                fails(CodeRepository.RepositoryCorruption.class, () -> repo.createBranch(b, "other", hash));
            }
        });
        check("closed-repository-rejects-operations", () -> {
            CodeRepository repo = new CodeRepository(area("closed")); String id = project(repo); repo.close(); repo.close();
            fails(IllegalStateException.class, repo::list); fails(IllegalStateException.class, () -> repo.branches(id));
        });
        check("controller-uses-409-and-safe-file-response", () -> {
            try (CodeRepository repo = new CodeRepository(area("http"))) {
                RepositoryController controller = new RepositoryController(repo); String id = project(repo), hash = save(repo, id, "", files("page.html", "<script>bad()</script>"));
                org.springframework.http.ResponseEntity<String> response = controller.file(id, hash, "page.html");
                require(response.getHeaders().getContentType().toString().startsWith("text/plain")); require(response.getHeaders().getFirst("X-Content-Type-Options").equals("nosniff"));
                require(controller.conflict(new CodeRepository.RepositoryConflict("刷新后重试")).getStatusCodeValue() == 409);
                require(controller.corrupt(new CodeRepository.RepositoryCorruption("内容错误")).getStatusCodeValue() == 409);
            }
        });
        long failed = results.stream().filter(r -> Boolean.FALSE.equals(r.get("passed"))).count();
        Files.write(output.resolve("results.json"), Util.bytes(Util.pretty(Util.mapOf("platform", platform, "runtime", System.getProperty("java.version"), "tests", results.size(), "failed", failed, "results", results))));
        System.out.println("Repository " + platform + ": " + (results.size() - failed) + "/" + results.size());
        if (failed != 0) throw new AssertionError("Repository failures=" + failed);
    }
    private static String repeat(char ch, int length) { char[] text = new char[length]; Arrays.fill(text, ch); return new String(text); }
}
