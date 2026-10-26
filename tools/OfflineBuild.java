import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import java.util.stream.*;
import java.util.zip.*;
import javax.tools.*;

/** Complete Java8 source build with the verified, bundled framework dependencies. */
public final class OfflineBuild {
    private static final String JAR = "offline-dev-assistant-2.1.1-rebuild.8-java8-kylin-linux.jar";
    private static final String VERSION = "2.1.1-rebuild.8-java8";
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        Path base = root.resolve("vendor/runtime-base.jar");
        String expected = new String(Files.readAllBytes(root.resolve("vendor/runtime-base.sha256")), StandardCharsets.UTF_8).trim().split("\\s+")[0];
        if (!sha(base).equals(expected)) throw new IllegalStateException("Bundled dependency checksum mismatch");
        Path work = root.resolve("build/offline-" + UUID.randomUUID());
        Path classes = Files.createDirectories(work.resolve("classes"));
        Path libs = Files.createDirectories(work.resolve("lib"));
        List<String> dependencies = new ArrayList<String>();
        try (ZipFile zip = new ZipFile(base.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("BOOT-INF/lib/") || !entry.getName().endsWith(".jar")) continue;
                String name = entry.getName().substring("BOOT-INF/lib/".length());
                if (name.contains("/") || name.contains("\\")) throw new IOException("Invalid bundled dependency path");
                Path target = libs.resolve(name);
                try (InputStream input = zip.getInputStream(entry)) { Files.copy(input, target); }
                dependencies.add(target.toString());
            }
        }
        List<File> sources = new ArrayList<File>();
        try (Stream<Path> paths = Files.walk(root.resolve("src/main/java"))) {
            paths.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> sources.add(p.toFile()));
        }
        if (sources.size() != 59) throw new IllegalStateException("Expected all 59 application Java sources");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full Java8 JDK is required for source builds");
        List<String> options = new ArrayList<String>(Arrays.asList("-encoding", "UTF-8", "-parameters", "-classpath", String.join(File.pathSeparator, dependencies), "-d", classes.toString()));
        if (System.getProperty("java.specification.version").equals("1.8")) options.addAll(Arrays.asList("-source", "8", "-target", "8"));
        else options.addAll(Arrays.asList("--release", "8"));
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            if (!compiler.getTask(null, manager, null, options, null, manager.getJavaFileObjectsFromFiles(sources)).call()) throw new IllegalStateException("Application source compilation failed");
        }
        List<String> verificationPath = new ArrayList<String>();
        verificationPath.add(classes.toString());
        verificationPath.add(root.resolve("src/main/resources").toString());
        verificationPath.addAll(dependencies);
        Files.write(root.resolve("build/verification-classpath.txt"), String.join(File.pathSeparator, verificationPath).getBytes(StandardCharsets.UTF_8));
        Map<String, Path> application = new TreeMap<String, Path>();
        collect(classes, application);
        collect(root.resolve("src/main/resources"), application);
        Path dist = Files.createDirectories(root.resolve("dist"));
        Path staged = dist.resolve("building-" + UUID.randomUUID() + ".jar");
        try (ZipFile zip = new ZipFile(base.toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(staged))) {
            Set<String> names = new HashSet<String>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                ZipEntry target = new ZipEntry(entry.getName());
                target.setTime(entry.getTime());
                byte[] replacement = null;
                if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    Manifest manifest;
                    try (InputStream input = zip.getInputStream(entry)) { manifest = new Manifest(input); }
                    manifest.getMainAttributes().putValue("Implementation-Version", VERSION);
                    manifest.getMainAttributes().putValue("Build-Jdk-Spec", System.getProperty("java.specification.version"));
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    manifest.write(bytes); replacement = bytes.toByteArray();
                } else if (entry.getName().equals("META-INF/maven/cn.bank.tools/offline-dev-assistant/pom.xml")) {
                    replacement = Files.readAllBytes(root.resolve("pom.xml"));
                } else if (entry.getName().equals("META-INF/maven/cn.bank.tools/offline-dev-assistant/pom.properties")) {
                    replacement = ("groupId=cn.bank.tools\nartifactId=offline-dev-assistant\nversion=" + VERSION + "\n").getBytes(StandardCharsets.UTF_8);
                }
                if (replacement == null && entry.getMethod() == ZipEntry.STORED) { target.setMethod(ZipEntry.STORED); target.setSize(entry.getSize()); target.setCrc(entry.getCrc()); }
                output.putNextEntry(target);
                if (replacement != null) output.write(replacement);
                else try (InputStream input = zip.getInputStream(entry)) { copy(input, output); }
                output.closeEntry(); names.add(target.getName());
            }
            addDirectory(output, names, "BOOT-INF/classes/");
            for (Map.Entry<String, Path> entry : application.entrySet()) {
                String name = "BOOT-INF/classes/" + entry.getKey();
                int slash = name.indexOf('/');
                while (slash >= 0) { addDirectory(output, names, name.substring(0, slash + 1)); slash = name.indexOf('/', slash + 1); }
                if (!names.add(name)) throw new IOException("Duplicate application entry: " + name);
                output.putNextEntry(new ZipEntry(name));
                Files.copy(entry.getValue(), output);
                output.closeEntry();
            }
        }
        Path result = dist.resolve(JAR);
        Files.move(staged, result, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("PASS: compiled " + sources.size() + " Java sources; bundled " + dependencies.size() + " verified dependencies.");
        System.out.println("SHA256 " + sha(result) + "  " + result);
    }
    private static void collect(Path folder, Map<String, Path> result) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path file : paths.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String name = folder.relativize(file).toString().replace(File.separatorChar, '/');
                if (result.put(name, file) != null) throw new IOException("Duplicate application resource: " + name);
            }
        }
    }
    private static void addDirectory(ZipOutputStream output, Set<String> names, String name) throws IOException {
        if (names.add(name)) { output.putNextEntry(new ZipEntry(name)); output.closeEntry(); }
    }
    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }
    private static String sha(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
        StringBuilder result = new StringBuilder(); for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255)); return result.toString();
    }
}
