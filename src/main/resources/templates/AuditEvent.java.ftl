package ${package}.integration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
/** 成功写操作的审计事件，前后快照和可信操作人随业务事务写入。 */
public final class ${entity}AuditEvent {
    private final String eventId;
    private final String operation;
    private final String actor;
    private final Long recordId;
    private final Instant occurredAt;
    private final Map<String, Object> before;
    private final Map<String, Object> after;
    public ${entity}AuditEvent(String operation, Long recordId, String actor, Map<String, Object> before, Map<String, Object> after) {
        ${package}.common.${entity}AccessIdentity.require(actor);
        this.eventId = UUID.randomUUID().toString(); this.operation = operation; this.recordId = recordId; this.actor = actor; this.occurredAt = Instant.now();
        this.before = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(before));
        this.after = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(after));
    }
    public String getEventId() { return eventId; }
    public String getOperation() { return operation; }
    public String getActor() { return actor; }
    public Long getRecordId() { return recordId; }
    public Instant getOccurredAt() { return occurredAt; }
    public Map<String, Object> getBefore() { return before; }
    public Map<String, Object> getAfter() { return after; }
}
