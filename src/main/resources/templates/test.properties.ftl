# 专用内存数据库，与任何真实 MySQL 配置分离。
spring.datasource.url=jdbc:h2:mem:${artifact?replace('-', '_')}_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE
spring.datasource.username=sa
spring.datasource.password=
spring.datasource.driver-class-name=org.h2.Driver
spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:schema-test.sql
