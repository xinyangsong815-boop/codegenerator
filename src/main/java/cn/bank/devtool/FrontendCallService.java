package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FrontendCallService {
    private static final Set<String> CLIENTS = new HashSet<>(Arrays.asList("axios", "client", "http", "request"));
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE"));
    private static final int TOKEN_LIMIT = 400000;
    private static final int CALL_LIMIT = 5000;
    private static final int FILE_LIMIT = 1000;
    private static final Pattern SCRIPTS = Pattern.compile("(?is)<script\\b[^>]*>(.*?)</script\\s*>");
    private static final Pattern SLOT = Pattern.compile("\\{[^/{}]+\\}");

    public JsonNode scan(byte[] zip, JsonNode contract) {
        JsonNode doc = contract != null && !contract.isNull() ? new ContractService().prepare(contract) : null;
        FrontendCallService.ContractIndex index = new FrontendCallService.ContractIndex(doc);
        Map<String, byte[]> files = ZipFiles.read(zip);
        Map<String, FrontendCallService.Source> sources = new LinkedHashMap<>();
        int[] count = new int[]{0};
        LinkedHashSet<String> issues = new LinkedHashSet<>();
        issues.add("保守词法扫描并非完整程序分析；动态请求、别名、重导出、高阶调用及页面归属需人工复核");

        for (Entry<String, byte[]> e : files.entrySet()) {
            String f = e.getKey();
            if (sourceFile(f) && !excluded(f)) {
                if (sources.size() >= 1000) {
                    throw new IllegalArgumentException("前端源码超过1000文件");
                }

                String text = utf8(e.getValue());
                List<FrontendCallService.T> tokens = new ArrayList<>();
                if (f.endsWith(".vue")) {
                    Matcher m = SCRIPTS.matcher(text);
                    int last = 0;

                    for (int line = 1; m.find(); last = m.end(1)) {
                        line += newlines(text, last, m.start(1));
                        lex(m.group(1), line, tokens, count);
                        line += newlines(text, m.start(1), m.end(1));
                    }
                } else {
                    lex(text, 1, tokens, count);
                }

                pair(tokens);
                FrontendCallService.Source s = new FrontendCallService.Source(f, tokens);
                indexFunctions(s);
                indexImportsAndInvocations(s);
                sources.put(f, s);
            }
        }

        List<FrontendCallService.Evidence> calls = new ArrayList<>();
        Map<String, List<FrontendCallService.Evidence>> wrappers = new HashMap<>();

        for (FrontendCallService.Source s : sources.values()) {
            extract(s, index, calls, wrappers);
        }

        ObjectNode result = Util.obj();
        ArrayNode out = result.putArray("calls");
        ArrayNode bindings = result.putArray("bindings");
        ArrayNode unresolved = result.putArray("unresolved");
        Set<String> emitted = new HashSet<>();

        for (FrontendCallService.Evidence ex : calls) {
            out.add(ex.node);
            String status = ex.node.path("status").asText();
            if (!bindable(status)) {
                unresolved.add(ex.node);
                issues.add("部分请求无法静态映射到选定契约");
            }

            if (ex.source.vue && bindable(status)) {
                binding(bindings, emitted, ex, ex.source.file, ex.node.path("line").asInt());
            }
        }

        for (FrontendCallService.Source s : sources.values()) {
            if (s.vue) {
                for (Entry<String, String> imp : s.imports.entrySet()) {
                    if (s.ambiguousImports.contains(imp.getKey())) {
                        issues.add("导入名称存在遮蔽或无法确认的引用，未自动关联：" + s.file + " " + imp.getKey());
                    } else if (s.invocations.contains(imp.getKey())) {
                        int sep = imp.getValue().indexOf(10);
                        String target = resolve(s.file, imp.getValue().substring(0, sep), sources);
                        if (target == null) {
                            issues.add("相对包装器导入无法唯一解析");
                        } else {
                            List<FrontendCallService.Evidence> found = wrappers.get(target + "\n" + imp.getValue().substring(sep + 1));
                            if (found == null) {
                                issues.add("包装器缺少直接可识别请求");
                            } else {
                                for (FrontendCallService.Evidence ex : found) {
                                    if (bindable(ex.node.path("status").asText())) {
                                        binding(bindings, emitted, ex, s.file, ex.node.path("line").asInt());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        ObjectNode coverage = result.putObject("coverage");
        coverage.put("complete", false);
        ArrayNode issueNodes = coverage.putArray("issues");

        for (String issue : issues) {
            issueNodes.add(issue);
        }

        result.put("scope", "仅当前ZIP内JS/TS/JSX/TSX和Vue script的保守静态请求证据；不执行代码、不加载依赖、不保证穷尽");
        result.put("sourceFiles", sources.size());
        return result;
    }

    private static boolean sourceFile(String f) {
        return f.matches(".*\\.(js|ts|jsx|tsx|vue)$");
    }

    private static boolean excluded(String f) {
        for (String p : f.split("/")) {
            if (Arrays.asList("node_modules", "dist", "build", ".git").contains(p)) {
                return true;
            }
        }

        return false;
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException var2) {
            throw new IllegalArgumentException("源码必须为有效UTF-8", var2);
        }
    }

    private static int newlines(String x, int a, int b) {
        int n = 0;

        for (int i = a; i < b; i++) {
            if (x.charAt(i) == '\n') {
                n++;
            }
        }

        return n;
    }

    private static void add(List<FrontendCallService.T> t, FrontendCallService.T token, int[] count) {
        if (++count[0] > 400000) {
            throw new IllegalArgumentException("前端词法token超过400000");
        } else {
            t.add(token);
        }
    }

    private static boolean ident(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static void lex(String x, int line, List<FrontendCallService.T> t, int[] count) {
        int i = 0;

        while (i < x.length()) {
            char c = x.charAt(i);
            if (Character.isWhitespace(c)) {
                if (c == '\n') {
                    line++;
                }

                i++;
            } else {
                int start = i;
                if (c == '/' && i + 1 < x.length() && x.charAt(i + 1) == '/') {
                    i += 2;

                    while (i < x.length() && x.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && i + 1 < x.length() && x.charAt(i + 1) == '*') {
                    int end = x.indexOf("*/", i + 2);
                    if (end < 0) {
                        throw new IllegalArgumentException("未闭合源码注释");
                    }

                    i = end + 2;
                    line += newlines(x, start, i);
                } else if (c != '\'' && c != '"') {
                    if (c == '`') {
                        i = templateEnd(x, i, 0);
                        String raw = x.substring(start + 1, i - 1);
                        add(t, new FrontendCallService.T("TEMPLATE", templateValue(raw), 't', line), count);
                        line += newlines(x, start, i);
                    } else if (c == '/' && regexStart(t)) {
                        i = regexEnd(x, i);
                        add(t, new FrontendCallService.T("REGEX", null, 'r', line), count);
                        line += newlines(x, start, i);
                    } else if (!ident(c)) {
                        String op = c + "";
                        if (x.startsWith("...", i)) {
                            op = "...";
                            i += 3;
                        } else if (x.startsWith("=>", i)) {
                            op = "=>";
                            i += 2;
                        } else {
                            i++;
                        }

                        add(t, new FrontendCallService.T(op, null, 'p', line), count);
                    } else {
                        i++;

                        while (i < x.length() && ident(x.charAt(i))) {
                            i++;
                        }

                        add(t, new FrontendCallService.T(x.substring(start, i), null, 'i', line), count);
                    }
                } else {
                    i = quotedEnd(x, i);
                    String raw = x.substring(start + 1, i - 1);
                    add(t, new FrontendCallService.T("STRING", decode(raw), 's', line), count);
                    line += newlines(x, start, i);
                }
            }
        }
    }

    private static int quotedEnd(String x, int i) {
        char quote = x.charAt(i++);

        while (i < x.length()) {
            char c = x.charAt(i++);
            if (c == '\\') {
                if (i < x.length()) {
                    i++;
                }
            } else if (c == quote) {
                return i;
            }
        }

        throw new IllegalArgumentException("未闭合源码字符串");
    }

    private static int regexEnd(String x, int i) {
        i++;
        boolean cls = false;

        while (i < x.length()) {
            char c = x.charAt(i++);
            if (c == '\\') {
                if (i < x.length()) {
                    i++;
                }
            } else if (c == '[') {
                cls = true;
            } else if (c == ']') {
                cls = false;
            } else {
                if (c == '/' && !cls) {
                    while (i < x.length() && Character.isLetter(x.charAt(i))) {
                        i++;
                    }

                    return i;
                }

                if (c == '\n') {
                    break;
                }
            }
        }

        throw new IllegalArgumentException("未闭合正则字面量");
    }

    private static boolean regexStart(List<FrontendCallService.T> t) {
        if (t.isEmpty()) {
            return true;
        } else {
            String p = t.get(t.size() - 1).s;
            if (p.equals(")")) {
                int depth = 1;

                for (int i = t.size() - 2; i >= 0; i--) {
                    String q = t.get(i).s;
                    if (q.equals(")")) {
                        depth++;
                    } else if (q.equals("(")) {
                        if (--depth == 0) {
                            return i > 0 && Arrays.asList("if", "while", "for", "with", "switch", "catch").contains(t.get(i - 1).s);
                        }
                    }
                }
            }

            return Arrays.asList(
                    "=",
                    "(",
                    "[",
                    "{",
                    "}",
                    ",",
                    ":",
                    ";",
                    "!",
                    "?",
                    "return",
                    "=>",
                    "case",
                    "throw",
                    "else",
                    "do",
                    "&",
                    "|",
                    "+",
                    "-",
                    "*",
                    "%",
                    "^",
                    "~",
                    "<",
                    ">",
                    "await",
                    "yield",
                    "delete",
                    "void",
                    "typeof",
                    "new",
                    "in",
                    "instanceof"
                )
                .contains(p);
        }
    }

    private static int templateEnd(String x, int i, int depth) {
        if (depth > 80) {
            throw new IllegalArgumentException("模板嵌套超过80层");
        } else {
            i++;

            while (i < x.length()) {
                char c = x.charAt(i++);
                if (c == '\\') {
                    if (i < x.length()) {
                        i++;
                    }
                } else {
                    if (c == '`') {
                        return i;
                    }

                    if (c == '$' && i < x.length() && x.charAt(i) == '{') {
                        i = expressionEnd(x, i + 1, depth + 1);
                    }
                }
            }

            throw new IllegalArgumentException("未闭合模板字符串");
        }
    }

    private static int expressionEnd(String x, int i, int depth) {
        int braces = 1;
        boolean expect = true;

        while (i < x.length()) {
            char c = x.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'' || c == '"') {
                i = quotedEnd(x, i);
                expect = false;
            } else if (c == '`') {
                i = templateEnd(x, i, depth);
                expect = false;
            } else if (c == '/' && i + 1 < x.length() && x.charAt(i + 1) == '*') {
                int end = x.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("未闭合模板注释");
                }

                i = end + 2;
            } else if (c == '/' && i + 1 < x.length() && x.charAt(i + 1) == '/') {
                while (i < x.length() && x.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && expect) {
                i = regexEnd(x, i);
                expect = false;
            } else if (ident(c)) {
                int start = i++;

                while (i < x.length() && ident(x.charAt(i))) {
                    i++;
                }

                expect = Arrays.asList("return", "throw", "case", "delete", "void", "typeof", "new").contains(x.substring(start, i));
            } else {
                i++;
                if (c == '{') {
                    braces++;
                    expect = true;
                } else if (c == '}') {
                    if (--braces == 0) {
                        return i;
                    }

                    expect = false;
                } else {
                    expect = c != ')' && c != ']' && c != '.';
                }

                if (braces > 80) {
                    throw new IllegalArgumentException("模板表达式过深");
                }
            }
        }

        throw new IllegalArgumentException("未闭合模板表达式");
    }

    private static String decode(String x) {
        StringBuilder b = new StringBuilder();

        for (int i = 0; i < x.length(); i++) {
            char c = x.charAt(i);
            if (c != '\\') {
                b.append(c);
            } else {
                if (++i >= x.length()) {
                    return null;
                }

                char d = x.charAt(i);
                if (d == 'u' || d == 'x') {
                    int n = d == 'u' ? 4 : 2;
                    if (i + n >= x.length()) {
                        return null;
                    }

                    try {
                        b.append((char)Integer.parseInt(x.substring(i + 1, i + n + 1), 16));
                    } catch (NumberFormatException var7) {
                        return null;
                    }

                    i += n;
                } else if (d == 'n') {
                    b.append('\n');
                } else if (d == 'r') {
                    b.append('\r');
                } else if (d == 't') {
                    b.append('\t');
                } else if (d != '\n') {
                    if (d == '0') {
                        b.append('\u0000');
                    } else {
                        b.append(d);
                    }
                }
            }
        }

        return b.toString();
    }

    private static String templateValue(String raw) {
        if (raw.indexOf(96) < 0 && raw.indexOf(92) < 0) {
            Matcher m = Pattern.compile("\\$\\{\\s*([A-Za-z_$][\\w$]*)\\s*\\}").matcher(raw);
            StringBuffer b = new StringBuffer();

            while (m.find()) {
                m.appendReplacement(b, "{" + Matcher.quoteReplacement(m.group(1)) + "}");
            }

            m.appendTail(b);
            String v = b.toString();
            return v.contains("${") ? null : decode(v);
        } else {
            return null;
        }
    }

    private static void pair(List<FrontendCallService.T> t) {
        Deque<Integer> stack = new ArrayDeque<>();

        for (int i = 0; i < t.size(); i++) {
            String s = t.get(i).s;
            if (Arrays.asList("(", "[", "{").contains(s)) {
                if (stack.size() >= 160) {
                    throw new IllegalArgumentException("源码括号嵌套超过160层");
                }

                stack.push(i);
            } else if (Arrays.asList(")", "]", "}").contains(s) && !stack.isEmpty()) {
                int a = stack.peek();
                String open = t.get(a).s;
                if (open.equals("(") && s.equals(")") || open.equals("[") && s.equals("]") || open.equals("{") && s.equals("}")) {
                    stack.pop();
                    t.get(a).pair = i;
                    t.get(i).pair = a;
                }
            }
        }
    }

    private static boolean eq(List<FrontendCallService.T> t, int i, String s) {
        return i >= 0 && i < t.size() && t.get(i).s.equals(s);
    }

    private static void indexFunctions(FrontendCallService.Source s) {
        List<FrontendCallService.T> t = s.ts;

        for (int i = 0; i < t.size(); i++) {
            String name = null;
            int search = -1;
            boolean arrow = false;
            if (eq(t, i, "function") && i + 2 < t.size() && t.get(i + 1).kind == 'i' && eq(t, i + 2, "(")) {
                name = t.get(i + 1).s;
                int close = t.get(i + 2).pair;
                if (close >= 0) {
                    search = close + 1;
                }
            } else if ((eq(t, i, "const") || eq(t, i, "let")) && i + 1 < t.size()) {
                name = t.get(i + 1).s;
                int j = i + 2;

                for (int max = Math.min(t.size(), j + 200); j < max && !eq(t, j, ";") && !eq(t, j, "=>"); j++) {
                    if (eq(t, j, "(") && t.get(j).pair > j) {
                        j = t.get(j).pair;
                    }
                }

                if (eq(t, j, "=>")) {
                    search = j + 1;
                    arrow = true;
                }
            }

            if (search >= 0) {
                if (arrow && !eq(t, search, "{")) {
                    int end;
                    for (end = search; end < t.size() && !eq(t, end, ";") && !eq(t, end, ","); end++) {
                        if (t.get(end).pair > end) {
                            end = t.get(end).pair;
                        }
                    }

                    for (int j = search; j < end; j++) {
                        s.functions[j] = name;
                    }
                } else {
                    int body = search;

                    while (body < t.size() && !eq(t, body, "{") && !eq(t, body, ";") && !eq(t, body, "=>") && body - search < 80) {
                        body++;
                    }

                    if (eq(t, body, "{") && t.get(body).pair > body) {
                        int end = t.get(body).pair;

                        for (int j = body + 1; j < end; j++) {
                            s.functions[j] = name;
                        }
                    }
                }
            }
        }
    }

    private static void indexImportsAndInvocations(FrontendCallService.Source s) {
        List<FrontendCallService.T> t = s.ts;
        Set<Integer> importTokens = new HashSet<>();

        for (int i = 0; i < t.size(); i++) {
            if (t.get(i).kind == 'i' && eq(t, i + 1, "(") && !eq(t, i - 1, "function") && !eq(t, i - 1, ".") && !eq(t, i - 1, "new")) {
                s.invocations.add(t.get(i).s);
            }

            if (eq(t, i, "import") && eq(t, i + 1, "{")) {
                int end = t.get(i + 1).pair;
                if (end >= 0 && eq(t, end + 1, "from") && end + 2 < t.size() && t.get(end + 2).kind == 's') {
                    String path = t.get(end + 2).v;
                    if (path != null && path.startsWith(".")) {
                        for (int j = i; j <= end + 2; j++) {
                            importTokens.add(j);
                        }

                        int j = i + 2;

                        while (j < end) {
                            if (t.get(j).kind != 'i') {
                                j++;
                            } else {
                                String original = t.get(j).s;
                                String alias = original;
                                j++;
                                if (eq(t, j, "as") && j + 1 < end) {
                                    alias = t.get(j + 1).s;
                                    j += 2;
                                }

                                if (s.imports.containsKey(alias)) {
                                    s.imports.remove(alias);
                                } else {
                                    s.imports.put(alias, path + "\n" + original);
                                }

                                while (j < end && !eq(t, j, ",")) {
                                    j++;
                                }

                                j++;
                            }
                        }
                    }
                }
            }
        }

        for (int i = 0; i < t.size(); i++) {
            if (!importTokens.contains(i) && s.imports.containsKey(t.get(i).s)) {
                int close = eq(t, i + 1, "(") ? t.get(i + 1).pair : -1;
                if (t.get(i).kind != 'i'
                    || close < 0
                    || eq(t, i - 1, "function")
                    || eq(t, i - 1, "new")
                    || eq(t, i - 1, ".")
                    || eq(t, close + 1, "{")
                    || eq(t, close + 1, ":")) {
                    s.ambiguousImports.add(t.get(i).s);
                }
            }
        }
    }

    private static List<int[]> args(List<FrontendCallService.T> t, int open) {
        List<int[]> result = new ArrayList<>();
        int end = t.get(open).pair;
        if (end < 0) {
            return result;
        } else {
            int a = open + 1;

            for (int i = a; i < end; i++) {
                if (eq(t, i, ",")) {
                    result.add(new int[]{a, i});
                    a = i + 1;
                } else if (t.get(i).pair > i) {
                    i = t.get(i).pair;
                }
            }

            if (a < end) {
                result.add(new int[]{a, end});
            }

            return result;
        }
    }

    private static String literal(List<FrontendCallService.T> t, int[] a) {
        if (a != null && a[1] - a[0] == 1) {
            FrontendCallService.T v = t.get(a[0]);
            return v.kind != 's' && v.kind != 't' ? null : v.v;
        } else {
            return null;
        }
    }

    private static FrontendCallService.Value options(List<FrontendCallService.T> t, int[] arg) {
        FrontendCallService.Value value = new FrontendCallService.Value();
        if (arg == null) {
            return value;
        } else if (eq(t, arg[0], "{") && t.get(arg[0]).pair == arg[1] - 1) {
            Set<String> keys = new HashSet<>();
            int i = arg[0] + 1;
            int end = arg[1] - 1;

            while (i < end) {
                if (!eq(t, i, ",")) {
                    FrontendCallService.T key = t.get(i++);
                    String k = key.kind == 's' ? key.v : key.s;
                    if (key.kind != 's' && key.kind != 'i' || k == null || !keys.add(k) || !eq(t, i, ":")) {
                        value.unsafe = true;
                        return value;
                    }

                    int a;
                    for (a = ++i; i < end && !eq(t, i, ","); i++) {
                        if (t.get(i).pair > i) {
                            i = t.get(i).pair;
                        }
                    }

                    String v = literal(t, new int[]{a, i});
                    if (k.equals("url")) {
                        value.url = v;
                        value.template = i - a == 1 && t.get(a).kind == 't';
                        if (v == null) {
                            value.unsafe = true;
                        }
                    }

                    if (k.equals("method")) {
                        value.method = v;
                        if (v == null) {
                            value.unsafe = true;
                        }
                    }
                } else {
                    i++;
                }
            }

            return value;
        } else {
            value.unsafe = true;
            return value;
        }
    }

    private static void extract(
        FrontendCallService.Source s,
        FrontendCallService.ContractIndex index,
        List<FrontendCallService.Evidence> calls,
        Map<String, List<FrontendCallService.Evidence>> wrappers
    ) {
        List<FrontendCallService.T> t = s.ts;

        for (int i = 0; i < t.size(); i++) {
            FrontendCallService.T tok = t.get(i);
            if (tok.kind == 'i' && (CLIENTS.contains(tok.s) || tok.s.equals("fetch")) && !eq(t, i - 1, ".") && !eq(t, i - 1, "function")) {
                int open = -1;
                String method = null;
                boolean config = false;
                boolean dynamic = false;
                if (eq(t, i + 1, ".") && i + 3 < t.size() && eq(t, i + 3, "(")) {
                    open = i + 3;
                    String m = t.get(i + 2).s.toUpperCase(Locale.ROOT);
                    if (METHODS.contains(m)) {
                        method = m;
                    } else {
                        dynamic = true;
                    }
                } else if (eq(t, i + 1, "[") && t.get(i + 1).pair > i && eq(t, t.get(i + 1).pair + 1, "(")) {
                    open = t.get(i + 1).pair + 1;
                    dynamic = true;
                } else if (eq(t, i + 1, "(")) {
                    open = i + 1;
                    config = !tok.s.equals("fetch");
                }

                if (open >= 0) {
                    if (calls.size() >= 5000) {
                        throw new IllegalArgumentException("前端请求超过5000");
                    }

                    List<int[]> a = args(t, open);
                    String url = null;
                    boolean template = false;
                    boolean unsafe;
                    if (config) {
                        FrontendCallService.Value v = options(t, a.isEmpty() ? null : a.get(0));
                        url = v.url;
                        template = v.template;
                        method = v.method == null && !v.unsafe ? "GET" : v.method;
                        unsafe = dynamic | v.unsafe;
                    } else {
                        url = literal(t, a.isEmpty() ? null : a.get(0));
                        if (!a.isEmpty() && a.get(0)[1] - a.get(0)[0] == 1) {
                            template = t.get(a.get(0)[0]).kind == 't';
                        }

                        int optionIndex = Arrays.asList("POST", "PUT", "PATCH").contains(method) ? 2 : 1;
                        FrontendCallService.Value v = options(t, a.size() > optionIndex ? a.get(optionIndex) : null);
                        unsafe = dynamic | v.unsafe;
                        if (tok.s.equals("fetch")) {
                            method = v.method == null && !v.unsafe ? "GET" : v.method;
                        }
                    }

                    if (method != null) {
                        method = method.toUpperCase(Locale.ROOT);
                    }

                    if (method == null || !METHODS.contains(method)) {
                        unsafe = true;
                    }

                    String fn = s.functions[i] == null ? "" : s.functions[i];
                    ObjectNode n = Util.obj();
                    n.put("file", s.file);
                    n.put("line", tok.line);
                    n.put("function", fn);
                    n.put("method", method == null ? "UNKNOWN" : method);
                    n.put("url", url == null ? "" : url);
                    n.put("path", "");
                    n.put("confidence", "LOW");
                    classify(n, url, method, template, unsafe, index);
                    FrontendCallService.Evidence ev = new FrontendCallService.Evidence(n, s, fn);
                    calls.add(ev);
                    if (!fn.isEmpty()) {
                        String k = s.file + "\n" + fn;
                        List<FrontendCallService.Evidence> group = wrappers.get(k);
                        if (group == null) {
                            group = new ArrayList<>();
                            wrappers.put(k, group);
                        }

                        group.add(ev);
                    }
                }
            }
        }
    }

    private static void classify(ObjectNode n, String url, String method, boolean template, boolean unsafe, FrontendCallService.ContractIndex index) {
        String status = "UNRESOLVED";
        String reason = "动态URL、未知方法或不安全选项需人工复核";
        if (!unsafe && url != null && url.startsWith("/") && !url.startsWith("//") && !url.contains("\n") && !url.contains("\r") && !url.contains("\u0000")) {
            int query = url.indexOf(63);
            int hash = url.indexOf(35);
            int stop = query < 0 ? url.length() : query;
            if (hash >= 0) {
                stop = Math.min(stop, hash);
            }

            String path = url.substring(0, stop);
            n.put("path", path);
            if (template && path.contains("{")) {
                List<String> candidates = index.templates.get(method + "\n" + shape(path));
                if (candidates == null) {
                    candidates = Collections.emptyList();
                }

                if (candidates.size() == 1) {
                    status = "TEMPLATE_UNIQUE";
                    n.put("path", candidates.get(0));
                    n.put("confidence", "MEDIUM");
                    reason = "简单模板与选定契约的方法和路径唯一匹配";
                } else if (candidates.size() > 1) {
                    status = "AMBIGUOUS";
                    reason = "多个契约路径可匹配模板";
                } else {
                    reason = "模板缺少唯一契约路径，不能可靠映射";
                }
            } else {
                status = !index.present ? "STATIC" : (index.exact.contains(method + "\n" + path) ? "MATCHED" : "NOT_IN_CONTRACT");
                reason = status.equals("STATIC") ? "静态相对URL；尚未提供契约" : (status.equals("MATCHED") ? "静态方法与路径匹配选定契约" : "选定契约中不存在该方法和路径");
                n.put("confidence", "HIGH");
            }
        }

        n.put("status", status);
        n.put("reason", reason);
    }

    private static String shape(String p) {
        return SLOT.matcher(p).replaceAll("{}");
    }

    private static boolean bindable(String status) {
        return Arrays.asList("STATIC", "MATCHED", "TEMPLATE_UNIQUE").contains(status);
    }

    private static void binding(ArrayNode a, Set<String> seen, FrontendCallService.Evidence e, String page, int line) {
        String key = e.node.path("method").asText() + "\n" + e.node.path("path").asText() + "\n" + page + "\n" + e.source.file + "\n" + line;
        if (seen.add(key)) {
            ObjectNode b = a.addObject();
            b.put("method", e.node.path("method").asText());
            b.put("path", e.node.path("path").asText());
            b.put("page", page);
            b.put("owner", "");
            b.put("file", e.source.file);
            b.put("line", line);
            b.put("confidence", e.node.path("confidence").asText());
        }
    }

    private static String resolve(String file, String relative, Map<String, FrontendCallService.Source> sources) {
        String parent = file.contains("/") ? file.substring(0, file.lastIndexOf(47) + 1) : "";
        Deque<String> parts = new ArrayDeque<>();

        for (String p : (parent + relative).split("/")) {
            if (p.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }

                parts.removeLast();
            } else if (!p.equals(".") && !p.isEmpty()) {
                parts.addLast(p);
            }
        }

        StringBuilder b = new StringBuilder();

        for (String px : parts) {
            if (b.length() > 0) {
                b.append('/');
            }

            b.append(px);
        }

        String stem = b.toString();
        List<String> matches = new ArrayList<>();

        for (String tail : Arrays.asList("", ".js", ".ts", ".jsx", ".tsx", "/index.js", "/index.ts")) {
            if (sources.containsKey(stem + tail)) {
                matches.add(stem + tail);
            }
        }

        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static final class ContractIndex {
        boolean present;
        Set<String> exact = new HashSet<>();
        Map<String, List<String>> templates = new HashMap<>();

        ContractIndex(JsonNode doc) {
            this.present = doc != null;
            if (doc != null) {
                Iterator<String> paths = doc.path("paths").fieldNames();

                while (paths.hasNext()) {
                    String p = paths.next();

                    for (String m : FrontendCallService.METHODS) {
                        if (doc.path("paths").path(p).has(m.toLowerCase(Locale.ROOT))) {
                            this.exact.add(m + "\n" + p);
                            String k = m + "\n" + FrontendCallService.shape(p);
                            List<String> group = this.templates.get(k);
                            if (group == null) {
                                group = new ArrayList<>();
                                this.templates.put(k, group);
                            }

                            group.add(p);
                        }
                    }
                }
            }
        }
    }

    private static final class Evidence {
        ObjectNode node;
        FrontendCallService.Source source;
        String function;

        Evidence(ObjectNode n, FrontendCallService.Source s, String f) {
            this.node = n;
            this.source = s;
            this.function = f;
        }
    }

    private static final class Source {
        String file;
        boolean vue;
        List<FrontendCallService.T> ts;
        String[] functions;
        Map<String, String> imports = new LinkedHashMap<>();
        Set<String> invocations = new HashSet<>();
        Set<String> ambiguousImports = new HashSet<>();

        Source(String f, List<FrontendCallService.T> t) {
            this.file = f;
            this.ts = t;
            this.vue = f.endsWith(".vue");
            this.functions = new String[t.size()];
        }
    }

    private static final class T {
        String s;
        String v;
        int line;
        int pair = -1;
        char kind;

        T(String s, String v, char k, int l) {
            this.s = s;
            this.v = v;
            this.kind = k;
            this.line = l;
        }
    }

    private static final class Value {
        String url;
        String method;
        boolean unsafe;
        boolean template;
    }
}
