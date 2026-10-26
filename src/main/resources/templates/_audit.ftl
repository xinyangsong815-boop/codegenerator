<#macro schema test=false><#if transactionalAudit>
-- 同事务审计；业务账号仅需 INSERT，审计留存、归档与防篡改另行接入。
<#if test>DROP TABLE IF EXISTS `${table}_audit`;
</#if>CREATE TABLE `${table}_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `event_id` VARCHAR(36) NOT NULL,
    `operation` VARCHAR(16) NOT NULL,
    `record_id` BIGINT NOT NULL,
    `actor` VARCHAR(128) NOT NULL,
    `occurred_at` DATETIME(6) NOT NULL,
    `before_json` MEDIUMTEXT NOT NULL,
    `after_json` MEDIUMTEXT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_id` (`event_id`),
    KEY `idx_record_time` (`record_id`, `occurred_at`)
)<#if !test> ENGINE=InnoDB DEFAULT CHARSET=${dbCharset} COLLATE=${dbCollation} ROW_FORMAT=${dbRowFormat}</#if>;
</#if></#macro>
