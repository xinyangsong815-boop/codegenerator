package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.chrono.ChronoLocalDate;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

public final class CompareService {
    private static final Set<String> METHODS = set("get", "post", "put", "delete", "patch", "head", "options", "trace");
    private static final List<String> CONSTRAINTS = Arrays.asList(
        "minLength",
        "maxLength",
        "x-utf16-min-length",
        "x-utf16-max-length",
        "minItems",
        "maxItems",
        "minProperties",
        "maxProperties",
        "x-numeric-minimum",
        "x-numeric-maximum",
        "x-temporal-precision",
        "format",
        "default"
    );
    private static final Set<String> NON_RESTRICTING = set("default", "format");
    private final ContractService contracts = new ContractService();

    public JsonNode compare(JsonNode oldDoc, JsonNode newDoc, JsonNode bindings) {
        ObjectNode old = this.contracts.prepare(oldDoc);
        ObjectNode next = this.contracts.prepare(newDoc);
        Set<String> issues = new LinkedHashSet<>();

        for (JsonNode x : old.path("x-coverage").path("issues")) {
            issues.add("基线：" + x.asText());
        }

        for (JsonNode x : next.path("x-coverage").path("issues")) {
            issues.add("目标：" + x.asText());
        }

        CapabilityRegistry.audit(old, "compare", issues);
        CapabilityRegistry.audit(next, "compare", issues);
        ObjectNode report = Util.obj();
        ArrayNode findings = report.putArray("findings");
        Map<String, JsonNode> a = this.operations(old, issues);
        Map<String, JsonNode> b = this.operations(next, issues);

        for (Entry<String, JsonNode> e : a.entrySet()) {
            String key = e.getKey();
            JsonNode prior = e.getValue();
            JsonNode current = b.get(key);
            if (current == null) {
                this.add(findings, bindings, key, "API001", "FAIL", "", "已有接口被删除", null, null);
            } else {
                int before = findings.size();
                Map<String, JsonNode> pa = this.parameters(prior, old, issues);
                Map<String, JsonNode> pb = this.parameters(current, next, issues);

                for (Entry<String, JsonNode> p : pb.entrySet()) {
                    JsonNode prev = pa.get(p.getKey());
                    JsonNode now = p.getValue();
                    if (now.path("required").asBoolean() && (prev == null || !prev.path("required").asBoolean())) {
                        this.add(findings, bindings, key, "REQ001", "FAIL", "/parameters/" + p.getKey(), "新增必填请求参数或可选改必填", prev, now);
                    }

                    if (prev != null) {
                        this.compareParameter(prev, now, findings, bindings, key, "/parameters/" + p.getKey(), issues);
                        this.compareSchema(prev.path("schema"), now.path("schema"), "request", "/parameters/" + p.getKey(), findings, bindings, key, issues, 0);
                    }
                }

                for (String p : pa.keySet()) {
                    if (!pb.containsKey(p)) {
                        this.add(findings, bindings, key, "REQ004", "WARN", "/parameters/" + p, "请求参数不再声明，请核对服务是否仍接收", pa.get(p), null);
                    }
                }

                JsonNode priorBody = this.contracts.schema(prior.path("requestBody"), old, issues);
                JsonNode currentBody = this.contracts.schema(current.path("requestBody"), next, issues);
                if (currentBody.path("required").asBoolean() && !priorBody.path("required").asBoolean()) {
                    this.add(findings, bindings, key, "REQ001", "FAIL", "/requestBody", "请求体变为必填", null, null);
                }

                this.compareContent(
                    priorBody.path("content"),
                    currentBody.path("content"),
                    "request",
                    old,
                    next,
                    key,
                    findings,
                    bindings,
                    issues,
                    "/requestBody/content"
                );
                Set<String> statuses = new LinkedHashSet<>();
                Iterator<String> ia = prior.path("responses").fieldNames();

                while (ia.hasNext()) {
                    statuses.add(ia.next());
                }

                Iterator<String> ib = current.path("responses").fieldNames();

                while (ib.hasNext()) {
                    statuses.add(ib.next());
                }

                for (String status : statuses) {
                    JsonNode ra = this.contracts.schema(prior.path("responses").path(status), old, issues);
                    JsonNode rb = this.contracts.schema(current.path("responses").path(status), next, issues);
                    if (rb.isMissingNode()) {
                        this.add(findings, bindings, key, "MED001", "WARN", "/responses/" + status, "已声明响应状态码被删除", null, null);
                    } else if (ra.isMissingNode()) {
                        this.add(findings, bindings, key, "RES003", "INFO", "/responses/" + status, "新增响应状态码", null, null);
                    } else {
                        this.compareResponseHeaders(ra.path("headers"), rb.path("headers"), old, next, key, findings, bindings, issues, "/responses/" + status + "/headers");
                        this.compareContent(
                            ra.path("content"), rb.path("content"), "response", old, next, key, findings, bindings, issues, "/responses/" + status + "/content"
                        );
                    }
                }

                if (!prior.path("security").equals(current.path("security"))
                    || !old.path("security").equals(next.path("security"))
                    || !old.path("components").path("securitySchemes").equals(next.path("components").path("securitySchemes"))
                    || !prior.path("x-authorization").equals(current.path("x-authorization"))) {
                    this.add(findings, bindings, key, "SEC001", "WARN", "/security", "认证或授权声明变化，需要人工复核", null, null);
                }

                JsonNode serversBefore = effectiveServers(old, key, issues);
                JsonNode serversAfter = effectiveServers(next, key, issues);
                if (!serversBefore.path("values").equals(serversAfter.path("values"))) {
                    this.add(findings, bindings, key, "SRV001", "WARN", serversAfter.path("pointer").asText(),
                        "有效服务地址或服务器变量变化；结构兼容不能证明调用地址可继续使用", serversBefore, serversAfter);
                    ((ObjectNode)findings.get(findings.size() - 1)).set("transportBefore", serversBefore);
                    ((ObjectNode)findings.get(findings.size() - 1)).set("transportAfter", serversAfter);
                }
                if (!old.path("x-business-semantics").equals(next.path("x-business-semantics"))
                        || !prior.path("x-business-semantics").equals(current.path("x-business-semantics"))) {
                    this.add(findings, bindings, key, "SEM001", "WARN", "/x-business-semantics",
                        "业务语义声明变化，需业务复核及关联消费者场景验收", null, null);
                }

                if (!prior.equals(current) && findings.size() == before) {
                    this.add(findings, bindings, key, "DOC001", "INFO", "", "说明、示例或其他接口元数据变化", null, null);
                }
            }
        }

        for (String key : b.keySet()) {
            if (!a.containsKey(key)) {
                this.add(findings, bindings, key, "ADD001", "INFO", "", "新增接口", null, null);
            }
        }

        long fail = 0L;
        long warn = 0L;
        long info = 0L;

        for (JsonNode f : findings) {
            String severity = f.path("severity").asText();
            if ("FAIL".equals(severity)) {
                fail++;
            } else if ("WARN".equals(severity)) {
                warn++;
            } else {
                info++;
            }
        }

        String verdict = fail > 0L ? "FAIL" : (!issues.isEmpty() ? "INCOMPLETE" : (warn > 0 ? "REVIEW_REQUIRED" : "PASS"));
        report.put("verdict", verdict);
        report.put("reviewRequired", warn > 0 || !issues.isEmpty());
        report.put("displayVerdict", verdict.equals("FAIL") ? "不兼容" : verdict.equals("INCOMPLETE") ? "检查不完整" : verdict.equals("REVIEW_REQUIRED") ? "需复核" : "兼容");
        report.put("engineVersion", "builtin-rules-v7");
        report.put("engineNote", "按请求与响应方向检查集合兼容性；未知语义保留复核状态");
        boolean transportReview = false;
        long structuralFail = 0, structuralWarn = 0;
        for (JsonNode finding : findings) {
            if (finding.path("rule").asText().equals("SRV001")) transportReview = true;
            else if (finding.path("severity").asText().equals("FAIL")) structuralFail++;
            else if (finding.path("severity").asText().equals("WARN")) structuralWarn++;
        }
        report.put("transportVerdict", transportReview ? "REVIEW_REQUIRED" : "UNCHANGED");
        report.put("structuralVerdict", structuralFail > 0 ? "FAIL" : !issues.isEmpty() ? "INCOMPLETE" : structuralWarn > 0 ? "REVIEW_REQUIRED" : "PASS");
        report.put("businessSemanticsVerified", false);
        report.put("businessSemanticsNote", "未执行业务语义或消费者运行验收；金额单位、状态含义、排序、授权和删除语义需项目确认。x-business-semantics 声明变化会要求复核，声明相同不代表业务已验收。");
        report.put("failCount", fail);
        report.put("warnCount", warn);
        report.put("infoCount", info);
        report.put("checkedOperations", a.size());
        ObjectNode coverage = report.putObject("coverage");
        coverage.put("complete", issues.isEmpty());
        ArrayNode list = coverage.putArray("issues");

        for (String issue : issues) {
            list.add(issue);
        }

        return report;
    }

