package cn.bank.devtool;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;

public final class ProductionReadiness {
    public ObjectNode inspect(Spec s) {
        new SpecValidator().validate(s);
        ArrayNode rows = Util.JSON.createArrayNode();
        this.row(rows, "NUM001", "P0", "数值传输", ProductionPolicy.strings(s) ? "CONFIGURED" : "REQUIRED", "Long／金额使用字符串；旧规格升级须同步客户端和宿主协议");
        this.row(rows, "STR001", "P1", "Unicode长度", "CONFIGURED", "生成字符串按码点校验；实际数据库字符集、组合字符要求仍需验收");
        this.row(
            rows,
            "AUTH001",
            "P0",
            "认证和操作权限",
            ProductionPolicy.accessGuard(s) ? "CONFIGURED" : "REQUIRED",
            "实现 AccessGuard 生产Bean，检查登录身份、操作权限和对象授权；启用后未提供Bean不能启动"
        );
        this.row(
            rows,
            "AUTH002",
            "P0",
            "记录对象权限",
            ProductionPolicy.recordAccess(s) ? "CONFIGURED" : "REQUIRED",
            "checkRecord检查当前记录；PAGE需确认整个SQL范围和总数可读，否则拒绝或改造宿主权限SQL"
        );
        this.row(rows, "API001", "P0", "更新ID一致性", "CONFIGURED", "服务层拒绝两份ID不一致；宿主将RequestMismatchException映射为400");
        this.row(rows, "ERR001", "P1", "公开校验字段", "CONFIGURED", "组合约束使用业务字段，$表示请求整体；不返回拒绝值或内部方法路径");
        this.row(rows, "STYLE001", "P2", "代码可读性", "CONFIGURED", "四空格、分行方法、完整控制块与中文组件／公共方法说明");
        this.row(
            rows,
            "SCOPE001",
            "P0",
            "机构／数据权限",
            ProductionPolicy.scope(s) != null ? "CONFIGURED" : "REVIEW_REQUIRED",
            "需要隔离时配置 SERVER scopeField，并测试详情、分页总数、修改、删除均不能跨范围"
        );
        if (!s.operations().contains("UPDATE") && !s.operations().contains("DELETE")) {
            boolean var5 = false;
        } else {
            boolean var10000 = true;
        }

        this.row(
            rows,
            "LOCK001",
            "P0",
            "更新并发",
            !s.operations().contains("UPDATE") ? "NOT_APPLICABLE" : (ProductionPolicy.locking(s) ? "CONFIGURED" : "REQUIRED"),
            "根据读取版本更新；冲突返回409，不能自动覆盖重试"
        );
        this.row(
            rows,
            "DEL001",
            "P0",
            "删除并发",
            !s.operations().contains("DELETE") ? "NOT_APPLICABLE" : (ProductionPolicy.deleteVersion(s) ? "CONFIGURED" : "REQUIRED"),
            "需要删除保护时启用 deleteVersionCheck；非BODY ID的版本固定在query，事务中检查实际行数"
        );
        this.row(
            rows, "DEL002", "P0", "删除／保留策略", s.operations().contains("DELETE") ? "REVIEW_REQUIRED" : "NOT_APPLICABLE", "物理删除／软删除均需明确业务状态、关联关系、留痕、数据保留和恢复规则"
        );
        this.row(
            rows, "AUD001", "P0", "业务规则与审计钩子", ProductionPolicy.hooks(s) ? "CONFIGURED" : "REQUIRED", "实现 Hooks 的业务状态检查及同事务审计；SERVER字段配置操作人／时间，钩子结构不代表已记录审计"
        );
        this.row(
            rows,
            "AUD002",
            "P0",
            "同事务前后审计",
            !s.operations().stream().anyMatch(op -> Arrays.asList("CREATE", "UPDATE", "DELETE").contains(op))
                ? "NOT_APPLICABLE"
                : (ProductionPolicy.audit(s) ? "CONFIGURED" : "REQUIRED"),
            EngineeringPolicy.existing(s) ? "宿主实现AuditWriter并共享业务事务；失败回滚，不允许REQUIRES_NEW或吞异常" : "独立工程可生成同数据源JDBC审计和前后快照；业务字段仅取auditFields白名单，留存和防篡改仍需验收"
        );
        this.row(rows, "TEST001", "P0", "当前项目验收", "NOT_RUN", "按acceptance-cases.json和acceptance-checklist.md验证真实账号、并发、审计和MySQL，不能以H2示例代替现场验收");
        this.row(rows, "OUTBOX001", "P1", "外部事件可靠投递", ProductionPolicy.hooks(s) ? "CONFIGURED" : "REVIEW_REQUIRED",
            "Hooks模式提供同事务Outbox与显式租约投递器；先迁移租约列，再接入有超时的发送器、调度与积压监控。至少一次投递需消费者持久去重，真实MySQL故障恢复尚待验收");
        this.row(
            rows, "IDEM001", "P1", "新增请求幂等", s.operations().contains("CREATE") ? "NOT_IMPLEMENTED" : "NOT_APPLICABLE", "宿主实现幂等键、请求内容校验和结果重放；业务唯一索引不能代替请求幂等"
        );
        this.row(
            rows,
            "IDX001",
            "P1",
            "业务唯一性与查询索引",
            s.getIndexes() != null && !s.getIndexes().isEmpty() ? "CONFIGURED" : "REVIEW_REQUIRED",
            "业务确认唯一范围；评审索引与存量重复数据，并执行EXPLAIN／真实数据量压测"
        );
        this.row(rows, "DB001", "P1", "迁移与真实MySQL", "NOT_RUN", "CREATE TABLE仅供参考；评审迁移／回填／恢复方案，验证字符集、驱动、事务与并发");
        this.row(rows, "OPS001", "P1", "部署／监控／恢复", "NOT_RUN", "使用生产配置和最小数据库权限；接入监控告警、进程托管、备份恢复和发布验收");
        this.row(rows, "TRACE001", "P1", "同步请求定位", EngineeringPolicy.existing(s) ? "REVIEW_REQUIRED" : "CONFIGURED", "独立工程生成受限X-Request-Id、脱敏耗时日志与线程上下文恢复；异步与跨服务链路需宿主接入");
        this.row(rows, "OPS002", "P1", "生产运行材料", EngineeringPolicy.existing(s) ? "REVIEW_REQUIRED" : "CONFIGURED", "独立工程附优雅停机配置、环境预检与接入手册；仍需部署、监控和恢复验收");
        this.row(rows, "DBTEST001", "P1", "真实MySQL检查入口", "NOT_RUN", "独立工程提供只读连接检查及专用空库CRUD事务回滚测试，两者分别显式启用且默认跳过；不替代并发、迁移或容量验收");
        this.row(rows, "DEP001", "P0", "依赖安全与支持", "NOT_RUN", "按组织策略执行依赖漏洞扫描、支持周期审查与补丁验收；固定版本和本地测试不等于无漏洞");
        ObjectNode report = Util.obj()
            .put("version", "2.1.1-rebuild.8-java8")
            .put("verdict", "INCOMPLETE")
            .put("deploymentReady", false)
            .put("scope", "仅检查声明规格和生成结构，未验证生产适配器、业务规则、数据库和目标环境");
        report.set("binding", ProductionArtifacts.binding(s));
        report.put("evidenceCheckEndpoint", "/api/production/evidence/check");
        report.set("checks", rows);
        return report;
    }

    private void row(ArrayNode rows, String code, String priority, String name, String status, String action) {
        rows.addObject()
            .put("code", code)
            .put("priority", priority)
            .put("name", name)
            .put("status", status)
            .put(
                "statusLabel",
                status.equals("CONFIGURED")
                    ? "结构已配置，待接入验收"
                    : (
                        status.equals("NOT_APPLICABLE")
                            ? "当前操作不适用"
                            : (
                                status.equals("NOT_IMPLEMENTED")
                                    ? "需宿主实现"
                                    : (status.equals("NOT_RUN") ? "尚未执行验收" : (status.equals("REVIEW_REQUIRED") ? "需业务确认" : "缺少配置／实现"))
                            )
                    )
            )
            .put("action", action);
    }
}
