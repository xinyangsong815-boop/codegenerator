-- Candidate only. Review and migrate into the SAME database/data source as the business table.
-- Schedule the explicit Dispatcher only after delivery adapter and consumer deduplication are ready.
-- Upgrading an existing outbox requires a reviewed migration for the lease columns below.
CREATE TABLE `${outboxTable}` (
    `event_id` VARCHAR(64) NOT NULL,
    `event_type` VARCHAR(80) NOT NULL,
    `payload` TEXT NOT NULL,
    `state` VARCHAR(24) NOT NULL,
    `attempts` INT NOT NULL DEFAULT 0,
    `available_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `lease_token` VARCHAR(36) NULL,
    `leased_until` TIMESTAMP NULL,
    `delivered_at` TIMESTAMP NULL,
    PRIMARY KEY (`event_id`),
    KEY `pending_events` (`state`, `available_at`)
)<#if !(outboxTest!false)> ENGINE=InnoDB DEFAULT CHARSET=${dbCharset} COLLATE=${dbCollation}</#if>;
