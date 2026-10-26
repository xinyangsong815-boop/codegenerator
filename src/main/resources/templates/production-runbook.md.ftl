# ${description}生产接入手册

本次目标 Java ${targetJava} / Spring Boot ${targetBoot}。模板 ${templateVersion}。production-readiness.json 和 acceptance-cases.json 对应当前规格，未执行项保持未验收。

## 构建与启动

1. 执行 Maven verify。缺少依赖时按 README 准备获准仓库；H2 通过只证明示例工程。
2. 审核 sql/schema.sql 与当前数据库差异，编写并评审增量迁移、回填和回滚方案。不要将 schema-test.sql 导入业务库；应用不会自动创建或删除表。
3. 设置 DB_URL、DB_USER、DB_PASSWORD；生产配置无数据库凭证默认值。用只含必要权限的运行账号，密钥由受控环境或密钥系统注入。
4. Linux 可执行 bash deployment/preflight.sh /绝对路径/应用.jar。它检查必要配置和 Java ${targetJava}，不会连接数据库，也不会替代生产验收。
5. 用 --spring.profiles.active=prod 启动。SERVER_ADDRESS 默认本机回环；对外接入需按实际网关、TLS 和访问控制配置。设置 SERVER_PORT、DB_POOL_MAX、DB_POOL_MIN、SHUTDOWN_TIMEOUT 时同时评审网关超时与进程停止时间。

生产配置启用优雅停止，默认等待 30 秒；进程管理器应给足停止时间。迁移、恢复和发布回退由部署流程负责，不能通过重新生成代码替代。

## 请求定位与监控

同步 HTTP 请求返回 X-Request-Id。允许的调用方编号为 1～64 位字母、数字、点、横线或下划线，首位为字母或数字；缺少或非法时生成 UUID。内部错误公开的编号与响应头一致。

日志记录请求方法、HTTP 状态、耗时及关联编号，不记录 URL、查询、正文、Cookie 或 Token。编号不是身份、幂等键或审计证明。异步任务与跨服务链路需要宿主显式传递上下文；本模板没有添加分布式追踪、报警或开放监控端点。

## 真实 MySQL 只读检查

提供 MysqlAcceptanceTest，默认跳过，避免误连真实库。先确认专用测试库与账号，再显式设置 MYSQL_ACCEPTANCE_ENABLED=true、MYSQL_ACCEPTANCE_READ_ONLY_CONFIRMED=true、MYSQL_ACCEPTANCE_URL、MYSQL_ACCEPTANCE_USER、MYSQL_ACCEPTANCE_PASSWORD，执行 Maven test -Dtest=MysqlAcceptanceTest。

该检查验证真实 MySQL 8+ 连接、SELECT 1 和业务表列存在，不启用 Spring、不执行初始化 SQL、不写业务数据。它不验证索引、事务隔离、行锁、迁移兼容、性能和备份恢复；这些项目按 acceptance-cases.json 实测并记录证据。无目标库时保持“未执行”，不能将跳过算作通过。

## 仍需项目实现与验收

- 真实登录、角色、机构与记录权限：按 AccessGuard、WriteContext、SQL 范围和实际事务接入；未配置这些选项的工程仍需补齐。
- 业务状态、审批流、关联约束、数据保留与审计防篡改。
- 新增幂等：可靠持久化的幂等键、请求指纹、并发冲突及结果重放；普通唯一索引和进程内缓存不能替代。
- 真实 MySQL 并发更新／删除、审计失败回滚、数据库最小权限、数据迁移及恢复演练。
- 压力容量、监控报警、TLS、发布回退和目标操作系统。不得将本地样例或合成 Mock PASS 解释为生产可用。


## 真实 MySQL CRUD 回滚测试

仅使用已备份、专用且空的测试 schema，人工评审并执行 sql/schema.sql 建表；不得指向业务库。不要使用 schema-test.sql（含 DROP）。
设置 MYSQL_CRUD_ACCEPTANCE_ENABLED=true、MYSQL_CRUD_EMPTY_SCHEMA_CONFIRMED=true，以及 MYSQL_ACCEPTANCE_URL、MYSQL_ACCEPTANCE_USER、MYSQL_ACCEPTANCE_PASSWORD，再运行 mvn -Dtest=MysqlCrudAcceptanceTest test。
该入口禁止 Spring 初始化 SQL、强制 MySQL8、每个测试先检查业务表为空；复用实际服务/HTTP/MyBatis 测试，在测试事务中回滚。自增序列仍可能前移，测试 Hooks 和身份仍是替身。
它不替代生产账号授权、并发锁等待、迁移和容量验证；默认全部跳过，未运行不能记作通过。

## 证据与迁移

保存 production-evidence-template.json 的副本，填写实际环境、执行命令、UTC时间、产物与结果SHA256及复核人；避免将密码或Token写入证据。核对入口 /api/production/evidence/check 只比对摘要与字段，不执行命令、不信任手工PASS。代码/配置/模板变化后旧证据失效。
按需求再生成候选附迁移评估，只生成有限的可空新增列SQL草稿。其他迁移必须人工编写、回填、评审和恢复演练。
分页默认限制偏移100000；app.pagination.max-offset 可在容量验收后调整。EXISTING宿主须将 PaginationLimitException 映射为400。

## Outbox 接入

<#if hooks>生成的 ${entity}Outbox 写入同事务待投递表（integration/outbox-schema.sql），可在 Hooks.after 中调用。提交前不得发短信或调用外部系统。${entity}OutboxDispatcher 提供显式批次投递、并发租约、失败退避、死信及过期租约恢复，默认不启动调度。宿主提供有超时的发送适配、消费者持久去重、监控与留存；接入见 integration/outbox-delivery-guide.md。旧表必须先迁移补齐租约和确认列再启用新投递器，旧 PENDING 数据可继续处理。至少一次投递允许重复，afterCommit 单回调不能保证崩溃后恢复。<#else>当前未启用 Hooks；外部副作用的同事务Outbox与投递器需项目设计。</#if>
