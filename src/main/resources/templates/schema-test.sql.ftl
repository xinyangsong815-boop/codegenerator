<#import "_audit.ftl" as audit>
-- 仅用于 test profile 的内存 H2，不能在真实业务数据库执行。
DROP TABLE IF EXISTS `${table}`;
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
);

<@audit.schema test=true />

<#if hooks>
-- Reset only the isolated in-memory test schema when another Spring test context starts.
DROP TABLE IF EXISTS `${outboxTable}`;
<#assign outboxTest=true>
<#include "outbox-schema.sql.ftl">
</#if>
