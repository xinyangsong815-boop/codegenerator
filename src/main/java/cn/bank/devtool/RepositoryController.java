package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** HTTP access to local text snapshots. No endpoint reads or writes arbitrary workspace paths. */
@RestController
@RequestMapping("/api/code-repositories")
public final class RepositoryController {
    private final CodeRepository repository;
    public RepositoryController(CodeRepository repository) { this.repository = repository; }

    @GetMapping public Object list() { return repository.list(); }
    @PostMapping public Object create(@RequestBody Input input) { return repository.create(input.name, input.description); }
    @GetMapping("/{id}/branches") public Object branches(@PathVariable("id") String id) { return repository.branches(id); }
    @PostMapping("/{id}/branches") public Object branch(@PathVariable("id") String id, @RequestBody Input input) { return repository.createBranch(id, input.name, input.fromCommit); }
    @GetMapping("/{id}/commits") public Object history(@PathVariable("id") String id,
        @RequestParam(value = "branch", defaultValue = "main") String branch,
        @RequestParam(value = "offset", defaultValue = "0") int offset,
        @RequestParam(value = "limit", defaultValue = "100") int limit) { return repository.history(id, branch, offset, limit); }
    @PostMapping("/{id}/commits") public Object commit(@PathVariable("id") String id, @RequestBody Input input) { return repository.commitText(id, input.branch, input.expectedHead, input.message, input.files); }
    @GetMapping("/{id}/commits/{hash}") public Object details(@PathVariable("id") String id, @PathVariable("hash") String hash) { return repository.details(id, hash); }
    @GetMapping("/{id}/commits/{hash}/file") public ResponseEntity<String> file(@PathVariable("id") String id, @PathVariable("hash") String hash,
        @RequestParam("path") String path) { return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
            .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store").body(repository.file(id, hash, path)); }
    @GetMapping("/{id}/commits/{hash}/archive.zip") public ResponseEntity<byte[]> archive(@PathVariable("id") String id, @PathVariable("hash") String hash) {
        byte[] bytes = repository.archive(id, hash);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"code-" + hash + ".zip\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store").body(bytes);
    }
    @PostMapping("/{id}/diff") public Object diff(@PathVariable("id") String id, @RequestBody Input input) { return repository.diff(id, input.baseCommit, input.targetCommit); }
    @PostMapping("/{id}/restore") public Object restore(@PathVariable("id") String id, @RequestBody Input input) { return repository.restore(id, input.branch, input.expectedHead, input.commitId, input.message); }
    @PostMapping("/{id}/import") public Object importZip(@PathVariable("id") String id, @RequestParam("branch") String branch,
        @RequestParam("expectedHead") String expectedHead, @RequestParam("message") String message, @RequestParam("file") MultipartFile file,
        @RequestParam(value = "stripSingleRoot", defaultValue = "false") boolean stripSingleRoot) throws java.io.IOException {
        if (file.isEmpty() || file.getSize() > 20971520) throw new IllegalArgumentException("代码ZIP必须非空且不超过20MiB");
        return repository.importZip(id, branch, expectedHead, message, file.getBytes(), stripSingleRoot);
    }
    @ExceptionHandler(CodeRepository.RepositoryConflict.class) public ResponseEntity<?> conflict(CodeRepository.RepositoryConflict error) {
        return ResponseEntity.status(409).body(Util.mapOf("code", "REPOSITORY_CONFLICT", "message", error.getMessage()));
    }
    @ExceptionHandler(CodeRepository.RepositoryCorruption.class) public ResponseEntity<?> corrupt(CodeRepository.RepositoryCorruption error) {
        return ResponseEntity.status(409).body(Util.mapOf("code", "REPOSITORY_CORRUPT", "message", error.getMessage()));
    }
    public static final class Input {
        public String name;
        public String description;
        public String branch;
        public String expectedHead;
        public String message;
        public String fromCommit;
        public String baseCommit;
        public String targetCommit;
        public String commitId;
        public JsonNode files;
    }
}
