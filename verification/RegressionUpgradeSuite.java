package cn.bank.devtool;

import cn.bank.devtool.regression.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** DEV-001/008/009/010: actual HTTP, disk persistence, pagination and strict integer boundaries. */
public final class RegressionUpgradeSuite {
    private static final List<ObjectNode> results = new ArrayList<>();
    private static Path root;
    private static String origin;
    private interface Check { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        root = Paths.get(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", x -> {
            byte[] body = Util.bytes("{\"data\":{\"id\":42,\"password\":\"SYNTHETIC_SECRET_SENTINEL\",\"customerName\":\"SYNTHETIC_CUSTOMER\"}}");
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, body.length); x.getResponseBody().write(body); x.close();
        });
        server.createContext("/bad-id", x -> {
            byte[] body = Util.bytes("{\"data\":{\"id\":{\"password\":\"SYNTHETIC_SECRET_SENTINEL\"}}}");
            x.sendResponseHeaders(200, body.length); x.getResponseBody().write(body); x.close();
        });
        server.start(); origin = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            for (String type : Arrays.asList("object", "array", "boolean", "null", "number")) {
                check("resource-rejects-" + type, () -> {
                    ObjectNode s = scenario("resource"); step(s).putObject("resource").put("pointer", "/data/id").put("type", type);
                    invalid(() -> ScenarioRules.check(s));
                });
            }
            check("resource-rejects-sensitive-field-pointer", () -> {
                for (String pointer : Arrays.asList("/data", "/data/password", "/data/customerName", "/data/token")) {
                    ObjectNode s = scenario("resource"); step(s).putObject("resource").put("pointer", pointer).put("type", "string");
                    invalid(() -> ScenarioRules.check(s));
                }
            });
            check("resource-allows-bounded-real-identifiers", () -> {
                require(ResourcePrivacy.identifier(TextNode.valueOf("order-123:abc")), "string identifier");
                require(ResourcePrivacy.identifier(LongNode.valueOf(Long.MAX_VALUE)), "long identifier");
                for (JsonNode value : Arrays.asList(TextNode.valueOf(""), TextNode.valueOf("unsafe body"), TextNode.valueOf(repeat('a',129)), NullNode.getInstance(), Util.obj(), Util.JSON.createArrayNode()))
                    require(!ResourcePrivacy.identifier(value), "unsafe resource accepted");
            });
            check("http-resource-tracks-only-id-and-exports-no-response-object", () -> {
                JsonNode result = execute("/probe");
                require(result.path("verdict").asText().equals("PASS"), "business verdict");
                require(result.path("resources").get(0).path("id").asInt() == 42, "resource id");
                absent(Util.pretty(result)); absent(new String(RegressionReports.html(result), StandardCharsets.UTF_8));
                absent(new String(RegressionReports.junit(result), StandardCharsets.UTF_8));
            });
            check("http-runtime-type-mismatch-does-not-persist-object", () -> {
                JsonNode result = execute("/bad-id");
                require(result.path("verdict").asText().equals("FAIL") && result.path("resources").size() == 0, "unsafe id must fail tracking");
                absent(Util.pretty(result));
            });
            check("legacy-unsafe-resources-sanitized-on-disk-and-exports", () -> {
                Path dir = folder("legacy"); Files.createDirectories(dir.resolve("runs"));
                ObjectNode run = completed("legacy");
                run.putArray("resources").addObject().put("stepId","one").putObject("id").put("password", "SYNTHETIC_SECRET_SENTINEL").put("customerName", "SYNTHETIC_CUSTOMER");
                Files.write(dir.resolve("runs/legacy.json"), Util.bytes(Util.pretty(run)));
                absent(new String(RegressionReports.html(run), StandardCharsets.UTF_8));
                require(new String(RegressionReports.junit(run), StandardCharsets.UTF_8).contains("<error"), "legacy tracking uncertainty must not pass JUnit");
                try (RegressionStore store = new RegressionStore(dir)) {
                    JsonNode safe = store.get("runs", "legacy");
                    require(safe.path("resources").size() == 0 && safe.path("resourceTrackingIncomplete").asBoolean(), "legacy resources removed");
                    require(RegressionReports.overall(safe).equals("INCOMPLETE"), "legacy uncertainty");
                    absent(new String(Files.readAllBytes(dir.resolve("runs/legacy.json")), StandardCharsets.UTF_8));
                }
            });
            check("pagination-and-impact-all-counts-199-200-201-1000", () -> {
                try (RegressionEngine engine = new RegressionEngine(folder("counts"), origin)) {
                    for (int i=1; i<=1000; i++) {
                        engine.scenario(scenario("s"+i));
                        if (i != 199 && i != 200 && i != 201 && i != 1000) continue;
                        require(engine.list("scenarios").size()==i && engine.all("scenarios").size()==i, "no silent cutoff at "+i);
                        Set<String> ids = new HashSet<>(); int offset=0;
                        do {
                            JsonNode page=engine.page("scenarios", offset, 199);
                            require(page.path("totalCount").asInt()==i, "page total");
                            for (JsonNode item:page.path("items")) require(ids.add(item.path("id").asText()), "duplicate page item");
                            if (!page.path("hasMore").asBoolean()) break;
                            offset+=199;
                        } while(true);
                        require(ids.size()==i, "paged completeness");
                        ObjectNode old = Util.obj(); old.putObject("paths").putObject("/probe").putObject("get").putObject("responses").putObject("200").put("description","ok");
                        ObjectNode next = Util.obj(); next.putObject("paths");
                        JsonNode impact = new ContractScenarioService().impact(old,next,engine.all("scenarios"),"base","next");
                        require(impact.path("affectedCount").asInt()==i && impact.path("scannedCount").asInt()==i && impact.path("complete").asBoolean(), "full impact coverage");
                    }
                    invalid(() -> engine.page("scenarios", -1, 100)); invalid(() -> engine.page("scenarios", 0, 201));
                    require(engine.page("scenarios", Integer.MAX_VALUE, 200).path("items").size()==0, "offset overflow");
                }
            });
            check("corrupt-files-isolated-with-normal-records-and-durable-diagnostics", () -> {
                Path dir=folder("corrupt"); Files.createDirectories(dir.resolve("runs")); Files.createDirectories(dir.resolve("scenarios"));
                Files.write(dir.resolve("runs/good.json"), Util.bytes(Util.pretty(completed("good"))));
                Files.write(dir.resolve("runs/bad.json"), Util.bytes("{broken"));
                Files.write(dir.resolve("runs/empty.json"), Util.bytes("  "));
                Files.write(dir.resolve("scenarios/mismatch.json"), Util.bytes(Util.pretty(scenario("different"))));
                try (RegressionStore store=new RegressionStore(dir)) {
                    require(store.list("runs").size()==1 && store.get("runs","good").path("verdict").asText().equals("PASS"), "normal records remain");
                    require(store.diagnostics().path("quarantinedCount").asInt()==3, "explicit diagnostics");
                    require(!Util.pretty(store.diagnostics()).contains("{broken"), "diagnostic payload leak");
                }
                try (RegressionStore store=new RegressionStore(dir)) { require(store.diagnostics().path("quarantinedCount").asInt()==3, "diagnostics survive restart"); }
            });
            check("get-corruption-is-isolated-and-history-survives", () -> {
                Path dir=folder("latecorrupt");
                try(RegressionStore store=new RegressionStore(dir)) {
                    store.save("runs","good",completed("good"));
                    Files.write(dir.resolve("runs/bad.json"),Util.bytes("[null]"));
                    try {store.get("runs","bad"); throw new AssertionError("corruption returned");} catch (IllegalStateException expected) { }
                    require(store.list("runs").size()==1 && store.diagnostics().path("quarantinedCount").asInt()==1,"isolation");
                }
            });
            check("failed-initialization-releases-writer-lock", () -> {
                Path dir=folder("initfailure"); Files.write(dir.resolve("scenarios"),Util.bytes("blocking directory creation"));
                try {new RegressionStore(dir);throw new AssertionError("initialization unexpectedly succeeded");} catch (IllegalStateException expected) { }
                Files.delete(dir.resolve("scenarios"));
                try(RegressionStore store=new RegressionStore(dir)) {
                    require(store.list("runs").isEmpty(),"retry acquires lock");
                    try {new RegressionStore(dir);throw new AssertionError("concurrent writer accepted");} catch (IllegalStateException expected) { }
                    store.save("runs","good",completed("good"));
                }
                try(RegressionStore store=new RegressionStore(dir)) {require(store.list("runs").size()==1,"losing writer must not release owner lock");}
            });
            check("interrupted-valid-run-never-replayed-or-passed", () -> {
                Path dir=folder("interrupted"); ObjectNode run=completed("running").put("state","RUNNING");
                run.putArray("steps").addObject().put("state","DISPATCHING").put("verdict","INCOMPLETE");
                try(RegressionStore store=new RegressionStore(dir)){store.save("runs","running",run);}
                try(RegressionStore store=new RegressionStore(dir)) {
                    JsonNode interrupted=store.get("runs","running");
                    require(interrupted.path("state").asText().equals("INTERRUPTED") && interrupted.path("steps").get(0).path("effectState").asText().equals("UNKNOWN_EFFECT"),"interruption recovery");
                    require(RegressionReports.overall(interrupted).equals("INCOMPLETE"),"never pass unknown effects");
                }
            });
            for(String key:Arrays.asList("connectTimeoutMs","readTimeoutMs","stepTimeoutMs","scenarioTimeoutMs")) {
                check("strict-timeout-"+key, () -> {
                    TargetPolicy policy=new TargetPolicy(origin); int max=key.equals("scenarioTimeoutMs")?300000:30000;
                    for(int boundary:new int[]{100,max}) {ObjectNode env=environment().put(key,boundary);require(policy.validate(env).path(key).intValue()==boundary,"accepted exact boundary");}
                    for(JsonNode value:Arrays.asList(LongNode.valueOf(4294967396L),BigIntegerNode.valueOf(new java.math.BigInteger("999999999999999999999")),DecimalNode.valueOf(new java.math.BigDecimal("100.5")),TextNode.valueOf("100"),BooleanNode.TRUE,IntNode.valueOf(-1),IntNode.valueOf(99),IntNode.valueOf(max+1),NullNode.getInstance())) {
                        ObjectNode env=environment();env.set(key,value);invalid(()->policy.validate(env));
                    }
                });
            }
            check("strict-schema-version-and-generated-length", () -> {
                ScenarioRules.check(scenario("valid"));
                for(JsonNode value:Arrays.asList(LongNode.valueOf(4294967297L),DecimalNode.valueOf(new java.math.BigDecimal("1.1")),TextNode.valueOf("1"),BooleanNode.TRUE,IntNode.valueOf(-1),NullNode.getInstance())) {
                    ObjectNode s=scenario("bad");s.set("schemaVersion",value);invalid(()->ScenarioRules.check(s));
                }
                for(JsonNode value:Arrays.asList(LongNode.valueOf(4294967328L),TextNode.valueOf("32"),DecimalNode.valueOf(new java.math.BigDecimal("32.1")),BooleanNode.TRUE,NullNode.getInstance())) {
                    ObjectNode s=scenario("bad");s.putObject("variables").putObject("unique").put("generator","runUnique").set("maxLength",value);invalid(()->ScenarioRules.check(s));
                }
                ObjectNode s=scenario("valid");s.putObject("variables").putObject("unique").put("generator","runUnique").put("maxLength",32);ScenarioRules.check(s);
            });
        } finally {server.stop(0);}
        long failed=results.stream().filter(n->!n.path("passed").asBoolean()).count();
        ObjectNode report=Util.obj().put("javaVersion",System.getProperty("java.version")).put("tests",results.size()).put("failed",failed).put("passed",results.size()-failed);
        report.set("results",Util.JSON.valueToTree(results));Files.write(root.resolve("results.json"),Util.bytes(Util.pretty(report)));
        System.out.println("RegressionUpgradeSuite Tests="+results.size()+" Passed="+(results.size()-failed)+" Failed="+failed);
        if(failed>0)System.exit(1);
    }
    private static String repeat(char c,int count) {char[] chars=new char[count];Arrays.fill(chars,c);return new String(chars);}
    private static Path folder(String prefix)throws Exception{return Files.createDirectories(root.resolve(prefix+"-"+UUID.randomUUID()));}
    private static void check(String name,Check action){ObjectNode result=Util.obj().put("name",name);try{action.run();result.put("passed",true);}catch(Throwable e){result.put("passed",false).put("error",e.toString());System.out.println("FAIL "+name+": "+e);}results.add(result);}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void invalid(Check action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("invalid input accepted");}
    private static void absent(String value){require(!value.contains("SYNTHETIC_SECRET_SENTINEL")&&!value.contains("SYNTHETIC_CUSTOMER"),"response payload leaked");}
    private static ObjectNode completed(String id){ObjectNode r=Util.obj().put("id",id).put("state","FINISHED").put("verdict","PASS").put("cleanupStatus","NOT_REQUIRED");r.putArray("steps");r.putArray("cleanupSteps");return r;}
    private static ObjectNode environment(){return Util.obj().put("id","env").put("name","test").put("baseUrl",origin).put("pathPrefix","/").put("authMode","NONE");}
    private static ObjectNode step(ObjectNode scenario){return (ObjectNode)scenario.path("steps").get(0);}
    private static ObjectNode scenario(String id){ObjectNode s=Util.obj().put("schemaVersion",1).put("id",id).put("name",id);ObjectNode step=s.putArray("steps").addObject().put("id","step").put("name","probe");step.putObject("request").put("method","GET").put("path","/probe");step.putArray("assertions").addObject().put("source","status").put("op","eq").put("expected",200);return s;}
    private static JsonNode execute(String path)throws Exception{
        Path dir=folder("http");
        try(RegressionEngine engine=new RegressionEngine(dir,origin)){
            engine.environment(environment());ObjectNode s=scenario("resource");((ObjectNode)step(s).path("request")).put("path",path);step(s).putObject("resource").put("pointer","/data/id").put("type","integer");engine.scenario(s);
            String id=engine.start("resource","env","request",Collections.<String,String>emptyMap(),null).path("id").asText();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<end){JsonNode r=engine.get("runs",id);if(r.path("state").asText().equals("FINISHED")){absent(new String(Files.readAllBytes(dir.resolve("runs/"+id+".json")),StandardCharsets.UTF_8));return r;}Thread.sleep(10);}
            throw new AssertionError("HTTP run timeout");
        }
    }
}
