<#import "_audit.ftl" as audit>
-- 在选定的开发数据库执行；不自动删除已有表，不推断业务唯一键或关联关系。
CREATE TABLE `${table}` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
<#list fields as field>
    `${field.column}` ${field.sqlType}<#if field.required> NOT NULL</#if><#if field.defaultSql?has_content> DEFAULT ${field.defaultSql}</#if>,
</#list>
<#if optimisticLock>
    `version` BIGINT NOT NULL DEFAULT 0,
</#if>
<#if softDelete>
    `deleted` BOOLEAN NOT NULL DEFAULT FALSE,
</#if>
    PRIMARY KEY (`id`)<#if indexes?has_content>,</#if>
<#list indexes as index>
    <#if index.unique>UNIQUE </#if>KEY `${index.name}` (<#list index.columns as column>`${column}`<#sep>, </#sep></#list>)<#sep>,</#sep>
</#list>
) ENGINE=InnoDB DEFAULT CHARSET=${dbCharset} COLLATE=${dbCollation} ROW_FORMAT=${dbRowFormat};

<@audit.schema test=false />

<#if hooks>
<#include "outbox-schema.sql.ftl">
</#if>
