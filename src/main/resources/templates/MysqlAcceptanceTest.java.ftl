package ${package};

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** 显式启用的真实 MySQL 只读接入检查；不初始化表，不执行测试 DROP 或业务写入。 */
@EnabledIfEnvironmentVariable(named = "MYSQL_ACCEPTANCE_ENABLED", matches = "true")
class MysqlAcceptanceTest {
    @Test void realMysqlConnectionAndExpectedColumns() throws Exception {
        assertEquals("true", System.getenv("MYSQL_ACCEPTANCE_READ_ONLY_CONFIRMED"), "需先确认专用测试库与只读检查");
        String url = environment("MYSQL_ACCEPTANCE_URL");
        assertTrue(url.startsWith("jdbc:mysql://"), "仅允许显式 MySQL JDBC 地址");
        try (Connection connection = DriverManager.getConnection(url, environment("MYSQL_ACCEPTANCE_USER"), environment("MYSQL_ACCEPTANCE_PASSWORD"))) {
            connection.setReadOnly(true);
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT 1")) {
                assertTrue(result.next()); assertEquals(1, result.getInt(1));
            }
            for (String table : new String[]{"${table}"<#if hooks>, "${outboxTable}"</#if>}) {
                try (java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
                    statement.setString(1, table);
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next(), "数据库表不存在");
                        assertEquals("InnoDB", result.getString(1), "业务及Outbox表必须使用事务引擎");
                    }
                }
            }
            Set<String> columns = new HashSet<String>();
            try (ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), null, "${table}", "%")) {
                while (result.next()) if ("${table}".equalsIgnoreCase(result.getString("TABLE_NAME"))) columns.add(result.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT));
            }
            assertTrue(columns.contains("id"), "数据库表或 id 列不存在");
<#list fields as field>
            assertTrue(columns.contains("${field.column?lower_case}"), "数据库列未就绪：${field.column}");
</#list>
<#if optimisticLock>
            assertTrue(columns.contains("version"));
</#if>
<#if softDelete>
            assertTrue(columns.contains("deleted"));
</#if>
        }
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "缺少环境变量：" + name);
        assertFalse(value.trim().isEmpty(), "环境变量不能为空：" + name);
        return value;
    }
}
