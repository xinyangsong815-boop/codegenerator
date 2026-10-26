package ${package}.integration;
import ${package}.entity.${entity};
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
/** 授权使用完整不可变标量快照；审计只写白名单字段，不保存请求体和凭据。 */
public final class ${entity}Snapshots {
    private ${entity}Snapshots() { }
    /** 从当前数据库实体创建只读标量快照供对象权限检查；null 返回空映射，包含全部持久化字段，不可直接返回前端或记录完整日志。 */
    public static Map<String, Object> record(${entity} value) {
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        if (value == null) { return Collections.emptyMap(); }
        record.put("id", value.getId());
<#if optimisticLock>record.put("version", value.getVersion());
</#if><#if softDelete>record.put("deleted", value.getDeleted());
</#if><#list fields as f>record.put("${f.name}", value.get${f.cap}());
</#list>
        return Collections.unmodifiableMap(record);
    }
<#if transactionalAudit>
    /** 创建审计白名单快照；仅保留主键、版本/删除元数据及 auditFields，Long/金额/日期按稳定字符串保存，null 实体返回空映射。 */
    public static Map<String, Object> audit(${entity} value) {
        if (value == null) { return Collections.emptyMap(); }
        Map<String, Object> audit = new LinkedHashMap<String, Object>();
        audit.put("id", String.valueOf(value.getId()));
<#if optimisticLock>audit.put("version", String.valueOf(value.getVersion()));
</#if><#if softDelete>audit.put("deleted", value.getDeleted());
</#if><#list auditFields as f>
        audit.put("${f.name}", value.get${f.cap}() == null ? null : <#if f.type == 'BigDecimal'>value.get${f.cap}().toPlainString()<#elseif f.type == 'Long' || f.type == 'LocalDate' || f.type == 'LocalDateTime'>value.get${f.cap}().toString()<#else>value.get${f.cap}()</#if>);
</#list>
        return Collections.unmodifiableMap(audit);
    }
</#if>
}
