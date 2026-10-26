package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Semaphore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final Store store;
    private final LocalGuard guard;
    private final Semaphore slots = new Semaphore(2);

    public ApiController(Store store, LocalGuard guard) {
        this.store = store;
        this.guard = guard;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Util.mapOf(
            "version",
            "2.1.1-rebuild.8-java8",
            "token",
            this.guard.token(),
            "mode",
            "offline-local",
            "targetJava",
            "8",
            "targetMybatis",
            "2.3.2",
            "java",
            System.getProperty("java.version"),
            "springBoot",
            "2.7.18"
        );
    }

    @GetMapping("/history")
    public List<JsonNode> history(@RequestParam(defaultValue = "") String kind) {
        return this.store.list(kind);
    }

    @GetMapping("/history/page")
    public Object historyPage(
        @RequestParam(defaultValue = "") String kind,
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        if (page >= 1 && size >= 1 && size <= 100) {
            List<JsonNode> rows = new ArrayList<>();

            for (JsonNode row : this.store.list(kind)) {
                if ((row.path("label").asText() + row.path("description").asText() + row.path("id").asText())
                    .toLowerCase(Locale.ROOT)
                    .contains(q.toLowerCase(Locale.ROOT))) {
                    rows.add(row);
                }
            }

            long offset = (long)(page - 1) * size;
            int start = (int)Math.min(offset, (long)rows.size());
            return Util.mapOf("total", rows.size(), "page", page, "items", rows.subList(start, Math.min(start + size, rows.size())));
        } else {
            throw new IllegalArgumentException("分页参数不合法");
        }
    }

    @PostMapping("/requirements/import")
    public Spec importRequirement(@RequestParam MultipartFile file) throws Exception {
        return new RequirementImporter().load(file.getBytes(), file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
    }

    @PostMapping("/requirements/text")
    public Spec importText(@RequestBody JsonNode input) {
        return SpecParser.read(Util.bytes(Util.pretty(input)));
    }

    @GetMapping("/requirements/template")
    public ResponseEntity<byte[]> requirementTemplate(@RequestParam String format) {
        byte[] file = new RequirementTemplates().create(format);
        return ((BodyBuilder)ResponseEntity.ok().header("Content-Disposition", new String[]{"attachment; filename=\"generation-template." + format + "\""}))
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(file);
    }

    @PostMapping("/production/check")
    public JsonNode productionCheck(@RequestBody JsonNode input) {
        return new ProductionReadiness().inspect(SpecParser.read(Util.bytes(Util.pretty(input))));
    }

    @GetMapping("/history/diagnostics")
    public JsonNode historyDiagnostics() { return this.store.diagnostics(); }

    @PostMapping("/production/migration")
    public JsonNode migrationPlan(@RequestBody JsonNode input) {
        return ProductionArtifacts.migration(SpecParser.read(Util.bytes(Util.pretty(input.path("previousSpec")))),
            SpecParser.read(Util.bytes(Util.pretty(input.path("currentSpec")))), input.path("previousTemplateVersion").asText());
    }

    @PostMapping("/production/evidence/check")
    public JsonNode evidenceCheck(@RequestBody JsonNode input) {
        Spec spec = SpecParser.read(Util.bytes(Util.pretty(input.path("spec"))));
        Map<String, byte[]> files = new GeneratorService().generate(spec);
        files.remove("generation-manifest.json");
        files.remove("production-evidence-template.json");
        return ProductionArtifacts.inspectEvidence(spec, input.path("evidence"), files);
    }

    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody JsonNode input) {
        if (!this.slots.tryAcquire()) {
            throw new ApiController.BusyException();
        } else {
            Map var12;
            try {
                Spec spec = SpecParser.read(Util.bytes(Util.pretty(input)));
                Map<String, byte[]> files = new GeneratorService().generate(spec);
                Map<String, byte[]> stored = new LinkedHashMap<>();
                stored.put("bundle.zip", ZipFiles.write(files));
                stored.put("spec.json", Util.bytes(Util.pretty(spec)));
                stored.put("manifest.json", files.get("generation-manifest.json"));
                JsonNode metadata = this.store.save("generation", spec.description(), stored);
                Map<String, String> previews = new LinkedHashMap<>();

                for (Entry<String, byte[]> e : files.entrySet()) {
                    previews.put(e.getKey(), new String(e.getValue(), StandardCharsets.UTF_8));
                }

                var12 = Util.mapOf(
                    "metadata",
                    metadata,
                    "files",
                    previews,
                    "production",
                    Util.parse(files.get("production-readiness.json")),
                    "validation",
                    Util.mapOf(
                        "spec",
                        "PASS",
                        "syntax",
                        "PASS",
                        "compilation",
                        "NOT_RUN",
                        "production",
                        "INCOMPLETE",
                        "integration",
                        EngineeringPolicy.existing(spec) ? "REQUIRED" : "NOT_APPLICABLE",
                        "message",
                        EngineeringPolicy.existing(spec) ? "模块规格及Java/XML语法通过；须在宿主工程编译、测试和接入验收" : "规格及Java/XML语法校验通过；生成工程仍需独立编译和测试"
                    )
                );
            } finally {
                this.slots.release();
            }

            return var12;
        }
    }

    @PostMapping("/regenerate")
    public Map<String, Object> regenerate(@RequestParam MultipartFile spec, @RequestParam MultipartFile current) throws Exception {
        if (!this.slots.tryAcquire()) {
            throw new ApiController.BusyException();
        } else {
            Map var10;
            try {
                Spec s = new RequirementImporter().load(spec.getBytes(), spec.getOriginalFilename() == null ? "" : spec.getOriginalFilename());
                Map<String, byte[]> existing = ZipFiles.projectFiles(current.getBytes());
                byte[] manifest = existing.get("generation-manifest.json");
                if (manifest == null) {
                    throw new IllegalArgumentException("上传项目ZIP需保留generation-manifest.json，才能判断人工修改");
                }

                Map<String, byte[]> candidate = new GeneratorService().generate(s);
                JsonNode report = new ConflictService().inspect(Util.parse(manifest), existing, candidate);
                Map<String, byte[]> stored = new LinkedHashMap<>();
                stored.put("bundle.zip", ZipFiles.write(candidate));
                stored.put("report.json", Util.bytes(Util.pretty(report)));
                stored.put("report.html", Reports.html("代码更新冲突清单", report));
                JsonNode meta = this.store.save("regeneration", s.description() + " 更新候选", stored);
                var10 = Util.mapOf("metadata", meta, "report", report);
            } finally {
                this.slots.release();
            }

            return var10;
        }
    }

    @PostMapping("/contracts/scan")
    public Map<String, Object> scan(@RequestParam MultipartFile file, @RequestParam String label) throws Exception {
        if (!this.slots.tryAcquire()) {
            throw new ApiController.BusyException();
        } else {
            Map var4;
            try {
                JsonNode doc = new SourceContractService().scan(file.getBytes());
                var4 = this.saveContract(label, doc);
            } finally {
                this.slots.release();
            }

            return var4;
        }
    }

    @PostMapping("/contracts/import")
    public Map<String, Object> importContract(@RequestParam MultipartFile file, @RequestParam String label) throws Exception {
        if (file.getSize() > 5242880L) {
            throw new IllegalArgumentException("接口文档最大5MiB");
        } else {
            return this.saveContract(label, new ContractService().prepare(Util.parse(file.getBytes())));
        }
    }

    private Map<String, Object> saveContract(String label, JsonNode doc) {
        Map<String, byte[]> stored = new LinkedHashMap<>();
        stored.put("openapi.json", Util.bytes(Util.pretty(doc)));
        stored.put("report.html", Reports.html(label + " 接口文档", doc));
        stored.put("interface-reference.md", ContractReports.markdown(label + " 接口文档", doc));
        JsonNode meta = this.store.save("contract", label, stored);
        ObjectNode display = (ObjectNode)doc.deepCopy();
        display.set("x-interface-index", new ContractService().index(doc));
        return Util.mapOf("metadata", meta, "document", display);
    }

    @PostMapping("/contracts/text")
    public Map<String, Object> importContractText(@RequestBody ApiController.ContractTextRequest r) {
        if (r.document != null && Util.bytes(Util.pretty(r.document)).length <= 5242880) {
            return this.saveContract(r.label != null && !r.label.trim().isEmpty() ? r.label : "粘贴接口版本", new ContractService().prepare(r.document));
        } else {
            throw new IllegalArgumentException("请粘贴5MiB以内的OpenAPI JSON");
        }
    }

    /** Render old and new saved versions without mutating their stored artifacts. */
    @GetMapping("/contracts/{id}/reference/{format}")
    public ResponseEntity<byte[]> reference(@PathVariable String id, @PathVariable String format) {
        if (!format.equals("html") && !format.equals("md")) throw new IllegalArgumentException("文档格式仅支持 html 或 md");
        JsonNode document = this.contract(id);
        String title = document.path("info").path("title").asText("接口文档");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(format.equals("html") ? "text/html;charset=UTF-8" : "text/markdown;charset=UTF-8"))
            .header("Content-Disposition", (format.equals("html") ? "inline" : "attachment") + "; filename=\"interface-reference." + format + "\"")
            .body(format.equals("html") ? ContractReports.html(title, document) : ContractReports.markdown(title, document));
    }

    @GetMapping("/contracts/{id}/operations")
    public JsonNode operations(@PathVariable String id) {
        return new ContractService().index(this.contract(id));
    }

    @GetMapping("/contracts/{id}")
    public JsonNode contract(@PathVariable String id) {
        return Util.parse(this.store.read(id, "openapi.json"));
    }

    @PostMapping("/compare")
    public Map<String, Object> compare(@RequestBody ApiController.CompareRequest req) {
        if (!this.slots.tryAcquire()) {
            throw new ApiController.BusyException();
        } else {
            Map var5;
            try {
                JsonNode report = new CompareService().compare(this.contract(req.baselineId()), this.contract(req.targetId()), req.bindings());
                ((ObjectNode)report).put("baselineId", req.baselineId()).put("targetId", req.targetId());
                Map<String, byte[]> stored = new LinkedHashMap<>();
                stored.put("report.json", Util.bytes(Util.pretty(report)));
                stored.put("report.html", Reports.html("接口变更报告", report));
                JsonNode meta = this.store.save("comparison", "接口变更报告", stored);
                var5 = Util.mapOf("metadata", meta, "report", report);
            } finally {
                this.slots.release();
            }

            return var5;
        }
    }

    @PostMapping("/validate-response")
    public JsonNode sample(@RequestBody ApiController.SampleRequest r) {
        return new SampleValidator().validate(this.contract(r.contractId()), r.method(), r.path(), r.status(), r.media(), r.bodyText());
    }

    @GetMapping("/artifacts/{id}/{file}")
    public ResponseEntity<byte[]> artifact(@PathVariable String id, @PathVariable String file) {
        byte[] bytes = this.store.read(id, file);
        String type = file.endsWith(".zip") ? "application/zip" : file.endsWith(".md") ? "text/markdown;charset=UTF-8" : (file.endsWith(".html") ? "text/html;charset=UTF-8" : "application/json;charset=UTF-8");
        return ((BodyBuilder)ResponseEntity.ok().header("Content-Disposition", new String[]{"attachment; filename=\"" + file + "\""}))
            .contentType(MediaType.parseMediaType(type))
            .body(bytes);
    }

    public static final class BusyException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public static class CompareRequest {
        private String baselineId;
        private String targetId;
        private JsonNode bindings;

        public CompareRequest() {
        }

        public CompareRequest(String baselineId, String targetId, JsonNode bindings) {
            this.baselineId = baselineId;
            this.targetId = targetId;
            this.bindings = bindings;
        }

        public String baselineId() {
            return this.baselineId;
        }

        public String targetId() {
            return this.targetId;
        }

        public JsonNode bindings() {
            return this.bindings;
        }

        public String getBaselineId() {
            return this.baselineId;
        }

        public void setBaselineId(String v) {
            this.baselineId = v;
        }

        public String getTargetId() {
            return this.targetId;
        }

        public void setTargetId(String v) {
            this.targetId = v;
        }

        public JsonNode getBindings() {
            return this.bindings;
        }

        public void setBindings(JsonNode v) {
            this.bindings = v;
        }
    }

    public static class ContractTextRequest {
        public String label;
        public JsonNode document;
    }

    public static class SampleRequest {
        private String contractId;
        private String method;
        private String path;
        private String status;
        private String media;
        private String bodyText;

        public SampleRequest() {
        }

        public SampleRequest(String contractId, String method, String path, String status, String media, String bodyText) {
            this.contractId = contractId;
            this.method = method;
            this.path = path;
            this.status = status;
            this.media = media;
            this.bodyText = bodyText;
        }

        public String contractId() {
            return this.contractId;
        }

        public String method() {
            return this.method;
        }

        public String path() {
            return this.path;
        }

        public String status() {
            return this.status;
        }

        public String media() {
            return this.media;
        }

        public String bodyText() {
            return this.bodyText;
        }

        public String getContractId() {
            return this.contractId;
        }

        public void setContractId(String v) {
            this.contractId = v;
        }

        public String getMethod() {
            return this.method;
        }

        public void setMethod(String v) {
            this.method = v;
        }

        public String getPath() {
            return this.path;
        }

        public void setPath(String v) {
            this.path = v;
        }

        public String getStatus() {
            return this.status;
        }

        public void setStatus(String v) {
            this.status = v;
        }

        public String getMedia() {
            return this.media;
        }

        public void setMedia(String v) {
            this.media = v;
        }

        public String getBodyText() {
            return this.bodyText;
        }

        public void setBodyText(String v) {
            this.bodyText = v;
        }
    }
}
