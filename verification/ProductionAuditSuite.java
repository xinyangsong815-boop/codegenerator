package cn.bank.devtool;

import cn.bank.devtool.regression.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reproduces compatibility, documentation and real HTTP regression defects. */
public final class ProductionAuditSuite {
    interface Check { void run() throws Exception; }
    static final List<Map<String,Object>> results=new ArrayList<Map<String,Object>>();
    static Path root; static String origin;
    public static void main(String[] args) throws Exception {
        root=Paths.get(args[0]).toAbsolutePath();
        HttpServer server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        origin="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/", e->{byte[] b=Util.bytes(e.getRequestURI().getPath().equals("/text")?"not JSON":"{\"ok\":true}");e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
        try {
            check("request-anyof-narrowing-is-breaking",()->{ObjectNode a=doc(choice(true));ObjectNode b=doc(choice(false));require(verdict(a,b).equals("FAIL"));});
            check("request-anyof-expansion-is-compatible",()->{ObjectNode a=doc(choice(false));ObjectNode b=doc(choice(true));require(verdict(a,b).equals("PASS"));});
            check("response-anyof-expansion-is-breaking",()->{ObjectNode a=responseDoc(choice(false)),b=responseDoc(choice(true));require(verdict(a,b).equals("FAIL"));});
            check("request-body-reference-required-change-is-breaking",()->{ObjectNode a=doc(object()),b=a.deepCopy();ObjectNode old=a.with("components").with("requestBodies").putObject("Payload");old.put("required",false).putObject("content").putObject("application/json").set("schema",object());((ObjectNode)a.at("/paths/~1probe/post")).putObject("requestBody").put("$ref","#/components/requestBodies/Payload");b=a.deepCopy();((ObjectNode)b.at("/components/requestBodies/Payload")).put("required",true);require(verdict(a,b).equals("FAIL"));});
            check("response-reference-field-removal-is-breaking",()->{ObjectNode a=responseDoc(object()),b;ObjectNode response=(ObjectNode)a.at("/paths/~1probe/get/responses/200");ObjectNode payload=object();payload.with("properties").putObject("id").put("type","integer");payload.putArray("required").add("id");response.with("content").with("application/json").set("schema",payload);a.with("components").with("responses").set("Shared",response.deepCopy());((ObjectNode)a.at("/paths/~1probe/get/responses")).putObject("200").put("$ref","#/components/responses/Shared");b=a.deepCopy();((ObjectNode)b.at("/components/responses/Shared/content/application~1json/schema")).remove("required");require(verdict(a,b).equals("FAIL"));});
            check("warning-does-not-report-a-clean-pass",()->{ObjectNode a=responseDoc(object()),b=a.deepCopy();((ObjectNode)b.at("/paths/~1probe/get/responses")).remove("200");((ObjectNode)b.at("/paths/~1probe/get/responses")).putObject("204").put("description","empty");JsonNode r=new CompareService().compare(a,b,Util.JSON.createArrayNode());require(r.path("warnCount").asInt()>0&&!r.path("verdict").asText().equals("PASS"));});
            check("operation-index-includes-inherited-path-parameter",()->{ObjectNode a=responseDoc(object());((ObjectNode)a.path("paths").path("/probe")).putArray("parameters").addObject().put("name","q").put("in","query").put("required",false).putObject("schema").put("type","string");JsonNode row=new ContractService().index(a).get(0);require(row.path("value").path("parameters").size()==1);});
            check("operation-parameter-overrides-path-parameter",()->{ObjectNode a=responseDoc(object());((ObjectNode)a.path("paths").path("/probe")).putArray("parameters").addObject().put("name","q").put("in","query").put("required",false).putObject("schema").put("type","string");((ObjectNode)a.at("/paths/~1probe/get")).putArray("parameters").addObject().put("name","q").put("in","query").put("required",true).putObject("schema").put("type","integer");JsonNode p=new ContractService().index(a).get(0).path("value").path("parameters");require(p.size()==1&&p.get(0).path("required").asBoolean());});
            check("schema-looking-business-property-does-not-poison-coverage",()->{ObjectNode s=object();s.with("properties").putObject("pattern").put("type","string");s.with("properties").putObject("oneOf").put("type","string");require(new ContractService().prepare(responseDoc(s)).path("x-coverage").path("complete").asBoolean());});
            check("literal-ref-in-example-is-not-a-document-reference",()->{ObjectNode s=object();s.set("example",Util.obj().put("$ref","literal business value"));require(new ContractService().prepare(responseDoc(s)).path("x-coverage").path("complete").asBoolean());});
            check("nested-numeric-equality-is-value-based",()->require(ScenarioRules.assertValue(Util.parse(Util.bytes("{\"n\":[1.00]}")),"eq",Util.parse(Util.bytes("{\"n\":[1]}")))));
            check("nested-numeric-ne-is-consistent",()->require(!ScenarioRules.assertValue(Util.parse(Util.bytes("{\"n\":1.00}")),"ne",Util.parse(Util.bytes("{\"n\":1}")))));
            check("array-contains-uses-value-based-numeric-equality",()->require(ScenarioRules.assertValue(Util.parse(Util.bytes("[{\"n\":1.00}]")),"contains",Util.parse(Util.bytes("{\"n\":1}")))));
            check("optional-body-extract-requires-valid-json",()->{ObjectNode s=scenario("optional","/text","GET");((ObjectNode)s.withArray("steps").get(0)).withArray("extract").addObject().put("name","optional").put("source","body").put("pointer","/missing").put("type","string").put("required",false);require(run(s).path("verdict").asText().equals("FAIL"));});
            check("cleanup-dependencies-are-included-in-version-impact",()->{ObjectNode a=responseDoc(object()),b=a.deepCopy();((ObjectNode)b.path("paths").path("/probe")).with("get").put("summary","changed");ObjectNode s=scenario("impact","/other","GET");s.putArray("cleanupSteps").add(s.path("steps").get(0).deepCopy());((ObjectNode)s.path("cleanupSteps").get(0).path("request")).put("path","/probe");require(new ContractScenarioService().impact(a,b,Collections.<JsonNode>singletonList(s),"before","after").path("affectedCount").asInt()==1);});
            check("cleanup-failure-has-an-overall-failure",()->{ObjectNode s=scenario("cleanup","/ok","POST");s.put("cleanupPolicy","HTTP_CLEANUP");ObjectNode clean=(ObjectNode)s.putArray("cleanupSteps").addObject().put("id","clean").put("name","cleanup");clean.putObject("request").put("method","DELETE").put("path","/ok");clean.putArray("assertions").addObject().put("source","status").put("op","eq").put("expected",204);JsonNode r=run(s);require(r.path("cleanupStatus").asText().equals("FAILED")&&r.path("overallVerdict").asText().equals("FAIL"));});
            check("manual-reset-does-not-erase-a-failed-cleanup-assertion",()->{ObjectNode r=Util.obj().put("state","FINISHED").put("verdict","PASS").put("cleanupStatus","MANUALLY_CONFIRMED");r.putArray("cleanupSteps").addObject().put("state","FINISHED").put("verdict","FAIL");require(RegressionReports.overall(r).equals("FAIL"));});
            check("manual-reset-is-not-an-overall-pass",()->{ObjectNode s=scenario("reset","/ok","POST");s.put("cleanupPolicy","RESET_TEST_DATABASE");JsonNode r=run(s);require(r.path("verdict").asText().equals("PASS")&&r.path("overallVerdict").asText().equals("INCOMPLETE"));});
            check("concurrent-generation-keeps-template-provenance-isolated",()->{
                final GeneratorService generator=new GeneratorService();
                final Spec first=SpecParser.read(Files.readAllBytes(root.resolve("src/main/resources/static/examples/contract-v2-spec.json")));
                final Spec second=SpecParser.read(Files.readAllBytes(root.resolve("src/main/resources/static/examples/production-safe-spec.json")));
                final JsonNode expectedFirst=Util.parse(generator.generate(first).get("generation-manifest.json")).path("templateHashes");
                final JsonNode expectedSecond=Util.parse(generator.generate(second).get("generation-manifest.json")).path("templateHashes");
                require(expectedFirst.isObject() && expectedFirst.size()>0 && expectedSecond.isObject() && expectedSecond.size()>0);
                java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(4);
                try { List<java.util.concurrent.Future<Boolean>> futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
                    for(int i=0;i<12;i++){final boolean choose=i%2==0;futures.add(workers.submit(()->Util.parse(generator.generate(choose?first:second).get("generation-manifest.json")).path("templateHashes").equals(choose?expectedFirst:expectedSecond)));}
                    for(java.util.concurrent.Future<Boolean> future:futures)require(future.get());
                } finally {workers.shutdownNow();}
            });
            check("production-assets-and-request-trace-are-generated",()->{Map<String,byte[]> f=generated();require(f.containsKey("src/main/java/com/bank/contract/common/RequestTraceFilter.java")&&f.containsKey("deployment/preflight.sh")&&f.containsKey("production-runbook.md"));});
            check("generated-document-declares-correlation-header",()->{JsonNode d=Util.parse(generated().get("openapi.json"));require(d.at("/paths/~1contracts/get/responses/200/headers/X-Request-Id/schema/type").asText().equals("string"));});
            check("production-profile-enables-controlled-shutdown",()->require(new String(generated().get("src/main/resources/application-prod.properties"),StandardCharsets.UTF_8).contains("server.shutdown=graceful")));
        } finally {server.stop(0);}
        long failed=results.stream().filter(x->!Boolean.TRUE.equals(x.get("passed"))).count();
        Files.write(root.resolve(args[1]),Util.bytes(Util.pretty(Util.mapOf("tests",results.size(),"passed",results.size()-failed,"failed",failed,"results",results))));
        System.out.println("Production audit: "+(results.size()-failed)+"/"+results.size());if(failed>0)System.exit(1);
    }
    static ObjectNode object(){ObjectNode s=Util.obj().put("type","object");s.putObject("properties");return s;}
    static ObjectNode choice(boolean both){ObjectNode s=object();s.with("properties").putObject("a").put("type","string");s.with("properties").putObject("b").put("type","string");s.putArray("anyOf").addObject().putArray("required").add("a");if(both)s.withArray("anyOf").addObject().putArray("required").add("b");return s;}
    static ObjectNode doc(ObjectNode schema){ObjectNode d=Util.obj().put("openapi","3.0.3");d.putObject("info").put("title","audit").put("version","1");ObjectNode op=d.putObject("paths").putObject("/probe").putObject("post");op.putObject("requestBody").put("required",true).putObject("content").putObject("application/json").set("schema",schema);op.putObject("responses").putObject("204").put("description","empty");return d;}
    static ObjectNode responseDoc(ObjectNode schema){ObjectNode d=doc(object());((ObjectNode)d.path("paths").path("/probe")).remove("post");((ObjectNode)d.path("paths").path("/probe")).putObject("get").putObject("responses").putObject("200").put("description","OK").putObject("content").putObject("application/json").set("schema",schema);return d;}
    static String verdict(JsonNode a,JsonNode b){return new CompareService().compare(a,b,Util.JSON.createArrayNode()).path("verdict").asText();}
    static ObjectNode scenario(String id,String path,String method){ObjectNode s=Util.obj().put("schemaVersion",1).put("id",id).put("name",id);ObjectNode step=s.putArray("steps").addObject().put("id","step1").put("name","probe");step.putObject("request").put("method",method).put("path",path);step.putArray("assertions").addObject().put("source","status").put("op","eq").put("expected",200);return s;}
    static JsonNode run(ObjectNode s) throws Exception {try(RegressionEngine e=new RegressionEngine(Files.createDirectories(root.resolve("build/production-audit/"+UUID.randomUUID())),origin)){e.environment(Util.obj().put("id","env").put("name","audit").put("baseUrl",origin).put("pathPrefix","/").put("authMode","NONE"));e.scenario(s);String id=e.start(s.path("id").asText(),"env","request",Util.mapOf(),null).path("id").asText();long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){JsonNode r=e.get("runs",id);if(r.path("state").asText().equals("FINISHED"))return r;Thread.sleep(10);}throw new AssertionError("timeout");}}
    static Map<String,byte[]> generated() throws Exception {return new GeneratorService().generate(SpecParser.read(Files.readAllBytes(root.resolve("src/main/resources/static/examples/contract-v2-spec.json"))));}
    static void require(boolean pass){if(!pass)throw new AssertionError("expected behavior not met");}
    static void check(String name,Check check){Map<String,Object> r=new LinkedHashMap<String,Object>();r.put("name",name);try{check.run();r.put("passed",true);}catch(Throwable e){r.put("passed",false);r.put("error",e.toString());System.out.println("FAIL "+name+": "+e);}results.add(r);}
}
