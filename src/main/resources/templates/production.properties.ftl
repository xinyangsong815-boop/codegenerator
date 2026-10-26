# Enable with --spring.profiles.active=prod after host, database and release acceptance.
# No fallback credentials or development database URL in this profile.
spring.datasource.url=${r'${DB_URL}'}
spring.datasource.username=${r'${DB_USER}'}
spring.datasource.password=${r'${DB_PASSWORD}'}
spring.datasource.hikari.maximum-pool-size=${r'${DB_POOL_MAX:10}'}
spring.datasource.hikari.minimum-idle=${r'${DB_POOL_MIN:2}'}
spring.datasource.hikari.connection-timeout=${r'${DB_CONNECTION_TIMEOUT_MS:30000}'}
server.address=${r'${SERVER_ADDRESS:127.0.0.1}'}
server.port=${r'${SERVER_PORT:8080}'}
spring.sql.init.mode=never
app.log-exception-details=false
# Reuse approved host monitoring/Actuator with access controls; this file does not add metrics endpoints.

# Bounded, graceful lifecycle; align orchestrator and gateway timeouts before release.
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=${r'${SHUTDOWN_TIMEOUT:30s}'}
spring.datasource.hikari.validation-timeout=${r'${DB_VALIDATION_TIMEOUT_MS:5000}'}
spring.datasource.hikari.max-lifetime=${r'${DB_MAX_LIFETIME_MS:1800000}'}
server.tomcat.connection-timeout=${r'${HTTP_CONNECTION_TIMEOUT:10s}'}
spring.mvc.log-request-details=false
spring.servlet.multipart.enabled=false
logging.pattern.level=%5p [requestId:%X{requestId:-}]
