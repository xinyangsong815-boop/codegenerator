<#import "_macros.ftl" as code>
package ${package};

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ${package}.common.BusinessException;
<#if page>
import ${package}.common.PageResult;
import ${package}.dto.${entity}QueryDTO;
import ${package}.vo.${entity}VO;
</#if>
<#if create>
import ${package}.dto.${entity}CreateDTO;
</#if>
<#if update>
import ${package}.dto.${entity}UpdateDTO;
</#if>
import ${package}.service.${entity}Service;
import ${validationPackage}.ConstraintViolationException;
<@code.imports values=testTypeImports />
import java.util.Arrays;
import org.junit.jupiter.api.Test;
<#if page && hasContains>
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
</#if>
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 基础集成测试：真实 Spring 代理、MockMvc、MyBatis 和隔离内存 H2；不是生产验收。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
<#if hasWriteContext || hooks || accessGuard>
@org.springframework.context.annotation.Import(CrudIntegrationTest.Providers.class)
</#if>
class CrudIntegrationTest {
    @Test
    void correlationHeaderIsPresentForAnActualMvcError() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/__generated_missing_route"))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("X-Request-Id"));
    }

    @Test
    void validCorrelationHeaderSurvivesAnActualMvcError() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/__generated_missing_route").header("X-Request-Id", "mvc-correlation-check"))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Request-Id", "mvc-correlation-check"));
    }


<#if hasWriteContext || hooks || accessGuard>
    /** Test-only fake context/hooks; never a production security implementation. */
    @org.springframework.boot.test.context.TestConfiguration
    static class Providers {
<#if accessGuard>
        @org.springframework.context.annotation.Bean
        ${package}.integration.${entity}AccessGuard accessGuard(){return new ${package}.integration.${entity}AccessGuard() {
            public String authorize(String operation, Long id, Object scope) { return "test-actor"; }
<#if recordAccessCheck>
            public void checkRecord(String operation, Long id, Object scope, java.util.Map<String,Object> record) { }
<#if page>public void checkPageScope(Object scope) { }
</#if></#if>
        };}
</#if>
<#if hasWriteContext>
        @org.springframework.context.annotation.Bean
        ${package}.integration.${entity}WriteContext writeContext() {
            return new ${package}.integration.${entity}WriteContext() {
<#list serverFields as field>
                public ${field.type} get${field.cap}() { return ${field.sample}; }
</#list>
            };
        }
</#if>
<#if hooks>
        @org.springframework.context.annotation.Bean
        ${package}.integration.${entity}Hooks hooks() {
            return new ${package}.integration.${entity}Hooks() {
                public void before(String operation, Long id) { }
                public void after(String operation, Long id) { }
            };
        }
</#if>
    }
