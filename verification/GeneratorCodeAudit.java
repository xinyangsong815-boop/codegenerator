import cn.bank.devtool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import javax.tools.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** Compile generated main and test sources against real Spring2.7 project dependencies. */
public class GeneratorCodeAudit {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]).toAbsolutePath();
        Path out = root.resolve(args[1]);
        Files.createDirectories(out);
        String xml = root.resolve("verification/generated/production-safe-spec/target/surefire-reports/TEST-com.bank.contract.SecurityIntegrationTest.xml").toString();
        org.w3c.dom.NodeList properties = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(xml)).getElementsByTagName("property");
        String classpath = null;
        for (int i=0;i<properties.getLength();i++) { org.w3c.dom.Element p=(org.w3c.dom.Element)properties.item(i); if(p.getAttribute("name").equals("java.class.path")) classpath=Arrays.stream(p.getAttribute("value").split(java.util.regex.Pattern.quote(File.pathSeparator))).filter(s->s.endsWith(".jar")).collect(Collectors.joining(File.pathSeparator)); }
        if(classpath==null) throw new IllegalStateException("Missing real project dependency classpath");
        ObjectNode normal=(ObjectNode)Util.parse(Files.readAllBytes(root.resolve("src/main/resources/static/examples/contract-v2-spec.json")));
        ObjectNode production=(ObjectNode)Util.parse(Files.readAllBytes(root.resolve("src/main/resources/static/examples/production-safe-spec.json")));
        Map<String,ObjectNode> cases=new LinkedHashMap<String,ObjectNode>();
        for(String operation:Arrays.asList("CREATE","UPDATE","PAGE","DELETE")) {
            ObjectNode spec=normal.deepCopy(); spec.putArray("operations").add(operation);
            if(!operation.equals("UPDATE")) spec.remove("updateMode");
            cases.put(operation.toLowerCase()+"-only",spec);
        }
        ObjectNode all=normal.deepCopy(); ArrayNode fields=all.putArray("fields");
        String[] types={"String","Long","Integer","BigDecimal","Boolean","LocalDate","LocalDateTime"};
        for(int i=0;i<types.length;i++) {ObjectNode f=fields.addObject().put("name","value"+i).put("column","value_"+i).put("description",types[i]).put("javaType",types[i]).put("query","IN").put("required",false).put("editable",true).put("exposed",true);if(i==0)f.put("maxLength",20);if(i==3)f.put("precision",18).put("scale",2);if(i==6)f.put("temporalPrecision",3);}
        cases.put("all-types-in",all);
        ObjectNode range=all.deepCopy();for(JsonNode f:range.path("fields"))if(!f.path("javaType").asText().equals("Boolean")&&!f.path("javaType").asText().equals("String"))((ObjectNode)f).put("query","RANGE");
        cases.put("all-types-range",range);
        ObjectNode full=all.deepCopy();full.put("updateMode","FULL");full.with("options").put("wireFormat","NATIVE"); cases.put("all-types-native-full",full);
        for(String name:Arrays.asList("LinkedHashMap","ArrayList","Arrays")) {ObjectNode spec=production.deepCopy();spec.put("entityName",name);cases.put("name-collision-"+name,spec);}
        List<Map<String,Object>> results=new ArrayList<Map<String,Object>>();
        for(Map.Entry<String,ObjectNode> item:cases.entrySet()) {
            String name=item.getKey();Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("fixture",name);
            try {
                Map<String,byte[]> files=new GeneratorService().generate(SpecParser.read(Util.bytes(Util.pretty(item.getValue()))));
                Path project=out.resolve(name); List<File> sources=new ArrayList<File>();
                for(Map.Entry<String,byte[]> entry:files.entrySet()){Path file=project.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.write(file,entry.getValue());if(entry.getKey().endsWith(".java"))sources.add(file.toFile());}
                Path classes=Files.createDirectories(project.resolve("compile-check"));StringWriter errors=new StringWriter();
                JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();boolean success;
                try(StandardJavaFileManager manager=compiler.getStandardFileManager(null,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8)){success=compiler.getTask(errors,manager,null,Arrays.asList("-encoding","UTF-8","-source","17","-target","17","-cp",classpath,"-d",classes.toString()),null,manager.getJavaFileObjectsFromFiles(sources)).call();}
                result.put("passed",success&&!name.startsWith("name-collision-"));result.put("compiles",success);if(!success)result.put("error",errors.toString());
                if(name.startsWith("name-collision-"))result.put("expected","reject ambiguous framework type names before producing an archive");
            } catch(IllegalArgumentException e){result.put("passed",name.startsWith("name-collision-"));result.put("rejected",true);result.put("message",e.getMessage());}
            results.add(result);System.out.println(name+": "+result.get("passed"));
        }
        long failed=results.stream().filter(x->!Boolean.TRUE.equals(x.get("passed"))).count();
        Files.write(root.resolve(args[2]),Util.bytes(Util.pretty(Util.mapOf("tests",results.size(),"passed",results.size()-failed,"failed",failed,"results",results))));
        if(failed>0)System.exit(1);
    }
}
