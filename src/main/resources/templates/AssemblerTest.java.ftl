package ${package};

import ${package}.assembler.${entity}Assembler;
<#if create>
import ${package}.dto.${entity}CreateDTO;
</#if>
<#if update && !partialUpdate>
import ${package}.dto.${entity}UpdateDTO;
</#if>
import ${package}.entity.${entity};
<#if detail || page>
import ${package}.vo.${entity}VO;
</#if>
<#list testTypeImports as value>
import ${value};
</#list>
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证创建、完整更新和展示的字段隔离；不启动数据库，不能替代服务校验与事务集成测试。 */
class AssemblerTest {
<#if create>
    @Test
    void createCopiesOnlyItsClientWhitelist() {
        ${entity}CreateDTO request = new ${entity}CreateDTO();
<#list createFields as field>
        request.set${field.cap}(${field.sample});
</#list>
        ${entity} entity = ${entity}Assembler.fromCreate(request);
        assertNotNull(entity);
        assertNull(entity.getId());
<#if optimisticLock>
        assertNull(entity.getVersion());
</#if>
<#list fields as field>
<#assign included=false>
<#list createFields as selected><#if selected.name == field.name><#assign included=true></#if></#list>
<#if included>
        assertEquals(request.get${field.cap}(), entity.get${field.cap}());
<#else>
        assertNull(entity.get${field.cap}(), "${field.name} 不在新增请求白名单内");
</#if>
</#list>
        assertThrows(NullPointerException.class, () -> ${entity}Assembler.fromCreate(null));
    }

</#if>
<#if update && !partialUpdate>
    @Test
    void fullUpdateDoesNotCopyIdentityVersionOrUnselectedFields() {
        ${entity}UpdateDTO request = new ${entity}UpdateDTO();
        request.setId(99L);
<#if optimisticLock>
        request.setExpectedVersion(7L);
</#if>
<#list editableFields as field>
        request.set${field.cap}(${field.updatedSample});
</#list>
        ${entity} entity = ${entity}Assembler.fromFullUpdate(request);
        assertNull(entity.getId());
<#if optimisticLock>
        assertNull(entity.getVersion());
</#if>
<#list fields as field>
<#assign included=false>
<#list editableFields as selected><#if selected.name == field.name><#assign included=true></#if></#list>
<#if included>
        assertEquals(request.get${field.cap}(), entity.get${field.cap}());
<#else>
        assertNull(entity.get${field.cap}(), "${field.name} 不在完整更新白名单内");
</#if>
</#list>
        assertThrows(NullPointerException.class, () -> ${entity}Assembler.fromFullUpdate(null));
    }

    @Test
    void fullUpdateKeepsExplicitNullRatherThanSkippingIt() {
        ${entity} entity = ${entity}Assembler.fromFullUpdate(new ${entity}UpdateDTO());
<#list editableFields as field>
        assertNull(entity.get${field.cap}());
</#list>
    }

</#if>
<#if detail || page>
    @Test
    void viewContainsOnlyExposedFieldsAndDoesNotMutateEntity() throws Exception {
        ${entity} entity = new ${entity}();
        entity.setId(19L);
<#if optimisticLock>
        entity.setVersion(3L);
</#if>
<#if softDelete>
        entity.setDeleted(true);
</#if>
<#list fields as field>
        entity.set${field.cap}(${field.sample});
</#list>
        ${entity}VO view = ${entity}Assembler.toView(entity);
        assertEquals(entity.getId(), view.getId());
<#if optimisticLock>
        assertEquals(entity.getVersion(), view.getVersion());
</#if>
<#list fields as field>
<#if field.exposed>
        assertEquals(entity.get${field.cap}(), view.get${field.cap}());
<#else>
        assertThrows(NoSuchMethodException.class, () -> ${entity}VO.class.getMethod("get${field.cap}"));
</#if>
        assertEquals(${field.sample}, entity.get${field.cap}());
</#list>
<#if softDelete>
        assertThrows(NoSuchMethodException.class, () -> ${entity}VO.class.getMethod("getDeleted"));
</#if>
        assertThrows(NullPointerException.class, () -> ${entity}Assembler.toView(null));
    }
</#if>
}
