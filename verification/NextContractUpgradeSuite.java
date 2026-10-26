package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;

/** Independent counterexamples for response presence, HTTP header names and incomplete contracts. */
public final class NextContractUpgradeSuite {
    interface Check { void run() throws Exception; }
    private static final List<ObjectNode> results = new ArrayList<ObjectNode>();
    public static void main(String[] args) throws Exception {
        check("response-required-additional-field-must-not-disappear", () -> {
            ObjectNode before=object();before.putArray("required").add("id");
            verdict("FAIL",compare(doc(before),doc(object())));
            verdict("PASS",new SampleValidator().validateSchema(object(),json("{}")));
            verdict("FAIL",new SampleValidator().validateSchema(before,json("{}")));
        });
        check("response-required-additional-field-can-be-strengthened", () -> {
            ObjectNode after=object();after.putArray("required").add("id");verdict("PASS",compare(doc(object()),doc(after)));
        });
        check("response-required-declared-field-loss-still-fails", () -> {
            ObjectNode before=object();before.putObject("properties").putObject("id").put("type","string");before.putArray("required").add("id");
            ObjectNode after=before.deepCopy();after.remove("required");verdict("FAIL",compare(doc(before),doc(after)));
        });
        check("response-header-required-to-optional-fails", () -> {
            ObjectNode before=doc(object());header(before,"X-Cursor",true);ObjectNode after=before.deepCopy();((ObjectNode)after.at("/paths/~1probe/post/responses/200/headers/X-Cursor")).put("required",false);verdict("FAIL",compare(before,after));
        });
        check("response-header-required-to-absent-fails", () -> {
            ObjectNode before=doc(object());header(before,"X-Cursor",true);ObjectNode after=before.deepCopy();((ObjectNode)after.at("/paths/~1probe/post/responses/200")).remove("headers");verdict("FAIL",compare(before,after));
        });
        check("optional-response-header-removal-retains-review", () -> {
            ObjectNode before=doc(object());header(before,"X-Cursor",false);ObjectNode after=before.deepCopy();((ObjectNode)after.at("/paths/~1probe/post/responses/200")).remove("headers");verdict("REVIEW_REQUIRED",compare(before,after));
        });
        check("response-header-case-only-change-is-compatible", () -> {
            ObjectNode before=doc(object());header(before,"X-Cursor",true);ObjectNode after=before.deepCopy();ObjectNode headers=(ObjectNode)after.at("/paths/~1probe/post/responses/200/headers");headers.set("x-cursor",headers.remove("X-Cursor"));verdict("PASS",compare(before,after));
        });
        check("request-header-case-only-change-is-compatible", () -> {
            ObjectNode before=doc(object());parameter(before,"header","X-Token",true);ObjectNode after=before.deepCopy();((ObjectNode)after.at("/paths/~1probe/post/parameters/0")).put("name","x-token");verdict("PASS",compare(before,after));
        });
        check("query-parameter-names-remain-case-sensitive", () -> {
            ObjectNode before=doc(object());parameter(before,"query","Token",true);ObjectNode after=before.deepCopy();((ObjectNode)after.at("/paths/~1probe/post/parameters/0")).put("name","token");verdict("FAIL",compare(before,after));
        });
        check("operation-header-overrides-path-header-ignoring-case", () -> {
            ObjectNode d=doc(object());parameter(d,"header","x-token",true);ObjectNode path=(ObjectNode)d.at("/paths/~1probe");ObjectNode p=path.putArray("parameters").addObject().put("name","X-Token").put("in","header").put("required",false);p.putObject("schema").put("type","string");
            JsonNode params=new ContractService().index(d).get(0).path("value").path("parameters");require(params.size()==1&&params.get(0).path("required").asBoolean(),"path/header override must share case-insensitive key");
        });
        check("required-header-is-visible-in-readable-documents", () -> {
            ObjectNode d=doc(object());header(d,"X-Cursor",true);String md=text(ContractReports.markdown("header",d)),html=text(ContractReports.html("header",d));require(md.contains("响应头必填")&&html.contains("响应头必填"),"required response header must be documented");
        });
        check("required-additional-field-is-visible-in-documents", () -> {
            ObjectNode s=object();s.putArray("required").add("accountId");String md=text(ContractReports.markdown("required",doc(s)));require(md.contains("required=")&&md.contains("accountId"),"required names outside properties must survive rendering");
        });
        check("empty-responses-cannot-pass-unchanged-comparison", () -> {
            ObjectNode d=doc(object());((ObjectNode)d.at("/paths/~1probe/post")).putObject("responses");verdict("INCOMPLETE",compare(d,d));
        });
        check("invalid-response-status-key-cannot-pass", () -> {
            ObjectNode d=doc(object());ObjectNode responses=(ObjectNode)d.at("/paths/~1probe/post/responses");responses.set("banana",responses.remove("200"));verdict("INCOMPLETE",compare(d,d));
        });
        check("response-without-description-is-incomplete", () -> {
            ObjectNode d=doc(object());((ObjectNode)d.at("/paths/~1probe/post/responses/200")).remove("description");verdict("INCOMPLETE",compare(d,d));verdict("INCOMPLETE",sample(d,"{}"));
        });
        check("nonboolean-header-required-is-incomplete", () -> {
            ObjectNode d=doc(object());header(d,"X-Cursor",true);((ObjectNode)d.at("/paths/~1probe/post/responses/200/headers/X-Cursor")).put("required","yes");verdict("INCOMPLETE",compare(d,d));
        });
        check("duplicate-case-insensitive-response-headers-are-incomplete", () -> {
            ObjectNode d=doc(object());header(d,"X-Cursor",true);((ObjectNode)d.at("/paths/~1probe/post/responses/200/headers")).putObject("x-cursor").putObject("schema").put("type","integer");verdict("INCOMPLETE",compare(d,d));
        });
        check("duplicate-parameters-in-one-scope-are-incomplete", () -> {
            ObjectNode d=doc(object());parameter(d,"header","X-Token",true);((com.fasterxml.jackson.databind.node.ArrayNode)d.at("/paths/~1probe/post/parameters")).add(d.at("/paths/~1probe/post/parameters/0").deepCopy());verdict("INCOMPLETE",compare(d,d));
        });
        for(final String combination:new String[]{"oneOf","allOf","not"})check("unsupported-"+combination+"-cannot-pass",()->{
            ObjectNode s=object();if(combination.equals("not"))s.putObject(combination).put("type","string");else s.putArray(combination).addObject().put("type","object");verdict("INCOMPLETE",compare(doc(s),doc(s)));verdict("INCOMPLETE",sample(doc(s),"{}"));
        });
        check("nullable-response-expansion-fails-and-request-widening-passes", () -> {
            ObjectNode a=object(),b=object().put("nullable",true);verdict("FAIL",compare(doc(a),doc(b)));ObjectNode da=requestDoc(a),db=requestDoc(b);verdict("PASS",compare(da,db));
        });
        check("sample-preserves-decimal-beyond-double-precision", () -> {
            ObjectNode s=Util.obj().put("type","number");s.set("maximum",json("0.12345678901234567890"));verdict("FAIL",sample(doc(s),"0.12345678901234567891"));verdict("PASS",sample(doc(s),"0.12345678901234567890"));
        });
        check("media-case-only-change-is-compatible", () -> {
            ObjectNode before=doc(object()),after=before.deepCopy();ObjectNode content=(ObjectNode)after.at("/paths/~1probe/post/responses/200/content");content.set("Application/JSON",content.remove("application/json"));verdict("PASS",compare(before,after));
        });
        check("sample-matches-media-case-insensitively", () -> {
            ObjectNode d=doc(object());ObjectNode content=(ObjectNode)d.at("/paths/~1probe/post/responses/200/content");content.set("Application/JSON",content.remove("application/json"));verdict("PASS",sample(d,"{}"));
        });
        check("sample-uses-most-specific-media-schema", () -> {
            ObjectNode d=doc(object());ObjectNode content=(ObjectNode)d.at("/paths/~1probe/post/responses/200/content");content.putObject("application/*").putObject("schema").put("type","string");verdict("PASS",sample(d,"{}"));
        });
        check("sample-can-use-json-wildcard-schema", () -> {
            ObjectNode d=doc(object());ObjectNode content=(ObjectNode)d.at("/paths/~1probe/post/responses/200/content");content.set("application/*",content.remove("application/json"));verdict("PASS",sample(d,"{}"));
        });
        check("non-json-sample-never-claims-json-validation-pass", () -> {
            ObjectNode d=doc(object());ObjectNode content=(ObjectNode)d.at("/paths/~1probe/post/responses/200/content");content.set("text/plain",content.remove("application/json"));JsonNode r=new SampleValidator().validate(d,"POST","/probe","200","text/plain","{}");verdict("INCOMPLETE",r);
        });
        long failed=results.stream().filter(r->!r.path("passed").asBoolean()).count();ObjectNode report=Util.obj().put("javaVersion",System.getProperty("java.version")).put("tests",results.size()).put("passed",results.size()-failed).put("failed",failed);report.set("results",Util.JSON.valueToTree(results));Path output=Paths.get(args[0]).resolve(args[1]);Files.createDirectories(output.toAbsolutePath().getParent());Files.write(output,Util.bytes(Util.pretty(report)));System.out.println("NextContractUpgradeSuite tests="+results.size()+" passed="+(results.size()-failed)+" failed="+failed);if(failed>0)System.exit(1);
    }
    static ObjectNode object(){return Util.obj().put("type","object");}
    static ObjectNode doc(JsonNode s){ObjectNode d=Util.obj().put("openapi","3.0.3");d.putObject("info").put("title","independent contract checks").put("version","1");d.putObject("paths").putObject("/probe").putObject("post").putObject("responses").putObject("200").put("description","ok").putObject("content").putObject("application/json").set("schema",s);return d;}
    static ObjectNode requestDoc(JsonNode s){ObjectNode d=doc(object());((ObjectNode)d.at("/paths/~1probe/post")).putObject("requestBody").putObject("content").putObject("application/json").set("schema",s);return d;}
    static void header(ObjectNode d,String name,boolean required){((ObjectNode)d.at("/paths/~1probe/post/responses/200")).with("headers").putObject(name).put("description","cursor").put("required",required).putObject("schema").put("type","string");}
    static void parameter(ObjectNode d,String in,String name,boolean required){((ObjectNode)d.at("/paths/~1probe/post")).putArray("parameters").addObject().put("in",in).put("name",name).put("required",required).putObject("schema").put("type","string");}
    static JsonNode compare(JsonNode a,JsonNode b){return new CompareService().compare(a,b,Util.JSON.createArrayNode());}
    static JsonNode sample(JsonNode d,String body){return new SampleValidator().validate(d,"POST","/probe","200","application/json",body);}
    static JsonNode json(String value)throws Exception{return Util.JSON.readTree(value);}
    static String text(byte[] bytes)throws Exception{return new String(bytes,"UTF-8");}
    static void verdict(String expected,JsonNode r){require(expected.equals(r.path("verdict").asText()),"expected "+expected+": "+r);}
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void check(String name,Check c){ObjectNode r=Util.obj().put("name",name);try{c.run();r.put("passed",true);}catch(Throwable e){r.put("passed",false).put("error",e.toString());System.out.println("FAIL "+name+": "+e);}results.add(r);}
}
