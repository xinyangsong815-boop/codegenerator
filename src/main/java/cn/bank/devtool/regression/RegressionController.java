package cn.bank.devtool.regression;

import cn.bank.devtool.Store;
import cn.bank.devtool.Util;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/regression")
public class RegressionController {
    private final RegressionEngine engine;
    private final Store contracts;

    public RegressionController(
        @Value("${tool.data-dir}") String root, @Value("${tool.regression.allowed-targets:http://127.0.0.1:8080}") String targets, Store contracts
    ) {
        this.engine = new RegressionEngine(Paths.get(root).resolve("regression"), targets);
        this.contracts = contracts;
    }

    @PreDestroy
    public void stop() throws Exception {
        this.engine.close();
    }

    @GetMapping("/config")
    public Object config() {
        return Util.mapOf("allowedTargets", this.engine.allowed(), "maxParallel", 1, "maxSteps", 50,
            "runListLimit", 200, "storageDiagnostics", this.engine.diagnostics());
    }

    @GetMapping("/environments/page")
    public Object environmentPage(@RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        return this.engine.page("environments", offset, limit);
    }

    @GetMapping("/scenarios/page")
    public Object scenarioPage(@RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        return this.engine.page("scenarios", offset, limit);
    }

    @GetMapping("/runs/page")
    public Object runPage(@RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        return this.engine.page("runs", offset, limit);
    }

    @GetMapping("/environments")
    public Object environments() {
        return this.engine.list("environments");
    }

    @PostMapping("/environments")
    public Object environment(@RequestBody JsonNode n) {
        return this.engine.environment(n);
    }

    @DeleteMapping("/environments/{id}")
    public Object deleteEnvironment(@PathVariable String id) {
        this.engine.delete("environments", id);
        return Util.mapOf("deleted", true);
    }

    @GetMapping("/scenarios")
    public Object scenarios() {
        return this.engine.list("scenarios");
    }

    @GetMapping("/scenarios/{id}")
    public Object scenario(@PathVariable String id) {
        return this.engine.get("scenarios", id);
    }

    @PostMapping("/scenarios")
    public Object scenario(@RequestBody JsonNode n) {
        return this.engine.scenario(n);
    }

    @PutMapping("/scenarios/{id}")
    public Object scenario(@PathVariable String id, @RequestBody JsonNode n) {
        if (!id.equals(n.path("id").asText())) {
            throw new IllegalArgumentException("路径与场景ID不一致");
        } else {
            return this.engine.scenario(n);
        }
    }

    @DeleteMapping("/scenarios/{id}")
    public Object deleteScenario(@PathVariable String id) {
        this.engine.delete("scenarios", id);
        return Util.mapOf("deleted", true);
    }

    @PostMapping("/scenarios/validate")
    public Object validate(@RequestBody JsonNode n) {
        ScenarioRules.check(n);
        return Util.mapOf("valid", true);
    }

    @PostMapping("/scenarios/{id}/validate")
    public Object validateSaved(@PathVariable String id) {
        ObjectNode s = (ObjectNode)this.engine.get("scenarios", id).deepCopy();
        s.remove("updatedAt");
        ScenarioRules.check(s);
        return Util.mapOf("valid", true, "definitionHash", Util.sha(Util.bytes(Util.pretty(s))));
    }

    @PostMapping("/runs")
    public ResponseEntity<?> start(@RequestBody RegressionController.Start n) {
        return ResponseEntity.status(202).body(this.engine.start(n.scenarioId, n.environmentId, n.clientRequestId, n.credentials, n.definitionHash));
    }

    @GetMapping("/runs")
    public Object runs() {
        return this.engine.list("runs");
    }

    @GetMapping("/runs/{id}")
    public Object run(@PathVariable String id) {
        return this.engine.get("runs", id);
    }

    @PostMapping("/runs/{id}/cancel")
    public Object cancel(@PathVariable String id) {
        return this.engine.cancel(id);
    }

    @PostMapping("/runs/{id}/confirm-reset")
    public Object confirm(@PathVariable String id, @RequestBody JsonNode n) {
        ScenarioRules.keys(n, "note");
        return this.engine.confirmReset(id, n.path("note").asText());
    }

    @PostMapping("/drafts")
    public Object draft(@RequestBody RegressionController.Draft n) {
        return new ContractScenarioService().draft(n.contractId, Util.parse(this.contracts.read(n.contractId, "openapi.json")), n.method, n.path);
    }

    @PostMapping("/impact")
    public Object impact(@RequestBody RegressionController.Impact n) {
        ObjectNode result = (ObjectNode)new ContractScenarioService()
            .impact(
                Util.parse(this.contracts.read(n.baselineId, "openapi.json")),
                Util.parse(this.contracts.read(n.targetId, "openapi.json")),
                this.engine.all("scenarios"),
                n.baselineId,
                n.targetId
            );
        JsonNode diagnostics = this.engine.diagnostics();
        int excluded = 0;
        for (JsonNode record : diagnostics.path("quarantinedRecords")) if (record.path("kind").asText().equals("scenarios")) excluded++;
        result.put("excludedScenarioCount", excluded).put("complete", excluded == 0)
            .put("totalCount", result.path("scannedCount").asInt() + excluded);
        result.set("storageDiagnostics", diagnostics);
        return result;
    }

    @PostMapping("/batches")
    public ResponseEntity<?> batch(@RequestBody RegressionController.Batch n) {
        return ResponseEntity.status(202).body(this.engine.batch(n.scenarioIds, n.environmentId, n.clientRequestId, n.credentials));
    }

    @GetMapping("/runs/{id}/report.html")
    public ResponseEntity<byte[]> html(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(RegressionReports.html(this.engine.get("runs", id)));
    }

    @GetMapping("/runs/{id}/report.json")
    public Object json(@PathVariable String id) {
        return this.engine.get("runs", id);
    }

    @GetMapping("/runs/{id}/junit.xml")
    public ResponseEntity<byte[]> junit(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(RegressionReports.junit(this.engine.get("runs", id)));
    }

    public static class Batch {
        public List<String> scenarioIds;
        public String environmentId;
        public String clientRequestId;
        public Map<String, String> credentials;
    }

    public static class Draft {
        public String contractId;
        public String method;
        public String path;
    }

    public static class Impact {
        public String baselineId;
        public String targetId;
    }

    public static class Start {
        public String scenarioId;
        public String environmentId;
        public String clientRequestId;
        public String definitionHash;
        public Map<String, String> credentials;
    }
}
