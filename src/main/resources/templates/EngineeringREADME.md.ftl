# ${description}：现有工程接入模块

此包采用 EXISTING 模式，不是可独立启动的 Spring Boot 工程。生成成功只表示规格、Java/XML语法校验通过，宿主依赖及运行效果尚未验收。

## 合入顺序

1. 将 src/main/java 的业务模块代码合入宿主工程。使用独立的业务包名，避免同名类型和同路径 Controller 冲突。
2. 将 Mapper XML 放入宿主已配置的 mapper-locations；核对宿主组件扫描和 Mapper 扫描覆盖生成包。
3. 查看 integration-config.json，对照实际公共组件的完整类名、泛型、工厂签名及序列化输出。
4. 按 integration/application.properties.sample 的说明逐项合并必要设置；不要覆盖宿主配置。数据源和事务由宿主维护。
5. integration/reference-schema.sql 仅作结构参考，由宿主既有数据库迁移流程评审；不要直接在已有业务库执行。
6. UPDATE 操作附带 ServiceUpdateTest，可按宿主 JUnit5/Mockito 配置运行。宿主集成测试须另行覆盖权限、事务、数据库及响应协议。
7. 用 integration/verification-checklist.json 记录现场验收。初始状态均为 NOT_VERIFIED。

<#if hasAssembler>
## 字段转换

`${entity}Assembler` 集中处理新增／完整更新／展示的纯字段映射，由生成器生成显式赋值，不新增反射或 MapStruct 依赖。
创建和完整更新采用各自 CLIENT 白名单；ID、版本和可信 SERVER 字段仍在 Service 单独处理，展示仅包含 exposed 字段。
PARTIAL 继续将带 Present 标记的 UpdateDTO 传入 Mapper；未传保持、显式 null 清空的语义不能用普通实体复制替代。
Assembler 不执行校验、授权或持久化。附带 AssemblerTest 可按宿主 JUnit5 配置执行，不能替代真实宿主集成测试。

</#if>

## 公共类型签名

成功返回：`${engineering.resultClass}<T>`，调用 `${engineering.resultClass}.${engineering.successMethod}(T data)`；Void 操作传 null。工厂须为静态泛型方法，返回兼容类型。

<#if externalPage>
分页返回：`${engineering.pageClass}<T>`。
<#if engineering.pageFactoryMethod != ''>
静态泛型工厂：`${engineering.pageClass}.${engineering.pageFactoryMethod}(List<T> records, long total)`。
<#else>
公开构造器：`new ${engineering.pageClass}<T>(List<T> records, long total)`。
</#if>
<#else>
分页使用本模块 common.PageResult，序列化字段固定 records/total。
</#if>

业务记录不存在：
<#if engineering.notFoundMethod != ''>
调用 `${engineering.exceptionClass}.${engineering.notFoundMethod}(String message)`，返回 `${engineering.exceptionClass}`；须为 RuntimeException 的子类。
<#else>
调用 `new ${engineering.exceptionClass}(String message)`；须为 RuntimeException 的子类。
</#if>
宿主全局异常处理器决定 HTTP 状态及错误响应。此包不生成全局 ControllerAdvice，不承诺错误是 404 或统一模板协议。

## 宿主依赖与设置

- 平台基线：Java ${targetJava} / Spring Boot ${targetBoot} / MyBatis Starter ${targetMybatis}。依赖版本由宿主 BOM 管理；不要向已有项目强行引入另一套 Spring。
- Spring MVC、方法参数校验、事务和 MyBatis；Jackson Java Time 支持；MySQL 驱动。${entity}ServiceImpl 的 @Validated 必须经过 Spring 代理调用。
- JSON 未知字段拒绝；`spring.jackson.deserialization.accept-float-as-int=false`；`spring.jackson.deserialization.fail-on-unknown-properties=true`。存在自定义 ObjectMapper 时必须核验实际配置是否生效。
- Mapper 数据权限及事务管理器需核对。提供可配置的单表 CRUD 和机构等值隔离；登录、角色授权、审批和幂等由宿主实现。
- 前端 api.js 接收宿主 HTTP 客户端。响应原样返回，是否已被拦截器解包由宿主约定。

