# ${description}生成工程

目标环境：Java ${targetJava}、Spring Boot ${targetBoot}、MyBatis ${targetMybatis}、MySQL8。
模板版本：${templateVersion}。本工程是基础 CRUD 候选，不包含银行业务规则、认证或数据权限。

## 编译与测试

本 ZIP 提供源码、SQL、测试、build-offline.sh／build-offline.cmd；不包含 Maven、JDK 或完整依赖仓库。
需先准备相应 Java、Maven 和获准的内网仓库。普通 Maven 构建可使用项目已配置的内网镜像：

```text
mvn verify
```

纯离线构建：将 OFFLINE_MAVEN_REPO 设置为已准备齐全的本地仓库绝对路径后，运行相应脚本。
脚本使用 mvn -o verify，会执行测试；依赖缺失会失败，不会偷偷联网下载或把失败当作成功。
测试使用隔离内存 H2；不能据此证明真实 MySQL、生产数据和并发语义已经验收。

生产接入、请求定位、优雅停止、只读 MySQL 检查及保留缺口见 production-runbook.md。MysqlAcceptanceTest 默认跳过；未执行不能记作通过。

## 独立运行

1. 选择开发库并执行 sql/schema.sql。本工程不自动建库、不自动删表。
2. 通过环境设置 DB_URL、DB_USER、DB_PASSWORD，用户名和密码没有硬编码默认值。
3. Windows PowerShell 中 JVM -D 参数必须整段加引号：

```powershell
java "-Dfile.encoding=UTF-8" -jar "target/${artifact}-1.0.0.jar"
```

默认业务后端地址为 http://127.0.0.1:8080；与开发助手的8790端口不同。

## 接口语义

- 路径前缀为 ${basePath}。仅生成所选操作的 Controller／Service／Mapper 方法和相关 DTO。
- ID 必须为正数；不存在为404，参数不合法为400，完整性冲突为409。
- 未知 JSON 字段和内部计算字段会拒绝；参数拼写错误不能悄悄被忽略。
- 更新语义为 <#if partialUpdate>PARTIAL：未传字段保持，显式 null 仅清空可空字段，空更新拒绝。<#else>FULL：接口声明的可空字段未传时清空，未选字段保持。</#if>
- HTTP 方法、路径、参数位置以 interface-config.json 和 openapi.json 为准；可使用 frontend/api.js 与 requests.http 联调。
- bodyProperty 可封装一层嵌套 DTO；需要在外层正确传入对应属性。
- IN 接收列表，null 表示无条件，空列表拒绝，最多100项；GET 使用重复同名查询参数。
- 自定义多表写入、审批操作、任意 Header/文件上传等不在本版单表 CRUD 范围。
- CONTAINS 按字面值包含查询，%、_、! 会转义，不作为用户通配符；EQ 不变。
- 范围包含端点，单侧范围允许，起始大于结束拒绝；页码1～100000，每页1～100。
- DELETE 由 softDelete 配置决定；不自动级联。清理前必须检查依赖关系及保留策略。
- Service 约束需要 Spring 管理的代理生效；直接 new 或绕过代理不受方法校验保护。
- 读方法使用只读事务，但分页快照一致性仍取决于数据库及事务隔离配置。
- LocalDateTime 按字段 temporalPrecision 生成 DATETIME(0～6)，默认3；小数秒需在真实 MySQL 验证。

## 合入行内工程

<#if hasAssembler>
### 对象转换

`${entity}Assembler` 集中执行纯字段映射，生成器自动维护显式 get/set；不新增反射转换或注解处理依赖。
新增只复制该接口的 CLIENT 字段，完整更新只复制选中的可编辑字段，展示只复制 exposed 字段及主键／已启用版本。
Service 单独处理可信 SERVER 上下文、ID 一致性、版本、事务、授权、审计和钩子。
Assembler 不代替 Bean Validation；调用它之前须经受管服务的校验。完整更新的可空 null 会被保留，不使用“忽略 null”批量复制。
PARTIAL 直接保留 UpdateDTO 的 Present 标记进入 Mapper，区分未传与显式 null；不得改成普通实体复制。
AssemblerTest 验证字段映射及隐藏字段隔离；目标环境的服务代理、SQL、权限和事务仍由集成测试验收。

</#if>

复用已有 Application、统一返回类、全局异常处理、事务策略和配置；不要重复创建公共组件。
接入认证、机构／数据权限、审计、唯一索引、软删除策略、并发版本控制及幂等措施。
本工程不推断合同编号唯一、金额必须正数、审批流等未提供的业务需求。
统一错误处理隐藏数据库与内部异常细节，内部日志仅记录关联编号和异常类型。

格式规则：UTF-8、LF、四空格缩进、显式 import、一个语句一行；.editorconfig 可用于 IDE。
注释说明输入／更新／删除语义，不为简单 getter／setter 添加重复注释。
保留 generation-spec.json 和 generation-manifest.json，以便候选再生成及人工修改冲突检查。
模板升级会产生候选差异，不能直接覆盖已经修改的工程。

## 本工程接口清单

|操作|方法|完整路径|业务字段位置|ID位置|JSON封装|
|---|---|---|---|---|---|
<#list apis as api>
|${api.operation}|${api.method}|${api.url}|${api.requestIn}|${api.idIn}|${api.bodyProperty}|
</#list>

openapi.json 从实际配置生成；源码扫描作为独立辅助验证，不保证识别所有部分更新跨字段约束。
requests.http 中空对象是待填写的请求草稿，不保证通过必填校验。

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
- 删除版本校验：<#if deleteVersionCheck>已生成，仍需真实并发验收。<#else>未启用；可配置 deleteVersionCheck 并同时开启 optimisticLock。</#if>审批、幂等重放、外部消息投递、游标分页及真实宿主权限仍需接入验收。

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

interface-reference.html 与 interface-reference.md 按同一份 openapi.json 生成，包含参数、请求、响应字段、响应头、示例与覆盖缺口。HTML 可离线打开，Markdown 可进入项目文档。数字默认值保留数字类型。接口只生产 application/json；不接受此响应格式的请求会在业务执行前返回无正文的 406。

## 本轮生产补强

- production-evidence-template.json 绑定规格、工具、模板和生成文件摘要；通过 /api/production/evidence/check 核对。PASS 申报只作为待复核证据，不能据此自动宣布生产可用。
- 按需求再生成候选包含 migration-plan.json / .md；只有可空、无默认值的新增列提供 SQL 草稿，删除、改类型、加必填和索引变更均要求人工迁移。计划不读取也不修改真实数据库。
- 分页增加 app.pagination.max-offset（默认100000），超限在 COUNT 前返回400；调整前须验证索引和实际容量。游标分页仍需项目实现。
- MysqlCrudAcceptanceTest 继承实际服务、MockMvc、MyBatis 与回滚测试，单独启用且强制专用空库，见生产手册。默认跳过不能计作成功。
<#if hooks>
- ${entity}Outbox 提供同数据源事务内持久化，${entity}OutboxDispatcher 提供显式批次投递、租约恢复、退避与死信；默认不启动后台线程。SQL 位于 integration/outbox-schema.sql，接入与至少一次语义见 integration/outbox-delivery-guide.md；宿主仍须实现超时受控的发送适配、调度、监控、消费者去重和留存策略。after 发生在事务提交前。
</#if>