    private Map<String, JsonNode> operations(JsonNode d, Set<String> issues) {
        Map<String, JsonNode> r = new LinkedHashMap<>();
        Iterator<Entry<String, JsonNode>> paths = d.path("paths").fields();

        while (paths.hasNext()) {
            Entry<String, JsonNode> p = paths.next();
            JsonNode pathItem = this.contracts.schema(p.getValue(), d, issues);
            Iterator<Entry<String, JsonNode>> methods = pathItem.fields();

            while (methods.hasNext()) {
                Entry<String, JsonNode> m = methods.next();
                if (METHODS.contains(m.getKey())) {
                    ObjectNode op = (ObjectNode)m.getValue().deepCopy();
                    ArrayNode params = Util.JSON.createArrayNode();

                    for (JsonNode x : pathItem.path("parameters")) {
                        params.add(x);
                    }

                    for (JsonNode x : m.getValue().path("parameters")) {
                        params.add(x);
                    }

                    if (!params.isEmpty()) {
                        op.set("parameters", params);
                    }

                    r.put(m.getKey().toUpperCase(Locale.ROOT) + " " + p.getKey(), op);
                }
            }
        }

        return r;
    }

    /** Operation overrides Path Item, which overrides the document. Descriptions do not affect endpoints. */
    private JsonNode effectiveServers(JsonNode document, String operationKey, Set<String> issues) {
        String[] key = operationKey.split(" ", 2);
        JsonNode item = contracts.schema(document.path("paths").path(key[1]), document, issues);
        JsonNode operation = item.path(key[0].toLowerCase(Locale.ROOT));
        String base = "/paths/" + Util.ptr(key[1]);
        JsonNode declared = operation.has("servers") ? operation.path("servers") : item.has("servers") ? item.path("servers") : document.path("servers");
        String pointer = operation.has("servers") ? base + "/" + key[0].toLowerCase(Locale.ROOT) + "/servers" : item.has("servers") ? base + "/servers" : "/servers";
        ObjectNode result = Util.obj().put("pointer", pointer);
        ArrayNode values = result.putArray("values");
        if (declared.isMissingNode() || declared.isArray() && declared.isEmpty()) values.addObject().put("url", "/");
        else if (!declared.isArray()) issues.add("servers 必须为数组：" + pointer);
        else for (JsonNode server : declared) {
            ObjectNode value = values.addObject();
            if (!server.isObject() || !server.path("url").isTextual() || server.path("url").asText().isEmpty()) issues.add("servers URL声明不合法：" + pointer);
            value.set("url", server.path("url"));
            if (server.has("variables")) {
                if (!server.path("variables").isObject()) issues.add("servers variables必须为对象：" + pointer);
                ObjectNode variables = value.putObject("variables");
                server.path("variables").fields().forEachRemaining(variable -> {
                    ObjectNode definition = variables.putObject(variable.getKey());
                    JsonNode source = variable.getValue();
                    if (!source.isObject() || !source.path("default").isTextual()) issues.add("服务器变量必须声明字符串default：" + pointer + "/variables/" + Util.ptr(variable.getKey()));
                    definition.set("default", source.path("default"));
                    if (source.has("enum")) {
                        if (!source.path("enum").isArray() || source.path("enum").isEmpty()) issues.add("服务器变量enum必须为非空数组：" + pointer);
                        java.util.TreeSet<String> choices = new java.util.TreeSet<String>();
                        for (JsonNode choice : source.path("enum")) {
                            if (!choice.isTextual()) issues.add("服务器变量enum值必须为字符串：" + pointer);
                            choices.add(choice.asText());
                        }
                        ArrayNode enumeration = definition.putArray("enum");
                        for (String choice : choices) enumeration.add(choice);
                        if (!choices.contains(source.path("default").asText())) issues.add("服务器变量default不在enum中：" + pointer);
                    }
                });
            }
        }
        return result;
    }

