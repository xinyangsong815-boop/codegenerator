package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class SourceDiffService {
    public JsonNode compare(Map<String, byte[]> oldFiles, Map<String, byte[]> nextFiles) {
        ObjectNode out = Util.obj().put("scope", "Java类、成员、注解及方法正文的源码事实；XML/配置按文件文本比较，不证明SQL或业务行为等价");
        ArrayNode rows = out.putArray("files");
        ArrayNode issues = out.putArray("issues");
        Set<String> paths = new TreeSet<>(oldFiles.keySet());
        paths.addAll(nextFiles.keySet());
        int changed = 0;
        long reportBytes = 0L;

        for (String file : paths) {
            byte[] a = oldFiles.get(file);
            byte[] b = nextFiles.get(file);
            if (a == null || b == null || !Arrays.equals(a, b)) {
                if (++changed > 1000) {
                    throw new IllegalArgumentException("最多展示1000个变更文件");
                }

                ObjectNode row = rows.addObject().put("path", file).put("status", a == null ? "ADDED" : (b == null ? "REMOVED" : "MODIFIED"));
                if (a != null) {
                    row.put("oldText", snippet(SourceFiles.utf8(a)));
                }

                if (b != null) {
                    row.put("newText", snippet(SourceFiles.utf8(b)));
                }

                if (file.endsWith(".java")) {
                    Map<String, String> before = this.members(a, file, issues);
                    Map<String, String> after = this.members(b, file, issues);
                    ArrayNode members = row.putArray("members");
                    Set<String> keys = new TreeSet<>(before.keySet());
                    keys.addAll(after.keySet());

                    for (String key : keys) {
                        if (!Objects.equals(before.get(key), after.get(key))) {
                            members.addObject()
                                .put("member", key)
                                .put("status", !before.containsKey(key) ? "ADDED" : (!after.containsKey(key) ? "REMOVED" : "MODIFIED"))
                                .put("oldText", snippet(before.get(key)))
                                .put("newText", snippet(after.get(key)));
                        }
                    }
                }

                reportBytes += Util.bytes(Util.pretty(row)).length;
                if (reportBytes > 4194304L) {
                    rows.remove(rows.size() - 1);
                    issues.add("源码差异预览达到4MiB预算，请按业务模块缩小范围；不能当成完整检查");
                    break;
                }
            }
        }

        if (oldFiles.keySet().stream().anyMatch(p -> p.endsWith(".java")) && nextFiles.keySet().stream().anyMatch(p -> p.endsWith(".java"))) {
            try {
                JsonNode oldDoc = new SourceContractService().scan(ZipFiles.write(oldFiles));
                JsonNode nextDoc = new SourceContractService().scan(ZipFiles.write(nextFiles));
                out.set("contractComparison", new CompareService().compare(oldDoc, nextDoc, Util.JSON.createArrayNode()));
            } catch (IllegalArgumentException var21) {
                issues.add("接口分析未完成：" + var21.getMessage());
            }
        }

        out.put("changedFiles", changed).put("verdict", issues.isEmpty() ? "REVIEW_REQUIRED" : "INCOMPLETE");
        return out;
    }

    private Map<String, String> members(byte[] bytes, String file, ArrayNode issues) {
        Map<String, String> out = new LinkedHashMap<>();
        if (bytes == null) {
            return out;
        } else {
            ParseResult<CompilationUnit> parsed = new JavaParser(new ParserConfiguration().setLanguageLevel(PlatformProfile.JAVA.equals("8") ? LanguageLevel.JAVA_8 : LanguageLevel.JAVA_17))
                .parse(SourceFiles.utf8(bytes));
            if (PlatformProfile.JAVA.equals("8") && parsed.getResult().isPresent()
                    && parsed.getResult().get().findAll(com.github.javaparser.ast.type.VarType.class).size() > 0) {
                issues.add("Java8目标不支持局部var：" + file);
                return out;
            }
            if (parsed.isSuccessful() && parsed.getResult().isPresent()) {
                for (TypeDeclaration<?> type : ((CompilationUnit)parsed.getResult().get()).findAll(TypeDeclaration.class)) {
                    String name = type.getFullyQualifiedName().orElse(type.getNameAsString());
                    out.put(
                        name + "#type",
                        type.getAnnotations().toString()
                            + type.getModifiers().toString()
                            + (
                                type.isClassOrInterfaceDeclaration()
                                    ? type.asClassOrInterfaceDeclaration().getExtendedTypes().toString()
                                        + type.asClassOrInterfaceDeclaration().getImplementedTypes().toString()
                                    : type.getClass().getSimpleName()
                            )
                    );

                    for (BodyDeclaration<?> member : type.getMembers()) {
                        String key = member instanceof MethodDeclaration
                            ? ((MethodDeclaration)member).getSignature().asString()
                            : (
                                member instanceof ConstructorDeclaration
                                    ? ((ConstructorDeclaration)member).getSignature().asString()
                                    : (
                                        member instanceof FieldDeclaration
                                            ? ((FieldDeclaration)member).getVariables().stream().map(v -> v.getNameAsString()).collect(Collectors.joining(","))
                                            : null
                                    )
                            );
                        if (key != null) {
                            out.put(name + "#" + key, member.toString());
                        }
                    }
                }

                return out;
            } else {
                issues.add("Java语法无法解析：" + file);
                return out;
            }
        }
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        } else {
            return text.length() > 12000 ? text.substring(0, 12000) + "\n…（预览已截断，原文件请自行核对）" : text;
        }
    }
}
