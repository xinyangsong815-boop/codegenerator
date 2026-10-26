package ${package}.integration;

<#list testTypeImports as value>
import ${value};
</#list>

/** 宿主必须注入的可信服务端字段提供器；从认证上下文、服务端时钟或受控配置取值，不能从客户端请求直接读取机构和操作人。
 * 服务在 CREATE 填充所有 SERVER 字段；UPDATE 只重取 serverUpdate=true 的字段，范围字段不能更新。
 */
public interface ${entity}WriteContext {
<#list serverFields as field>
    /** 从可信服务端上下文提供${field.description}（${field.type}）；<#if field.required>必须非空<#else>允许返回 null</#if><#if field.type == 'String'>，最多 ${field.length} 个 Unicode 码点</#if><#if field.type == 'BigDecimal'>，整数最多 ${field.precision - field.scale} 位、小数最多 ${field.scale} 位</#if>。服务在持久化前再次检查，不可依赖请求字段替代。 */
    ${field.type} get${field.cap}();
</#list>
}
