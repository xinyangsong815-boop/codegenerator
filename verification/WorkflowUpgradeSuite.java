package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/** Target-JDK and conservative merge acceptance checks for DEV-013/023. */
public final class WorkflowUpgradeSuite {
    static final List<Map<String,Object>> results = new ArrayList<>();
    interface Check { void run() throws Exception; }
    static void check(String name, Check c) { try { c.run(); results.add(Util.mapOf("name",name,"passed",true)); } catch (Throwable e) { results.add(Util.mapOf("name",name,"passed",false,"error",e.toString())); } }
    static void require(boolean b) { if (!b) throw new AssertionError(); }
    static Map<String,byte[]> files(String text) { Map<String,byte[]> f = new LinkedHashMap<>(); if(text!=null)f.put("X.java",Util.bytes(text)); f.put("generation-manifest.json",Util.bytes("{\"targetJava\":\"17\",\"files\":{}}")); return f; }
    static Map<String,Object> merge(String a,String l,String n) { return new UpdateService().merge(files(a),files(l),files(n),Util.obj()); }
    static JsonNode report(Map<String,Object> r) { return (JsonNode)r.get("report"); }
    static String merged(Map<String,Object> r) { return SourceFiles.utf8(((Map<String,byte[]>)r.get("files")).get("X.java")); }
    static void conflict(String a,String l,String n) { require(report(merge(a,l,n)).path("pending").asInt()==1); }
    public static void main(String[] args) throws Exception {
        final String target=args[0]; final Path output=Paths.get(args[1]);Files.createDirectories(output);
        final String base="public class X {\n  int first=1;\n  int middle=2;\n  int last=3;\n}\n";
        check("non-overlapping-lines-merge-and-compile-on-actual-target-jdk",()->{
            Map<String,Object> r=merge(base,base.replace("first=1","first=10"),base.replace("last=3","last=30"));
            require(report(r).path("pending").asInt()==0 && report(r).path("files").get(0).path("status").asText().equals("AUTO_MERGED_LINES"));
            require(merged(r).contains("first=10")&&merged(r).contains("last=30"));
            Path source=output.resolve("X.java");Files.write(source,Util.bytes(merged(r)));JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
            try(StandardJavaFileManager fm=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8)) { require(compiler.getTask(null,fm,null,Arrays.asList("-d",output.toString()),null,fm.getJavaFileObjects(source.toFile())).call()); }
        });
        check("overlapping-edits-keep-explicit-conflict",()->conflict(base,base.replace("first=1","first=10"),base.replace("first=1","first=30")));
        check("repeated-lines-do-not-hide-a-delete-edit-conflict",()->{String a="public class X {\n// repeat\n// repeat\n}\n",l="public class X {\n// repeat\n}\n",n="public class X {\n// changed\n// repeat\n}\n";conflict(a,l,n);});
        check("delete-versus-edit-keeps-conflict",()->conflict(base,null,base.replace("last=3","last=30")));
        check("same-position-different-insertions-conflict",()->conflict(base,base.replace("  int middle", "  int local=9;\n  int middle"),base.replace("  int middle", "  int candidate=8;\n  int middle")));
        check("insertion-touching-replacement-stays-conservative",()->conflict(base,base.replace("  int middle", "  int local=9;\n  int middle"),base.replace("middle=2","middle=8")));
        check("independent-insertions-and-replacements-preserved",()->{String local=base.replace("  int first", "  int local=9;\n  int first");Map<String,Object> r=merge(base,local,base.replace("last=3","last=30"));require(report(r).path("pending").asInt()==0&&merged(r).contains("local=9")&&merged(r).contains("last=30"));});
        check("identical-edit-shared-by-both-sides-deduplicated",()->{String local=base.replace("first=1","first=10"),candidate=local.replace("last=3","last=30");Map<String,Object> r=merge(base,local,candidate);require(report(r).path("pending").asInt()==0&&merged(r).equals(candidate));});
        check("overlap-can-be-resolved-by-explicit-local-decision",()->{Map<String,Object> r=new UpdateService().merge(files(base),files(base.replace("first=1","first=10")),files(base.replace("first=1","first=30")),Util.obj().put("X.java","LOCAL"));require(report(r).path("pending").asInt()==0&&merged(r).contains("first=10"));});
        check("crlf-and-final-newline-preserved",()->{String a=base.replace("\n","\r\n");Map<String,Object> r=merge(a,a.replace("first=1","first=10"),a.replace("last=3","last=30"));require(merged(r).equals(a.replace("first=1","first=10").replace("last=3","last=30")));});
        check("auto-merged-invalid-java-is-rejected",()->{try{merge(base,base.replace("first=1","first="),base.replace("last=3","last=30"));throw new AssertionError("accepted invalid Java");}catch(IllegalArgumentException expected){require(expected.getMessage().contains("Java"));}});
        check("target-java-record-language-gate",()->{try{Map<String,Object> r=merge("public class X {}","public class X {}","public record X(int value) {}");require(target.equals("17")&&report(r).path("targetJava").asText().equals("17"));}catch(IllegalArgumentException expected){require(target.equals("8")&&expected.getMessage().contains("Java 8"));}});
        check("target-java-var-language-gate",()->{try{merge("public class X {}","public class X {}","public class X { void run(){var x=1;} }");require(target.equals("17"));}catch(IllegalArgumentException expected){require(target.equals("8"));}});
        check("target-java-switch-expression-language-gate",()->{try{merge("public class X {}","public class X {}","public class X { int run(int x){return switch(x){case 1 -> 2; default -> 3;};} }");require(target.equals("17"));}catch(IllegalArgumentException expected){require(target.equals("8"));}});
        check("manifest-cannot-override-distribution-target",()->require(report(merge(base,base,base)).path("targetJava").asText().equals(target)));
        long failed=results.stream().filter(r->Boolean.FALSE.equals(r.get("passed"))).count();
        Files.write(output.resolve("results.json"),Util.bytes(Util.pretty(Util.JSON.valueToTree(Util.mapOf("target",target,"runtime",System.getProperty("java.version"),"tests",results.size(),"failed",failed,"results",results)))));
        System.out.println("Workflow target "+target+": "+(results.size()-failed)+"/"+results.size());if(failed>0)throw new AssertionError("workflow failures="+failed);
    }
}