    private Map<String, JsonNode> parameters(JsonNode op, JsonNode root, Set<String> issues) {
        Map<String, JsonNode> r = new LinkedHashMap<>();

        for (JsonNode p : op.path("parameters")) {
            JsonNode resolved = this.contracts.schema(p, root, issues);
            r.put(ContractService.parameterKey(resolved), resolved);
        }

        return r;
    }

    private void compareResponseHeaders(JsonNode before, JsonNode after, JsonNode rootA, JsonNode rootB,
            String key, ArrayNode out, JsonNode bindings, Set<String> issues, String pointer) {
        Map<String, JsonNode> current = new LinkedHashMap<String, JsonNode>();
        Map<String, JsonNode> previous = new LinkedHashMap<String, JsonNode>();
        after.fields().forEachRemaining(e -> current.putIfAbsent(e.getKey().toLowerCase(Locale.ROOT), e.getValue()));
        before.fields().forEachRemaining(e -> previous.putIfAbsent(e.getKey().toLowerCase(Locale.ROOT), e.getValue()));
        previous.forEach((name, source) -> {
            JsonNode next = current.get(name);
            JsonNode prior = contracts.schema(source, rootA, issues);
            String location = pointer + "/" + Util.ptr(name);
            if (next == null) add(out, bindings, key, "HDR001", prior.path("required").asBoolean() ? "FAIL" : "WARN", location, "响应头不再声明，需要核对依赖该响应头的调用方", prior, null);
            else {
                JsonNode target = contracts.schema(next, rootB, issues);
                if (prior.path("required").asBoolean() && !target.path("required").asBoolean())
                    add(out, bindings, key, "HDR_REQUIRED", "FAIL", location, "响应头必填保证被取消", prior, target);
                compareSchema(prior.path("schema"), target.path("schema"), "response", location + "/schema", out, bindings, key, issues, 0);
            }
        });
    }

