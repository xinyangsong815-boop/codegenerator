package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public final class UpdateController {
    private final Semaphore slots = new Semaphore(1);
    private final Store store;

    private void enter() {
        if (!this.slots.tryAcquire()) {
            throw new ApiController.BusyException();
        }
    }

    public UpdateController(Store store) {
        this.store = store;
    }

    @PostMapping("/api/contracts/source-text")
    public Object scan(@RequestBody UpdateController.Text input) {
        this.enter();

        Object var3;
        try {
            JsonNode doc = new SourceContractService().scan(ZipFiles.write(SourceFiles.text(input.files)));
            var3 = this.save("contract", input.label == null ? "粘贴源码版本" : input.label, doc, "openapi.json");
        } finally {
            this.slots.release();
        }

        return var3;
    }

    @PostMapping("/api/frontend/source-text")
    public Object frontend(@RequestBody UpdateController.Text input) {
        this.enter();

        Object var4;
        try {
            JsonNode contract = input.contractId != null && !input.contractId.isEmpty() ? Util.parse(this.store.read(input.contractId, "openapi.json")) : null;
            JsonNode report = new FrontendCallService().scan(ZipFiles.write(SourceFiles.text(input.files)), contract);
            var4 = this.save("frontend", "粘贴前端分析", report, "report.json");
        } finally {
            this.slots.release();
        }

        return var4;
    }

    @PostMapping("/api/source-diff/text")
    public Object diff(@RequestBody UpdateController.Text input) {
        this.enter();

        Object var2;
        try {
            var2 = this.save(
                "sourcecomparison", "前后源码变更", new SourceDiffService().compare(SourceFiles.text(input.before), SourceFiles.text(input.after)), "report.json"
            );
        } finally {
            this.slots.release();
        }

        return var2;
    }

    @PostMapping("/api/source-diff/zip")
    public Object diffZip(@RequestParam MultipartFile before, @RequestParam MultipartFile after) throws Exception {
        this.enter();

        Object var3;
        try {
            var3 = this.save(
                "sourcecomparison",
                "前后源码变更",
                new SourceDiffService().compare(ZipFiles.projectFiles(before.getBytes()), ZipFiles.projectFiles(after.getBytes())),
                "report.json"
            );
        } finally {
            this.slots.release();
        }

        return var3;
    }

    @PostMapping("/api/regenerate/text")
    public Object regenerate(@RequestBody UpdateController.Text input) {
        this.enter();

        Object var6;
        try {
            Map<String, byte[]> current = SourceFiles.text(input.current);
            Map<String, byte[]> candidate = new GeneratorService().generate(SpecParser.read(Util.bytes(Util.pretty(input.spec))));
            byte[] manifest = current.get("generation-manifest.json");
            if (manifest == null) {
                throw new IllegalArgumentException("按需求再生成需要当前项目的generation-manifest.json；无清单项目请选择前后源码检查");
            }

            JsonNode baseline = Util.parse(manifest).path("spec");
            JsonNode migration = ProductionArtifacts.migration(SpecParser.read(Util.bytes(Util.pretty(baseline))),
                SpecParser.read(Util.bytes(Util.pretty(input.spec))), Util.parse(manifest).path("templateVersion").asText());
            candidate.put("migration-plan.json", Util.bytes(Util.pretty(migration)));
            candidate.put("migration-plan.md", Util.bytes(ProductionArtifacts.migrationMarkdown(migration)));
            JsonNode report = new ConflictService().inspect(Util.parse(manifest), current, candidate);
            var6 = this.saveCandidate(report, candidate);
        } finally {
            this.slots.release();
        }

        return var6;
    }

    @PostMapping("/api/merge/text")
    public Object merge(@RequestBody UpdateController.Text input) {
        this.enter();

        Map var6;
        try {
            Map<String, byte[]> candidate = new GeneratorService().generate(SpecParser.read(Util.bytes(Util.pretty(input.spec))));
            Map<String, Object> merged = new UpdateService().merge(SourceFiles.text(input.base), SourceFiles.text(input.current), candidate, input.decisions);
            JsonNode report = (JsonNode)merged.get("report");
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("report.json", Util.bytes(Util.pretty(report)));
            files.put("report.html", Reports.html("三方合并检查", report));
            if (report.path("pending").asInt() == 0) {
                files.put("merged.zip", ZipFiles.write((Map<String, byte[]>)merged.get("files")));
            }

            var6 = Util.mapOf("metadata", this.store.save("merge", "三方合并", files), "report", report);
        } finally {
            this.slots.release();
        }

        return var6;
    }

    private Object saveCandidate(JsonNode report, Map<String, byte[]> candidate) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("report.json", Util.bytes(Util.pretty(report)));
        files.put("report.html", Reports.html("未合并更新候选", report));
        files.put("bundle.zip", ZipFiles.write(candidate));
        return Util.mapOf("metadata", this.store.save("regeneration", "未合并更新候选", files), "report", report);
    }

    private Object save(String kind, String label, JsonNode report, String name) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(name, Util.bytes(Util.pretty(report)));
        files.put("report.html", Reports.html(label, report));
        if (name.equals("openapi.json")) files.put("interface-reference.md", ContractReports.markdown(label, report));
        JsonNode meta = this.store.save(kind, label, files);
        if (name.equals("openapi.json")) {
            ObjectNode display = (ObjectNode)report.deepCopy();
            display.set("x-interface-index", new ContractService().index(report));
            return Util.mapOf("metadata", meta, "document", display);
        } else {
            return Util.mapOf("metadata", meta, "report", report);
        }
    }

    public static class Text {
        public JsonNode files;
        public JsonNode before;
        public JsonNode after;
        public JsonNode base;
        public JsonNode current;
        public JsonNode spec;
        public JsonNode decisions;
        public String label;
        public String contractId;
    }
}