## 文档与验收

OpenAPI 成功响应采用配置的字段名：data=`${engineering.dataProperty}`，code=`${engineering.codeProperty}`，message=`${engineering.messageProperty}`；分页 records=`${engineering.recordsProperty}`，total=`${engineering.totalProperty}`。code/message 可配置为空表示不声明。

这些字段是用户声明的契约，生成器不解析外部类或证明其实际序列化结果。宿主公共类型可包含额外属性。请使用真实成功响应、空分页、无返回数据操作、参数错误、记录不存在及权限拒绝样例核验。

保留 generation-spec.json 与 generation-manifest.json；下次生成采用候选包和冲突报告合并。不要把此模块切换为独立工程后直接覆盖宿主项目。

## 1.6 配置与生产接入

- schemaVersion=2 默认 STRING_NUMBERS 与 STRICT；旧版配置保留原协议。Long、BigDecimal 的 JSON 请求和响应使用字符串，前端不得先转 Number。GET 参数本身是文本；内部 Entity 和 SQL 仍采用 Long/BigDecimal。
- CLIENT 字段按操作白名单进入请求；SERVER 字段来自宿主实现的 WriteContext；DATABASE 字段不进入 INSERT，使用数据库默认值。defaultValue 是 JSON 字面量，不接受 Java/SQL 表达式。
- nullable 描述数据库可空性，required 为旧配置兼容写法；必填字符串仍拒绝空白。已有表的默认值、可空性和索引必须由迁移脚本核验，生成器不会自动修改已有数据库。
<#if hasWriteContext>
- 必须提供 ${entity}WriteContext Spring Bean，从认证会话取得服务端字段；不得从请求体、未经校验的 Header 或客户端查询参数取得机构号。
</#if>
<#if scoped>
- scopeField=${scopeName}：所有详情、更新、删除、分页和总数 SQL 均绑定可信机构值。只实现等值机构隔离；机构树、多维权限由宿主扩展。无可信机构时拒绝继续执行。
</#if>
<#if hooks>
- 必须提供 ${entity}Hooks Spring Bean。before 执行权限检查，写操作 after 在同一事务内运行，可写事务审计；抛异常会回滚。读操作为只读事务，不应在同一只读事务写审计；由宿主提供合适策略。不可回滚的外部调用应使用宿主可靠消息机制。
</#if>
<#if optimisticLock>
- 更新必须传 expectedVersion；冲突返回409（EXISTING 模式由宿主异常处理决定协议），成功更新版本加1。删除默认按ID执行；启用deleteVersionCheck后必须提供expectedVersion。客户端应刷新后由用户决定是否重试。
</#if>
<#if softDelete>
- 删除设置 deleted=true，并从查询中隐藏。唯一索引仍约束已删除记录；编号复用、保留期限和物理清理策略需要业务明确。
<#else>
- 删除为物理删除，不自动级联。
</#if>
<#if strictJson>
- JSON 作用域=${jsonScope}。MODULE 注册 ${entity}StrictJsonConfig，仅对本模块的生成 DTO 拒绝未知字段、重复键、尾随 token 和错误类型；不改变宿主 Mapper 全局开关。HOST 不生成此配置，由宿主实施并验收。数字字符串字段注解仍遵循 wireFormat。
</#if>
- 唯一索引只约束配置的字段组合；请求幂等重放、审批、多表事务和远程调用未自动实现。
- test 目录中的机构/权限 Bean 仅用于测试，不是生产认证实现。
- 宿主签名检查：java -jar 生成器.jar --verify-host=模块目录 --host-classpath=真实依赖与公共类目录。使用平台对应完整 JDK，classpath 分隔符为 Windows 分号或 Linux 冒号。检查报告在模块/host-compile-check/report.json；不执行宿主类静态初始化，也不证明 JSON、SQL、事务和权限运行时行为。
<#if externalPage && stringNumbers>
- 外部分页 total 的字符串协议必须由宿主序列化配置实现；模块无法给外部类添加注解，必须用实际响应验收。
</#if>

