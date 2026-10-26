package ${package};

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real MySQL run of the same Service/HTTP/MyBatis suite. All writes roll back.
 * Requires a dedicated empty schema prepared from reviewed SQL; never runs schema-test.sql.
 */
@EnabledIfEnvironmentVariable(named = "MYSQL_CRUD_ACCEPTANCE_ENABLED", matches = "true")
@ActiveProfiles(value = "mysql-acceptance", inheritProfiles = false)
@TestPropertySource(properties = {
    "spring.sql.init.mode=never",
    "spring.datasource.url=${r'${MYSQL_ACCEPTANCE_URL}'}",
    "spring.datasource.username=${r'${MYSQL_ACCEPTANCE_USER}'}",
    "spring.datasource.password=${r'${MYSQL_ACCEPTANCE_PASSWORD}'}",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
class MysqlCrudAcceptanceTest extends CrudIntegrationTest {
    @Autowired private JdbcTemplate mysql;

    @BeforeEach void dedicatedEmptyMysqlIsRequired() throws Exception {
        assertEquals("true", System.getenv("MYSQL_CRUD_EMPTY_SCHEMA_CONFIRMED"), "必须显式确认专用空测试库");
        assertTrue(System.getenv("MYSQL_ACCEPTANCE_URL").startsWith("jdbc:mysql://"));
        try (java.sql.Connection connection = mysql.getDataSource().getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
        }
        assertEquals(0L, mysql.queryForObject("SELECT COUNT(*) FROM `${table}`", Long.class));
    }

    @Test void actualMysqlTransactionIsActive() {
        assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
    }
}
