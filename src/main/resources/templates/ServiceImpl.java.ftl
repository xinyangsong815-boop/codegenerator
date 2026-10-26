package ${package}.service.impl;

<#if hasAssembler>
import ${package}.assembler.${entity}Assembler;
</#if>
<#if update || delete || detail>
<#if !existing>
import ${package}.common.BusinessException;
</#if>
</#if>
<#if page>
<#if !existing || !externalPage>
import ${package}.common.PageResult;
</#if>
import ${package}.dto.${entity}QueryDTO;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
</#if>
<#if create>
import ${package}.dto.${entity}CreateDTO;
</#if>
<#if update>
import ${package}.dto.${entity}UpdateDTO;
</#if>
<#if create || update || detail || page || rowLock>
import ${package}.entity.${entity};
</#if>
import ${package}.mapper.${entity}Mapper;
import ${package}.service.${entity}Service;
<#if detail || page>
import ${package}.vo.${entity}VO;
</#if>
<#if hasWriteContext>
import ${package}.integration.${entity}WriteContext;
</#if>
<#if hooks>
import ${package}.integration.${entity}Hooks;
</#if>
<#list testTypeImports as value>
import ${value};
</#list>
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/** ${description}应用服务；负责事务、可信上下文字段和已配置的授权、审计、版本保护<#if hasAssembler>，纯字段映射交给 Assembler</#if>。业务状态及审批规则由宿主补充。 */
@Service
@Validated
public class ${entity}ServiceImpl implements ${entity}Service {
<#if page>
    @org.springframework.beans.factory.annotation.Value("${r'${app.pagination.max-offset:100000}'}")
    private int maxPaginationOffset = 100000;
</#if>

    private final ${entity}Mapper mapper;
<#if transactionalAudit>private final ${package}.integration.${entity}AuditWriter auditWriter;
</#if>
<#if accessGuard>
    private final ${package}.integration.${entity}AccessGuard accessGuard;
</#if>
<#if hasWriteContext>
    private final ${entity}WriteContext context;
</#if>
<#if hooks>
    private final ${entity}Hooks hooks;
</#if>