## 1.7 正确性与接入约定

- database profile：MySQL 8.0/8.4、${dbCharset}/${dbCollation}、${dbRowFormat}。索引按最大编码字节预算检查。实际数据库版本、页大小和行格式须匹配规格。
- LocalDateTime 可配置 temporalPrecision（0至6，默认3）；数据库列、默认值、请求和查询校验保持一致。超精度请求拒绝，绝不隐式舍入。
- SERVER 字段 serverUpdate=true 时，在 UPDATE 从可信 WriteContext 重新填充；scopeField 禁止改写。创建时仍填充所有 SERVER 字段。
- hooks=true 可覆盖 before/after(${entity}OperationContext)，读取 request、id、actor、scope；currentActor 必须由宿主认证上下文提供。旧 String/Long 方法保留兼容。上下文不自动提供修改前后快照，不替代角色权限或审批状态检查。
- 不支持的约束检查会返回 INCOMPLETE。源码扫描只有在清单全部哈希和重建契约一致时复用生成模型；源码编辑或新增后执行 AST 扫描并保留覆盖缺口。
- 内部异常默认仅记录关联编号和类型。经过内部日志权限与敏感信息审查后，可以设置 app.log-exception-details=true 记录内部堆栈；对外仍只返回通用错误与编号。
- CREATE TABLE 是结构参考，不是存量迁移脚本。上线需编写并评审 ALTER/回填、唯一键重复数据预检、迁移窗口和恢复方案。
- 删除版本校验可由 deleteVersionCheck 启用并在宿主错误协议中对齐版本冲突；审批、幂等重放、游标分页及真实宿主权限仍须按业务扩展并验收。

## 1.7.1 数据边界

MODULE 严格解析使用独立树读取器保留原生 BigDecimal 的数字和小数位，不修改宿主全局数值转换策略。浏览器需要无损金额/Long时仍选择 STRING_NUMBERS；服务端精确解析不能恢复浏览器此前丢失的数字。

LocalDate 的请求、查询（包括IN集合）、默认值和SERVER值限定为1000-01-01至9999-12-31。OpenAPI以x-date-minimum/x-date-maximum声明；LocalDateTime沿用该日期部分范围与小数秒规则。合入旧数据前检查是否有超范围日期。

生成前按所声明字符集和字段长度核对MySQL65535字节表行预算；这不证明InnoDB页内行限制、排序规则或真实建表已验收，必须在目标数据库执行候选SQL。


## 2.0.2 生产接入与检查

- production-readiness.html／json检查当前规格的结构和缺口，INCOMPLETE表示生产接入／现场验收尚未完成；不等于生成失败。配置结构不能证明认证、审计或幂等已实现。
- accessGuard=true生成必需的${entity}AccessGuard接口，没有生产Bean时不能启动。实现必须读取真实登录上下文，检查操作权限与对象政策后返回非空操作人；不能信任请求中的操作人或机构。测试目录的允许访问Bean仅用于测试。EXISTING宿主需对齐401／403异常协议。
- scopeField仍用于统一SQL隔离，必须是可信SERVER字段，范围为空拒绝执行；权限适配器不能代替详情、列表总数、更新和删除的范围条件。
- deleteVersionCheck=true要求optimisticLock=true；BODY删除使用独立DeleteDTO，PATH／QUERY ID的版本固定为query expectedVersion。过期版本返回409，成功后是否允许重复删除由业务定义。
- Hooks负责业务状态检查和同事务审计；操作上下文中的actor由AccessGuard返回并在本次操作保持一致。没有AccessGuard时保留旧currentActor行为，需要宿主保证可信。before/after不会自动保存前后快照、提供审批流或请求幂等。
- request-examples.json提供业务字段样例，requests.http新增／查询含完整字段；修改／删除的ID和版本必须替换为本次真实读取结果。仅在专用测试环境执行。
- 独立工程参数校验错误增加errors数组（field/code/message），不包含原始输入、拒绝值或异常消息。宿主模式复用宿主错误协议。
- 独立工程新增application-prod.properties：DB_URL／DB_USER／DB_PASSWORD没有开发默认值，连接池和端口通过环境设置。该文件不会启用监控端点；复用获准的宿主监控并保护管理入口。
- 上线仍需业务确认金额范围、唯一范围、状态、留痕与保留策略，实现幂等结果重放；在真实MySQL、目标JDK／操作系统和实际宿主执行迁移、权限矩阵、并发、压力和恢复验收。CREATE TABLE不是存量迁移脚本。

