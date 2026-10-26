package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map.Entry;
import javax.xml.parsers.DocumentBuilderFactory;

public final class UpdateService {
    public Map<String, Object> merge(Map<String, byte[]> base, Map<String, byte[]> current, Map<String, byte[]> candidate, JsonNode decisions) {
        if (base != null && !base.isEmpty()) {
            Set<String> paths = new TreeSet<>(base.keySet());
            paths.addAll(current.keySet());
            paths.addAll(candidate.keySet());
            Map<String, byte[]> merged = new LinkedHashMap<>();
            ArrayNode rows = Util.JSON.createArrayNode();
            int pending = 0;
            if (decisions != null && !decisions.isObject()) {
                throw new IllegalArgumentException("合并决策必须为对象");
            } else {
                if (decisions != null) {
                    Iterator<String> keys = decisions.fieldNames();

                    while (keys.hasNext()) {
                        if (!paths.contains(keys.next())) {
                            throw new IllegalArgumentException("合并决策包含不存在的文件");
                        }
                    }
                }

                for (String p : paths) {
                    if (!p.equals("generation-manifest.json")) {
                        byte[] a = base.get(p);
                        byte[] l = current.get(p);
                        byte[] n = candidate.get(p);
                        byte[] chosen = null;
                        String status;
                        if (Arrays.equals(l, n)) {
                            chosen = l;
                            status = l == null ? "DELETED" : "UNCHANGED";
                        } else if (Arrays.equals(l, a)) {
                            chosen = n;
                            status = n == null ? "DELETED" : "ACCEPT_CANDIDATE";
                        } else if (Arrays.equals(n, a)) {
                            chosen = l;
                            status = l == null ? "DELETED" : "KEEP_LOCAL";
                        } else {
                            status = "CONFLICT";
                            String decision = decisions == null ? "" : decisions.path(p).asText();
                            if (decision.equals("LOCAL")) {
                                chosen = l;
                                status = "RESOLVED_LOCAL";
                            } else if (decision.equals("CANDIDATE")) {
                                chosen = n;
                                status = "RESOLVED_CANDIDATE";
                            } else {
                                if (!decision.isEmpty()) {
                                    throw new IllegalArgumentException("仅支持LOCAL/CANDIDATE决策：" + p);
                                }

                                chosen = this.mergeLines(p, a, l, n);
                                if (chosen != null) status = "AUTO_MERGED_LINES";
                                else pending++;
                            }
                        }

                        ObjectNode row = rows.addObject().put("path", p).put("status", status);
                        if (status.equals("CONFLICT")) {
                            row.put("baseText", this.text(a)).put("localText", this.text(l)).put("candidateText", this.text(n));
                        }

                        if (chosen != null) {
                            merged.put(p, chosen);
                        }
                    }
                }

                if (pending == 0) {
                    for (Entry<String, byte[]> e : merged.entrySet()) {
                        if (e.getKey().endsWith(".java")) {
                            ParseResult<CompilationUnit> parsed = new JavaParser(new ParserConfiguration().setLanguageLevel(this.languageLevel()))
                                .parse(SourceFiles.utf8(e.getValue()));
                            if (parsed.isSuccessful() && parsed.getResult().get().findAll(com.github.javaparser.ast.expr.VariableDeclarationExpr.class).stream().flatMap(v -> v.getVariables().stream()).anyMatch(v -> v.getType().asString().equals("var"))) { throw new IllegalArgumentException("目标 Java 8 不支持局部变量 var 类型推断，请写明类型：" + e.getKey()); }
                            if (!parsed.isSuccessful()) {
                                throw new IllegalArgumentException("合并后Java语法不兼容目标 Java " + this.targetJava() + "：" + e.getKey());
                            }
                        }
                    }

                    for (Entry<String, byte[]> ex : merged.entrySet()) {
                        if (ex.getKey().endsWith(".xml")) {
                            try {
                                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
                                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                                factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
                                factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
                                factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "");
                                factory.setExpandEntityReferences(false);
                                factory.newDocumentBuilder().parse(new ByteArrayInputStream(ex.getValue()));
                            } catch (Exception var17) {
                                throw new IllegalArgumentException("合并后XML语法错误：" + ex.getKey());
                            }
                        }
                    }

                    ObjectNode manifest = (ObjectNode)Util.parse(candidate.get("generation-manifest.json")).deepCopy();
                    ObjectNode hashes = manifest.putObject("files");

                    for (Entry<String, byte[]> exx : candidate.entrySet()) {
                        if (!exx.getKey().equals("generation-manifest.json")) {
                            hashes.put(exx.getKey(), Util.sha(exx.getValue()));
                        }
                    }

                    manifest.put("mergeNote", "基线哈希继续指向生成候选；保留的人工内容下次仍能识别");
                    merged.put("generation-manifest.json", Util.bytes(Util.pretty(manifest)));
                }

                ObjectNode report = Util.obj()
                    .put("mode", "three-way-line-and-file-merge")
                    .put("targetJava", this.targetJava())
                    .put("verdict", pending == 0 ? "MERGED_SYNTAX_CHECKED" : "DECISIONS_REQUIRED")
                    .put("pending", pending)
                    .put("compilation", "NOT_RUN")
                    .put("message", pending == 0 ? "人工内容已按决策保留；仍需项目编译与回归" : "存在未决文件，不提供可替换项目的合并包");
                report.set("files", rows);
                return Util.mapOf("report", report, "files", merged);
            }
        } else {
            throw new IllegalArgumentException("三方合并需要原始生成基线文件正文");
        }
    }

    // The target belongs to this distribution, regardless of an untrusted input manifest.
    private String targetJava() { return "8"; }

    private LanguageLevel languageLevel() { return LanguageLevel.JAVA_8; }

    /** Merge independent complete-line edits only. Ambiguous edits, delete/edit pairs and
     * binary/large inputs retain the explicit whole-file decision workflow. */
    private byte[] mergeLines(String path, byte[] base, byte[] local, byte[] candidate) {
        if (base == null || local == null || candidate == null ||
            !path.matches(".*\\.(java|xml|json|ya?ml|properties|md|txt|sql|js|ts|vue|html|css|sh|bat|ps1)$")) return null;
        try {
            List<String> a = lines(SourceFiles.utf8(base)), l = lines(SourceFiles.utf8(local)), n = lines(SourceFiles.utf8(candidate));
            if (a.size() > 2000 || l.size() > 2000 || n.size() > 2000 ||
                (long)(a.size() + 1) * (l.size() + n.size() + 2) > 1000000L) return null;
            List<Edit> left = edits(a, l), right = edits(a, n), all = new ArrayList<>(left);
            Map<String, Integer> occurrences = new LinkedHashMap<>();
            for (String line : a) occurrences.put(line, occurrences.getOrDefault(line, 0) + 1);
            for (Edit edit : left) if (ambiguousAnchor(a, occurrences, edit)) return null;
            for (Edit edit : right) if (ambiguousAnchor(a, occurrences, edit)) return null;
            for (Edit next : right) {
                boolean duplicate = false;
                for (Edit own : left) {
                    if (own.start == next.start && own.end == next.end && own.replacement.equals(next.replacement)) { duplicate = true; break; }
                    if (overlaps(own, next)) return null;
                }
                if (!duplicate) all.add(next);
            }
            Collections.sort(all, Comparator.comparingInt(e -> e.start));
            StringBuilder text = new StringBuilder(); int cursor = 0;
            for (Edit edit : all) {
                while (cursor < edit.start) text.append(a.get(cursor++));
                for (String line : edit.replacement) text.append(line);
                cursor = edit.end;
            }
            while (cursor < a.size()) text.append(a.get(cursor++));
            return Util.bytes(text.toString());
        } catch (IllegalArgumentException invalidText) { return null; }
    }

    private static boolean ambiguousAnchor(List<String> base, Map<String, Integer> occurrences, Edit edit) {
        // Repeated identical lines admit multiple alignments. A guessed match can otherwise
        // turn delete/edit on the same original line into apparently independent edits.
        for (int i = edit.start; i < edit.end; i++) if (occurrences.get(base.get(i)) > 1) return true;
        if (edit.start == edit.end) {
            return (edit.start < base.size() && occurrences.get(base.get(edit.start)) > 1) ||
                (edit.start > 0 && occurrences.get(base.get(edit.start - 1)) > 1);
        }
        return false;
    }

    private static boolean overlaps(Edit x, Edit y) {
        if (x.start == x.end) return x.start >= y.start && x.start <= y.end;
        if (y.start == y.end) return y.start >= x.start && y.start <= x.end;
        return x.start < y.end && y.start < x.end;
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>(); int start = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') { lines.add(text.substring(start, i + 1)); start = i + 1; }
        if (start < text.length()) lines.add(text.substring(start));
        return lines;
    }

    private static List<Edit> edits(List<String> base, List<String> changed) {
        int[][] common = new int[base.size() + 1][changed.size() + 1];
        for (int i = base.size() - 1; i >= 0; i--) for (int j = changed.size() - 1; j >= 0; j--)
            common[i][j] = base.get(i).equals(changed.get(j)) ? common[i + 1][j + 1] + 1 : Math.max(common[i + 1][j], common[i][j + 1]);
        List<Edit> result = new ArrayList<>(); int i = 0, j = 0;
        while (i < base.size() || j < changed.size()) {
            if (i < base.size() && j < changed.size() && base.get(i).equals(changed.get(j))) { i++; j++; continue; }
            int start = i; List<String> replacement = new ArrayList<>();
            while (i < base.size() || j < changed.size()) {
                if (i < base.size() && j < changed.size() && base.get(i).equals(changed.get(j))) break;
                if (j < changed.size() && (i == base.size() || common[i][j + 1] >= common[i + 1][j])) replacement.add(changed.get(j++));
                else i++;
            }
            result.add(new Edit(start, i, replacement));
        }
        return result;
    }

    private static final class Edit {
        final int start, end; final List<String> replacement;
        Edit(int start, int end, List<String> replacement) { this.start = start; this.end = end; this.replacement = replacement; }
    }

    private String text(byte[] value) {
        if (value == null) {
            return "（文件不存在）";
        } else {
            String text = SourceFiles.utf8(value);
            return text.length() > 20000 ? text.substring(0, 20000) + "\n…预览截断" : text;
        }
    }
}
