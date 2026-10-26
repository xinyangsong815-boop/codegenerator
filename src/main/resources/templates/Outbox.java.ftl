package ${package}.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 在当前业务事务中持久化事件；使用与业务 Mapper 相同的数据源，事务回滚时事件一起回滚。
 * 调用方须审查事件类型和正文，排除凭据及私密请求体。此类不执行远程调用。
 * 提交后可显式调用 ${entity}OutboxDispatcher 投递；宿主负责发送适配、调度、消费者去重和数据留存。
 */
public final class ${entity}Outbox {
    private final JdbcTemplate jdbc;

    public ${entity}Outbox(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 在写操作的事务钩子中保存事件。eventId 为 1~64 位字母数字/下划线/短横线且全局唯一；eventType 为 1~80 位安全标识符，正文最多 16384 个 Java 字符。
     * 相同 eventId 重复写入会抛数据库完整性异常并回滚；消费者必须以该 ID 去重，不得把重复事件当成新业务。
     */
    public void enqueue(String eventId, String eventType, String approvedPayload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("Outbox requires the same active JDBC business transaction");
        }
        if (eventId == null || !eventId.matches("[a-zA-Z0-9_-]{1,64}")
                || eventType == null || !eventType.matches("[a-zA-Z0-9_.-]{1,80}")
                || approvedPayload == null || approvedPayload.length() > 16384) {
            throw new IllegalArgumentException("Invalid outbox event");
        }
        int affected = jdbc.update("INSERT INTO `${outboxTable}` (`event_id`, `event_type`, `payload`, `state`, `attempts`) VALUES (?, ?, ?, 'PENDING', 0)",
                eventId, eventType, approvedPayload);
        if (affected != 1) throw new IllegalStateException("Outbox event was not persisted");
    }
}