</#if>

    @Autowired
    private ${entity}Service service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void incompatibleAcceptIsRejectedBeforeBusinessDispatch() throws Exception {
        mvc.perform(endpointRequest("${apis[0].operation}", "1", <#if firstBody != ''>validBody()<#else>json.createObjectNode()</#if>).accept("text/plain"))
                .andExpect(status().isNotAcceptable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        assertEquals(0L, rowCount());
    }

    @Test
    void applicationAndTestDatabaseStart() {
        assertNotNull(service);
        assertEquals(0L, rowCount());
    }

    @Test
    void unsupportedMethodUsesUniformEnvelope() throws Exception {
        mvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf("${unsupportedMethod}"), "${unsupportedPath}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405));
    }

    @Test
    void missingRouteUsesUniformEnvelope() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("${basePath}/missing/route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

<#if firstBody != ''>
    @Test
    void malformedJsonIsRejected() throws Exception {
        mvc.perform(bodyRequest().contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void unknownJsonFieldIsRejected() throws Exception {
        ObjectNode body = validBody();
        body.put("unexpectedField", 1);
        mvc.perform(bodyRequest().contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void unsupportedContentTypeIsRejected() throws Exception {
        mvc.perform(bodyRequest().contentType("text/plain").content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(415));
    }

    private MockHttpServletRequestBuilder bodyRequest() throws Exception {
        return endpointRequest("${firstBody}", "1", validBody());
    }

    private ObjectNode validBody() {
<#if firstBody == 'CREATE'>
        return json.valueToTree(createRequest());
<#elseif firstBody == 'UPDATE'>
        return json.valueToTree(updateRequest());
<#elseif firstBody == 'PAGE'>
        return json.valueToTree(queryRequest());
<#else>
        return json.createObjectNode().put("id", <#if stringNumbers>"1"<#else>1</#if>);
</#if>
    }

</#if>
<#if hasId>
    @Test
    void nonPositiveIdIsRejected() throws Exception {
        MockHttpServletRequestBuilder request = endpointRequest("${firstIdApi.operation}", "0", <#if firstIdApi.operation == 'UPDATE'>json.valueToTree(updateRequest())<#else>json.createObjectNode()</#if>);
        mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void malformedIdUsesUniformEnvelope() throws Exception {
        MockHttpServletRequestBuilder request = endpointRequest("${firstIdApi.operation}", "not-a-number", <#if firstIdApi.operation == 'UPDATE'>json.valueToTree(updateRequest())<#else>json.createObjectNode()</#if>);
        mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void serviceIdConstraintRunsBeforeSql() {
<#if detail>
        assertThrows(ConstraintViolationException.class, () -> service.detail(0L));
<#elseif delete>
        assertThrows(ConstraintViolationException.class, () -> service.delete(0L<#if deleteVersionCheck>, 0L</#if>));
<#else>
        assertThrows(ConstraintViolationException.class, () -> service.update(0L, updateRequest(0L)));
</#if>
    }

    @Test
    void missingRecordHasNotFoundStatus() {
<#if detail>
        BusinessException exception = assertThrows(BusinessException.class, () -> service.detail(Long.MAX_VALUE));
<#elseif delete>
        BusinessException exception = assertThrows(BusinessException.class, () -> service.delete(Long.MAX_VALUE<#if deleteVersionCheck>, 0L</#if>));
<#else>
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.update(Long.MAX_VALUE, updateRequest(Long.MAX_VALUE)));
</#if>
        assertEquals(404, exception.getStatus());
    }

</#if>
<#if create>
    @Test
    void createReturnsPersistedId() {
        Long id = service.create(createRequest());
        assertNotNull(id);
        assertEquals(1L, rowCount());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM `${table}` WHERE `id` = ?", Long.class, id));
    }

    @Test
    void nullServiceInputIsRejected() {
        assertThrows(ConstraintViolationException.class, () -> service.create(null));
    }

<#if hasRequired>
    @Test
    void missingRequiredValuesAreRejectedBeforeSql() {
        assertThrows(ConstraintViolationException.class, () -> service.create(new ${entity}CreateDTO()));
        assertEquals(0L, rowCount());
    }

</#if>
<#if firstRequiredString??>
    @Test
    void blankRequiredStringIsRejectedBeforeSql() {
        ${entity}CreateDTO request = createRequest();
        request.set${firstRequiredString.cap}(" ");
        assertThrows(ConstraintViolationException.class, () -> service.create(request));
        assertEquals(0L, rowCount());
    }

</#if>
<#if firstString??>
    @Test
    void unicodeLengthUsesCodePoints() {
        ${entity}CreateDTO request = createRequest();
        StringBuilder value = new StringBuilder();
        for(int i=0;i<${firstString.length};i++)value.append("\uD83D\uDE00");
        request.set${firstString.cap}(value.toString());
        try(${validationPackage}.ValidatorFactory factory=${validationPackage}.Validation.buildDefaultValidatorFactory()) {
            org.junit.jupiter.api.Assertions.assertTrue(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test
    void overlongStringIsRejectedBeforeSql() {
        ${entity}CreateDTO request = createRequest();
        char[] value = new char[${firstString.length + 1}];
        Arrays.fill(value, 'A');
        request.set${firstString.cap}(new String(value));
        assertThrows(ConstraintViolationException.class, () -> service.create(request));
        assertEquals(0L, rowCount());
    }

</#if>
<#if firstDecimal??>
    @Test
    void decimalPrecisionIsRejectedBeforeSql() {
        ${entity}CreateDTO request = createRequest();
        request.set${firstDecimal.cap}(new BigDecimal("1E+${firstDecimal.precision - firstDecimal.scale}"));
        assertThrows(ConstraintViolationException.class, () -> service.create(request));
        assertEquals(0L, rowCount());
    }

</#if>
    private ${entity}CreateDTO createRequest() {
        ${entity}CreateDTO request = new ${entity}CreateDTO();
<#list createFields as field>
        request.set${field.cap}(${field.sample});
</#list>
        return request;
    }

</#if>
<#if update>
    @Test
    void updateWritesEditableColumnsAndAllowsRepeatedValues() {
        Long id = seedRow();
        ${entity}UpdateDTO request = updateRequest(id);
        service.update(id, request);
<#if optimisticLock>
        request.setExpectedVersion(1L);
</#if>
        service.update(id, request);
        assertEquals(1L, rowCount());
    }

<#if fields?size != editableFields?size>
    @Test
    void updatePreservesUnselectedColumns() {
        Long id = seedRow();
        java.util.Map<String, Object> before = jdbc.queryForMap("SELECT * FROM `${table}` WHERE `id` = ?", id);
        service.update(id, updateRequest(id));
        java.util.Map<String, Object> after = jdbc.queryForMap("SELECT * FROM `${table}` WHERE `id` = ?", id);
<#list fields as field>
<#if !updateApi.fields?seq_contains(field.name)>
        assertEquals(before.get("${field.column}"), after.get("${field.column}"));
</#if>
</#list>
    }

</#if>
<#if firstNullableEditable??>
    @Test
    void <#if partialUpdate>partialUpdateExplicitNullClearsNullableField<#else>fullUpdateClearsNullableEditableField</#if>() {
        Long id = seedRow();
        ${entity}UpdateDTO request = updateRequest(id);
        request.set${firstNullableEditable.cap}(null);
        service.update(id, request);
        assertEquals(null, jdbc.queryForMap("SELECT * FROM `${table}` WHERE `id` = ?", id)
                .get("${firstNullableEditable.column}"));
    }

</#if>
    private ${entity}UpdateDTO updateRequest(Long id) { ${entity}UpdateDTO request=updateRequest();request.setId(id);return request; }
    private ${entity}UpdateDTO updateRequest() {
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

</#if>
<#if detail>
    @Test
    void detailReturnsViewForPersistedRow() {
        Long id = seedRow();
        assertEquals(id, service.detail(id).getId());
    }

</#if>
<#if delete>
    @Test
    void deleteRemovesOnlyRequestedRow() {
        Long id = seedRow();
<#if canSeedMultiple>
        seedRow();
</#if>
        service.delete(id<#if deleteVersionCheck>, 0L</#if>);
        assertEquals(<#if canSeedMultiple>1L<#else>0L</#if>, rowCount());
        assertThrows(BusinessException.class, () -> service.delete(id<#if deleteVersionCheck>, 0L</#if>));
    }

</#if>
<#if page>
    @Test
    void paginationReportsTotalAndUsesDescendingIds() {
        Long firstId = seedRow();
<#if canSeedMultiple>
        Long secondId = seedRow();
</#if>
        ${entity}QueryDTO query = queryRequest();
        query.setSize(1);
        PageResult<${entity}VO> first = service.page(query);
        assertEquals(<#if canSeedMultiple>2L<#else>1L</#if>, first.getTotal());
        assertEquals(1, first.getRecords().size());
        assertEquals(<#if canSeedMultiple>secondId<#else>firstId</#if>, first.getRecords().get(0).getId());
        query.setPage(2);
<#if canSeedMultiple>
        assertEquals(firstId, service.page(query).getRecords().get(0).getId());
<#else>
        assertEquals(0, service.page(query).getRecords().size());
</#if>
        query.setPage(100);
        assertEquals(0, service.page(query).getRecords().size());
        assertEquals(<#if canSeedMultiple>2L<#else>1L</#if>, service.page(query).getTotal());
    }

    @Test
    void invalidPaginationIsRejected() {
        ${entity}QueryDTO query = queryRequest();
        query.setPage(0);
        assertThrows(ConstraintViolationException.class, () -> service.page(query));
        query.setPage(1);
        query.setSize(101);
        assertThrows(ConstraintViolationException.class, () -> service.page(query));
        query.setSize(null);
        assertThrows(ConstraintViolationException.class, () -> service.page(query));
    }

    @Test
    void internalOffsetCannotBeProvidedByClient() throws Exception {
        mvc.perform(endpointRequest("PAGE", "1", json.createObjectNode().put("offset", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

<#list rangeFields as field>
    @Test
    void reversed${field.cap}RangeIsRejected() {
        ${entity}QueryDTO query = queryRequest();
        query.set${field.cap}From(${field.updatedSample});
        query.set${field.cap}To(${field.sample});
        assertThrows(ConstraintViolationException.class, () -> service.page(query));
<#if !field.required>
        query.set${field.cap}To(null);
        service.page(query);
</#if>
    }

</#list>
<#if hasContains>
    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!", "\\", "'"})
    void containsTreatsSpecialCharactersAsLiterals(String literal) {
        Long id = seedRow();
<#if canSeedMultiple>
        seedRow();
</#if>
        jdbc.update("UPDATE `${table}` SET `${firstContains.column}` = ? WHERE `id` = ?", literal, id);
        ${entity}QueryDTO query = queryRequest();
        query.set${firstContains.cap}(literal);
        assertEquals(1L, service.page(query).getTotal());
        assertEquals(id, service.page(query).getRecords().get(0).getId());
    }

</#if>
</#if>
<#if page>
    private ${entity}QueryDTO queryRequest() {
        ${entity}QueryDTO query = new ${entity}QueryDTO();
<#list queryFields as f>
<#if f.required>
<#if f.query == 'RANGE'>
        query.set${f.cap}From(${f.sample});
        query.set${f.cap}To(${f.updatedSample});
<#else>
        query.set${f.cap}(<#if f.query == 'IN'>java.util.Collections.singletonList(${f.sample})<#else>${f.sample}</#if>);
</#if>
</#if>
</#list>
        return query;
    }

<#list queryFields as f>
<#if f.query == 'IN'>
    @Test
    void ${f.name}ListMatchesAndRejectsEmptyInput() {
        Long id = seedRow();
        ${entity}QueryDTO query = queryRequest();
        query.set${f.cap}(java.util.Collections.singletonList(${f.sample}));
        assertEquals(1L, service.page(query).getTotal());
        query.set${f.cap}(java.util.Collections.emptyList());
        assertThrows(ConstraintViolationException.class, () -> service.page(query));
        query.set${f.cap}(java.util.Collections.singletonList(null));
        assertThrows(ConstraintViolationException.class, () -> service.page(query));
    }

    @Test
    void ${f.name}ListHttpBindingMatchesDatabase() throws Exception {
        seedRow();
        ObjectNode input = json.valueToTree(queryRequest());
        input.set("${f.name}", json.valueToTree(java.util.Collections.singletonList(${f.sample})));
        mvc.perform(endpointRequest("PAGE", "1", input))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(<#if stringNumbers>"1"<#else>1</#if>));
    }


</#if>
</#list>
</#if>
<#if update && partialUpdate>
    @Test
    void emptyPartialUpdateIsRejectedBeforeSql() {
        Long id = seedRow();
        ${entity}UpdateDTO request = new ${entity}UpdateDTO();
<#if optimisticLock>
        request.setExpectedVersion(0L);
</#if>
<#if updateBodyId>
        request.setId(id);
</#if>
        assertThrows(ConstraintViolationException.class, () -> service.update(id, request));
    }

    @Test
    void emptyPartialUpdateIsRejectedOverHttp() throws Exception {
        Long id = seedRow();
        mvc.perform(endpointRequest("UPDATE", id.toString(), json.createObjectNode()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void internalPresenceFlagCannotBeForged() throws Exception {
        Long id = seedRow();
        ObjectNode input = json.valueToTree(updateRequest());
        input.put("${editableFields[0].name}Present", false);
        mvc.perform(endpointRequest("UPDATE", id.toString(), input))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

<#if editableFields?size gt 1>
    @Test
    void partialUpdatePreservesOmittedFields() {
        Long id = seedRow();
        ${entity}UpdateDTO request = new ${entity}UpdateDTO();
<#if optimisticLock>
        request.setExpectedVersion(0L);
</#if>
<#if updateBodyId>
        request.setId(id);
</#if>
        request.set${editableFields[0].cap}(${editableFields[0].updatedSample});
        service.update(id, request);
<#list editableFields as f>
<#if f?index gt 0>
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM `${table}` WHERE `id` = ? AND `${f.column}` = ?", Long.class, id, ${f.sample}));
</#if>
</#list>
    }

</#if>
<#list editableFields as f>
<#if f.nonNull>
    @Test
    void partial${f.cap}CannotClearNonNullableField() {
        Long id = seedRow();
        ${entity}UpdateDTO request = updateRequest();
        request.set${f.cap}(null);
        assertThrows(ConstraintViolationException.class, () -> service.update(id, request));
    }

</#if>
</#list>
</#if>
<#list apis as api>
    @Test
    void configured${api.operation?cap_first}HttpEndpointIsCallable() throws Exception {
<#if api.operation == 'CREATE'>
        mvc.perform(endpointRequest("CREATE", "1", json.valueToTree(createRequest())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").<#if stringNumbers>isString<#else>isNumber</#if>());
<#elseif api.operation == 'PAGE'>
        seedRow();
        mvc.perform(endpointRequest("PAGE", "1", json.valueToTree(queryRequest())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(<#if stringNumbers>"1"<#else>1</#if>));
<#else>
        Long id = seedRow();
        mvc.perform(endpointRequest("${api.operation}", id.toString(), <#if api.operation == 'UPDATE'>json.valueToTree(updateRequest(id))<#else>json.createObjectNode()</#if>))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
</#if>
    }

</#list>
<#list apis as api>
<#if api.idIn == 'BODY'>
    @Test
    void fractional${api.operation?cap_first}IdIsRejected() throws Exception {
        Long id = seedRow();
        ObjectNode wire = <#if api.operation == 'UPDATE'>json.valueToTree(updateRequest())<#else>json.createObjectNode()</#if>;
<#if api.wrapped>
        wire = json.createObjectNode().set("${api.bodyProperty}", wire);
</#if>
        wire.put("id", id.doubleValue() + 0.5);
        mvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf("${api.method}"), "${api.url}")
                .contentType("application/json").content(wire.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

</#if>
</#list>
<#if update>
    @Test void mismatchedUpdateIdsAreRejectedBeforeMutation() {
        Long id=seedRow();${entity}UpdateDTO request=updateRequest(id+1L);assertThrows(${package}.common.RequestMismatchException.class,()->service.update(id,request));assertEquals(1L,rowCount());
    }
<#if updateApi.idIn != 'BODY' || updateApi.wrapped>
    @Test void mismatchedUpdateIdsReturnPublicHttp400() throws Exception {
        Long id=seedRow();ObjectNode input=json.valueToTree(updateRequest(id+1L));
        mvc.perform(endpointRequest("UPDATE",id.toString(),input)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("id")).andExpect(jsonPath("$.errors[0].code").value("ID_MISMATCH"));assertEquals(1L,rowCount());
    }
</#if><#if partialUpdate>
    @Test void emptyUpdateErrorUsesPublicRequestPath() throws Exception {
        Long id=seedRow();ObjectNode input=json.createObjectNode();
<#if optimisticLock>input.put("expectedVersion",<#if stringNumbers>"0"<#else>0L</#if>);
</#if>
        String response=mvc.perform(endpointRequest("UPDATE",id.toString(),input)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        boolean found=false;for(com.fasterxml.jackson.databind.JsonNode issue:json.readTree(response).path("errors")) if(issue.path("code").asText().equals("EMPTY_UPDATE")){found=true;assertEquals("<#if updateApi.wrapped>${updateApi.bodyProperty}<#else>$</#if>",issue.path("field").asText());}
        org.junit.jupiter.api.Assertions.assertTrue(found);org.junit.jupiter.api.Assertions.assertFalse(response.contains("updatedFields"));
    }
<#list editableFields as f><#if f.nonNull>
    @Test void explicitNull${f.cap}ReportsBusinessField() throws Exception {
        Long id=seedRow();ObjectNode input=json.valueToTree(updateRequest(id));input.putNull("${f.name}");
        String response=mvc.perform(endpointRequest("UPDATE",id.toString(),input)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();boolean found=false;
        for(com.fasterxml.jackson.databind.JsonNode issue:json.readTree(response).path("errors")) if(issue.path("field").asText().equals("<#if updateApi.wrapped>${updateApi.bodyProperty}.</#if>${f.name}"))found=true;
        org.junit.jupiter.api.Assertions.assertTrue(found);org.junit.jupiter.api.Assertions.assertFalse(response.contains("ValueValid"));
    }
</#if></#list></#if></#if>
<#if page><#list rangeFields as f>
    @Test void reversed${f.cap}RangeReportsBothPublicEndpoints() throws Exception {
        ${entity}QueryDTO query=queryRequest();query.set${f.cap}From(${f.updatedSample});query.set${f.cap}To(${f.sample});
        String response=mvc.perform(endpointRequest("PAGE","1",json.valueToTree(query))).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();java.util.Set<String> fields=new java.util.HashSet<String>();
        for(com.fasterxml.jackson.databind.JsonNode issue:json.readTree(response).path("errors"))fields.add(issue.path("field").asText());
        org.junit.jupiter.api.Assertions.assertTrue(fields.contains("<#if pageApi.wrapped>${pageApi.bodyProperty}.</#if>${f.name}From"));org.junit.jupiter.api.Assertions.assertTrue(fields.contains("<#if pageApi.wrapped>${pageApi.bodyProperty}.</#if>${f.name}To"));org.junit.jupiter.api.Assertions.assertFalse(response.contains("RangeValid"));
    }
</#list></#if>
<#if deleteVersionCheck && deleteApi.idIn == 'BODY' && strictJson && jsonScope == 'MODULE'>
    @Test void strictDeleteRejectsUnknownProperties() throws Exception {
        Long id=seedRow();mvc.perform(endpointRequest("DELETE",id.toString(),json.createObjectNode().put("unknownSecret","PRIVATE"))).andExpect(status().isBadRequest());assertEquals(1L,rowCount());
    }
</#if>

<#if page>
    @Test void deepOffsetIsRejectedBeforeCounting() {
        ${entity}QueryDTO query = queryRequest();
        query.setPage(100000);
        query.setSize(100);
        assertThrows(${package}.common.PaginationLimitException.class, () -> service.page(query));
    }
    @Test void deepOffsetHasHttp400() throws Exception {
        ObjectNode input = json.valueToTree(queryRequest());
        input.put("page", 100000).put("size", 100);
        mvc.perform(endpointRequest("PAGE", "1", input)).andExpect(status().isBadRequest());
    }
</#if>
<#if hooks>
    @Test void outboxPersistsInsideTheSameBusinessTransaction() {
        ${package}.integration.${entity}Outbox outbox = new ${package}.integration.${entity}Outbox(jdbc);
        outbox.enqueue("event-1", "test.updated", "{}");
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM `${outboxTable}` WHERE `state`='PENDING'", Long.class));
        org.springframework.transaction.support.TransactionTemplate outside = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        outside.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        outside.execute(status -> { assertThrows(IllegalStateException.class, () -> outbox.enqueue("event-2", "test.updated", "{}")); return null; });
    }
</#if>

    /** Test requests use the resolved contract, while SQL assertions verify persisted effects. */
    private MockHttpServletRequestBuilder endpointRequest(String operation, String id, ObjectNode input) throws Exception {
        ObjectNode wire = input.deepCopy();
        switch (operation) {
<#list apis as api>
            case "${api.operation}": {
                MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf("${api.method}"), "${api.url}".replace("{id}", id));
<#if api.idIn == 'QUERY'>
                request.param("id", id);
</#if>
<#if api.operation == 'DELETE' && deleteVersionCheck>
<#if api.idIn == 'BODY'>
                wire.put("expectedVersion",<#if stringNumbers>"0"<#else>0L</#if>);
<#else>
                request.param("expectedVersion","0");
</#if>
</#if>
<#if api.wrapped>
                wire = json.createObjectNode().set("${api.bodyProperty}", wire);
</#if>
<#if api.idIn == 'BODY'>
<#if stringNumbers>
                wire.put("id", id);
<#else>
                try { wire.put("id", Long.parseLong(id)); }
                catch (NumberFormatException malformedId) { wire.put("id", id); }
</#if>
</#if>
<#if api.body>
                request.contentType("application/json").content(json.writeValueAsString(wire));
<#elseif api.requestIn == 'QUERY'>
                java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> entries = wire.fields();
                while (entries.hasNext()) {
                    java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> entry = entries.next();
                    if (entry.getValue().isNull()) continue;
                    if (entry.getValue().isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode value : entry.getValue()) request.param(entry.getKey(), value.asText());
                    } else request.param(entry.getKey(), entry.getValue().asText());
                }
</#if>
                return request;
            }
</#list>
            default: throw new IllegalArgumentException(operation);
        }
    }

    /** 每条测试由事务回滚；使用参数绑定，不经过被测 Service，避免循环自证。 */
    private int seedNumber;

    private Long seedRow() {
        jdbc.update("INSERT INTO `${table}` ("
<#list fields as field>
                + "`${field.column}`<#sep>, </#sep>"
</#list>
                + ") VALUES ("
<#list fields as field>
                + "?<#sep>, </#sep>"
</#list>
                + ")",
<#list fields as field>
                ${field.seedSample}<#sep>,</#sep>
</#list>
        );
        seedNumber++;
        return jdbc.queryForObject("SELECT MAX(`id`) FROM `${table}`", Long.class);
    }

    private long rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `${table}`<#if softDelete> WHERE `deleted` = FALSE</#if>", Long.class);
    }
}
