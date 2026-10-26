<#import "_macros.ftl" as code>
package ${package};
import ${package}.service.${entity}Service;
import ${package}.integration.${entity}AccessGuard;
import ${package}.common.${entity}AccessException;
<#if create>import ${package}.dto.${entity}CreateDTO;
</#if><#if update>import ${package}.dto.${entity}UpdateDTO;
</#if><#if page>import ${package}.dto.${entity}QueryDTO;
</#if>
<@code.imports values=testTypeImports />
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 权限与审计基础回归：真实服务代理和SQL、测试身份；不代表银行权限平台已验收。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(CrudIntegrationTest.Providers.class)
@Transactional
class SecurityIntegrationTest {
    @Autowired private ${entity}Service service;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private ${entity}AccessGuard guard;
<#if transactionalAudit>
    @org.springframework.boot.test.mock.mockito.SpyBean private ${package}.integration.${entity}AuditWriter writer;
</#if>
    @BeforeEach void identity() { when(guard.authorize(anyString(), nullable(Long.class), nullable(Object.class))).thenReturn("test-actor"); }
    @Test void deniedOperationHasNoWriteEffects() {
        when(guard.authorize(anyString(), nullable(Long.class), nullable(Object.class))).thenThrow(${entity}AccessException.forbidden());
        assertThrows(${entity}AccessException.class, () -> probe()); assertEquals(0L, rowCount());
<#if transactionalAudit>assertEquals(0L, auditCount());
</#if>
    }
    @Test void invalidIdentityIsRejected() {
        for (String actor : java.util.Arrays.asList(null, " ", "bad\nactor", String.join("", java.util.Collections.nCopies(129,"x")))) {
            when(guard.authorize(anyString(),nullable(Long.class),nullable(Object.class))).thenReturn(actor); assertThrows(${entity}AccessException.class, () -> probe());
        }
        assertEquals(0L,rowCount());
    }
<#if page && recordAccessCheck>
    @Test void pagePolicyMustAuthorizeEntireSqlScope() {
        doThrow(${entity}AccessException.forbidden()).when(guard).checkPageScope(nullable(Object.class)); assertThrows(${entity}AccessException.class, () -> service.page(query()));
    }
</#if><#if detail && recordAccessCheck>
    @Test void detailObjectPolicyIsChecked() {
        Long id=seed(); doThrow(${entity}AccessException.forbidden()).when(guard).checkRecord(eq("DETAIL"),eq(id),nullable(Object.class),anyMap());
        assertThrows(${entity}AccessException.class, () -> service.detail(id));
    }
    @Test void recordSnapshotCannotBeChangedByPolicyAdapter() {
        Long id=seed(); doAnswer(call -> {java.util.Map<String,Object> record=call.getArgument(3); assertThrows(UnsupportedOperationException.class, () -> record.clear()); return null;})
                .when(guard).checkRecord(eq("DETAIL"),eq(id),nullable(Object.class),anyMap()); assertNotNull(service.detail(id));
    }
</#if><#if update>
    @Test void mismatchedBodyIdCannotModifyTheRecord() {
        Long id=seed(); ${entity}UpdateDTO request=updateRequest(); request.setId(id+1L);
        assertThrows(${package}.common.RequestMismatchException.class, () -> service.update(id,request));
<#if optimisticLock>assertEquals(0L,jdbc.queryForObject("SELECT `version` FROM `${table}` WHERE `id`=?",Long.class,id));
</#if><#if transactionalAudit>assertEquals(0L,auditCount());
</#if>
    }
<#if recordAccessCheck>
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void updatedRecordPolicyRejectionRollsBackTheEntireWrite() {
        Long id=seed();
        java.util.Map<String,Object> before=new java.util.LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM `${table}` WHERE `id`=?",id));
        java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        try {
            ${entity}UpdateDTO request=updateRequest();request.setId(id);
            doAnswer(call -> {if(checks.incrementAndGet()==2)throw ${entity}AccessException.forbidden();return null;})
                .when(guard).checkRecord(eq("UPDATE"),eq(id),nullable(Object.class),anyMap());
            assertThrows(${entity}AccessException.class,()->service.update(id,request));
            assertEquals(2,checks.get());
            assertEquals(before,jdbc.queryForMap("SELECT * FROM `${table}` WHERE `id`=?",id));
<#if transactionalAudit>            assertEquals(0L,auditCount());
</#if>
        } finally {jdbc.update("DELETE FROM `${table}` WHERE `id`=?",id);}
    }
    @Test void updateObjectPolicyRejectsBeforeMutation() {
        Long id=seed(); ${entity}UpdateDTO request=updateRequest(); request.setId(id);
        doThrow(${entity}AccessException.forbidden()).when(guard).checkRecord(eq("UPDATE"),eq(id),nullable(Object.class),anyMap());
        assertThrows(${entity}AccessException.class, () -> service.update(id,request));
<#if optimisticLock>assertEquals(0L,jdbc.queryForObject("SELECT `version` FROM `${table}` WHERE `id`=?",Long.class,id));
</#if>
    }
</#if><#if transactionalAudit>
    @Test void updatePersistsWhitelistedBeforeAfterAndTrustedActor() throws Exception {
        Long id=seed(); ${entity}UpdateDTO request=updateRequest(); request.setId(id); service.update(id,request); assertEquals(1L,auditCount());
        java.util.Map<String,Object> audit=jdbc.queryForMap("SELECT * FROM `${table}_audit`"); assertEquals("test-actor",audit.get("actor")); assertEquals("UPDATE",audit.get("operation"));
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode before=json.readTree(audit.get("before_json").toString()),after=json.readTree(audit.get("after_json").toString());
        assertEquals(id.toString(),before.path("id").asText()); assertEquals("0",before.path("version").asText()); assertEquals("1",after.path("version").asText());
        java.util.Set<String> allowed=new java.util.HashSet<String>(java.util.Arrays.asList("id","version","deleted"<#list auditFields as f>,"${f.name}"</#list>));
        before.fieldNames().forEachRemaining(key -> assertTrue(allowed.contains(key))); after.fieldNames().forEachRemaining(key -> assertTrue(allowed.contains(key)));
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void auditFailureRollsBackUpdateAndVersion() {
        Long id=seed(); try {${entity}UpdateDTO request=updateRequest();request.setId(id);doThrow(new IllegalStateException("controlled audit failure")).when(writer).write(any());
            assertThrows(IllegalStateException.class, () -> service.update(id,request)); assertEquals(0L,jdbc.queryForObject("SELECT `version` FROM `${table}` WHERE `id`=?",Long.class,id)); assertEquals(0L,auditCount());
        } finally {jdbc.update("DELETE FROM `${table}` WHERE `id`=?",id);}
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void checkedAuditFailureAlsoRollsBackBusiness() {
        Long id=seed();try{${entity}UpdateDTO request=updateRequest();request.setId(id);
            doAnswer(call->{throw new Exception("controlled checked audit failure");}).when(writer).write(any());
            assertThrows(Exception.class,()->service.update(id,request));assertEquals(0L,jdbc.queryForObject("SELECT `version` FROM `${table}` WHERE `id`=?",Long.class,id));assertEquals(0L,auditCount());
        }finally{jdbc.update("DELETE FROM `${table}` WHERE `id`=?",id);}
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentUpdatesHaveOneWinnerAndOneAudit() throws Exception {
        Long id=seed();java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try {java.util.concurrent.Callable<Boolean> change=()->{start.await();${entity}UpdateDTO request=updateRequest();request.setId(id);try{service.update(id,request);return true;}catch(org.springframework.dao.OptimisticLockingFailureException e){return false;}};
            java.util.concurrent.Future<Boolean> first=pool.submit(change),second=pool.submit(change);start.countDown();assertNotEquals(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1L,jdbc.queryForObject("SELECT `version` FROM `${table}` WHERE `id`=?",Long.class,id));assertEquals(1L,auditCount());
        } finally {pool.shutdownNow();jdbc.update("DELETE FROM `${table}_audit` WHERE `record_id`=?",id);jdbc.update("DELETE FROM `${table}` WHERE `id`=?",id);}
    }
</#if></#if><#if delete && recordAccessCheck>
    @Test void deleteObjectPolicyRejectsBeforeMutation() {
        Long id=seed();doThrow(${entity}AccessException.forbidden()).when(guard).checkRecord(eq("DELETE"),eq(id),nullable(Object.class),anyMap());
        assertThrows(${entity}AccessException.class,()->service.delete(id<#if deleteVersionCheck>,0L</#if>));assertEquals(1L,rowCount());
    }
</#if><#if delete && transactionalAudit>
    @Test void deleteAuditRetainsBeforeSnapshot() throws Exception {
        Long id=seed();service.delete(id,0L);assertEquals(0L,rowCount());assertEquals(1L,auditCount());
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();java.util.Map<String,Object> audit=jdbc.queryForMap("SELECT * FROM `${table}_audit`");
        assertEquals(id.toString(),json.readTree(audit.get("before_json").toString()).path("id").asText());
<#if softDelete>assertTrue(json.readTree(audit.get("after_json").toString()).path("deleted").asBoolean());
<#else>assertTrue(json.readTree(audit.get("after_json").toString()).isEmpty());
</#if>
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void auditFailureRollsBackDelete() {
        Long id=seed();try{doThrow(new IllegalStateException("controlled audit failure")).when(writer).write(any());assertThrows(IllegalStateException.class,()->service.delete(id,0L));assertEquals(1L,rowCount());assertEquals(0L,auditCount());}
        finally{jdbc.update("DELETE FROM `${table}` WHERE `id`=?",id);}
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentDeletesHaveOneWinnerAndOneAudit() throws Exception {
        Long id=seed();java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try{java.util.concurrent.Callable<Boolean> change=()->{start.await();try{service.delete(id,0L);return true;}catch(${package}.common.BusinessException|org.springframework.dao.OptimisticLockingFailureException e){return false;}};
            java.util.concurrent.Future<Boolean> first=pool.submit(change),second=pool.submit(change);start.countDown();assertNotEquals(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1L,auditCount());assertEquals(0L,rowCount());
        }finally{pool.shutdownNow();jdbc.update("DELETE FROM `${table}_audit` WHERE `record_id`=?",id);jdbc.update("DELETE FROM `${table}` WHERE `id`=?",id);}
    }
</#if><#if create && transactionalAudit>
    @Test void createdRecordHasActualPersistedAfterSnapshot() throws Exception {
        Long id=service.create(createRequest());assertEquals(1L,auditCount());com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.Map<String,Object> audit=jdbc.queryForMap("SELECT * FROM `${table}_audit`");assertEquals("CREATE",audit.get("operation"));assertEquals("test-actor",audit.get("actor"));
        assertTrue(json.readTree(audit.get("before_json").toString()).isEmpty());assertEquals(id.toString(),json.readTree(audit.get("after_json").toString()).path("id").asText());
    }
</#if><#if transactionalAudit>
    @Test void auditRetainsExplicitNullEvenWhenHostJsonOmitsIt() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper hostJson=new com.fasterxml.jackson.databind.ObjectMapper().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        ${package}.integration.${entity}JdbcAuditWriter local=new ${package}.integration.${entity}JdbcAuditWriter(jdbc,hostJson);
        java.util.Map<String,Object> snapshot=java.util.Collections.singletonMap("nullable",null);
        local.write(new ${package}.integration.${entity}AuditEvent("UPDATE",1L,"test-actor",snapshot,snapshot));
        com.fasterxml.jackson.databind.JsonNode actual=hostJson.readTree(jdbc.queryForObject("SELECT `after_json` FROM `${table}_audit`",String.class));assertTrue(actual.has("nullable"));assertTrue(actual.get("nullable").isNull());
    }
</#if>
    private void probe(){
<#if create>service.create(createRequest());
<#elseif page>service.page(query());
<#elseif detail>service.detail(1L);
<#elseif update>service.update(1L,updateRequest());
<#elseif delete>service.delete(1L<#if deleteVersionCheck>,0L</#if>);
</#if>}
<#if create>
    private ${entity}CreateDTO createRequest(){${entity}CreateDTO request=new ${entity}CreateDTO();
<#list createFields as f>request.set${f.cap}(${f.sample});
</#list>return request;}
</#if><#if update>
    private ${entity}UpdateDTO updateRequest(){${entity}UpdateDTO request=new ${entity}UpdateDTO();
<#if updateBodyId>request.setId(1L);
</#if><#if optimisticLock>request.setExpectedVersion(0L);
</#if><#list editableFields as f>request.set${f.cap}(${f.updatedSample});
</#list>return request;}
</#if><#if page>
    private ${entity}QueryDTO query(){${entity}QueryDTO query=new ${entity}QueryDTO();
<#list queryFields as f><#if f.required><#if f.query == 'RANGE'>query.set${f.cap}From(${f.sample});query.set${f.cap}To(${f.sample});
<#else>query.set${f.cap}(<#if f.query == 'IN'>java.util.Collections.singletonList(${f.sample})<#else>${f.sample}</#if>);
</#if></#if></#list>return query;}
</#if>
    private Long seed(){jdbc.update("INSERT INTO `${table}` (<#list fields as f>`${f.column}`<#sep>,</#sep></#list>) VALUES (<#list fields as f>?<#sep>,</#sep></#list>)",<#list fields as f>${f.sample}<#sep>,</#sep></#list>);return jdbc.queryForObject("SELECT MAX(`id`) FROM `${table}`",Long.class);}
    private long rowCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM `${table}`<#if softDelete> WHERE `deleted`=FALSE</#if>",Long.class);}
<#if transactionalAudit>private long auditCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM `${table}_audit`",Long.class);}
</#if>
}
