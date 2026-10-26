package ${package}.integration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** 同事务 JDBC 审计，复用业务数据源；失败回滚，不提供审计修改／删除接口。 */
@Component
public class ${entity}JdbcAuditWriter implements ${entity}AuditWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ${entity}JdbcAuditWriter(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json.copy().setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS)); }
    /** 将事件写入同数据源审计表；无业务事务或数据源不匹配立即拒绝，序列化与数据库错误继续传播以回滚业务。 */
    @Override
    public void write(${entity}AuditEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) { throw new IllegalStateException("审计必须使用当前业务事务绑定的同一数据源"); }
        try {
            int affected = jdbc.update("INSERT INTO `${table}_audit` (`event_id`, `operation`, `record_id`, `actor`, `occurred_at`, `before_json`, `after_json`) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    event.getEventId(), event.getOperation(), event.getRecordId(), event.getActor(), Timestamp.from(event.getOccurredAt()), json.writeValueAsString(event.getBefore()), json.writeValueAsString(event.getAfter()));
            if (affected != 1) { throw new IllegalStateException("审计记录写入数量异常"); }
        } catch (JsonProcessingException exception) { throw new IllegalStateException("审计快照序列化失败", exception); }
    }
}
