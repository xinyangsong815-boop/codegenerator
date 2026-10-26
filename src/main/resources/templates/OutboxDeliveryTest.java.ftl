package ${package};

import ${package}.integration.${entity}Outbox;
import ${package}.integration.${entity}OutboxDispatcher;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实隔离 JDBC 事务验证 Outbox 状态机；不启动远程发送或生产调度，不能代替 MySQL 故障验收。 */
class OutboxDeliveryTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ${entity}Outbox outbox;

    @BeforeEach
    void setup() {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:outbox_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema-test.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        outbox = new ${entity}Outbox(jdbc);
    }

    private void enqueue(String id) { tx.execute(status -> { outbox.enqueue(id, "CONTRACT_CHANGED", "{}"); return null; }); }
    private String state(String id) { return jdbc.queryForObject("SELECT `state` FROM `${outboxTable}` WHERE `event_id`=?", String.class, id); }
    private ${entity}OutboxDispatcher worker(${entity}OutboxDispatcher.Delivery delivery) { return new ${entity}OutboxDispatcher(jdbc, delivery, 60, 2, 10); }
    private void makeDue(String id) { jdbc.update("UPDATE `${outboxTable}` SET `available_at`=? WHERE `event_id`=?", new Timestamp(0), id); }

    @Test
    void enqueueRequiresSameDataSourceAndRollbackRemovesEvent() {
        assertThrows(IllegalStateException.class, () -> outbox.enqueue("outside", "T", "{}"));
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> { outbox.enqueue("rolledback", "T", "{}"); throw new IllegalStateException("business failed"); }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM `${outboxTable}`", Integer.class));
        TransactionTemplate readOnly = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        readOnly.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> readOnly.execute(status -> { outbox.enqueue("readonly", "T", "{}"); return null; }));
        DriverManagerDataSource other = new DriverManagerDataSource("jdbc:h2:mem:other_" + UUID.randomUUID(), "sa", "");
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(new DataSourceTransactionManager(other)).execute(status -> { outbox.enqueue("wrongds", "T", "{}"); return null; }));
    }

    @Test
    void committedEventIsDeliveredAndConfirmedOnce() {
        enqueue("good"); AtomicInteger sends = new AtomicInteger();
        ${entity}OutboxDispatcher worker = worker((id, type, payload) -> {
            assertEquals("good", id); assertEquals("CONTRACT_CHANGED", type); assertEquals("{}", payload);
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            sends.incrementAndGet();
        });
        assertEquals(1, worker.runBatch(10)); assertEquals(0, worker.runBatch(10));
        assertEquals(1, sends.get()); assertEquals("DELIVERED", state("good"));
        assertNotNull(jdbc.queryForObject("SELECT `delivered_at` FROM `${outboxTable}` WHERE `event_id`='good'", Timestamp.class));
    }

    @Test
    void failuresBackoffThenRemainInDeadLetterState() {
        enqueue("fail"); AtomicInteger sends = new AtomicInteger();
        ${entity}OutboxDispatcher worker = worker((id, type, payload) -> { sends.incrementAndGet(); throw new java.io.IOException("sensitive remote error never persisted"); });
        assertEquals(0, worker.runBatch(10)); assertEquals("RETRY", state("fail"));
        assertEquals(0, worker.runBatch(10)); assertEquals(1, sends.get());
        makeDue("fail"); worker.runBatch(10); assertEquals("DEAD", state("fail"));
        makeDue("fail"); worker.runBatch(10); assertEquals(2, sends.get());
    }

    @Test
    void restartedWorkerRecoversExpiredLeaseAndPreservesEventId() {
        enqueue("restart");
        jdbc.update("UPDATE `${outboxTable}` SET `state`='IN_FLIGHT', `attempts`=1, `lease_token`='crashed', `leased_until`=? WHERE `event_id`='restart'", new Timestamp(0));
        AtomicReference<String> sent = new AtomicReference<>();
        assertEquals(1, worker((id, type, payload) -> sent.set(id)).runBatch(10));
        assertEquals("restart", sent.get()); assertEquals("DELIVERED", state("restart"));
    }

    @Test
    void exhaustedCrashedLeaseBecomesDeadWithoutSendingAgain() {
        enqueue("exhausted");
        jdbc.update("UPDATE `${outboxTable}` SET `state`='IN_FLIGHT', `attempts`=2, `lease_token`='crashed', `leased_until`=? WHERE `event_id`='exhausted'", new Timestamp(0));
        assertEquals(0, worker((id, type, payload) -> fail("must not send exhausted event")).runBatch(10));
        assertEquals("DEAD", state("exhausted"));
    }

    @Test
    void concurrentWorkersCannotBothOwnAnUnexpiredLease() throws Exception {
        enqueue("parallel"); CountDownLatch sending = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger(); AtomicReference<Throwable> failure = new AtomicReference<>();
        ${entity}OutboxDispatcher first = worker((id, type, payload) -> { sends.incrementAndGet(); sending.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); });
        Thread thread = new Thread(() -> { try { assertEquals(1, first.runBatch(10)); } catch (Throwable t) { failure.set(t); } });
        thread.start();
        try { assertTrue(sending.await(10, TimeUnit.SECONDS)); assertEquals(0, worker((id, type, payload) -> sends.incrementAndGet()).runBatch(10)); }
        finally { release.countDown(); thread.join(10000); }
        assertFalse(thread.isAlive()); if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(1, sends.get()); assertEquals("DELIVERED", state("parallel"));
    }

    @Test
    void staleWorkerCannotAcknowledgeAnotherWorkersLease() {
        enqueue("stale");
        ${entity}OutboxDispatcher first = worker((id, type, payload) -> {
            jdbc.update("UPDATE `${outboxTable}` SET `leased_until`=? WHERE `event_id`=?", new Timestamp(0), id);
            assertEquals(1, worker((nextId, nextType, nextPayload) -> assertEquals(id, nextId)).runBatch(10));
        });
        assertEquals(0, first.runBatch(10)); assertEquals("DELIVERED", state("stale"));
        assertEquals(2, jdbc.queryForObject("SELECT `attempts` FROM `${outboxTable}` WHERE `event_id`='stale'", Integer.class));
    }

    @Test
    void interruptedWorkerDoesNotClaimOrSendPendingEvents() {
        enqueue("interrupted");
        AtomicInteger sends = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            assertEquals(0, worker((id, type, payload) -> sends.incrementAndGet()).runBatch(10));
            assertTrue(Thread.currentThread().isInterrupted(), "cancel flag must remain set for the scheduler");
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, sends.get());
        assertEquals("PENDING", state("interrupted"));
        assertEquals(0, jdbc.queryForObject("SELECT `attempts` FROM `${outboxTable}` WHERE `event_id`='interrupted'", Integer.class));
    }

    @Test
    void dispatchRejectsBusinessTransactionAndInvalidLimits() {
        ${entity}OutboxDispatcher worker = worker((id, type, payload) -> fail("must not send"));
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> worker.runBatch(1)));
        assertThrows(IllegalArgumentException.class, () -> worker.runBatch(0));
        assertThrows(IllegalArgumentException.class, () -> worker.runBatch(101));
        assertThrows(IllegalArgumentException.class, () -> new ${entity}OutboxDispatcher(jdbc, (id,t,p)->{}, 0, 1, 1));
    }

<#if transactionalAudit>
    @Test
    void auditWriterRejectsActiveTransactionBoundToOtherDataSource() {
        ${package}.integration.${entity}JdbcAuditWriter writer = new ${package}.integration.${entity}JdbcAuditWriter(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        DriverManagerDataSource other = new DriverManagerDataSource("jdbc:h2:mem:audit_other_" + UUID.randomUUID(), "sa", "");
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(new DataSourceTransactionManager(other)).execute(status -> {
            writer.write(new ${package}.integration.${entity}AuditEvent("CREATE", 1L, "trusted-actor", java.util.Collections.emptyMap(), java.util.Collections.emptyMap())); return null;
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM `${table}_audit`", Integer.class));
    }
</#if>
}
