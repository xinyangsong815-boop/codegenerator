package cn.bank.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Real public API boundaries for conservative migration planning, evidence binding and damaged history isolation. */
public final class ProductionUpgradeSuite {
    private interface Check {void run() throws Exception;}
    private static final List<ObjectNode> results = new ArrayList<>();
    private static Path project, work;
    public static void main(String[] args) throws Exception {
        project = Paths.get(args[0]).toAbsolutePath(); work = Paths.get(args[1]).toAbsolutePath();Files.createDirectories(work);
        check("migration-unchanged-is-review-only-with-no-sql", () -> {
            Spec before = spec(fixture());JsonNode plan=ProductionArtifacts.migration(before,before);
            conservative(plan);require(plan.path("changes").size()==0,"unchanged spec has no storage changes");
        });
        check("migration-nullable-addition-is-candidate-never-executed", () -> {
            ObjectNode changed=fixture();add(changed,false);
            JsonNode plan=ProductionArtifacts.migration(spec(fixture()),spec(changed));conservative(plan);
            JsonNode row=change(plan,"ADD_COLUMN");require(row.path("status").asText().equals("SQL_CANDIDATE"),"nullable candidate");
            require(row.path("sql").asText().equals("ALTER TABLE `biz_contract` ADD COLUMN `memo` VARCHAR(100) NULL;"),"quoted nullable DDL");
            require(ProductionArtifacts.migrationMarkdown(plan).contains("REVIEW_REQUIRED")&&ProductionArtifacts.migrationMarkdown(plan).contains("```sql"),"review markdown");
        });
        check("migration-required-addition-blocked-without-sql", () -> {
            ObjectNode changed=fixture();add(changed,true);blocked(ProductionArtifacts.migration(spec(fixture()),spec(changed)),"ADD_COLUMN");
        });
        check("migration-database-default-addition-blocked-without-sql", () -> {
            ObjectNode changed=fixture();add(changed,false).put("source","DATABASE").put("editable",false).put("defaultValue","NEW");
            blocked(ProductionArtifacts.migration(spec(fixture()),spec(changed)),"ADD_COLUMN");
        });
        check("migration-drop-blocked-without-destructive-sql", () -> {
            ObjectNode changed=fixture();((ArrayNode)changed.path("fields")).remove(2);
            blocked(ProductionArtifacts.migration(spec(fixture()),spec(changed)),"DROP_COLUMN");
        });
        check("migration-renamed-column-produces-blocked-drop-and-reviewed-add", () -> {
            ObjectNode changed=fixture();ObjectNode field=(ObjectNode)changed.path("fields").get(2);field.put("name","signedOn").put("column","signed_on");
            JsonNode plan=ProductionArtifacts.migration(spec(fixture()),spec(changed));blocked(plan,"DROP_COLUMN");
            require(change(plan,"ADD_COLUMN").path("status").asText().equals("SQL_CANDIDATE"),"rename does not masquerade as safe rename");
            require(!Util.pretty(plan).contains("RENAME COLUMN"),"no automatic rename");
        });
        check("migration-type-change-blocked", () -> {
            ObjectNode changed=fixture();((ObjectNode)changed.path("fields").get(2)).put("javaType","LocalDateTime");
            blocked(ProductionArtifacts.migration(spec(fixture()),spec(changed)),"ALTER_COLUMN");
        });
        check("migration-length-and-nullability-changes-blocked", () -> {
            ObjectNode changed=fixture();((ObjectNode)changed.path("fields").get(0)).put("maxLength",80);
            blocked(ProductionArtifacts.migration(spec(fixture()),spec(changed)),"ALTER_COLUMN");
            changed=fixture();((ObjectNode)changed.path("fields").get(2)).put("required",true);
            blocked(ProductionArtifacts.migration(spec(fixture()),spec(changed)),"ALTER_COLUMN");
        });
        check("migration-index-change-blocked", () -> {
            ObjectNode changed=fixture();changed.putArray("indexes").addObject().put("name","uk_contract_no").put("unique",true).putArray("fields").add("contractNo");
            blocked(ProductionArtifacts.migration(spec(fixture()),spec(changed)),"INDEX_CHANGED");
        });
        check("migration-table-change-blocks-all-added-column-sql", () -> {
            ObjectNode changed=fixture().put("tableName","biz_contract_new");add(changed,false);
            JsonNode plan=ProductionArtifacts.migration(spec(fixture()),spec(changed));blocked(plan,"TABLE_CHANGED");blocked(plan,"ADD_COLUMN");
            require(!ProductionArtifacts.migrationMarkdown(plan).contains("```sql"),"no table rename candidate SQL");
        });
        check("migration-invalid-identifiers-cannot-enter-sql", () -> {
            ObjectNode changed=fixture().put("tableName","x`; DROP TABLE users; --");
            try {ProductionArtifacts.migration(spec(fixture()),spec(changed));throw new AssertionError("unsafe identifier accepted");} catch(IllegalArgumentException expected) { }
        });
        check("evidence-template-binds-exact-spec-version-and-file-bytes", () -> {
            Spec current=spec(fixture());Map<String,byte[]> files=files();JsonNode template=ProductionArtifacts.evidenceTemplate(current,files);
            JsonNode binding=ProductionArtifacts.binding(current);
            for(String key:Arrays.asList("specSha256","toolVersion","templateVersion"))require(template.path(key).equals(binding.path(key)),"binding "+key);
            require(template.path("files").size()==files.size(),"file set");
            for(Map.Entry<String,byte[]> file:files.entrySet())require(template.path("files").path(file.getKey()).asText().equals(Util.sha(file.getValue())),"file digest");
            JsonNode report=ProductionArtifacts.inspectEvidence(current,template,files);neverReady(report);require(report.path("bindingMatched").asBoolean(),"fresh binding");
            for(JsonNode row:report.path("checks"))require(Arrays.asList("NOT_RUN","NOT_APPLICABLE").contains(row.path("status").asText()),"template never fabricated PASS");
        });
        check("evidence-stale-spec-template-and-tool-bindings-are-explicit", () -> {
            Spec current=spec(fixture());Map<String,byte[]> files=files();
            for(String key:Arrays.asList("specSha256","toolVersion","templateVersion")) {
                ObjectNode evidence=ProductionArtifacts.evidenceTemplate(current,files).put(key,"old");JsonNode report=ProductionArtifacts.inspectEvidence(current,evidence,files);
                neverReady(report);require(!report.path("bindingMatched").asBoolean()&&contains(report.path("findings"),"STALE_BINDING:"+key),"stale binding reported");
            }
            ObjectNode changed=fixture().put("description","changed requirements");JsonNode report=ProductionArtifacts.inspectEvidence(spec(changed),ProductionArtifacts.evidenceTemplate(current,files),files);
            require(!report.path("bindingMatched").asBoolean(),"changed spec cannot reuse previous evidence");
        });
        check("evidence-stale-missing-extra-and-tampered-files-cannot-match", () -> {
            Spec current=spec(fixture());Map<String,byte[]> files=files();
            ObjectNode evidence=ProductionArtifacts.evidenceTemplate(current,files);Map<String,byte[]> changed=files();changed.put("module.java",Util.bytes("changed"));
            JsonNode stale=ProductionArtifacts.inspectEvidence(current,evidence,changed);neverReady(stale);require(contains(stale.path("findings"),"STALE_FILE:module.java"),"changed bytes");
            ((ObjectNode)evidence.path("files")).remove("module.java");JsonNode missing=ProductionArtifacts.inspectEvidence(current,evidence,files);neverReady(missing);require(contains(missing.path("findings"),"FILE_SET_MISMATCH"),"missing digest");
            evidence=ProductionArtifacts.evidenceTemplate(current,files);((ObjectNode)evidence.path("files")).put("injected.java",repeat('a',64));JsonNode extra=ProductionArtifacts.inspectEvidence(current,evidence,files);neverReady(extra);require(!extra.path("bindingMatched").asBoolean(),"extra digest");
        });
        check("evidence-missing-rows-explicit-and-required-na-invalid", () -> {
            Spec current=spec(fixture());Map<String,byte[]> files=files();ObjectNode evidence=ProductionArtifacts.evidenceTemplate(current,files);evidence.remove("evidence");
            JsonNode report=ProductionArtifacts.inspectEvidence(current,evidence,files);neverReady(report);require(contains(report.path("findings"),"MISSING_EVIDENCE_ROWS"),"missing array");
            for(JsonNode row:report.path("checks"))require(row.path("status").asText().equals("MISSING"),"missing row status");
            evidence=ProductionArtifacts.evidenceTemplate(current,files);String id=requiredId(current);find(evidence.path("evidence"),id).put("status","NOT_APPLICABLE");
            report=ProductionArtifacts.inspectEvidence(current,evidence,files);require(find(report.path("checks"),id).path("status").asText().equals("INVALID"),"cannot mark required check NA");neverReady(report);
        });
        check("evidence-duplicate-and-unknown-row-ids-reported", () -> {
            Spec current=spec(fixture());Map<String,byte[]> files=files();ObjectNode evidence=ProductionArtifacts.evidenceTemplate(current,files);
            JsonNode first=evidence.path("evidence").get(0).deepCopy();((ArrayNode)evidence.path("evidence")).add(first);((ArrayNode)evidence.path("evidence")).addObject().put("id","UNKNOWN-TEST").put("status","PASS");
            JsonNode report=ProductionArtifacts.inspectEvidence(current,evidence,files);neverReady(report);require(contains(report.path("findings"),"DUPLICATE_EVIDENCE:"+first.path("id").asText()),"duplicate evidence");require(contains(report.path("findings"),"UNKNOWN_EVIDENCE:UNKNOWN-TEST"),"unknown evidence");
        });
        check("evidence-fake-pass-without-details-is-incomplete", () -> {
            Spec current=spec(fixture());Map<String,byte[]> files=files();ObjectNode evidence=ProductionArtifacts.evidenceTemplate(current,files);String id=requiredId(current);
            find(evidence.path("evidence"),id).put("status","PASS");JsonNode report=ProductionArtifacts.inspectEvidence(current,evidence,files);
            neverReady(report);require(find(report.path("checks"),id).path("status").asText().equals("INCOMPLETE_EVIDENCE"),"fake PASS rejected");
        });
        check("evidence-complete-self-attestation-awaits-review-never-grants-readiness", () -> {
            Spec current=spec(fixture());Map<String,byte[]> files=files();ObjectNode evidence=ProductionArtifacts.evidenceTemplate(current,files);String id=requiredId(current);
            attest(find(evidence.path("evidence"),id),Instant.now().minusSeconds(60).toString());
            JsonNode report=ProductionArtifacts.inspectEvidence(current,evidence,files);neverReady(report);require(find(report.path("checks"),id).path("status").asText().equals("ATTESTED_PENDING_REVIEW"),"external evidence not trusted as executed PASS");
            find(evidence.path("evidence"),id).put("executedAt",Instant.now().plusSeconds(3600).toString());
            report=ProductionArtifacts.inspectEvidence(current,evidence,files);neverReady(report);require(find(report.path("checks"),id).path("status").asText().equals("INCOMPLETE_EVIDENCE"),"future evidence time rejected");
        });
        check("evidence-no-submitted-status-can-set-deployment-ready", () -> {
            Spec current=spec(fixture());Map<String,byte[]> files=files();
            for(String status:Arrays.asList("PASS","FAIL","SKIPPED","NOT_RUN","MANUAL_REVIEW","NOT_APPLICABLE","ALL_PASS","ATTESTED_PENDING_REVIEW")) {
                ObjectNode evidence=ProductionArtifacts.evidenceTemplate(current,files).put("deploymentReady",true).put("verdict","PASS");
                for(JsonNode row:evidence.path("evidence")){attest((ObjectNode)row,"2026-01-01T00:00:00Z");((ObjectNode)row).put("status",status);}
                neverReady(ProductionArtifacts.inspectEvidence(current,evidence,files));
            }
        });
        check("history-corrupt-metadata-isolated-normal-history-still-readable", () -> {
            Path dir=folder("history");Store store=new Store(dir);Map<String,byte[]> payload=Collections.singletonMap("openapi.json",Util.bytes("{\"synthetic\":true}"));
            String good=store.save("contract","good",payload).path("id").asText();String bad=store.save("contract","bad",payload).path("id").asText();
            byte[] broken=Util.bytes("{broken \"SYNTHETIC_PRESERVED_BYTES\"");Files.write(dir.resolve(bad).resolve("metadata.json"),broken);
            List<JsonNode> listed=store.list("contract");require(listed.size()==1&&listed.get(0).path("id").asText().equals(good),"bad metadata must not hide normal history");
            require(Arrays.equals(store.read(good,"openapi.json"),payload.get("openapi.json")),"normal payload unchanged");
            boolean preserved=false;try(Stream<Path> paths=Files.walk(dir)){for(Path path:paths.filter(Files::isRegularFile).collect(Collectors.toList()))if(Arrays.equals(Files.readAllBytes(path),broken))preserved=true;}
            require(preserved,"corrupt bytes must be retained for recovery");
            JsonNode diagnostic=Util.JSON.valueToTree(store.diagnostics());require(!Util.pretty(diagnostic).contains("SYNTHETIC_PRESERVED_BYTES"),"no corrupt content in diagnostics");
            require(diagnostic.path("quarantinedRecords").asInt()>=1&&!diagnostic.path("recovery").asText().isEmpty(),"explicit history health warning and recovery guidance");
            require(new Store(dir).list("contract").size()==1,"isolation survives reload");
        });
        check("history-failed-quarantine-reports-unisolated-damage-and-preserves-bytes", () -> {
            Path dir=folder("blocked-quarantine");Store store=new Store(dir);Map<String,byte[]> payload=Collections.singletonMap("openapi.json",Util.bytes("{}"));
            store.save("contract","good",payload);String bad=store.save("contract","bad",payload).path("id").asText();
            byte[] broken=Util.bytes("{broken metadata");Files.write(dir.resolve(bad).resolve("metadata.json"),broken);Files.write(dir.resolve(".quarantine"),Util.bytes("blocks quarantine directory creation"));
            require(store.list("contract").size()==1,"healthy history survives failed isolation");
            require(store.diagnostics().path("unquarantinedRecords").asInt()==1,"failed isolation must not look healthy");
            require(Arrays.equals(Files.readAllBytes(dir.resolve(bad).resolve("metadata.json")),broken),"failed isolation preserves original bytes");
            Files.delete(dir.resolve(".quarantine"));require(store.list("contract").size()==1,"retry history");
            require(store.diagnostics().path("unquarantinedRecords").asInt()==0&&store.diagnostics().path("quarantinedRecords").asInt()==1,"diagnostics reflect successful retry");
        });
        check("history-invalid-created-at-is-isolated-without-hiding-good-records", () -> {
            Path dir=folder("bad-date");Store store=new Store(dir);Map<String,byte[]> payload=Collections.singletonMap("openapi.json",Util.bytes("{}"));
            String good=store.save("contract","good",payload).path("id").asText();ObjectNode damaged=(ObjectNode)store.save("contract","bad date",payload);String id=damaged.path("id").asText();
            damaged.put("createdAt","definitely-not-an-instant");byte[] bytes=Util.bytes(Util.pretty(damaged));Files.write(dir.resolve(id).resolve("metadata.json"),bytes);
            List<JsonNode> listed=store.list("contract");require(listed.size()==1&&listed.get(0).path("id").asText().equals(good),"invalid dates must not abort history sorting");
            require(store.diagnostics().path("quarantinedRecords").asInt()==1,"invalid date quarantine warning");
            boolean preserved=false;try(Stream<Path> paths=Files.walk(dir.resolve(".quarantine"))){for(Path path:paths.filter(Files::isRegularFile).collect(Collectors.toList()))if(Arrays.equals(Files.readAllBytes(path),bytes))preserved=true;}
            require(preserved,"invalid-date metadata retained for recovery");
        });
        check("generated-production-outbox-explicitly-uses-transactional-innodb", () -> {
            Map<String,byte[]> generated=productionFiles();String outbox=text(generated,"integration/outbox-schema.sql");String production=text(generated,"sql/schema.sql");
            String table=outboxTable(outbox);require(outbox.contains(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE="),"standalone Outbox DDL must explicitly select InnoDB and character policy");
            require(production.contains("CREATE TABLE `"+table+"`"),"main production schema includes Outbox");
            java.util.regex.Matcher creation=java.util.regex.Pattern.compile("(?s)CREATE TABLE `"+java.util.regex.Pattern.quote(table)+"`\\s*\\(.*?;",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(production);
            require(creation.find()&&creation.group().contains(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE="),"embedded production Outbox DDL must not inherit a nontransactional server default");
            require(!production.toUpperCase(Locale.ROOT).contains("DROP TABLE"),"production SQL never includes test reset");
        });
        check("generated-h2-reset-and-mysql-acceptance-have-separate-engine-and-init-rules", () -> {
            Map<String,byte[]> generated=productionFiles();String outboxTable=outboxTable(text(generated,"integration/outbox-schema.sql"));String h2=text(generated,"src/test/resources/schema-test.sql");
            require(h2.contains("DROP TABLE IF EXISTS `biz_contract`")&&h2.contains("DROP TABLE IF EXISTS `"+outboxTable+"`"),"isolated H2 test schema resets business and Outbox tables");
            require(!java.util.regex.Pattern.compile("(?i)\\bENGINE\\s*=|\\bCHARSET\\s*=|\\bCOLLATE\\s*=").matcher(h2).find(),"H2 schema must not contain MySQL engine/charset clauses");
            require(text(generated,"src/main/resources/application.properties").contains("spring.sql.init.mode=never")&&text(generated,"src/main/resources/application-prod.properties").contains("spring.sql.init.mode=never"),"application and production must disable automatic schema initialization");
            String mysqlCrud=text(generated,"src/test/java/com/bank/contract/MysqlCrudAcceptanceTest.java");
            require(mysqlCrud.contains("spring.sql.init.mode=never")&&mysqlCrud.contains("inheritProfiles = false")&&mysqlCrud.contains("mysql-acceptance"),"opt-in MySQL CRUD test cannot inherit H2 initializer");
            String mysql=text(generated,"src/test/java/com/bank/contract/MysqlAcceptanceTest.java");
            require(mysql.contains("connection.setReadOnly(true)")&&mysql.contains("MYSQL_ACCEPTANCE_READ_ONLY_CONFIRMED"),"MySQL engine check must remain explicit and read-only");
            require(mysql.contains("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")&&mysql.contains("assertEquals(\"InnoDB\", result.getString(1)"),"real database engine must be checked");
            require(java.util.regex.Pattern.compile("new\\s+String\\[\\]\\s*\\{\\s*\"biz_contract\"\\s*,\\s*\""+java.util.regex.Pattern.quote(outboxTable)+"\"\\s*\\}").matcher(mysql).find(),"engine check covers both business and Outbox tables");
            require(!mysql.contains("executeUpdate(")&&!mysql.contains("CREATE TABLE")&&!mysql.contains("DROP TABLE"),"read-only MySQL acceptance must not initialize or reset schema");
        });
        long failed=results.stream().filter(n->!n.path("passed").asBoolean()).count();ObjectNode report=Util.obj().put("javaVersion",System.getProperty("java.version")).put("tests",results.size()).put("passed",results.size()-failed).put("failed",failed);report.set("results",Util.JSON.valueToTree(results));Files.write(work.resolve("results.json"),Util.bytes(Util.pretty(report)));System.out.println("ProductionUpgradeSuite Tests="+results.size()+" Passed="+(results.size()-failed)+" Failed="+failed);if(failed>0)System.exit(1);
    }
    private static ObjectNode fixture()throws Exception{return (ObjectNode)Util.parse(Files.readAllBytes(project.resolve("src/main/resources/static/examples/contract-v2-spec.json")));}
    private static Spec spec(ObjectNode source){return SpecParser.read(Util.bytes(Util.pretty(source)));}
    private static Map<String,byte[]> productionFiles()throws Exception{return new GeneratorService().generate(SpecParser.read(Files.readAllBytes(project.resolve("src/main/resources/static/examples/production-safe-spec.json"))));}
    private static String text(Map<String,byte[]> files,String path){require(files.containsKey(path),"missing generated file "+path);return new String(files.get(path),StandardCharsets.UTF_8);}
    private static String outboxTable(String sql){java.util.regex.Matcher match=java.util.regex.Pattern.compile("CREATE TABLE `([^`]+)`").matcher(sql);require(match.find(),"Outbox DDL table name");return match.group(1);}
    private static ObjectNode add(ObjectNode source,boolean required){return ((ArrayNode)source.path("fields")).addObject().put("name","memo").put("column","memo").put("javaType","String").put("description","optional memo").put("required",required).put("editable",true).put("exposed",true).put("query","NONE").put("maxLength",100);}
    private static JsonNode change(JsonNode plan,String code){for(JsonNode row:plan.path("changes"))if(row.path("code").asText().equals(code))return row;throw new AssertionError("missing migration change "+code);}
    private static void conservative(JsonNode plan){require(plan.path("executionStatus").asText().equals("NOT_RUN")&&!plan.path("autoExecutable").asBoolean(true)&&plan.path("verdict").asText().equals("REVIEW_REQUIRED"),"migration must remain review-only");}
    private static void blocked(JsonNode plan,String code){conservative(plan);JsonNode row=change(plan,code);require(row.path("status").asText().equals("BLOCKED")&&row.path("sql").asText().isEmpty(),code+" must not emit executable SQL");}
    private static Map<String,byte[]> files(){Map<String,byte[]> files=new LinkedHashMap<>();files.put("module.java",Util.bytes("synthetic source"));files.put("openapi.json",Util.bytes("{}"));return files;}
    private static void neverReady(JsonNode report){require(!report.path("deploymentReady").asBoolean(true)&&report.path("verdict").asText().equals("INCOMPLETE"),"self-attestation cannot grant release readiness");}
    private static boolean contains(JsonNode values,String expected){for(JsonNode value:values)if(value.asText().equals(expected))return true;return false;}
    private static ObjectNode find(JsonNode rows,String id){for(JsonNode row:rows)if(row.path("id").asText().equals(id))return (ObjectNode)row;throw new AssertionError("missing evidence row "+id);}
    private static String requiredId(Spec spec){for(JsonNode row:AcceptancePlanning.cases(spec).path("cases"))if(row.path("evidenceRequired").asBoolean())return row.path("id").asText();throw new AssertionError("fixture needs required acceptance case");}
    private static void attest(ObjectNode row,String time){row.put("status","PASS").put("environment","synthetic integration test").put("command","external-check --dry-run").put("reviewer","synthetic-reviewer").put("executedAt",time).put("artifactSha256",repeat('a',64)).put("resultSha256",repeat('b',64));}
    private static String repeat(char c,int length){char[] chars=new char[length];Arrays.fill(chars,c);return new String(chars);}
    private static Path folder(String prefix)throws Exception{return Files.createDirectories(work.resolve(prefix+"-"+UUID.randomUUID()));}
    private static void require(boolean passed,String message){if(!passed)throw new AssertionError(message);}
    private static void check(String name,Check action){ObjectNode result=Util.obj().put("name",name);try{action.run();result.put("passed",true);}catch(Throwable ex){result.put("passed",false).put("error",ex.toString());System.out.println("FAIL "+name+": "+ex);}results.add(result);}
}
