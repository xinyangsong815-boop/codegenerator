package cn.bank.devtool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;

/** Concrete generated-code documentation and durable-delivery structural checks. Generated OutboxDeliveryTest executes the JDBC state machine. */
public final class GeneratedNextSuite {
    private interface Check { void run() throws Exception; }
    private static final List<ObjectNode> results = new ArrayList<>();
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void check(String name, Check test) { try { test.run(); results.add(Util.obj().put("name",name).put("passed",true)); } catch(Throwable failure) { results.add(Util.obj().put("name",name).put("passed",false).put("error",failure.toString())); } }
    private static String text(Map<String,byte[]> files, String suffix) { for(Map.Entry<String,byte[]> row:files.entrySet())if(row.getKey().endsWith(suffix))return SourceFiles.utf8(row.getValue());throw new AssertionError("Missing "+suffix); }
    public static void main(String[] args) throws Exception {
        final Path root=Paths.get(args[0]); final Path report=Paths.get(args[1]);
        final Map<String,Map<String,byte[]>> projects=new LinkedHashMap<>();
        for(String name:Arrays.asList("contract-spec","contract-v2-spec","interface-config-spec","engineering-spec","production-spec","production-safe-spec")) {
            final String fixture=name;
            check("generate-and-document-"+name, () -> {
                Map<String,byte[]> files=new GeneratorService().generate(SpecParser.read(Files.readAllBytes(root.resolve("src/main/resources/static/examples/"+fixture+".json"))));
                projects.put(fixture,files);
                for(Map.Entry<String,byte[]> file:files.entrySet()) if(file.getKey().endsWith(".java")) {
                    String source=SourceFiles.utf8(file.getValue());
                    require(!source.contains("使用方式与约束见所属组件说明")&&!source.contains("职责和接入要求见生成工程 README"),file.getKey()+" boilerplate removed");
                    require(com.github.javaparser.StaticJavaParser.parse(source)!=null,"valid generated Java");
                }
            });
        }
        check("existing-specific-chinese-documentation-is-preserved",()-> {
            String source="/** 原有组件用途：处理审批状态。 */ public class X { /** 只读取缓存，不访问数据库。 */ public String read(){return \"ok\";} }";
            String formatted=GeneratedJavaStyle.format(source);
            require(formatted.contains("原有组件用途：处理审批状态")&&formatted.contains("只读取缓存，不访问数据库"),"do not overwrite domain documentation");
        });
        check("legacy-boilerplate-replaced-with-real-signature-and-return-contract",()-> {
            String formatted=GeneratedJavaStyle.format("/** 生成的 X 组件。职责和接入要求见生成工程 README。领域说明需要保留。 */ public class X { /** 执行 combine 操作，使用方式与约束见所属组件说明。仅用于组合显示值。 */ public String combine(String left,int right){return left+right;} }");
            require(formatted.contains("X.combine(String left, int right)")&&formatted.contains("@param left")&&formatted.contains("@param right")&&formatted.contains("@return String"),"signature documentation");
            require(!formatted.contains("使用方式与约束见所属组件说明")&&!formatted.contains("职责和接入要求见生成工程 README"),"legacy replacement");
            require(formatted.contains("领域说明需要保留")&&formatted.contains("仅用于组合显示值"),"retain useful comments next to the boilerplate");
        });
        check("get-prefix-does-not-invent-an-attribute-or-database-policy",()-> {
            String formatted=GeneratedJavaStyle.format("public class X { public int getTotal(int first,int second){return first+second;} }");
            require(formatted.contains("X.getTotal(int first, int second)")&&!formatted.contains("读取 Total"),"cannot infer semantics solely from name");
        });
        check("generated-service-and-mapper-describe-transactions-and-row-count",()-> {
            Map<String,byte[]> files=projects.get("production-safe-spec");
            require(text(files,"ServiceImpl.java").contains("当前写事务回滚")&&text(files,"Mapper.java").contains("返回影响行数"),"concrete usage");
            require(text(files,"Hooks.java").contains("事务提交前")&&text(files,"AccessGuard.java").contains("禁止从请求正文读取操作人"),"integration boundary");
        });
        check("outbox-default-compatible-explicit-worker-and-reviewed-guide",()-> {
            Map<String,byte[]> files=projects.get("production-safe-spec");String worker=text(files,"OutboxDispatcher.java");
            require(worker.contains("runBatch")&&worker.contains("lease_token")&&worker.contains("DEAD")&&!worker.contains("@Component")&&!worker.contains("@Scheduled"),"explicit lease worker");
            require(text(files,"outbox-delivery-guide.md").contains("至少一次")&&text(files,"OutboxDeliveryTest.java").contains("concurrentWorkersCannotBothOwnAnUnexpiredLease"),"meaningful recovery tests");
            require(!projects.get("contract-spec").keySet().stream().anyMatch(p->p.endsWith("OutboxDispatcher.java")),"no hooks, no worker");
        });
        check("audit-requires-actual-transaction-bound-to-own-data-source",()-> {
            String audit=text(projects.get("production-safe-spec"),"JdbcAuditWriter.java");
            require(audit.contains("hasResource(jdbc.getDataSource())")&&audit.contains("isActualTransactionActive()"),"wrong data source cannot autocommit audit");
        });
        Path generated=report.toAbsolutePath().getParent().resolve("generated-next");
        for(Map.Entry<String,Map<String,byte[]>> project:projects.entrySet())for(Map.Entry<String,byte[]> file:project.getValue().entrySet()) {
            Path target=generated.resolve(project.getKey()).resolve(file.getKey());Files.createDirectories(target.getParent());Files.write(target,file.getValue());
        }
        int failed=(int)results.stream().filter(r->!r.path("passed").asBoolean()).count();
        Files.createDirectories(report.toAbsolutePath().getParent());Files.write(report,Util.bytes(Util.pretty(Util.JSON.valueToTree(Util.mapOf("runtime",System.getProperty("java.version"),"tests",results.size(),"failed",failed,"generated",generated.toString(),"results",results)))));
        System.out.println("GeneratedNext: "+(results.size()-failed)+"/"+results.size());if(failed>0)throw new AssertionError("GeneratedNext failures="+failed);
    }
}