    private void compareContent(
        JsonNode a,
        JsonNode b,
        String direction,
        JsonNode rootA,
        JsonNode rootB,
        String key,
        ArrayNode out,
        JsonNode bindings,
        Set<String> issues,
        String pointer
    ) {
        Map<String, JsonNode> previousMedia = ContentTypes.normalized(a);
        Map<String, JsonNode> targetMedia = ContentTypes.normalized(b);
        Iterator<Entry<String, JsonNode>> it = a.fields();

        while (it.hasNext()) {
            Entry<String, JsonNode> e = it.next();
            JsonNode target = targetMedia.get(ContentTypes.normalize(e.getKey()));
            if (target == null) target = ContentTypes.match(b, e.getKey(), issues);
            if (target.isMissingNode()) {
                this.add(out, bindings, key, "MED001", direction.equals("request") ? "FAIL" : "WARN", pointer + "/" + Util.ptr(e.getKey()), "已声明媒体类型不再被目标契约覆盖", null, null);
            } else {
                this.compareSchema(
                    this.contracts.schema(e.getValue().path("schema"), rootA, issues),
                    this.contracts.schema(target.path("schema"), rootB, issues),
                    direction,
                    pointer + "/" + Util.ptr(e.getKey()) + "/schema",
                    out,
                    bindings,
                    key,
                    issues,
                    0
                );
            }
        }

        // A newly declared exact type takes precedence over a retained wildcard. Its effective
        // schema must be compared as well; checking only removed/retained keys misses narrowing.
        Iterator<Entry<String, JsonNode>> additions = b.fields();
        while (additions.hasNext()) {
            Entry<String, JsonNode> entry = additions.next();
            if (previousMedia.containsKey(ContentTypes.normalize(entry.getKey()))) continue;
            JsonNode prior = ContentTypes.match(a, entry.getKey(), issues);
            if (!prior.isMissingNode()) {
                compareSchema(contracts.schema(prior.path("schema"), rootA, issues),
                    contracts.schema(entry.getValue().path("schema"), rootB, issues), direction,
                    pointer + "/" + Util.ptr(entry.getKey()) + "/schema", out, bindings, key, issues, 0);
            } else if (direction.equals("response")) {
                add(out, bindings, key, "MED_ADDED", "WARN", pointer + "/" + Util.ptr(entry.getKey()),
                    "新增响应媒体类型，需要核对调用方是否能够接收及协商", null, entry.getValue());
            }
        }
    }

