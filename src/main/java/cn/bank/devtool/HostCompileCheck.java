package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class HostCompileCheck {
    private HostCompileCheck() {
    }

    public static int run(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();

        for (String arg : args) {
            int split = arg.indexOf(61);
            if (split > 0) {
                options.put(arg.substring(0, split), arg.substring(split + 1));
            }
        }

        String module = options.get("--verify-host");
        String classpath = options.get("--host-classpath");
        if (module != null && classpath != null && !classpath.trim().isEmpty()) {
            Path root = Paths.get(module).toAbsolutePath().normalize();
            JsonNode manifest = Util.parse(Files.readAllBytes(root.resolve("generation-manifest.json")));
            String target = manifest.path("targetJava").asText("8").replace("1.8", "8");
            require(target.equals("8") || target.equals("17"), "无法识别目标 Java 版本");
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            require(compiler != null, "必须使用 JDK，不能使用仅 JRE 环境");
            List<Path> sources = new ArrayList<>();

            try (Stream<Path> paths = Files.walk(root.resolve("src/main/java"))) {
                paths.filter(px -> px.toString().endsWith(".java")).forEach(sources::add);
            }

            require(!sources.isEmpty(), "未找到模块 Java 源码");
            Path output = root.resolve("host-compile-check");
            Files.createDirectories(output);
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

            boolean passed;
            try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                List<File> files = new ArrayList<>();

                for (Path p : sources) {
                    files.add(p.toFile());
                }

                List<String> flags = new ArrayList<>(Arrays.asList("-proc:none", "-encoding", "UTF-8", "-classpath", classpath, "-d", output.toString()));
                if (System.getProperty("java.specification.version").equals("1.8")) {
                    flags.addAll(Arrays.asList("-source", target, "-target", target));
                } else {
                    flags.addAll(Arrays.asList("--release", target));
                }

                passed = compiler.getTask(null, manager, diagnostics, flags, null, manager.getJavaFileObjectsFromFiles(files)).call();
            }

            List<Map<String, Object>> messages = new ArrayList<>();

            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                messages.add(
                    Util.mapOf(
                        "kind",
                        diagnostic.getKind().name(),
                        "file",
                        diagnostic.getSource() == null ? "" : diagnostic.getSource().getName(),
                        "line",
                        diagnostic.getLineNumber(),
                        "message",
                        diagnostic.getMessage(Locale.ROOT)
                    )
                );
            }

            Map<String, Object> report = Util.mapOf(
                "status",
                passed ? "PASS" : "FAIL",
                "targetJava",
                target,
                "scope",
                "仅 Java 编译及公共类型/方法签名兼容；不执行宿主类或注解处理器，不验证序列化、SQL、事务和权限",
                "diagnostics",
                messages
            );
            Files.write(output.resolve("report.json"), Util.bytes(Util.pretty(report)));
            System.out.println(Util.pretty(report));
            return passed ? 0 : 1;
        } else {
            throw new IllegalArgumentException("需要 --verify-host=模块目录 --host-classpath=宿主编译依赖路径");
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