    public ${entity}ServiceImpl(${entity}Mapper mapper<#if transactionalAudit>, ${package}.integration.${entity}AuditWriter auditWriter</#if><#if accessGuard>, ${package}.integration.${entity}AccessGuard accessGuard</#if><#if hasWriteContext>, ${entity}WriteContext context</#if><#if hooks>, ${entity}Hooks hooks</#if>) {
        this.mapper = mapper;
<#if transactionalAudit>this.auditWriter = auditWriter;
</#if>
<#if accessGuard>
        this.accessGuard = accessGuard;
</#if>
<#if hasWriteContext>
        this.context = context;
</#if>
<#if hooks>
        this.hooks = hooks;
</#if>
    }

<#if create>
    /** 新增 ${description}。通过注入的服务代理传入已校验的请求，返回数据库主键；写入、授权、钩子或审计失败时回滚当前事务。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(${entity}CreateDTO request) {
        ${entity} entity = ${entity}Assembler.fromCreate(request);
<#list serverFields as field>
        entity.set${field.cap}(context.get${field.cap}());
<#if field.required>
        if (entity.get${field.cap}() == null<#if field.type == 'String'> || entity.get${field.cap}().trim().isEmpty()</#if>) throw new IllegalStateException("服务端字段 ${field.name} 未提供有效值");
</#if>
<#if field.type == 'String'>
        if (entity.get${field.cap}() != null && entity.get${field.cap}().codePointCount(0,entity.get${field.cap}().length()) > ${field.length}) throw new IllegalStateException("服务端字段 ${field.name} 超过长度");
<#elseif field.type == 'LocalDate'>
        if(!${package}.common.DatabaseDateRangeValidator.valid(entity.get${field.cap}()))throw new IllegalStateException("服务端字段 ${field.name} 超出日期范围");
<#elseif field.type == 'LocalDateTime'>
        if (!${package}.common.TemporalPrecisionValidator.valid(entity.get${field.cap}(),${field.temporalPrecision})) throw new IllegalStateException("服务端字段 ${field.name} 超过日期时间范围或精度");
<#elseif field.type == 'BigDecimal'>
        if (entity.get${field.cap}() != null && (Math.max(0, entity.get${field.cap}().scale()) > ${field.scale} || Math.max(0, entity.get${field.cap}().precision() - entity.get${field.cap}().scale()) > ${field.precision - field.scale})) throw new IllegalStateException("服务端字段 ${field.name} 超过金额精度");
</#if>
</#list>
<#if accessGuard>
        String actor = accessGuard.authorize("CREATE", null, <#if scoped>entity.get${scopeCap}()<#else>null</#if>);
        ${package}.common.${entity}AccessIdentity.require(actor);
<#elseif hooks>
        String actor = hooks.currentActor();
</#if>
<#if hooks>
        hooks.before(new ${package}.integration.${entity}OperationContext("CREATE", null, request, actor, <#if scoped>entity.get${scopeCap}()<#else>null</#if>));
</#if>
        if (mapper.insert(entity) != 1 || entity.getId() == null) {
            throw new IllegalStateException("新增记录未返回预期主键");
        }
<#if hooks>
        hooks.after(new ${package}.integration.${entity}OperationContext("CREATE", entity.getId(), request, actor, <#if scoped>entity.get${scopeCap}()<#else>null</#if>));
</#if>
<#if recordAccessCheck || transactionalAudit>
        ${entity} persisted = mapper.findByIdForAudit(entity.getId()<#if scoped>, entity.get${scopeCap}()</#if>);
        if (persisted == null) { throw new IllegalStateException("新增后缺少当前记录快照"); }
<#if recordAccessCheck>
        accessGuard.checkRecord("CREATE", entity.getId(), <#if scoped>entity.get${scopeCap}()<#else>null</#if>, ${package}.integration.${entity}Snapshots.record(persisted));
</#if><#if transactionalAudit>
        auditWriter.write(new ${package}.integration.${entity}AuditEvent("CREATE", entity.getId(), actor, java.util.Collections.emptyMap(), ${package}.integration.${entity}Snapshots.audit(persisted)));
</#if></#if>
        return entity.getId();
    }

</#if>
<#if update>
    /** 按正数 id <#if partialUpdate>仅更新显式传入<#else>完整替换接口声明</#if>的字段；请求 ID 不一致、记录不存在<#if optimisticLock>或 expectedVersion 过期</#if>时抛异常，当前写事务回滚。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ${entity}UpdateDTO request) {
        // ID 一致性检查位于服务层，宿主直接调用服务也无法绕过。
        if (request.getId() != null && !java.util.Objects.equals(id, request.getId())) { throw new ${package}.common.RequestMismatchException(); }
<#if scoped>
        ${scopeType} scope = context.get${scopeCap}();
        if (scope == null<#if scopeType == 'String'> || scope.trim().isEmpty()</#if>) throw new IllegalStateException("缺少可信数据范围");
</#if>
<#if optimisticLock>
        if (request.getExpectedVersion() == null || request.getExpectedVersion() < 0) throw new IllegalArgumentException("必须提供非负 expectedVersion");
</#if>
<#if accessGuard>
        String actor = accessGuard.authorize("UPDATE", id, <#if scoped>scope<#else>null</#if>);
        ${package}.common.${entity}AccessIdentity.require(actor);
<#elseif hooks>
        String actor = hooks.currentActor();
</#if>
<#if rowLock>
        ${entity} current = mapper.findByIdForUpdate(id<#if scoped>, scope</#if>);
        if (current == null) { throw ${notFoundPrefix}"记录不存在或已被删除"); }
<#if recordAccessCheck>
        accessGuard.checkRecord("UPDATE", id, <#if scoped>scope<#else>null</#if>, ${package}.integration.${entity}Snapshots.record(current));
</#if></#if>
<#if hooks>
        hooks.before(new ${package}.integration.${entity}OperationContext("UPDATE", id, request, actor, <#if scoped>scope<#else>null</#if>));
</#if>
<#if !partialUpdate>
        ${entity} entity = ${entity}Assembler.fromFullUpdate(request);
        entity.setId(id);
<#if optimisticLock>
        entity.setVersion(request.getExpectedVersion());
</#if>
</#if>
<#if hasServerUpdate>
        ${entity} server = new ${entity}();
<#list serverUpdateFields as field>
        server.set${field.cap}(context.get${field.cap}());
<#if field.required>
        if(server.get${field.cap}()==null<#if field.type == 'String'> || server.get${field.cap}().trim().isEmpty()</#if>)throw new IllegalStateException("更新服务端字段 ${field.name} 未提供有效值");
</#if>
<#if field.type == 'String'>
        if(server.get${field.cap}()!=null&&server.get${field.cap}().codePointCount(0,server.get${field.cap}().length())>${field.length})throw new IllegalStateException("更新服务端字段 ${field.name} 超过长度");
<#elseif field.type == 'BigDecimal'>
        if(server.get${field.cap}()!=null&&(Math.max(0,server.get${field.cap}().scale())>${field.scale}||Math.max(0,server.get${field.cap}().precision()-server.get${field.cap}().scale())>${field.precision-field.scale}))throw new IllegalStateException("更新服务端字段 ${field.name} 超过金额精度");
<#elseif field.type == 'LocalDate'>
        if(!${package}.common.DatabaseDateRangeValidator.valid(server.get${field.cap}()))throw new IllegalStateException("更新服务端字段 ${field.name} 超出日期范围");
<#elseif field.type == 'LocalDateTime'>
        if(!${package}.common.TemporalPrecisionValidator.valid(server.get${field.cap}(),${field.temporalPrecision}))throw new IllegalStateException("更新服务端字段 ${field.name} 超过时间精度");
</#if>
</#list>
</#if>
        int affectedRows = mapper.update(id, <#if partialUpdate>request<#else>entity</#if><#if scoped>, scope</#if><#if hasServerUpdate>, server</#if>);
        if (affectedRows == 0) {
            if (mapper.existsById(id<#if scoped>, scope</#if>) == 0) throw ${notFoundPrefix}"记录不存在或已被删除");
<#if optimisticLock>
            throw new org.springframework.dao.OptimisticLockingFailureException("记录版本已变化");
</#if>
        }
<#if hooks>
        hooks.after(new ${package}.integration.${entity}OperationContext("UPDATE", id, request, actor, <#if scoped>scope<#else>null</#if>));
</#if>
<#if recordAccessCheck || transactionalAudit>
        ${entity} persisted = mapper.findByIdForAudit(id<#if scoped>, scope</#if>);
        if (persisted == null) { throw new IllegalStateException("更新后缺少当前记录快照"); }
<#if recordAccessCheck>
        // 对实际写入结果再次授权；拒绝时由当前事务回滚业务数据、版本与审计。
        accessGuard.checkRecord("UPDATE", id, <#if scoped>scope<#else>null</#if>, ${package}.integration.${entity}Snapshots.record(persisted));
</#if>
<#if transactionalAudit>
        auditWriter.write(new ${package}.integration.${entity}AuditEvent("UPDATE", id, actor, ${package}.integration.${entity}Snapshots.audit(current), ${package}.integration.${entity}Snapshots.audit(persisted)));
</#if></#if>
    }

</#if>
<#if detail>
    /** 在可信数据范围内读取 id 对应的对外视图；只读事务内校验权限，记录不可见或不存在时抛业务异常。 */
    @Override
    @Transactional(readOnly = true)
    public ${entity}VO detail(Long id) {
<#if scoped>
        ${scopeType} scope = context.get${scopeCap}();
        if (scope == null<#if scopeType == 'String'> || scope.trim().isEmpty()</#if>) throw new IllegalStateException("缺少可信数据范围");
</#if>
<#if accessGuard>
        String actor = accessGuard.authorize("DETAIL", id, <#if scoped>scope<#else>null</#if>);
        ${package}.common.${entity}AccessIdentity.require(actor);
<#elseif hooks>
        String actor = hooks.currentActor();
</#if>
        ${entity} entity = mapper.findById(id<#if scoped>, scope</#if>);
        if (entity == null) {
            throw ${notFoundPrefix}"记录不存在");
        }
<#if recordAccessCheck>
        accessGuard.checkRecord("DETAIL", id, <#if scoped>scope<#else>null</#if>, ${package}.integration.${entity}Snapshots.record(entity));
</#if>
<#if hooks>
        hooks.before(new ${package}.integration.${entity}OperationContext("DETAIL", id, null, actor, <#if scoped>scope<#else>null</#if>));
</#if>
<#if hooks>
        hooks.after(new ${package}.integration.${entity}OperationContext("DETAIL", id, null, actor, <#if scoped>scope<#else>null</#if>));
</#if>
        return ${entity}Assembler.toView(entity);
    }

</#if>
<#if page>
    /** 用已校验的页码、每页数量和查询条件查询记录及总数；按 ID 倒序，偏移超过配置上限时拒绝请求，空页仍保留总数。 */
    @Override
    @Transactional(readOnly = true)
    public ${pageType}<${entity}VO> page(${entity}QueryDTO query) {
        if (maxPaginationOffset < 0) throw new IllegalStateException("app.pagination.max-offset must be nonnegative");
        if (query.getOffset() > maxPaginationOffset) throw new ${package}.common.PaginationLimitException("分页偏移超过容量限制，请缩小范围");
<#if scoped>
        ${scopeType} scope = context.get${scopeCap}();
        if (scope == null<#if scopeType == 'String'> || scope.trim().isEmpty()</#if>) throw new IllegalStateException("缺少可信数据范围");
</#if>
<#if accessGuard>
        String actor = accessGuard.authorize("PAGE", null, <#if scoped>scope<#else>null</#if>);
        ${package}.common.${entity}AccessIdentity.require(actor);
<#elseif hooks>
        String actor = hooks.currentActor();
</#if>
<#if hooks>
        hooks.before(new ${package}.integration.${entity}OperationContext("PAGE", null, query, actor, <#if scoped>scope<#else>null</#if>));
</#if>
<#if recordAccessCheck>accessGuard.checkPageScope(<#if scoped>scope<#else>null</#if>);
</#if>
        long total = mapper.count(query<#if scoped>, scope</#if>);
        if (total == 0) {
<#if hooks>
            hooks.after(new ${package}.integration.${entity}OperationContext("PAGE", null, query, actor, <#if scoped>scope<#else>null</#if>));
</#if>
            return ${pageFactoryPrefix}Collections.emptyList(), 0);
        }
        List<${entity}VO> records = mapper.page(query<#if scoped>, scope</#if>).stream()
                .map(${entity}Assembler::toView)
                .collect(Collectors.toList());
<#if hooks>
        hooks.after(new ${package}.integration.${entity}OperationContext("PAGE", null, query, actor, <#if scoped>scope<#else>null</#if>));
</#if>
        return ${pageFactoryPrefix}records, total);
    }

</#if>
<#if delete>
    /** 按 id 执行<#if softDelete>逻辑<#else>物理</#if>删除<#if deleteVersionCheck>，expectedVersion 必须与当前版本一致</#if>；失败向外抛异常，同事务业务数据、审计和 Outbox 写入一起回滚。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id<#if deleteVersionCheck>, Long expectedVersion</#if>) {
<#if deleteVersionCheck>
        if(expectedVersion==null||expectedVersion<0)throw new IllegalArgumentException("必须提供非负 expectedVersion");
</#if>
<#if scoped>
        ${scopeType} scope = context.get${scopeCap}();
        if (scope == null<#if scopeType == 'String'> || scope.trim().isEmpty()</#if>) throw new IllegalStateException("缺少可信数据范围");
</#if>
<#if accessGuard>
        String actor = accessGuard.authorize("DELETE", id, <#if scoped>scope<#else>null</#if>);
        ${package}.common.${entity}AccessIdentity.require(actor);
<#elseif hooks>
        String actor = hooks.currentActor();
</#if>
<#if rowLock>
        ${entity} current = mapper.findByIdForUpdate(id<#if scoped>, scope</#if>);
        if (current == null) { throw ${notFoundPrefix}"记录不存在或已被删除"); }
<#if recordAccessCheck>
        accessGuard.checkRecord("DELETE", id, <#if scoped>scope<#else>null</#if>, ${package}.integration.${entity}Snapshots.record(current));
</#if></#if>
<#if hooks>
        hooks.before(new ${package}.integration.${entity}OperationContext("DELETE", id, null, actor, <#if scoped>scope<#else>null</#if>));
</#if>
        if (mapper.delete(id<#if deleteVersionCheck>, expectedVersion</#if><#if scoped>, scope</#if>) == 0) {
<#if deleteVersionCheck>
            if(mapper.existsById(id<#if scoped>, scope</#if>)>0)throw new org.springframework.dao.OptimisticLockingFailureException("记录版本已变化");
</#if>
            throw ${notFoundPrefix}"记录不存在或已被删除");
        }
<#if hooks>
        hooks.after(new ${package}.integration.${entity}OperationContext("DELETE", id, null, actor, <#if scoped>scope<#else>null</#if>));
</#if>
<#if transactionalAudit>
        ${entity} persisted = mapper.findByIdForAudit(id<#if scoped>, scope</#if>);
        auditWriter.write(new ${package}.integration.${entity}AuditEvent("DELETE", id, actor, ${package}.integration.${entity}Snapshots.audit(current), ${package}.integration.${entity}Snapshots.audit(persisted)));
</#if>
    }

</#if>
}
