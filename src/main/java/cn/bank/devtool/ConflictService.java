package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class ConflictService {
    private static final Set<String> CONFLICTS = new HashSet<>(Arrays.asList("LOCAL_MODIFIED", "LOCAL_FILE_COLLISION", "DELETED_LOCALLY", "DELETE_SUGGESTION"));

    public JsonNode inspect(JsonNode manifest, Map<String, byte[]> current, Map<String, byte[]> candidate) {
        if (!manifest.path("files").isObject()) {
            throw new IllegalArgumentException("缺少上次生成清单files，无法判断人工修改");
        } else {
            ObjectNode report = Util.obj();
            report.put("mode", "candidate-only");
            report.put("verdict", "UNMERGED_CANDIDATE");
            report.put("merged", false);
            ArrayNode rows = report.putArray("files");
            int conflicts = 0;
            Set<String> paths = new TreeSet<>(candidate.keySet());
            Iterator<String> fields = manifest.path("files").fieldNames();

            while (fields.hasNext()) {
                paths.add(fields.next());
            }

            for (String path : paths) {
                if (!path.equals("generation-manifest.json")) {
                    JsonNode base = manifest.path("files").path(path);
                    byte[] actual = current.get(path);
                    byte[] next = candidate.get(path);
                    String status;
                    if (next == null) {
                        status = actual != null ? "DELETE_SUGGESTION" : "UNCHANGED";
                    } else if (actual == null) {
                        status = base.isMissingNode() ? "NEW" : "DELETED_LOCALLY";
                    } else if (Util.sha(actual).equals(Util.sha(next))) {
                        status = "UNCHANGED";
                    } else if (base.isMissingNode()) {
                        status = "LOCAL_FILE_COLLISION";
                    } else if (Util.sha(actual).equals(base.asText())) {
                        status = "SAFE_REPLACE";
                    } else if (Util.sha(next).equals(base.asText())) {
                        status = "KEEP_LOCAL";
                    } else {
                        status = "LOCAL_MODIFIED";
                    }

                    if (CONFLICTS.contains(status)) {
                        conflicts++;
                    }

                    ObjectNode r = rows.addObject();
                    r.put("path", path);
                    r.put("status", status);
                    if (actual != null) {
                        r.put("currentHash", Util.sha(actual));
                    }

                    if (next != null) {
                        r.put("candidateHash", Util.sha(next));
                    }
                }
            }

            int keep = 0;

            for (JsonNode row : rows) {
                if (row.path("status").asText().equals("KEEP_LOCAL")) {
                    keep++;
                }
            }

            report.put("keepLocalCount", keep);
            report.put("conflictCount", conflicts);
            report.put("message", "未合并候选不包含保留的人工内容；零冲突也不可整包替换。请用三方合并或按清单人工合并");
            return report;
        }
    }
}
