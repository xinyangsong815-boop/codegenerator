# 独立开发默认仅监听本机；合入业务项目时对齐现有端口和网关策略。
server.address=127.0.0.1
server.port=8080
spring.datasource.url=${r"${DB_URL:jdbc:mysql://127.0.0.1:3306/"}${artifact?replace('-', '_')}${r"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai}"}
spring.datasource.username=${r"${DB_USER:}"}
spring.datasource.password=${r"${DB_PASSWORD:}"}
mybatis.mapper-locations=classpath:mapper/*.xml
spring.sql.init.mode=never
# 防止请求字段拼写错误或内部计算字段被悄悄忽略。
spring.jackson.deserialization.fail-on-unknown-properties=true
spring.jackson.deserialization.fail-on-ignored-properties=true
# 禁止将小数截断为资源 ID 或整数字段。
spring.jackson.deserialization.accept-float-as-int=false
server.error.include-message=never
server.error.include-stacktrace=never
server.error.include-binding-errors=never
# 本工程只提供 API；没有静态资源，并统一处理不存在的路由。
spring.web.resources.add-mappings=false
spring.mvc.throw-exception-if-no-handler-found=true
