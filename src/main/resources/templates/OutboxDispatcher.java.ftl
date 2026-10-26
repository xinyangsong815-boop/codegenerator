package ${package}.integration;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 持久化 Outbox 的显式批次投递器；不注册 Bean 或启动后台线程，由宿主调度 runBatch。
 * 用同数据源短事务原子领取租约，提交后在事务外发送；过期租约可被其他进程恢复，失败退避重试，耗尽次数进入 DEAD。
 * 本组件提供至少一次投递：发送成功后进程崩溃或租约过期均可能重复，接收端必须按 eventId 去重。
 * 适配器必须设置网络超时且短于 leaseSeconds；长任务可并发重复投递。禁止将事件正文、异常消息或凭据写入日志。
 */
public final class ${entity}OutboxDispatcher {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate claims;
    private final Delivery delivery;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final int retrySeconds;

    /** 远程发送适配器；仅收到已提交事件。失败须抛异常，成功须在返回前得到持久接收确认；对方按 eventId 幂等去重。 */
    public interface Delivery {
        /** 在数据库事务外发送审查过的事件；实现须设定连接/响应超时，不得吞掉发送失败。 */
        void send(String eventId, String eventType, String approvedPayload) throws Exception;
    }

    /** 创建投递器。jdbc 必须指向业务 Outbox 所在主库；租约为 1~3600 秒，最大尝试 1~100 次，基础退避 1~3600 秒。
     * 业务写入仍使用 enqueue 所在事务；此处仅为领取、确认建立独立短事务。
     */
    public ${entity}OutboxDispatcher(JdbcTemplate jdbc, Delivery delivery, int leaseSeconds, int maxAttempts, int retrySeconds) {
        if (jdbc == null || jdbc.getDataSource() == null || delivery == null || leaseSeconds < 1 || leaseSeconds > 3600
                || maxAttempts < 1 || maxAttempts > 100 || retrySeconds < 1 || retrySeconds > 3600) {
            throw new IllegalArgumentException("Invalid Outbox dispatcher configuration");
        }
        this.jdbc = jdbc; this.delivery = delivery; this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts; this.retrySeconds = retrySeconds;
        this.claims = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        this.claims.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.claims.setTimeout(10);
    }

    /** 扫描最多 limit 条到期事件（1~100），逐条领取并投递，返回本批已成功确认的数量。
     * 调度必须在业务事务外执行；DB 异常向外传播供宿主告警，发送失败只更新重试/死信状态，不暴露远程错误细节。
     * 一个批次不保证清空队列；多实例可能选中相同候选，但只有租约条件更新成功者能发送。
     * 线程已中断时停止领取后续事件并保留中断标记，交由下一次调度继续处理。
     */
    public int runBatch(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Outbox batch limit must be 1..100");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Dispatch must run outside the business transaction");
        if (Thread.currentThread().isInterrupted()) return 0;
        List<String> ids = jdbc.queryForList("SELECT `event_id` FROM `${outboxTable}` WHERE "
                + "((`state` IN ('PENDING','RETRY') AND `available_at` <= CURRENT_TIMESTAMP) "
                + "OR (`state` = 'IN_FLIGHT' AND `leased_until` <= CURRENT_TIMESTAMP)) ORDER BY `available_at`, `event_id` LIMIT ?", String.class, limit);
        int delivered = 0;
        for (String id : ids) {
            if (Thread.currentThread().isInterrupted()) break;
            final String token = UUID.randomUUID().toString();
            Map<String, Object> event = claims.execute(status -> {
                Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
                // Exhausted events abandoned by a crashed worker are retained as DEAD, never silently discarded.
                jdbc.update("UPDATE `${outboxTable}` SET `state`='DEAD', `lease_token`=NULL, `leased_until`=NULL WHERE `event_id`=? AND `attempts`>=? "
                        + "AND ((`state` IN ('PENDING','RETRY') AND `available_at`<=?) OR (`state`='IN_FLIGHT' AND `leased_until`<=?))", id, maxAttempts, now, now);
                int changed = jdbc.update("UPDATE `${outboxTable}` SET `state`='IN_FLIGHT', `lease_token`=?, `leased_until`=?, `attempts`=`attempts`+1 "
                        + "WHERE `event_id`=? AND `attempts`<? AND ((`state` IN ('PENDING','RETRY') AND `available_at`<=?) OR (`state`='IN_FLIGHT' AND `leased_until`<=?))",
                        token, new Timestamp(now.getTime() + leaseSeconds * 1000L), id, maxAttempts, now, now);
                return changed == 1 ? jdbc.queryForMap("SELECT `event_type`, `payload`, `attempts` FROM `${outboxTable}` WHERE `event_id`=? AND `lease_token`=?", id, token) : null;
            });
            if (event == null) continue;
            boolean success;
            try {
                delivery.send(id, (String) event.get("event_type"), (String) event.get("payload"));
                success = true;
            } catch (Exception failure) {
                success = false;
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            final boolean sent = success;
            Integer acknowledged = claims.execute(status -> {
                Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
                if (sent) return jdbc.update("UPDATE `${outboxTable}` SET `state`='DELIVERED', `delivered_at`=?, `lease_token`=NULL, `leased_until`=NULL WHERE `event_id`=? AND `state`='IN_FLIGHT' AND `lease_token`=?", now, id, token);
                int attempts = ((Number) event.get("attempts")).intValue();
                long delay = Math.min(86400L, retrySeconds * (1L << Math.min(16, attempts - 1)));
                return jdbc.update("UPDATE `${outboxTable}` SET `state`=?, `available_at`=?, `lease_token`=NULL, `leased_until`=NULL WHERE `event_id`=? AND `state`='IN_FLIGHT' AND `lease_token`=?",
                        attempts >= maxAttempts ? "DEAD" : "RETRY", new Timestamp(now.getTime() + delay * 1000L), id, token);
            });
            if (sent && acknowledged != null && acknowledged == 1) delivered++;
            if (Thread.currentThread().isInterrupted()) break;
        }
        return delivered;
    }
}
