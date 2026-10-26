package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;

/** Reproduces migration identifier and evidence-format mistakes without a database. */
public final class ReleaseSafetySuite {
    private interface Check { void run() throws Exception; }
    private static final List<ObjectNode> rows = new ArrayList<ObjectNode>();
    private static Path project;
    public static void main(String[] args) throws Exception {
        project = Paths.get(args[0]);
        check("case-only-column-change-never-suggests-duplicate-add-and-drop", () -> {
            Spec a=spec(), b=spec(); b.fields().get(0).setColumn(a.fields().get(0).column().toUpperCase(Locale.ROOT));
            JsonNode plan=ProductionArtifacts.migration(a,b);
            require(find(plan,"ADD_COLUMN")==null && find(plan,"DROP_COLUMN")==null);
        });
        check("case-change-with-type-change-is-still-an-alter-review", () -> {
            Spec a=spec(), b=spec(); Spec.Field f=b.fields().get(0);
            f.setColumn(f.column().toUpperCase(Locale.ROOT)); f.setMaxLength(f.maxLength()+1);
            JsonNode plan=ProductionArtifacts.migration(a,b);
            require(find(plan,"ALTER_COLUMN")!=null && find(plan,"ADD_COLUMN")==null && find(plan,"DROP_COLUMN")==null);
        });
        check("unsupported-evidence-schema-is-not-a-matching-evidence-format", () -> {
            ObjectNode e=evidence();e.put("schemaVersion",999);require(!inspect(e).path("bindingMatched").asBoolean());
        });
        check("missing-evidence-schema-does-not-silently-assume-current", () -> {
            ObjectNode e=evidence();e.remove("schemaVersion");require(!inspect(e).path("bindingMatched").asBoolean());
        });
        check("string-evidence-schema-does-not-coerce-to-an-integer", () -> {
            ObjectNode e=evidence();e.put("schemaVersion","1");require(!inspect(e).path("bindingMatched").asBoolean());
        });
        check("malformed-evidence-row-is-explicitly-flagged", () -> {
            ObjectNode e=evidence();e.withArray("evidence").add(123);
            require(inspect(e).path("findings").toString().contains("INVALID_EVIDENCE_ROW"));
        });
        check("numeric-pass-metadata-is-not-accepted-as-human-attestation", () -> {
            ObjectNode e=evidence();ObjectNode row=(ObjectNode)e.path("evidence").get(0);
            row.put("status","PASS").put("environment",123).put("command",456).put("reviewer",789)
                .put("executedAt","2026-01-01T00:00:00Z").put("artifactSha256",repeat('a')).put("resultSha256",repeat('b'));
            require(inspect(e).path("checks").get(0).path("status").asText().equals("INCOMPLETE_EVIDENCE"));
        });
        check("current-format-does-not-grant-production-readiness", () -> {
            JsonNode r=inspect(evidence()); require(r.path("bindingMatched").asBoolean() && !r.path("deploymentReady").asBoolean(true));
        });
        check("known-v18-outbox-schema-does-not-invent-an-alter", () -> {
            Spec s=production(); String old=PlatformProfile.JAVA.equals("8")?"bank-crud-java8-sb2718-rebuild-v18":"bank-crud-java17-sb35-rebuild-v18";
            require(find(ProductionArtifacts.migration(s,s,old),"OUTBOX_TEMPLATE_MIGRATION")==null);
        });
        int failed=0;for(JsonNode r:rows)if(!r.path("passed").asBoolean())failed++;
        ObjectNode out=Util.obj().put("tests",rows.size()).put("passed",rows.size()-failed).put("failed",failed).put("runtime",System.getProperty("java.version"));out.set("results",Util.JSON.valueToTree(rows));
        Path dest=Paths.get(args[1]);Files.createDirectories(dest.toAbsolutePath().getParent());Files.write(dest,Util.bytes(Util.pretty(out)));
        System.out.println("Release safety: "+(rows.size()-failed)+"/"+rows.size());if(failed>0)throw new AssertionError(Util.pretty(out));
    }
    private static Spec spec()throws Exception{return SpecParser.read(Files.readAllBytes(project.resolve("src/main/resources/static/examples/contract-spec.json")));}
    private static Spec production()throws Exception{return SpecParser.read(Files.readAllBytes(project.resolve("src/main/resources/static/examples/production-spec.json")));}
    private static Map<String,byte[]> files(){Map<String,byte[]> f=new LinkedHashMap<String,byte[]>();f.put("x.java",Util.bytes("synthetic"));return f;}
    private static ObjectNode evidence()throws Exception{return ProductionArtifacts.evidenceTemplate(spec(),files());}
    private static JsonNode inspect(JsonNode e)throws Exception{return ProductionArtifacts.inspectEvidence(spec(),e,files());}
    private static String repeat(char c){char[] chars=new char[64];Arrays.fill(chars,c);return new String(chars);}
    private static JsonNode find(JsonNode r,String code){for(JsonNode row:r.path("changes"))if(row.path("code").asText().equals(code))return row;return null;}
    private static void require(boolean ok){if(!ok)throw new AssertionError();}
    private static void check(String name,Check c){ObjectNode row=Util.obj().put("name",name);try{c.run();row.put("passed",true);}catch(Throwable ex){row.put("passed",false).put("error",ex.toString());}rows.add(row);}
}
