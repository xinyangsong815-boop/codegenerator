-- 合成示例，不含真实客户资料。
CREATE TABLE `contract_record` (
 `id` BIGINT NOT NULL AUTO_INCREMENT,
 `contract_no` VARCHAR(40) NOT NULL COMMENT '合同编号',
 `contract_name` VARCHAR(100) NOT NULL COMMENT '合同名称',
 `amount` DECIMAL(18,2) NOT NULL COMMENT '合同金额',
 `signed_date` DATE NULL COMMENT '签订日期',
 PRIMARY KEY (`id`), KEY `idx_contract_no` (`contract_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合同管理';
