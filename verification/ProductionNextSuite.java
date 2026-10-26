package cn.bank.devtool;

import cn.bank.devtool.regression.RegressionEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;

/** Regression input, target-language source facts and template storage migration boundaries. */
public final class ProductionNextSuite {
    private interface Check { void run() throws Exception; }
    private static final List<ObjectNode> rows = new ArrayList<ObjectNode>();
    private static Path project;
    public static void main(String[] args) throws Exception {
        project=Paths.get(args[0]);
        check("null-array-and-scalar-regression-definitions-fail-as-400-input-errors", () -> {
            try(RegressionEngine engine=new RegressionEngine(project.resolve("build/production-next/"+UUID.randomUUID()),"http://127.0.0.1:8080")) {
                for(JsonNode n:Arrays.asList(null,Util.JSON.createArrayNode(),Util.JSON.getNodeFactory().textNode("bad"))) {
                    invalid(()->engine.scenario(n)); invalid(()->engine.environment(n));
                }
                require(engine.list("scenarios").isEmpty()&&engine.list("environments").isEmpty());
            }
        });
        check("old-outbox-template-needs-three-column-migration-even-for-same-spec", () -> {
            Spec s=spec();JsonNode r=ProductionArtifacts.migration(s,s,"bank-crud-old-rebuild-v17");
            JsonNode row=find(r,"OUTBOX_TEMPLATE_MIGRATION");require(row!=null);
            String sql=row.path("sql").asText();require(sql.contains("lease_token")&&sql.contains("leased_until")&&sql.contains("delivered_at"));
            require(row.path("status").asText().equals("BLOCKED")&&!r.path("autoExecutable").asBoolean()&&r.path("executionStatus").asText().equals("NOT_RUN"));
        });
        check("current-template-does-not-invent-an-outbox-upgrade", () -> require(find(ProductionArtifacts.migration(spec(),spec(),GeneratorService.TEMPLATE_VERSION),"OUTBOX_TEMPLATE_MIGRATION")==null));
        check("unknown-template-requires-review-and-does-not-invent-alter-sql", () -> {JsonNode r=ProductionArtifacts.migration(spec(),spec(),"");require(!r.path("templateStorageChecked").asBoolean());require(find(r,"OUTBOX_TEMPLATE_MIGRATION").path("sql").asText().isEmpty());});
        check("table-rename-does-not-apply-old-outbox-alter-to-new-table", () -> {Spec a=spec(),b=spec();b.setTableName("renamed_contract");require(find(ProductionArtifacts.migration(a,b,"bank-old-rebuild-v17"),"OUTBOX_TEMPLATE_MIGRATION").path("sql").asText().isEmpty());});
        check("source-fact-checker-respects-distribution-java-language", () -> {
            Map<String,byte[]> a=new LinkedHashMap<String,byte[]>(),b=new LinkedHashMap<String,byte[]>();
            a.put("X.java",Util.bytes("public class X {}"));b.put("X.java",Util.bytes("public record X(int value) {}"));
            JsonNode r=new SourceDiffService().compare(a,b);
            require(!r.path("files").isEmpty());if(PlatformProfile.JAVA.equals("8"))require(r.path("verdict").asText().equals("INCOMPLETE"));
        });
        int failed=0;for(JsonNode row:rows)if(!row.path("passed").asBoolean())failed++;
        ObjectNode report=Util.obj().put("tests",rows.size()).put("passed",rows.size()-failed).put("failed",failed).put("runtime",System.getProperty("java.version"));report.set("results",Util.JSON.valueToTree(rows));
        Path output=Paths.get(args[1]);Files.createDirectories(output.toAbsolutePath().getParent());Files.write(output,Util.bytes(Util.pretty(report)));
        System.out.println("Production next: "+(rows.size()-failed)+"/"+rows.size());if(failed>0)throw new AssertionError(Util.pretty(report));
    }
    private static Spec spec()throws Exception{return SpecParser.read(Files.readAllBytes(project.resolve("src/main/resources/static/examples/production-spec.json")));}
    private static JsonNode find(JsonNode r,String code){for(JsonNode x:r.path("changes"))if(x.path("code").asText().equals(code))return x;return null;}
    private static void invalid(Check check)throws Exception{try{check.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected input rejection");}
    private static void require(boolean value){if(!value)throw new AssertionError();}
    private static void check(String name,Check check){ObjectNode row=Util.obj().put("name",name);try{check.run();row.put("passed",true);}catch(Throwable e){row.put("passed",false).put("error",e.toString());}rows.add(row);}
}