    private void compareSchema(
        JsonNode a, JsonNode b, String direction, String pointer, ArrayNode out, JsonNode bindings, String key, Set<String> issues, int depth
    ) {
        if (depth > 24) {
            issues.add("差异Schema嵌套过深：" + key);
        } else {
            CapabilityRegistry.schema(a, "compare", pointer + "/old", issues);
            CapabilityRegistry.schema(b, "compare", pointer + "/new", issues);
            if (!a.path("anyOf").equals(b.path("anyOf"))
                    && (!a.has("anyOf") || SchemaConstraints.requiredAlternatives(a))
                    && (!b.has("anyOf") || SchemaConstraints.requiredAlternatives(b))) {
                boolean compatible = direction.equals("request") ? requiredSubset(a, b) : requiredSubset(b, a);
                this.add(out, bindings, key, "REQUIRED_COMBINATION", compatible ? "INFO" : "FAIL",
                        pointer + "/anyOf", "业务字段必填组合变化", a, b);
            }
            String ta = a.path("type").asText();
            String tb = b.path("type").asText();
            if (!ta.equals(tb)) {
                boolean compatible = direction.equals("request")
                    ? tb.isEmpty() || ta.equals("integer") && tb.equals("number")
                    : ta.isEmpty() || ta.equals("number") && tb.equals("integer");
                this.add(out, bindings, key, "TYP001", compatible ? "INFO" : "FAIL", pointer, "字段类型或类型保证变化", a, b);
            }

            if (!a.path("x-business-semantics").equals(b.path("x-business-semantics"))) {
                this.add(out, bindings, key, "SEM001", "WARN", pointer + "/x-business-semantics", "字段业务语义声明变化，需业务及消费者场景复核", a, b);
            }

            if (a.path("nullable").asBoolean() != b.path("nullable").asBoolean()) {
                boolean breaks = direction.equals("request") ? a.path("nullable").asBoolean() : b.path("nullable").asBoolean();
                this.add(out, bindings, key, "NUL001", breaks ? "FAIL" : "INFO", pointer, "字段可空约束变化", a, b);
            }

            Set<String> oldReq = strings(a.path("required"));
            Set<String> newReq = strings(b.path("required"));
            if (direction.equals("request")) {
                for (String n : newReq) {
                    if (!oldReq.contains(n)) {
                        this.add(out, bindings, key, "REQ001", "FAIL", pointer + "/properties/" + Util.ptr(n), "请求字段新增必填约束", null, null);
                    }
                }
            } else {
                for (String nx : oldReq) {
                    if (!newReq.contains(nx)) {
                        this.add(out, bindings, key, "RES001", "FAIL", pointer + "/properties/" + Util.ptr(nx), "响应必填字段改为可选", null, null);
                    }
                }
            }

            JsonNode ap = a.path("properties");
            JsonNode bp = b.path("properties");
            Iterator<Entry<String, JsonNode>> api = ap.fields();

            while (api.hasNext()) {
                Entry<String, JsonNode> e = api.next();
                String nxx = e.getKey();
                if (!bp.has(nxx)) {
                    this.add(out, bindings, key, direction.equals("response") ? "RES001" : "REQ004",
                        direction.equals("response") && oldReq.contains(nxx) ? "FAIL" : "INFO",
                        pointer + "/properties/" + Util.ptr(nxx), "字段不再单独声明；同时检查额外属性约束", e.getValue(), null);
                    compareProperty(e.getValue(), additional(b), direction, pointer + "/properties/" + Util.ptr(nxx), out, bindings, key, issues, depth + 1);
                } else {
                    this.compareSchema(e.getValue(), bp.get(nxx), direction, pointer + "/properties/" + Util.ptr(nxx), out, bindings, key, issues, depth + 1);
                }
            }

            Iterator<Entry<String, JsonNode>> bpi = bp.fields();

            while (bpi.hasNext()) {
                Entry<String, JsonNode> e = bpi.next();
                if (!ap.has(e.getKey())) {
                    this.add(out, bindings, key, "ADD002", "INFO", pointer + "/properties/" + Util.ptr(e.getKey()), "新增字段", null, e.getValue());
                    compareProperty(additional(a), e.getValue(), direction, pointer + "/properties/" + Util.ptr(e.getKey()), out, bindings, key, issues, depth + 1);
                }
            }

            if (a.has("items") && b.has("items")) {
                this.compareSchema(a.get("items"), b.get("items"), direction, pointer + "/items", out, bindings, key, issues, depth + 1);
            }

            JsonNode extraA = (JsonNode)(a.has("additionalProperties") ? a.get("additionalProperties") : BooleanNode.TRUE);
            JsonNode extraB = (JsonNode)(b.has("additionalProperties") ? b.get("additionalProperties") : BooleanNode.TRUE);
            if (!extraA.equals(extraB)) {
                int priorCount = out.size();
                compareProperty(extraA, extraB, direction, pointer + "/additionalProperties", out, bindings, key, issues, depth + 1);
                if (priorCount == out.size()) add(out, bindings, key, "CON001", "INFO", pointer + "/additionalProperties", "额外对象属性约束变化", a, b);
            }

            if ((a.has("enum") || b.has("enum")) && !a.path("enum").equals(b.path("enum"))) {
                Set<String> oa = strings(a.path("enum"));
                Set<String> nb = strings(b.path("enum"));
                if (!direction.equals("request") || !b.has("enum") || a.has("enum") && enumSubset(a.path("enum"), b.path("enum"))) {
                    this.add(
                        out,
                        bindings,
                        key,
                        "ENU001",
                        !direction.equals("response") || !a.has("enum") || b.has("enum") && enumSubset(b.path("enum"), a.path("enum")) ? "INFO" : "FAIL",
                        pointer,
                        "枚举取值变化",
                        a,
                        b
                    );
                } else {
                    this.add(out, bindings, key, "REQ002", "FAIL", pointer, "请求枚举范围缩小", a, b);
                }
            }

            for (String k : CONSTRAINTS) {
                if (!SchemaConstraints.equal(a.path(k), b.path(k))) {
                    boolean breaks = false;
                    if (!NON_RESTRICTING.contains(k)) {
                        if (!a.has(k)) {
                            breaks = direction.equals("request");
                        } else if (!b.has(k)) {
                            breaks = direction.equals("response");
                        } else {
                            try {
                                int cmp = new BigDecimal(b.path(k).asText()).compareTo(new BigDecimal(a.path(k).asText()));
                                boolean lower = k.contains("minimum") || k.startsWith("min");
                                boolean requestRestricts = lower ? cmp > 0 : cmp < 0;
                                breaks = direction.equals("request") ? requestRestricts : cmp != 0 && !requestRestricts;
                            } catch (NumberFormatException var27) {
                                if (k.equals("format")) {
                                    issues.add("compare 格式变化需复核：" + pointer);
                                }
                            }
                        }
                    }

                    if (k.equals("format")) {
                        String before = a.path(k).asText();
                        String after = b.path(k).asText();
                        if (before.equals("int64") && after.equals("int32") || before.equals("int32") && after.equals("int64")) {
                            breaks = direction.equals("request") ? after.equals("int32") : after.equals("int64");
                        } else {
                            issues.add("compare 格式变化需复核：" + pointer);
                        }
                    }

                    this.add(out, bindings, key, breaks ? "CONSTRAINT_BREAK" : "CON001", breaks ? "FAIL" : "WARN", pointer + "/" + k, "字段约束变化：" + k, a, b);
                }
            }

            for (String bound : Arrays.asList("minimum", "maximum")) {
                String exclusive = bound.equals("minimum") ? "exclusiveMinimum" : "exclusiveMaximum";
                if (!SchemaConstraints.equal(a.path(bound), b.path(bound)) || a.path(exclusive).asBoolean() != b.path(exclusive).asBoolean()) {
                    boolean breaksx = direction.equals("request") ? restrictsBound(a, b, bound, exclusive) : restrictsBound(b, a, bound, exclusive);
                    this.add(out, bindings, key, "BOUND_CHANGE", breaksx ? "FAIL" : "INFO", pointer + "/" + bound, "数值有效边界变化", a, b);
                }
            }

            for (String boundx : Arrays.asList("x-date-minimum", "x-date-maximum")) {
                if (!a.path(boundx).equals(b.path(boundx))) {
                    boolean breaksx = false;
                    if (!a.has(boundx)) {
                        breaksx = direction.equals("request");
                    } else if (!b.has(boundx)) {
                        breaksx = direction.equals("response");
                    } else {
                        try {
                            int cmp = LocalDate.parse(b.path(boundx).asText()).compareTo((ChronoLocalDate)LocalDate.parse(a.path(boundx).asText()));
                            boolean restrict = boundx.endsWith("minimum") ? cmp > 0 : cmp < 0;
                            breaksx = direction.equals("request") ? restrict : cmp != 0 && !restrict;
                        } catch (RuntimeException var26) {
                            issues.add("日期边界需复核：" + pointer);
                        }
                    }

                    this.add(out, bindings, key, "DATE_BOUND_CHANGE", breaksx ? "FAIL" : "INFO", pointer + "/" + boundx, "日期有效边界变化", a, b);
                }
            }

            if (!a.path("x-precision").equals(b.path("x-precision")) || !a.path("x-scale").equals(b.path("x-scale"))) {
                boolean restrict = !a.has("x-precision") && b.has("x-precision")
                    || a.has("x-precision")
                        && b.has("x-precision")
                        && (
                            b.path("x-scale").asInt() < a.path("x-scale").asInt()
                                || b.path("x-precision").asInt() - b.path("x-scale").asInt() < a.path("x-precision").asInt() - a.path("x-scale").asInt()
                        );
                boolean expands = a.has("x-precision") && !b.has("x-precision")
                    || a.has("x-precision")
                        && b.has("x-precision")
                        && (
                            b.path("x-scale").asInt() > a.path("x-scale").asInt()
                                || b.path("x-precision").asInt() - b.path("x-scale").asInt() > a.path("x-precision").asInt() - a.path("x-scale").asInt()
                        );
                boolean breaksx = direction.equals("request") ? restrict : expands;
                this.add(out, bindings, key, "DECIMAL_PRECISION", breaksx ? "FAIL" : "INFO", pointer + "/x-precision", "金额有效位和小数位变化", a, b);
            }

            if (!SchemaConstraints.equal(a.path("multipleOf"), b.path("multipleOf"))) {
                boolean oldSubset = !b.has("multipleOf");
                boolean newSubset = !a.has("multipleOf");
                if (a.path("multipleOf").isNumber()
                    && b.path("multipleOf").isNumber()
                    && a.path("multipleOf").decimalValue().signum() > 0
                    && b.path("multipleOf").decimalValue().signum() > 0) {
                    oldSubset = a.path("multipleOf").decimalValue().remainder(b.path("multipleOf").decimalValue()).signum() == 0;
                    newSubset = b.path("multipleOf").decimalValue().remainder(a.path("multipleOf").decimalValue()).signum() == 0;
                }

                boolean breaksx = direction.equals("request") ? !oldSubset : !newSubset;
                this.add(out, bindings, key, "MULTIPLE_OF", breaksx ? "FAIL" : "INFO", pointer + "/multipleOf", "允许的倍数集合变化", a, b);
            }

            if (!a.path("pattern").equals(b.path("pattern"))) {
                boolean breaksx = direction.equals("request") ? !a.has("pattern") && b.has("pattern") : a.has("pattern") && !b.has("pattern");
                if (a.has("pattern") && b.has("pattern")) {
                    issues.add("compare 无法证明两个正则的集合包含关系：" + pointer);
                }

                this.add(out, bindings, key, "PATTERN_CHANGE", breaksx ? "FAIL" : "WARN", pointer + "/pattern", "模式限制变化", a, b);
            }
        }
    }

