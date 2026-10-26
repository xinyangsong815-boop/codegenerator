package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;

/** Directional contract, transport, sample and readable document regressions for DEV-002..007. */
public final class ContractUpgradeSuite {
    interface Check { void run() throws Exception; }
    private static final List<Map<String,Object>> results = new ArrayList<Map<String,Object>>();
    public static void main(String[] args) throws Exception {
        check("response-closed-object-new-field-fails", () -> verdict("FAIL", compare("response", closed(), extra(closed(), "string"))));
        check("response-open-object-new-field-passes", () -> verdict("PASS", compare("response", open(), extra(open(), "string"))));
        check("response-open-object-new-nullable-field-passes", () -> {ObjectNode b=extra(open(),"string");((ObjectNode)b.at("/properties/extra")).put("nullable",true);verdict("PASS",compare("response",open(),b));});
        check("response-additional-schema-is-respected", () -> verdict("FAIL", compare("response", typedAdditional("integer"), extra(typedAdditional("integer"), "string"))));
        check("response-integer-property-fits-old-number-extra", () -> verdict("PASS", compare("response", typedAdditional("number"), extra(typedAdditional("number"), "integer"))));
        check("response-enum-extra-rejects-new-values", () -> {ObjectNode s=typedAdditional("string"); ((ObjectNode)s.path("additionalProperties")).putArray("enum").add("a"); verdict("FAIL",compare("response",s,extra(s,"string")));});
        check("request-new-optional-field-constrains-old-free-values", () -> {JsonNode r=compare("request",open(),extra(open(),"string"));verdict("FAIL",r);require(r.path("findings").toString().contains("/properties/extra"));});
        check("request-closed-object-new-optional-field-widens", () -> verdict("PASS", compare("request",closed(),extra(closed(),"string"))));
        check("request-number-extra-to-integer-property-fails", () -> verdict("FAIL",compare("request",typedAdditional("number"),extra(typedAdditional("number"),"integer"))));
        check("request-integer-extra-to-number-property-passes", () -> verdict("PASS",compare("request",typedAdditional("integer"),extra(typedAdditional("integer"),"number"))));
        check("request-added-empty-schema-does-not-restrict", () -> {ObjectNode b=open();b.with("properties").putObject("extra");verdict("PASS",compare("request",open(),b));});
        check("request-old-payload-counterexample", () -> {ObjectNode payload=schema("{\"extra\":123}");verdict("PASS",new SampleValidator().validateSchema(open(),payload));verdict("FAIL",new SampleValidator().validateSchema(extra(open(),"string"),payload));});
        check("response-new-payload-counterexample", () -> verdict("FAIL",new SampleValidator().validateSchema(closed(),schema("{\"extra\":\"x\"}"))));
        check("request-nested-new-field-is-checked", () -> {ObjectNode a=closed();a.with("properties").set("child",open());ObjectNode b=a.deepCopy();((ObjectNode)b.at("/properties/child")).with("properties").putObject("extra").put("type","string");verdict("FAIL",compare("request",a,b));});
        check("response-referenced-additional-schema-is-checked", () -> {ObjectNode a=doc("response",typedAdditional("integer"));((ObjectNode)a.at("/paths/~1probe/post/responses/200/content/application~1json/schema")).putObject("additionalProperties").put("$ref","#/components/schemas/Id");a.putObject("components").putObject("schemas").putObject("Id").put("type","integer");ObjectNode b=a.deepCopy();((ObjectNode)b.at("/paths/~1probe/post/responses/200/content/application~1json/schema")).with("properties").putObject("extra").put("type","string");verdict("FAIL",compare(a,b));});
        check("request-removing-field-from-closed-object-fails", () -> verdict("FAIL",compare("request",extra(closed(),"string"),closed())));
        check("request-removing-field-into-open-object-widens", () -> verdict("PASS",compare("request",extra(open(),"string"),open())));
        check("response-removing-field-into-open-object-can-expand", () -> verdict("FAIL",compare("response",extra(open(),"string"),open())));
        check("response-removing-optional-field-into-closed-object-narrows", () -> verdict("PASS",compare("response",extra(closed(),"string"),closed())));
        check("unconstrained-extra-schema-equivalent-to-true", () -> {ObjectNode a=open(),b=open();b.putObject("additionalProperties");verdict("PASS",compare("request",a,b));verdict("PASS",compare("response",b,a));});
        check("response-number-to-integer-is-compatible", () -> verdict("PASS",compare("response",type("number"),type("integer"))));
        check("response-integer-to-number-is-breaking", () -> verdict("FAIL",compare("response",type("integer"),type("number"))));
        check("request-integer-to-number-is-compatible", () -> verdict("PASS",compare("request",type("integer"),type("number"))));
        check("request-number-to-integer-is-breaking", () -> verdict("FAIL",compare("request",type("number"),type("integer"))));
        check("root-server-change-requires-transport-review", () -> {ObjectNode a=doc("response",open()),b=a.deepCopy();server(a,"https://a.example/v1");server(b,"https://b.example/v2");JsonNode r=compare(a,b);verdict("REVIEW_REQUIRED",r);require(r.path("transportVerdict").asText().equals("REVIEW_REQUIRED")&&r.path("structuralVerdict").asText().equals("PASS")&&r.path("findings").toString().contains("https://b.example/v2"));});
        check("path-server-overrides-root", () -> {ObjectNode a=doc("response",open());server(a,"https://a.example");server((ObjectNode)a.at("/paths/~1probe"),"/fixed");ObjectNode b=a.deepCopy();server(b,"https://b.example");verdict("PASS",compare(a,b));});
        check("path-server-change-is-located", () -> {ObjectNode a=doc("response",open());server((ObjectNode)a.at("/paths/~1probe"),"/v1");ObjectNode b=a.deepCopy();server((ObjectNode)b.at("/paths/~1probe"),"/v2");JsonNode r=compare(a,b);verdict("REVIEW_REQUIRED",r);require(r.path("findings").toString().contains("/paths/~1probe/servers"));});
        check("operation-server-overrides-path-and-root", () -> {ObjectNode a=doc("response",open());server((ObjectNode)a.at("/paths/~1probe"),"/v1");server((ObjectNode)a.at("/paths/~1probe/post"),"/fixed");ObjectNode b=a.deepCopy();server((ObjectNode)b.at("/paths/~1probe"),"/v2");verdict("PASS",compare(a,b));});
        check("operation-server-variable-default-change-is-located", () -> {ObjectNode a=doc("response",open());server((ObjectNode)a.at("/paths/~1probe/post"),"https://{region}.example");((ObjectNode)a.at("/paths/~1probe/post/servers/0")).putObject("variables").putObject("region").put("default","east");ObjectNode b=a.deepCopy();((ObjectNode)b.at("/paths/~1probe/post/servers/0/variables/region")).put("default","west");JsonNode r=compare(a,b);verdict("REVIEW_REQUIRED",r);require(r.path("findings").toString().contains("/paths/~1probe/post/servers"));});
        check("server-description-only-is-not-breaking", () -> {ObjectNode a=doc("response",open());server(a,"https://a.example");ObjectNode b=a.deepCopy();((ObjectNode)b.at("/servers/0")).put("description","new explanation");verdict("PASS",compare(a,b));});
        check("malformed-servers-cannot-be-complete-pass", () -> {ObjectNode a=doc("response",open()),b=a.deepCopy();b.put("servers","bad");verdict("INCOMPLETE",compare(a,b));});
        for (String status : new String[]{"banana", "", " 200", "200 ", "099", "600", "999", "2XX", "200.0"}) {
            final String invalidStatus=status;
            check("invalid-sample-status-"+status.replace(' ','_'), () -> invalid(() -> sample(defaultDoc(),invalidStatus,"{}")));
        }
        check("null-sample-status-is-rejected", () -> invalid(() -> sample(defaultDoc(),null,"{}")));
        check("exact-response-matches-before-default", () -> {ObjectNode d=defaultDoc();((ObjectNode)d.at("/paths/~1probe/post/responses")).set("200",response(type("string")));verdict("FAIL",sample(d,"200","{}"));verdict("PASS",sample(d,"404","{}"));});
        check("range-response-matches-before-default", () -> {ObjectNode d=defaultDoc();((ObjectNode)d.at("/paths/~1probe/post/responses")).set("4XX",response(type("string")));verdict("FAIL",sample(d,"404","{}"));verdict("PASS",sample(d,"200","{}"));});
        check("missing-response-is-incomplete", () -> verdict("INCOMPLETE",sample(doc("response",open()),"404","{}")));
        check("readable-documents-show-all-supported-extensions", () -> {ObjectNode s=constraints();ObjectNode d=doc("response",s);String md=new String(ContractReports.markdown("constraints",d),"UTF-8"),html=new String(ContractReports.html("constraints",d),"UTF-8");for(String token:new String[]{"x-precision","x-scale","x-date-minimum","x-date-maximum","x-temporal-precision","x-utf16-min-length","x-utf16-max-length","1000-01-01","9999-12-31","最大整数位=10"})require(md.contains(token)&&html.contains(token));});
        check("documented-constraints-have-positive-and-negative-samples", () -> {ObjectNode s=constraints();SampleValidator validator=new SampleValidator();verdict("PASS",validator.validateSchema(s,schema("{\"amount\":\"1.23\",\"date\":\"2026-10-23\",\"time\":\"2026-10-23T10:00:00.123\",\"name\":\"AB\"}")));for(String value:new String[]{"{\"amount\":\"1.234\"}","{\"date\":\"0999-01-01\"}","{\"time\":\"2026-10-23T10:00:00.1234\"}","{\"name\":\"A\"}"})verdict("FAIL",validator.validateSchema(s,schema(value)));});
        check("unknown-doc-constraint-is-visible-and-incomplete", () -> {ObjectNode s=type("string");s.put("x-unverified-range","<script>");String md=new String(ContractReports.markdown("constraints",doc("response",s)),"UTF-8"),html=new String(ContractReports.html("constraints",doc("response",s)),"UTF-8");require(md.contains("未识别约束")&&md.contains("x-unverified-range")&&md.contains("检查不完整")&&html.contains("&lt;script&gt;")&&!html.contains("<script>"));});
        check("unchanged-structure-does-not-claim-business-verification", () -> {ObjectNode d=doc("response",open());JsonNode r=compare(d,d);verdict("PASS",r);require(!r.path("businessSemanticsVerified").asBoolean(true));});
        check("declared-business-unit-change-requires-review", () -> {ObjectNode a=doc("response",type("number"));((ObjectNode)a.at("/paths/~1probe/post/responses/200/content/application~1json/schema")).put("x-business-semantics","amount:CNY");ObjectNode b=a.deepCopy();((ObjectNode)b.at("/paths/~1probe/post/responses/200/content/application~1json/schema")).put("x-business-semantics","amount:fen");verdict("REVIEW_REQUIRED",compare(a,b));});
        long failed=results.stream().filter(r -> !Boolean.TRUE.equals(r.get("passed"))).count();
        Path output=Paths.get(args[0]).resolve(args[1]);Files.createDirectories(output.toAbsolutePath().getParent());
        Files.write(output,Util.bytes(Util.pretty(Util.mapOf("tests",results.size(),"passed",results.size()-failed,"failed",failed,"results",results))));
        System.out.println("Contract upgrade: "+(results.size()-failed)+"/"+results.size());if(failed>0)System.exit(1);
    }
    static ObjectNode type(String type){return Util.obj().put("type",type);}
    static ObjectNode open(){ObjectNode s=type("object");s.putObject("properties");return s;}
    static ObjectNode closed(){return open().put("additionalProperties",false);}
    static ObjectNode typedAdditional(String type){ObjectNode s=open();s.set("additionalProperties",type(type));return s;}
    static ObjectNode extra(ObjectNode input,String type){ObjectNode s=input.deepCopy();s.with("properties").putObject("extra").put("type",type);return s;}
    static ObjectNode schema(String json)throws Exception{return (ObjectNode)Util.JSON.readTree(json);}
    static ObjectNode doc(String direction,JsonNode schema){ObjectNode d=Util.obj().put("openapi","3.0.3");d.putObject("info").put("title","contract upgrade").put("version","1");ObjectNode op=d.putObject("paths").putObject("/probe").putObject("post");op.putObject("responses").putObject("200").put("description","ok");if(direction.equals("response"))((ObjectNode)op.at("/responses/200")).putObject("content").putObject("application/json").set("schema",schema);else op.putObject("requestBody").putObject("content").putObject("application/json").set("schema",schema);return d;}
    static ObjectNode response(JsonNode schema){ObjectNode r=Util.obj().put("description","response");r.putObject("content").putObject("application/json").set("schema",schema);return r;}
    static ObjectNode defaultDoc(){ObjectNode d=doc("response",open());ObjectNode responses=(ObjectNode)d.at("/paths/~1probe/post/responses");responses.set("default",responses.remove("200"));return d;}
    static ObjectNode constraints(){ObjectNode s=open();ObjectNode p=s.with("properties");p.putObject("amount").put("type","string").put("x-precision",12).put("x-scale",2);p.putObject("date").put("type","string").put("format","date").put("x-date-minimum","1000-01-01").put("x-date-maximum","9999-12-31");p.putObject("time").put("type","string").put("format","local-date-time").put("x-temporal-precision",3);p.putObject("name").put("type","string").put("x-utf16-min-length",2).put("x-utf16-max-length",4);return s;}
    static void server(ObjectNode parent,String url){parent.putArray("servers").addObject().put("url",url);}
    static JsonNode compare(String direction,JsonNode a,JsonNode b){return compare(doc(direction,a),doc(direction,b));}
    static JsonNode compare(JsonNode a,JsonNode b){return new CompareService().compare(a,b,Util.JSON.createArrayNode());}
    static JsonNode sample(JsonNode d,String status,String body){return new SampleValidator().validate(d,"POST","/probe",status,"application/json",body);}
    static void verdict(String expected,JsonNode r){if(!r.path("verdict").asText().equals(expected))throw new AssertionError("Expected "+expected+": "+r);}
    static void invalid(Check c)throws Exception{try{c.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("invalid input accepted");}
    static void require(boolean condition){if(!condition)throw new AssertionError("condition not met");}
    static void check(String name,Check c){Map<String,Object> r=new LinkedHashMap<String,Object>();r.put("name",name);try{c.run();r.put("passed",true);}catch(Throwable e){r.put("passed",false);r.put("error",e.toString());System.out.println("FAIL "+name+": "+e);}results.add(r);}
}
