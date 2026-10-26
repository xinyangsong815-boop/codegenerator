package cn.bank.devtool;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class AcceptancePlanning {
    private AcceptancePlanning() {
    }

    public static ObjectNode cases(Spec spec) {
        ObjectNode plan = Util.obj()
            .put("version", "2.1.1-rebuild.8-java8")
            .put("specSha256", Util.sha(Util.bytes(Util.pretty(spec))))
            .put("executionStatus", "NOT_RUN")
            .put("deploymentReady", false);
        ArrayNode rows = plan.putArray("cases");
        add(rows, "AUTH-01", "未登录、身份失效、没有操作权限", "401/403且没有写入或成功审计", ProductionPolicy.accessGuard(spec));
        add(rows, "SCOPE-01", "跨机构详情、分页总数、更新和删除", "记录与总数不能跨SQL范围", ProductionPolicy.scope(spec) != null);
        add(rows, "OBJECT-01", "同范围内另一用户的受限记录", "对象拒绝；分页确认整个SQL范围可读，不能仅检查一页", ProductionPolicy.recordAccess(spec));
        add(rows, "ID-01", "接口ID与请求体ID不一致", "400，errors.field=id，版本、数据库及审计不变", spec.operations().contains("UPDATE"));
        add(rows, "INPUT-01", "空修改、显式null、倒置范围", "业务字段或$，不返回内部属性名及拒绝值", true);
        add(rows, "LOCK-01", "同版本同时修改", "一个成功，其他409，版本仅增加一次", spec.operations().contains("UPDATE") && ProductionPolicy.locking(spec));
        add(rows, "DELETE-01", "过期版本和同时删除", "过期409，重复404或业务约定，最多一次成功写入", ProductionPolicy.deleteVersion(spec));
        add(rows, "AUDIT-01", "成功写入的前后快照和操作人", "同事务、真实操作人、白名单；不存完整请求和凭据", ProductionPolicy.audit(spec));
        add(rows, "AUDIT-02", "受控审计故障", "业务和版本回滚，不能只提交其中一方", ProductionPolicy.audit(spec));
        add(rows, "OUTBOX-01", "事件回滚、并发领取、租约过期、崩溃重投与死信", "同事务保存；旧租约不能确认新租约；消费者按eventId持久去重，断网恢复和真实MySQL压力保留证据", ProductionPolicy.hooks(spec));
        add(rows, "IDEMPOTENCY-01", "幂等键重放与不同内容", "结果重放或明确冲突；业务唯一键不能替代请求幂等", spec.operations().contains("CREATE"));
        add(rows, "HOST-01", "真实宿主代理、异常、JSON及事务", "离线编译并对齐400/401/403/409和实际宿主响应协议", EngineeringPolicy.existing(spec));
        add(rows, "DB-01", "真实MySQL迁移、锁等待与数据量", "审核迁移、EXPLAIN、并发和字符集；不能执行测试DROP脚本", true);
        add(rows, "OPS-01", "目标系统、JDK、压力与备份恢复", "记录环境、角色、接口响应、落库和恢复证据", true);
        add(rows, "DEPENDENCY-01", "依赖扫描、支持策略与批准补丁", "记录漏洞库日期、完整传递依赖、风险处置与补丁回归；不能以固定版本代替", true);
        return plan;
    }

    private static void add(ArrayNode rows, String id, String name, String expected, boolean relevant) {
        rows.addObject()
            .put("id", id)
            .put("name", name)
            .put("expected", expected)
            .put("status", relevant ? "NOT_RUN" : "NOT_APPLICABLE")
            .put("evidenceRequired", relevant);
    }

    public static String guide(Spec spec) {
        return "# 当前项目验收步骤\n\n规格摘要：`"
            + Util.sha(Util.bytes(Util.pretty(spec)))
            + "`。清单对应本次生成，不代表执行结果。\n\n1. 三方合并接入实际工程，在目标JDK与宿主执行离线 Maven verify。\n2. 审核专用测试库迁移，不能把 schema-test.sql 的 DROP 导入业务库。\n3. 实现可信上下文、AccessGuard和Hooks；宿主审计实现AuditWriter并共享业务事务。\n4. 不同机构、角色、对象用户使用独立测试账号；环境TOKEN/SESSION只传本次凭证，不写入场景文件。\n5. 新增→读取版本→修改→再次读取→删除，变量从真实响应提取；按 acceptance-cases.json 编写401/403/400/409和数据库断言。\n6. 独立线程／并发客户端提交同版本，核对成功数、版本和审计数；工具顺序场景不能替代并发测试。\n7. 注入受控审计故障，检查回滚、白名单、操作人、时区、留存、防篡改和拒绝访问留痕。\n8. 从契约创建草稿，补全真实错误码、字段断言、变量和清理；取消draft后执行。Mock PASS只证明合成响应满足已支持契约。\n9. 真实MySQL、目标系统、迁移、压力和恢复结果绑定此规格摘要；未执行项保留NOT_RUN，人工清理确认不会执行数据库操作。\n\n生成测试使用隔离H2和测试身份，不代表实际银行权限系统、审批、业务幂等和生产环境已验收。\n";
    }
}
