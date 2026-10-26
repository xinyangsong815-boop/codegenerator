package ${package};

<#if !existing>
import ${package}.common.BusinessException;
</#if>
import ${package}.dto.${entity}UpdateDTO;
import ${package}.entity.${entity};
import ${package}.mapper.${entity}Mapper;
import ${package}.service.impl.${entity}ServiceImpl;
<#list testTypeImports as value>
import ${value};
</#list>
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 模拟 DAO 的 0 行更新语义；真实驱动及并发仍需现场测试。 */
class ServiceUpdateTest {

    @Test
    void unchangedExistingRecordDoesNotBecomeNotFound() {
        ${entity}Mapper mapper = mock(${entity}Mapper.class);
        when(mapper.update(eq(1L), any(${entity}<#if partialUpdate>UpdateDTO</#if>.class)<#if scoped>, any(${scopeType}.class)</#if><#if hasServerUpdate>, any(${entity}.class)</#if>)).thenReturn(0);
        when(mapper.existsById(eq(1L)<#if scoped>, any(${scopeType}.class)</#if>)).thenReturn(1L);
<#if optimisticLock>
        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class, () -> service(mapper).update(1L, request()));
<#else>
        service(mapper).update(1L, request());
</#if>
    }

    @Test
    void zeroUpdatedRowsAndMissingRecordRemainNotFound() {
        ${entity}Mapper mapper = mock(${entity}Mapper.class);
        when(mapper.update(eq(1L), any(${entity}<#if partialUpdate>UpdateDTO</#if>.class)<#if scoped>, any(${scopeType}.class)</#if><#if hasServerUpdate>, any(${entity}.class)</#if>)).thenReturn(0);
        when(mapper.existsById(eq(1L)<#if scoped>, any(${scopeType}.class)</#if>)).thenReturn(0L);
        ${exceptionType} exception = assertThrows(${exceptionType}.class,
                () -> service(mapper).update(1L, request()));
<#if !existing>
        assertEquals(404, exception.getStatus());
</#if>
    }

    private ${entity}ServiceImpl service(${entity}Mapper mapper) {
<#if hasWriteContext>
        ${package}.integration.${entity}WriteContext context = mock(${package}.integration.${entity}WriteContext.class);
<#list serverFields as field>
<#if field.serverUpdate || (scoped && field.name == scopeName)>
        when(context.get${field.cap}()).thenReturn(${field.sample});
</#if>
</#list>
</#if>
<#if accessGuard>
        ${package}.integration.${entity}AccessGuard guard = mock(${package}.integration.${entity}AccessGuard.class);
        when(guard.authorize(any(String.class), org.mockito.ArgumentMatchers.nullable(Long.class), org.mockito.ArgumentMatchers.nullable(Object.class))).thenReturn("test-actor");
</#if><#if rowLock>
        when(mapper.findByIdForUpdate(eq(1L)<#if scoped>, any(${scopeType}.class)</#if>)).thenReturn(new ${entity}());
</#if>
        return new ${entity}ServiceImpl(mapper<#if transactionalAudit>, mock(${package}.integration.${entity}AuditWriter.class)</#if><#if accessGuard>, guard</#if><#if hasWriteContext>, context</#if><#if hooks>, mock(${package}.integration.${entity}Hooks.class)</#if>);
    }

    private ${entity}UpdateDTO request() {
        ${entity}UpdateDTO request = new ${entity}UpdateDTO();
<#if optimisticLock>
        request.setExpectedVersion(0L);
</#if>
<#if updateBodyId>
        request.setId(1L);
</#if>
<#list editableFields as field>
        request.set${field.cap}(${field.updatedSample});
</#list>
        return request;
    }
}
