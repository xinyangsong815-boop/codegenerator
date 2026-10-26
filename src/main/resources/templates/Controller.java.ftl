<#macro mapping api>
    @RequestMapping(value = "${api.path}", method = RequestMethod.${api.method}<#if api.body>, consumes = "application/json"</#if>)
</#macro>
<#macro idarg api>
<#if api.idIn == 'PATH'>@Positive @PathVariable("id") Long id<#elseif api.idIn == 'QUERY'>@Positive @RequestParam("id") Long id</#if>
</#macro>
package ${package}.controller;

<#if !existing>
import ${package}.common.Result;
</#if>
<#if !existing || !externalPage>
import ${package}.common.PageResult;
</#if>
import ${package}.service.${entity}Service;
import ${package}.vo.${entity}VO;
<#if create>
import ${package}.dto.${entity}CreateDTO;
<#if createApi.wrapped>
import ${package}.dto.${entity}CreateRequest;
</#if>
</#if>
<#if update>
import ${package}.dto.${entity}UpdateDTO;
<#if updateApi.wrapped>
import ${package}.dto.${entity}UpdateRequest;
</#if>
</#if>
<#if page>
import ${package}.dto.${entity}QueryDTO;
<#if pageApi.wrapped>
import ${package}.dto.${entity}PageRequest;
</#if>
</#if>
<#if needsIdDTO>
import ${package}.dto.${entity}IdDTO;
</#if>
<#if deleteVersionCheck && deleteApi.idIn == 'BODY'>
import ${package}.dto.${entity}DeleteDTO;
</#if>
import ${validationPackage}.Valid;
import ${validationPackage}.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.BindingResult;
import org.springframework.validation.BindException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** ${description}接口。接口契约由规格配置，认证和数据权限需接入业务工程。 */
@RestController
@RequestMapping(value = "${basePath}", produces = "application/json")
@Validated
public class ${entity}Controller {

    private final ${entity}Service service;

    public ${entity}Controller(${entity}Service service) {
        this.service = service;
    }

<#if page && pageApi.requestIn == 'QUERY'>
    /** 只绑定正式查询字段；禁止客户端覆盖内部 offset、LIKE 值等属性。 */
    @InitBinder("query")
    public void configureQueryBinding(WebDataBinder binder) {
        binder.setAllowedFields("page", "size"<#list queryFields as f>, "${f.name}<#if f.query == 'RANGE'>From", "${f.name}To</#if>"</#list>);
        binder.setAutoGrowCollectionLimit(100);
    }

</#if>
<#if create>
    /** 新增记录，返回数据库生成的 ID。 */
<@mapping api=createApi />
    public ${resultType}<<#if stringNumbers>String<#else>Long</#if>> create(@Valid @RequestBody ${entity}<#if createApi.wrapped>CreateRequest<#else>CreateDTO</#if> request) {
        return ${resultType}.${successMethod}(<#if stringNumbers>String.valueOf(</#if>service.create(request<#if createApi.wrapped>.get${createApi.wrapperCap}()</#if>)<#if stringNumbers>)</#if>);
    }

</#if>
<#if update>
    /** <#if partialUpdate>部分更新：仅写入显式传入的字段；null 只可清空允许为空的列。<#else>完整更新该接口声明的字段；可空字段未传时清空，未选字段保留。</#if> */
<@mapping api=updateApi />
    public ${resultType}<Void> update(<#if updateApi.idIn != 'BODY'><@idarg api=updateApi />, </#if>@Valid @RequestBody ${entity}<#if updateApi.wrapped>UpdateRequest<#else>UpdateDTO</#if> request) {
        service.update(<#if updateApi.idIn == 'BODY'>request.getId()<#else>id</#if>, request<#if updateApi.wrapped>.get${updateApi.wrapperCap}()</#if>);
        return ${resultType}.${successMethod}(null);
    }

</#if>
<#if detail>
    /** 查询正数 ID 对应的记录。 */
<@mapping api=detailApi />
    public ${resultType}<${entity}VO> detail(<#if detailApi.idIn == 'BODY'>@Valid @RequestBody ${entity}IdDTO request<#else><@idarg api=detailApi /></#if>) {
        return ${resultType}.${successMethod}(service.detail(<#if detailApi.idIn == 'BODY'>request.getId()<#else>id</#if>));
    }

</#if>
<#if page>
    /** 分页与查询字段按规格接收；范围起止包含端点。 */
<@mapping api=pageApi />
    public ${resultType}<${pageType}<${entity}VO>> page(<#if pageApi.requestIn == 'QUERY'>@Valid @ModelAttribute("query") ${entity}QueryDTO query, BindingResult binding<#else>@Valid @RequestBody ${entity}<#if pageApi.wrapped>PageRequest<#else>QueryDTO</#if> query</#if>)<#if pageApi.requestIn == 'QUERY'> throws BindException</#if> {
<#if pageApi.requestIn == 'QUERY'>
        if (binding.getSuppressedFields().length > 0) binding.reject("unknownFields", "包含未声明的查询字段");
        if (binding.hasErrors()) throw new BindException(binding);
</#if>
        return ${resultType}.${successMethod}(service.page(query<#if pageApi.wrapped>.get${pageApi.wrapperCap}()</#if>));
    }

</#if>
<#if delete>
    /** <#if softDelete>逻辑<#else>物理</#if>删除；关联限制与保留策略需由业务定义。 */
<@mapping api=deleteApi />
    public ${resultType}<Void> delete(<#if deleteApi.idIn == 'BODY'>@Valid @RequestBody ${entity}<#if deleteVersionCheck>DeleteDTO<#else>IdDTO</#if> request<#else><@idarg api=deleteApi /><#if deleteVersionCheck>, @${validationPackage}.constraints.PositiveOrZero @RequestParam("expectedVersion") Long expectedVersion</#if></#if>) {
        service.delete(<#if deleteApi.idIn == 'BODY'>request.getId()<#else>id</#if><#if deleteVersionCheck>, <#if deleteApi.idIn == 'BODY'>request.getExpectedVersion()<#else>expectedVersion</#if></#if>);
        return ${resultType}.${successMethod}(null);
    }
</#if>
}