    private static JsonNode additional(JsonNode schema) {
        return schema.has("additionalProperties") ? schema.get("additionalProperties") : BooleanNode.TRUE;
    }

    /** Effective property schemas include names previously governed by additionalProperties. */
    private void compareProperty(JsonNode before, JsonNode after, String direction, String pointer,
            ArrayNode out, JsonNode bindings, String key, Set<String> issues, int depth) {
        boolean oldForbidden = before.isBoolean() && !before.asBoolean();
        boolean newForbidden = after.isBoolean() && !after.asBoolean();
        if (oldForbidden || newForbidden) {
            boolean breaks = direction.equals("request") ? !oldForbidden && newForbidden : oldForbidden && !newForbidden;
            if (breaks) add(out, bindings, key, "PROPERTY_DOMAIN", "FAIL", pointer, "字段声明与额外属性规则改变了可接受值集合", before, after);
            return;
        }
        // An unrestricted property already contains every nullable/type/enum combination.
        if ((direction.equals("response") && before.isBoolean() && before.asBoolean())
                || (direction.equals("request") && after.isBoolean() && after.asBoolean())) return;
        compareSchema(before.isBoolean() ? Util.obj() : before, after.isBoolean() ? Util.obj() : after,
            direction, pointer, out, bindings, key, issues, depth);
    }

