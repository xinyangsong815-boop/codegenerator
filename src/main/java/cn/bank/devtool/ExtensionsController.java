package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.Semaphore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ExtensionsController {
    private final Store store;
    private final Semaphore slots = new Semaphore(1);
    private final Map<String, JsonNode> profiles = Collections.synchronizedMap(new LinkedHashMap<>());

    public ExtensionsController(Store store) {
        this.store = store;
    }

    @PostMapping("/api/ddl/import")
    public JsonNode ddl(@RequestBody ExtensionsController.DdlRequest req) {
        this.enter();

        JsonNode var2;
        try {
            var2 = new DdlImportService().parse(req.ddl, req.packageName);
        } finally {
            this.slots.release();
        }

        return var2;
    }

    @PostMapping("/api/frontend/scan")
    public Map<String, Object> frontend(@RequestParam MultipartFile file, @RequestParam(defaultValue = "") String contractId) throws Exception {
        this.enter();

        Map var6;
        try {
            JsonNode doc = contractId.isEmpty() ? null : this.contract(contractId);
            JsonNode report = new FrontendCallService().scan(file.getBytes(), doc);
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("report.json", Util.bytes(Util.pretty(report)));
            files.put("bindings.json", Util.bytes(Util.pretty(report.path("bindings"))));
            files.put("report.html", Reports.html("前端接口关联", report));
            var6 = result("metadata", this.store.save("frontend", "前端接口关联", files), "report", report);
        } finally {
            this.slots.release();
        }

        return var6;
    }

    @PostMapping("/api/mock/generate")
    public Map<String, Object> generateMock(@RequestBody ExtensionsController.MockRequest req) {
        this.enter();

        Map var10;
        try {
            MockTestService service = new MockTestService();
            JsonNode source = this.contract(req.contractId);
            JsonNode report = service.generate(source);
            String title = source.path("info").path("title").asText("未命名接口");
            ((ObjectNode)report).put("sourceContractId", req.contractId).put("sourceTitle", title).put("createdAt", Instant.now().toString());
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("report.json", Util.bytes(Util.pretty(report)));
            files.put("report.html", Reports.html("离线Mock及测试用例", report));
            files.put("bundle.zip", service.bundle(report));
            JsonNode meta = this.store.save("mock", "Mock · " + title + " · " + req.contractId.substring(0, 8), files);
            String id = null;
            String reason = "";
            if (report.path("metrics").path("available").asInt() == 0) {
                reason = "没有可用响应，报告已保存，请处理覆盖问题。";
            } else if (this.profiles.size() >= 16) {
                reason = "启用配置已达16个，报告已保存；停用旧配置后重新生成。";
            } else {
                id = UUID.randomUUID().toString();
                this.profiles.put(id, report);
            }

            var10 = result("metadata", meta, "report", report, "profileId", id, "mockBase", id == null ? null : "/mock/" + id, "activationMessage", reason);
        } finally {
            this.slots.release();
        }

        return var10;
    }

    @GetMapping("/api/mock/profiles")
    public JsonNode listProfiles() {
        ArrayNode arr = Util.JSON.createArrayNode();
        synchronized (this.profiles) {
            for (Entry<String, JsonNode> p : this.profiles.entrySet()) {
                int count = 0;

                for (JsonNode op : p.getValue().path("operations")) {
                    if (op.path("available").asBoolean()) {
                        count++;
                    }
                }

                arr.addObject()
                    .put("id", p.getKey())
                    .put("responses", count)
                    .put("sourceTitle", p.getValue().path("sourceTitle").asText())
                    .put("sourceContractId", p.getValue().path("sourceContractId").asText())
                    .put("createdAt", p.getValue().path("createdAt").asText())
                    .put("mockBase", "/mock/" + p.getKey());
            }

            return arr;
        }
    }

    @DeleteMapping("/api/mock/profiles/{id}")
    public JsonNode disable(@PathVariable String id) {
        if (this.profiles.remove(id) == null) {
            throw new NoSuchElementException();
        } else {
            return Util.obj().put("disabled", true);
        }
    }

    @RequestMapping(
        value = "/mock/{id}/**",
        method = {
                RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.HEAD, RequestMethod.OPTIONS
        }
    )
    public ResponseEntity<byte[]> mock(@PathVariable String id, HttpServletRequest req) {
        JsonNode profile = this.profiles.get(id);
        if (profile == null) {
            throw new NoSuchElementException();
        } else {
            String prefix = req.getContextPath() + "/mock/" + id;
            String path = req.getRequestURI().substring(prefix.length());
            String method = req.getMethod();
            String status = req.getHeader("X-Mock-Status");
            if (status != null && !status.matches("[1-5][0-9]{2}")) {
                throw new IllegalArgumentException("X-Mock-Status必须是三位HTTP状态码");
            } else {
                List<JsonNode> exact = new ArrayList<>();
                List<JsonNode> templates = new ArrayList<>();
                Set<String> templatePaths = new HashSet<>();

                for (JsonNode op : profile.path("operations")) {
                    if (op.path("method").asText().equals(method)) {
                        String declared = op.path("path").asText();
                        if (declared.equals(path)) {
                            exact.add(op);
                        } else if (matches(declared, path)) {
                            templates.add(op);
                            templatePaths.add(declared);
                        }
                    }
                }

                if (exact.isEmpty() && templatePaths.size() > 1) {
                    throw new IllegalArgumentException("路径匹配多个Mock接口，请调整契约");
                } else {
                    for (JsonNode opx : exact.isEmpty() ? templates : exact) {
                        if (status == null ? opx.path("default").asBoolean() : opx.path("status").asInt() == Integer.parseInt(status)) {
                            if (!opx.path("available").asBoolean()) {
                                throw new IllegalArgumentException("该响应未通过完整样例校验，请查看生成报告");
                            } else {
                                return ((BodyBuilder)ResponseEntity.status(opx.path("status").asInt()).header("X-Offline-Mock", new String[]{"synthetic"}))
                                    .contentType(MediaType.parseMediaType(opx.path("media").asText()))
                                    .body(Util.bytes(opx.path("bodyText").asText()));
                            }
                        }
                    }

                    throw new NoSuchElementException();
                }
            }
        }
    }

    private static boolean matches(String template, String value) {
        String[] a = template.split("/", -1);
        String[] b = value.split("/", -1);
        if (a.length != b.length) {
            return false;
        } else {
            for (int i = 0; i < a.length; i++) {
                if (a[i].matches("\\{[^{}]+\\}")) {
                    if (b[i].isEmpty()) {
                        return false;
                    }
                } else if (!a[i].equals(b[i])) {
                    return false;
                }
            }

            return true;
        }
    }

    private JsonNode contract(String id) {
        return Util.parse(this.store.read(id, "openapi.json"));
    }

    private void enter() {
        if (!this.slots.tryAcquire()) {
            throw new ApiController.BusyException();
        }
    }

    private static Map<String, Object> result(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();

        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String)pairs[i], pairs[i + 1]);
        }

        return out;
    }

    public static class DdlRequest {
        public String ddl;
        public String packageName;
    }

    public static class MockRequest {
        public String contractId;
    }
}