## 2.1 接口、安全与验收

更新请求体可选 id 必须与路径／查询／外层 id 一致；服务层也检查。RequestMismatchException 在独立工程返回400 + errors.id，宿主需对齐异常协议。PARTIAL空修改使用$，显式null绑定业务字段，倒置范围分别绑定From／To。

recordAccessCheck=true时，AccessGuard必须实现checkRecord；更新／删除用SELECT FOR UPDATE锁定当前记录，再判断对象权限，写入仍使用expectedVersion原子条件。分页checkPageScope必须保证整个SQL范围和总数可读，否则拒绝或补齐宿主权限SQL。可编辑敏感／权限字段与业务状态仍需Hooks校验。

transactionalAudit=true要求accessGuard，更新和删除同时要求版本保护。独立工程提供同事务JDBC审计、表结构与前后快照。auditFields默认空，仅记录ID、版本、删除状态及元数据；业务字段需明确评审白名单，不写凭据和完整请求。EXISTING需提供AuditWriter，共享数据源和事务管理器；不允许REQUIRES_NEW、吞异常和不可回滚远程副作用。读取／拒绝访问审计、留存／归档和防篡改由宿主实现。

验收见acceptance-checklist.md和acceptance-cases.json。现场默认NOT_RUN，生产检查INCOMPLETE，测试目录的允许访问实现仅用于基础回归。


## 重建版的接口与测试说明

本工程由基于 2.1.0 恢复源码的 模板 ${templateVersion} 生成，不代表原 2.2.1 的完整功能。

- 部分更新的 OpenAPI 使用 anyOf 要求至少提供一个业务字段，单传 id／expectedVersion 不是有效更新；运行时仍执行跨字段校验。
- 开启 recordAccessCheck 后，UPDATE 对写入前及写入后实际记录各检查一次。适配器必须可重复调用且不自行提交事务；拒绝时业务写入、版本与同事务审计均回滚。
- frontend/api.js 在发出请求前检查 ID 一致性、Long 范围、安全整数与必需版本。金额要无损传递应使用 STRING_NUMBERS。
- 真实宿主、权限平台、MySQL 与上线验收仍需独立执行。任何结构检查或演示测试通过都不代表可直接上线。

## 可阅读的接口说明

interface-reference.html 与 interface-reference.md 按同一份 openapi.json 生成，包含参数、请求、响应字段、响应头、示例与覆盖缺口。HTML 可离线打开，Markdown 可进入项目文档。数字默认值保留数字类型。模块接口声明 application/json；406 的处理和其他错误协议须由宿主的异常处理器对齐并验收。

<#if hooks>
## Outbox 投递接入

${entity}Outbox 使用与业务相同的数据源，在当前事务中保存事件；${entity}OutboxDispatcher 提供显式批次投递、数据库租约、失败退避、死信和进程重启后的过期租约恢复。默认不会注册后台 Bean 或发送消息。先评审并执行 integration/outbox-schema.sql（已有表补充租约和投递确认列），再按 integration/outbox-delivery-guide.md 提供真实发送适配和调度；接收方必须按 eventId 持久去重。宿主负责真实数据库、事务管理器、外部超时、积压告警及故障验收。
</#if>