    /** Presence-only anyOf implication, including unconditional required fields. */
    private static boolean requiredSubset(JsonNode subset, JsonNode superset) {
        for (Set<String> candidate : requiredSets(subset)) {
            boolean covered = false;
            for (Set<String> accepted : requiredSets(superset)) if (candidate.containsAll(accepted)) { covered = true; break; }
            if (!covered) return false;
        }
        return true;
    }

    private static List<Set<String>> requiredSets(JsonNode schema) {
        List<Set<String>> sets = new java.util.ArrayList<Set<String>>();
        Set<String> base = strings(schema.path("required"));
        if (!schema.has("anyOf")) sets.add(base);
        else for (JsonNode alternative : schema.path("anyOf")) {
            Set<String> names = new LinkedHashSet<String>(base);
            names.addAll(strings(alternative.path("required"))); sets.add(names);
        }
        return sets;
    }

    private static boolean enumSubset(JsonNode values, JsonNode container) {
        for (JsonNode value : values) {
            boolean found = false;

            for (JsonNode other : container) {
                if (SchemaConstraints.equal(value, other)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                return false;
            }
        }

        return true;
    }

    private static boolean restrictsBound(JsonNode a, JsonNode b, String bound, String exclusive) {
        if (!b.has(bound)) {
            return false;
        } else if (!a.has(bound)) {
            return true;
        } else if (a.path(bound).isNumber() && b.path(bound).isNumber()) {
            int cmp = b.path(bound).decimalValue().compareTo(a.path(bound).decimalValue());
            return (bound.equals("minimum") ? cmp > 0 : cmp < 0) || cmp == 0 && !a.path(exclusive).asBoolean() && b.path(exclusive).asBoolean();
        } else {
            return false;
        }
    }

    private void compareParameter(JsonNode a, JsonNode b, ArrayNode out, JsonNode bindings, String key, String pointer, Set<String> issues) {
        if (!a.has("content") && !b.has("content")) {
            String styleA = a.path("style").asText(!a.path("in").asText().equals("query") && !a.path("in").asText().equals("cookie") ? "simple" : "form");
            String styleB = b.path("style").asText(!b.path("in").asText().equals("query") && !b.path("in").asText().equals("cookie") ? "simple" : "form");
            boolean explodeA = a.path("explode").asBoolean(styleA.equals("form"));
            boolean explodeB = b.path("explode").asBoolean(styleB.equals("form"));
            if (!styleA.equals(styleB) || explodeA != explodeB || a.path("allowReserved").asBoolean() != b.path("allowReserved").asBoolean()) {
                this.add(out, bindings, key, "PARAM_ENCODING_CHANGE", "FAIL", pointer, "参数序列化协议变化", a, b);
            }
        } else {
            issues.add("参数content编码暂未完整比较：" + pointer);
        }
    }

    private static Set<String> strings(JsonNode a) {
        Set<String> r = new LinkedHashSet<>();

        for (JsonNode x : a) {
            r.add(x.isTextual() ? x.asText() : x.toString());
        }

        return r;
    }

    private void add(ArrayNode out, JsonNode bindings, String key, String rule, String severity, String pointer, String reason, JsonNode a, JsonNode b) {
        ObjectNode f = out.addObject();
        String[] p = key.split(" ", 2);
        f.put("method", p[0]);
        f.put("path", p[1]);
        f.put("rule", rule);
        f.put("severity", severity);
        f.put("schemaPointer", pointer);
        f.put("reason", reason);
        f.put("fingerprint", Util.sha(Util.bytes(key + rule + pointer + reason)));
        if (a != null) {
            f.set("oldConstraint", this.constraint(a));
        }

        if (b != null) {
            f.set("newConstraint", this.constraint(b));
        }

        ArrayNode pages = f.putArray("pages");
        if (bindings != null && bindings.isArray()) {
            for (JsonNode bind : bindings) {
                if (bind.path("method").asText().equalsIgnoreCase(p[0]) && bind.path("path").asText().equals(p[1])) {
                    pages.add(bind.deepCopy());
                }
            }
        }
    }

    private JsonNode constraint(JsonNode s) {
        if (s.isBoolean()) return s;
        ObjectNode r = Util.obj();

        for (String k : Arrays.asList(
            "type",
            "format",
            "required",
            "nullable",
            "enum",
            "minimum",
            "maximum",
            "minLength",
            "maxLength",
            "x-utf16-min-length",
            "x-utf16-max-length",
            "additionalProperties",
            "multipleOf",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "minProperties",
            "maxProperties",
            "x-precision",
            "x-scale",
            "x-numeric-minimum",
            "x-numeric-maximum",
            "x-temporal-precision",
            "x-date-minimum",
            "x-date-maximum",
            "pattern",
            "style",
            "explode",
            "allowReserved",
            "x-business-semantics"
        )) {
            if (s.has(k)) {
                r.set(k, s.get(k));
            }
        }

        return r;
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
