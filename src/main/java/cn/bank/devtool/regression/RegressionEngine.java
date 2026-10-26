package cn.bank.devtool.regression;

import cn.bank.devtool.Util;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.cookie.BasicClientCookie;

public final class RegressionEngine implements Closeable {
    private final RegressionStore store;
    private final TargetPolicy policy;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10), r -> {
        Thread t = new Thread(r, "regression-runner");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "regression-deadline");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentMap<String, RegressionEngine.Job> jobs = new ConcurrentHashMap<>();

    public RegressionEngine(Path root, String allowed) {
        this.policy = new TargetPolicy(allowed);
        this.store = new RegressionStore(root);
    }

    public List<String> allowed() {
        return this.policy.allowed();
    }

    public List<JsonNode> list(String kind) {
        List<JsonNode> items = this.store.list(kind);
        return kind.equals("runs") ? items.subList(0, Math.min(200, items.size())) : items;
    }

    public List<JsonNode> all(String kind) {
        return this.store.list(kind);
    }

    public ObjectNode page(String kind, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("分页范围不合法，limit应为1至200");
        List<JsonNode> items = this.all(kind);
        int from = Math.min(offset, items.size());
        int to = from + Math.min(limit, items.size() - from);
        ObjectNode page = Util.obj().put("totalCount", items.size()).put("offset", offset).put("limit", limit).put("hasMore", to < items.size());
        page.set("items", Util.JSON.valueToTree(items.subList(from, to)));
        page.set("storageDiagnostics", this.diagnostics());
        return page;
    }

    public JsonNode diagnostics() {
        return this.store.diagnostics();
    }

    public JsonNode get(String kind, String id) {
        RegressionEngine.Job j = kind.equals("runs") ? this.jobs.get(id) : null;
        if (j != null) {
            synchronized (j) {
                return j.result.deepCopy();
            }
        } else {
            return this.store.get(kind, id);
        }
    }

    public synchronized JsonNode environment(JsonNode n) {
        ObjectNode v = this.policy.validate(this.withoutMeta(n));
        v.put("updatedAt", Instant.now().toString());
        this.store.save("environments", v.path("id").asText(), v);
        return v;
    }

    public synchronized JsonNode scenario(JsonNode n) {
        JsonNode clean = this.withoutMeta(n);
        ObjectNode structural = (ObjectNode)clean.deepCopy();
        structural.put("draft", false);
        ScenarioRules.check(structural);
        ObjectNode v = (ObjectNode)clean.deepCopy();
        v.put("updatedAt", Instant.now().toString());
        this.store.save("scenarios", v.path("id").asText(), v);
        return v;
    }

    private JsonNode withoutMeta(JsonNode n) {
        if (n == null || !n.isObject()) throw new IllegalArgumentException("场景或环境必须是JSON对象");
        ObjectNode c = (ObjectNode)n.deepCopy();
        c.remove("updatedAt");
        return c;
    }

    public synchronized void delete(String kind, String id) {
        if (!Arrays.asList("environments", "scenarios").contains(kind)) {
            throw new IllegalArgumentException("不支持删除运行记录");
        } else {
            this.store.delete(kind, id);
        }
    }

    public synchronized JsonNode start(String sid, String eid, String requestId, Map<String, String> credentials, String expectedHash) {
        if (requestId != null && requestId.matches("[A-Za-z0-9_-]{1,64}")) {
            JsonNode s = this.withoutMeta(this.store.get("scenarios", sid));
            JsonNode e = this.withoutMeta(this.store.get("environments", eid));
            ScenarioRules.check(s);
            this.policy.validate(e);
            String hash = Util.sha(Util.bytes(Util.pretty(s)));
            String fingerprint = Util.sha(Util.bytes(sid + "\n" + eid + "\n" + hash + "\n" + Util.pretty(e)));
            if (expectedHash != null && !expectedHash.equals(hash)) {
                throw new IllegalArgumentException("场景版本已变化，请刷新后执行");
            } else {
                for (JsonNode r : this.store.list("runs")) {
                    if (r.path("clientRequestId").asText().equals(requestId)) {
                        if (!r.path("launchHash").asText().equals(fingerprint)) {
                            throw new IllegalArgumentException("clientRequestId已用于不同执行请求");
                        }

                        return this.get("runs", r.path("id").asText());
                    }
                }

                checkCredentials(credentials, e);
                if (this.jobs.size() >= 11) {
                    throw new IllegalArgumentException("运行队列已满");
                } else {
                    String id = UUID.randomUUID().toString();
                    ObjectNode rx = Util.obj();
                    rx.put("id", id)
                        .put("scenarioId", sid)
                        .put("environmentId", eid)
                        .put("name", s.path("name").asText())
                        .put("state", "QUEUED")
                        .put("verdict", "INCOMPLETE")
                        .put("cleanupStatus", "NOT_REQUIRED")
                        .put("clientRequestId", requestId)
                        .put("launchHash", fingerprint)
                        .put("definitionHash", hash)
                        .put("updatedAt", Instant.now().toString());
                    rx.set("scenarioSnapshot", redact(s));
                    rx.set("environmentSnapshot", e);
                    rx.putArray("steps");
                    rx.putArray("resources");
                    rx.putArray("cleanupSteps");
                    RegressionEngine.Job j = new RegressionEngine.Job(rx, s, e, credentials == null ? Collections.emptyMap() : credentials);
                    this.store.save("runs", id, rx);
                    this.jobs.put(id, j);

                    try {
                        this.workers.execute(() -> this.execute(j));
                    } catch (RejectedExecutionException var16) {
                        this.jobs.remove(id);
                        j.credentials.clear();
                        rx.put("state", "FINISHED").put("verdict", "ERROR").put("reason", "队列已满");
                        this.store.save("runs", id, rx);
                        throw new IllegalArgumentException("运行队列已满");
                    }

                    synchronized (j) {
                        return rx.deepCopy();
                    }
                }
            }
        } else {
            throw new IllegalArgumentException("clientRequestId不合法");
        }
    }

    public synchronized JsonNode batch(List<String> ids, String environment, String requestId, Map<String, String> credentials) {
        if (ids != null && !ids.isEmpty() && ids.size() <= 10 && new HashSet<>(ids).size() == ids.size()) {
            if (requestId != null && requestId.matches("[A-Za-z0-9_-]{1,48}")) {
                JsonNode env = this.withoutMeta(this.store.get("environments", environment));
                this.policy.validate(env);
                checkCredentials(credentials, env);
                List<JsonNode> existing = this.store.list("runs");
                int additions = 0;

                for (int i = 0; i < ids.size(); i++) {
                    String id = ids.get(i);
                    JsonNode spec = this.withoutMeta(this.store.get("scenarios", id));
                    ScenarioRules.check(spec);
                    String hash = Util.sha(Util.bytes(Util.pretty(spec)));
                    String fingerprint = Util.sha(Util.bytes(id + "\n" + environment + "\n" + hash + "\n" + Util.pretty(env)));
                    boolean found = false;

                    for (JsonNode old : existing) {
                        if (old.path("clientRequestId").asText().equals(requestId + "_" + i)) {
                            found = true;
                            if (!old.path("launchHash").asText().equals(fingerprint)) {
                                throw new IllegalArgumentException("批次请求ID已用于其他场景或版本");
                            }
                        }
                    }

                    if (!found) {
                        additions++;
                    }
                }

                ObjectNode result = Util.obj().put("batchId", requestId);
                ArrayNode runs = result.putArray("runs");
                if (this.jobs.size() + additions > 11) {
                    throw new IllegalArgumentException("队列容量不足，请稍后执行批次");
                } else {
                    for (int i = 0; i < ids.size(); i++) {
                        runs.add(this.start(ids.get(i), environment, requestId + "_" + i, credentials, null));
                    }

                    return result;
                }
            } else {
                throw new IllegalArgumentException("批次请求ID不合法");
            }
        } else {
            throw new IllegalArgumentException("批次需1至10个不同场景");
        }
    }

    private static void checkCredentials(Map<String, String> c, JsonNode e) {
        if (c == null) {
            c = Collections.emptyMap();
        }

        for (Entry<String, String> a : c.entrySet()) {
            if (!Arrays.asList("token", "cookie", "csrfHeader", "csrfValue").contains(a.getKey())
                || a.getValue() == null
                || a.getValue().length() > 8192
                || a.getValue().matches("(?s).*[\\r\\n\\x00].*")) {
                throw new IllegalArgumentException("凭证格式不合法");
            }
        }

        String mode = e.path("authMode").asText();
        if (mode.equals("TOKEN") && Util.isBlank(c.get("token"))) {
            throw new IllegalArgumentException("请提供本次运行Token");
        } else if (mode.equals("SESSION") && Util.isBlank(c.get("cookie"))) {
            throw new IllegalArgumentException("请提供本次运行Session Cookie");
        } else {
            if (c.containsKey("csrfHeader")) {
                ScenarioRules.header(c.get("csrfHeader"));
                if (!c.containsKey("csrfValue")) {
                    throw new IllegalArgumentException("CSRF值缺失");
                }
            }
        }
    }

    public JsonNode cancel(String id) {
        RegressionEngine.Job j = this.jobs.get(id);
        if (j == null) {
            return this.get("runs", id);
        } else {
            synchronized (j) {
                if (j.result.path("state").asText().equals("FINISHED")) return j.result.deepCopy();
                j.cancel.set(true);
                j.result.put("state", "STOPPING");
                this.save(j);
            }

            HttpRequestBase q = j.request;
            if (q != null) q.abort();

            return this.get("runs", id);
        }
    }

    public JsonNode confirmReset(String id, String note) {
        if (note == null || note.trim().isEmpty() || note.length() > 200) {
            throw new IllegalArgumentException("需填写重置核对说明");
        } else if (this.jobs.containsKey(id)) {
            throw new IllegalArgumentException("执行尚未结束");
        } else {
            ObjectNode r = (ObjectNode)this.get("runs", id);
            if (!Arrays.asList("MANUAL_REQUIRED", "FAILED").contains(r.path("cleanupStatus").asText())) {
                throw new IllegalArgumentException("无需人工确认");
            } else {
                r.put("cleanupStatus", "MANUALLY_CONFIRMED").put("cleanupConfirmation", note).put("cleanupConfirmedAt", Instant.now().toString());
                r.put("overallVerdict", RegressionReports.overall(r));
                this.store.save("runs", id, r);
                return r;
            }
        }
    }

    private void execute(RegressionEngine.Job j) {
        j.started = true;
        j.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(j.env.path("scenarioTimeoutMs").asInt(300000));
        String verdict = "PASS";
        boolean unknown = false;
        boolean wrote = false;
        synchronized (j) {
            j.result.put("state", j.cancel.get() ? "STOPPING" : "RUNNING").put("startedAt", Instant.now().toString());
            this.save(j);
        }

        j.scenario.path("variables").fields().forEachRemaining(e -> {
            JsonNode v = (JsonNode)e.getValue();
            String scopedId = Util.sha(Util.bytes(j.result.path("id").asText() + "\n" + e.getKey())).substring(0, 16);
            j.vars.put((String)e.getKey(), (JsonNode)(v.isObject() ? TextNode.valueOf(v.path("prefix").asText("") + scopedId) : v.deepCopy()));
        });
        BasicCookieStore cookies = new BasicCookieStore();
        if (j.env.path("authMode").asText().equals("SESSION")) {
            try {
                URI target = new URI(j.env.path("baseUrl").asText());

                for (String part : j.credentials.get("cookie").split(";")) {
                    String[] kv = part.trim().split("=", 2);
                    if (kv.length != 2 || !kv[0].matches("[A-Za-z0-9_-]+")) {
                        throw new IllegalArgumentException("Cookie格式错误");
                    }

                    BasicClientCookie c = new BasicClientCookie(kv[0], kv[1]);
                    c.setDomain(target.getHost());
                    c.setPath("/");
                    c.setSecure(target.getScheme().equals("https"));
                    cookies.addCookie(c);
                }
            } catch (Exception var48) {
                synchronized (j) {
                    j.result.put("state", "FINISHED").put("verdict", "ERROR").put("reason", "Cookie格式不合法");
                    this.save(j);
                }

                j.credentials.clear();
                j.vars.clear();
                this.jobs.remove(j.result.path("id").asText());
                return;
            }
        }

        try {
            CloseableHttpClient client = HttpClients.custom()
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableContentCompression()
                .setDefaultCookieStore(cookies)
                .setDnsResolver(host -> {
                    try {
                        URI u = new URI(j.env.path("baseUrl").asText());
                        if (!host.equalsIgnoreCase(u.getHost())) {
                            throw new UnknownHostException("目标主机不允许");
                        } else {
                            return this.policy.addresses(j.env);
                        }
                    } catch (Exception var4x) {
                        throw new UnknownHostException("目标解析失败");
                    }
                })
                .build();

            try {
                for (JsonNode step : j.scenario.path("steps")) {
                    if (verdict.equals("PASS") && !j.cancel.get() && System.nanoTime() < j.deadline) {
                        ObjectNode result = this.runStep(j, client, step, false);
                        String v = result.path("verdict").asText();
                        if (!v.equals("PASS")) {
                            verdict = v;
                        }

                        wrote |= result.path("writeDispatched").asBoolean();
                        unknown |= result.path("effectState").asText().equals("UNKNOWN_EFFECT");
                    } else {
                        ObjectNode skip = Util.obj().put("id", step.path("id").asText()).put("name", step.path("name").asText()).put("state", "SKIPPED");
                        synchronized (j) {
                            ((ArrayNode)j.result.path("steps")).add(skip);
                            this.save(j);
                        }

                        if (verdict.equals("PASS")) {
                            verdict = j.cancel.get() ? "CANCELLED" : "ERROR";
                        }
                    }
                }

                String cleanup = j.scenario.path("cleanupPolicy").asText("NONE");
                String cleanupStatus = "NOT_REQUIRED";
                if (wrote) {
                    cleanupStatus = "MANUAL_REQUIRED";
                    if (cleanup.equals("HTTP_CLEANUP") && !unknown && !j.cancel.get() && System.nanoTime() < j.deadline) {
                        boolean okay = true;
                        List<JsonNode> clean = new ArrayList<>();
                        j.scenario.path("cleanupSteps").forEach(clean::add);

                        for (JsonNode c : clean) {
                            if (j.cancel.get() || System.nanoTime() >= j.deadline) {
                                okay = false;
                                break;
                            }

                            try {
                                ObjectNode rr = this.runStep(j, client, c, true);
                                okay &= rr.path("verdict").asText().equals("PASS");
                            } catch (Exception var42) {
                                okay = false;
                            }
                        }

                        cleanupStatus = okay ? "DONE" : "FAILED";
                    }
                }

                synchronized (j) {
                    j.result.put("cleanupStatus", cleanupStatus);
                }
            } catch (Throwable var45) {
                if (client != null) {
                    try {
                        client.close();
                    } catch (Throwable var40) {
                        var45.addSuppressed(var40);
                    }
                }

                throw var45;
            }

            if (client != null) {
                client.close();
            }
        } catch (Exception var46) {
            verdict = "ERROR";
            synchronized (j) {
                j.result.put("reason", "执行器异常；未保存原始响应或凭证").put("cleanupStatus", wrote ? "MANUAL_REQUIRED" : "NOT_REQUIRED");
            }
        } finally {
            j.credentials.clear();
            j.vars.clear();
            cookies.clear();
            j.request = null;
            synchronized (j) {
                j.result.put("state", "FINISHED").put("verdict", j.cancel.get() ? "CANCELLED" : verdict).put("endedAt", Instant.now().toString());
                j.result.put("overallVerdict", RegressionReports.overall(j.result));
                this.save(j);
            }

            this.jobs.remove(j.result.path("id").asText());
        }
    }

    private ObjectNode runStep(RegressionEngine.Job j, CloseableHttpClient client, JsonNode definition, boolean cleanup) {
        ObjectNode r = Util.obj()
            .put("id", definition.path("id").asText())
            .put("name", definition.path("name").asText())
            .put("state", "PREPARING")
            .put("verdict", "INCOMPLETE");
        r.putArray("assertions");
        long start = System.nanoTime();
        boolean dispatched = false;
        boolean write = false;
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> deadlineTask = null;
        HttpRequestBase req = null;
        synchronized (j) {
            ((ArrayNode)j.result.path(cleanup ? "cleanupSteps" : "steps")).add(r);
            this.save(j);
        }

        try {
            JsonNode request = ScenarioRules.resolve(definition.path("request"), j.vars);
            final String method = request.path("method").asText();
            write = !Arrays.asList("GET", "HEAD", "OPTIONS").contains(method);
            URI uri = this.policy.uri(j.env, request);
            req = new HttpRequestBase() {
                public String getMethod() {
                    return method;
                }
            };
            if (request.has("body")) {
                HttpEntityEnclosingRequestBase entity = new HttpEntityEnclosingRequestBase() {
                    public String getMethod() {
                        return method;
                    }
                };
                byte[] body = Util.bytes(Util.pretty(request.path("body")));
                if (body.length > 1048576) {
                    throw new IllegalArgumentException("请求体过大");
                }

                entity.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
                req = entity;
            }

            req.setURI(uri);
            req.setConfig(
                RequestConfig.custom()
                    .setConnectTimeout(j.env.path("connectTimeoutMs").asInt(3000))
                    .setConnectionRequestTimeout(3000)
                    .setSocketTimeout(j.env.path("readTimeoutMs").asInt(10000))
                    .build()
            );
            req.setHeader("Accept", "application/json");
            Iterator<Entry<String, JsonNode>> hs = request.path("headers").fields();

            while (hs.hasNext()) {
                Entry<String, JsonNode> h = hs.next();
                ScenarioRules.header(h.getKey());
                if (!h.getValue().isTextual()) throw new IllegalArgumentException("请求头变量必须解析为字符串");
                String value = h.getValue().asText();
                if (value.matches("(?s).*[\\r\\n\\x00].*")) {
                    throw new IllegalArgumentException("请求头不合法");
                }

                req.setHeader(h.getKey(), value);
            }

            String auth = j.env.path("authMode").asText();
            if (auth.equals("TOKEN")) {
                req.setHeader(j.env.path("authHeader").asText(), j.credentials.get("token"));
            }

            if (j.credentials.containsKey("csrfHeader")) {
                req.setHeader(j.credentials.get("csrfHeader"), j.credentials.get("csrfValue"));
            }

            if (j.cancel.get() || System.nanoTime() >= j.deadline) {
                throw new IOException("cancel/deadline");
            }

            j.request = req;
            synchronized (j) {
                r.put("method", method)
                    .put("path", definition.path("request").path("path").asText())
                    .put("state", "DISPATCHING")
                    .put("effectState", write ? "UNKNOWN_EFFECT" : "READ_ONLY");
                this.save(j);
            }

            HttpRequestBase active = req;
            long ms = Math.max(1L, Math.min((long)j.env.path("stepTimeoutMs").asInt(30000), TimeUnit.NANOSECONDS.toMillis(j.deadline - System.nanoTime())));
            deadlineTask = this.timer.schedule(() -> {
                timedOut.set(true);
                active.abort();
            }, ms, TimeUnit.MILLISECONDS);
            dispatched = true;
            synchronized (j) {
                r.put("writeDispatched", write);
            }

            CloseableHttpResponse response = client.execute(req);

            try {
                int status = response.getStatusLine().getStatusCode();
                byte[] body = bounded(response.getEntity());
                JsonNode parsed = MissingNode.getInstance();
                boolean validJson = false;
                if (body.length > 0) {
                    try {
                        RegressionRequestGuard.validate(body);
                        parsed = Util.JSON.readTree(body);
                        validJson = parsed != null && !parsed.isMissingNode();
                    } catch (Exception var63) {
                    }
                }

                if (timedOut.get() || j.cancel.get()) {
                    throw new IOException("cancel/deadline");
                }

                boolean resourceValid = true;
                synchronized (j) {
                    r.put("status", status)
                        .put("responseBytes", body.length)
                        .put("effectState", write ? (status >= 200 && status < 300 ? "ACKNOWLEDGED" : "UNVERIFIED") : "READ_ONLY");
                    if (definition.has("resource") && status >= 200 && status < 300) {
                        JsonNode handle = parsed.at(definition.path("resource").path("pointer").asText());
                        if (validJson && ResourcePrivacy.identifier(handle) && ScenarioRules.type(handle, definition.path("resource").path("type").asText("integer"))) {
                            ObjectNode resource = ((ArrayNode)j.result.path("resources")).addObject();
                            resource.put("stepId", definition.path("id").asText());
                            resource.set("id", handle);
                        } else {
                            resourceValid = false;
                            r.put("resourceError", "响应未提供符合声明类型及安全长度的标量资源标识，无法确认资源跟踪");
                        }
                    }

                    this.save(j);
                }

                boolean pass = resourceValid;

                for (JsonNode a : definition.path("assertions")) {
                    JsonNode expected = ScenarioRules.resolve(a.path("expected"), j.vars);
                    JsonNode actual = (JsonNode)(a.path("source").asText().equals("status") ? IntNode.valueOf(status) : parsed.at(a.path("pointer").asText()));
                    boolean jsonRequired = a.path("source").asText().equals("body");
                    boolean okay = (!jsonRequired || validJson) && ScenarioRules.assertValue(actual, a.path("op").asText(), expected);
                    pass &= okay;
                    ObjectNode ar = Util.obj()
                        .put("source", a.path("source").asText())
                        .put("pointer", a.path("pointer").asText())
                        .put("op", a.path("op").asText())
                        .put("passed", okay)
                        .put("actualType", actual.getNodeType().name())
                        .put("expectedType", expected.getNodeType().name())
                        .put(
                            "failureReason",
                            okay
                                ? ""
                                : (jsonRequired && !validJson ? "响应不是有效且无重复键的JSON" : (actual.isMissingNode() ? "响应字段不存在" : (a.path("op").asText().equals("type") ? "字段类型不匹配" : "字段存在，但未满足此断言；请核对字段指针、运算符和预期类型")))
                        );
                    if (a.path("source").asText().equals("status")) {
                        ar.set("expected", expected);
                        ar.set("actual", actual);
                    } else {
                        ar.put("expected", "[REDACTED]").put("actual", "[REDACTED]");
                    }

                    synchronized (j) {
                        ((ArrayNode)r.path("assertions")).add(ar);
                    }
                }

                Map<String, JsonNode> extracts = new LinkedHashMap<>();

                for (JsonNode e : definition.path("extract")) {
                    JsonNode v = (JsonNode)(e.path("source").asText().equals("status") ? IntNode.valueOf(status) : parsed.at(e.path("pointer").asText()));
                    if (e.path("source").asText().equals("body") && !validJson) {
                        pass = false;
                        synchronized (j) { r.put("extractError", "响应不是有效JSON，无法确认可选变量是否存在"); }
                        continue;
                    }
                    if (!v.isMissingNode() || e.path("required").asBoolean(true)) {
                        if (!ScenarioRules.type(v, e.path("type").asText())) {
                            pass = false;
                            synchronized (j) {
                                r.put("extractError", "变量提取缺失或类型不匹配");
                            }
                        } else {
                            extracts.put(e.path("name").asText(), v.deepCopy());
                        }
                    }
                }

                if (pass) {
                    j.vars.putAll(extracts);
                }

                synchronized (j) {
                    r.put("state", "FINISHED").put("verdict", pass ? "PASS" : "FAIL");
                }
            } catch (Throwable var69) {
                if (response != null) {
                    try {
                        response.close();
                    } catch (Throwable var59) {
                        var69.addSuppressed(var59);
                    }
                }

                throw var69;
            }

            if (response != null) {
                response.close();
            }
        } catch (Exception var70) {
            Exception ex = var70;
            synchronized (j) {
                r.put("state", "FINISHED")
                    .put("verdict", j.cancel.get() ? "CANCELLED" : "ERROR")
                    .put("reason", ex instanceof IllegalArgumentException ? "请求配置或变量错误" : "网络、截止时间或响应体限制异常");
                r.put("writeDispatched", write && dispatched);
                if (write && dispatched) {
                    r.put("effectState", "UNKNOWN_EFFECT");
                }
            }
        } finally {
            if (deadlineTask != null) {
                deadlineTask.cancel(false);
            }

            if (req != null) {
                req.releaseConnection();
            }

            j.request = null;
            synchronized (j) {
                r.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                this.save(j);
            }
        }

        return r;
    }

    private static byte[] bounded(HttpEntity e) throws IOException {
        if (e == null) {
            return new byte[0];
        } else if (e.getContentLength() > 1048576L) {
            throw new IOException("response limit");
        } else {
            byte[] var5;
            try (
                InputStream in = e.getContent();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
            ) {
                byte[] b = new byte[8192];

                int n;
                while ((n = in.read(b)) != -1) {
                    if (out.size() + n > 1048576) {
                        throw new IOException("response limit");
                    }

                    out.write(b, 0, n);
                }

                var5 = out.toByteArray();
            }

            return var5;
        }
    }

    private void save(RegressionEngine.Job j) {
        j.result.put("updatedAt", Instant.now().toString());
        this.store.save("runs", j.result.path("id").asText(), j.result);
    }

    public static JsonNode redact(JsonNode n) {
        if (n.isObject()) {
            ObjectNode o = Util.obj();
            n.fields()
                .forEachRemaining(
                    e -> {
                        String k = (String)e.getKey();
                        o.set(
                            k,
                            (JsonNode)(k.toLowerCase(Locale.ROOT).matches(".*(password|passwd|secret|token|cookie|authorization|csrf).*")
                                ? TextNode.valueOf("[REDACTED]")
                                : redact((JsonNode)e.getValue()))
                        );
                    }
                );
            return o;
        } else if (n.isArray()) {
            ArrayNode a = Util.JSON.createArrayNode();
            n.forEach(v -> a.add(redact(v)));
            return a;
        } else {
            return n.deepCopy();
        }
    }

    @Override
    public void close() throws IOException {
        for (RegressionEngine.Job j : this.jobs.values()) {
            j.cancel.set(true);
            if (j.request != null) {
                j.request.abort();
            }
        }

        this.workers.shutdown();

        try {
            if (!this.workers.awaitTermination(5L, TimeUnit.SECONDS)) {
                this.workers.shutdownNow();
            }
        } catch (InterruptedException var3) {
            Thread.currentThread().interrupt();
        }

        this.timer.shutdownNow();
        this.store.close();
    }

    static final class Job {
        final ObjectNode result;
        final JsonNode scenario;
        final JsonNode env;
        final Map<String, JsonNode> vars = new HashMap<>();
        final Map<String, String> credentials = new HashMap<>();
        final AtomicBoolean cancel = new AtomicBoolean();
        volatile HttpRequestBase request;
        volatile boolean started;
        long deadline;

        Job(ObjectNode result, JsonNode scenario, JsonNode env, Map<String, String> creds) {
            this.result = result;
            this.scenario = scenario;
            this.env = env;
            this.credentials.putAll(creds);
        }
    }
}
