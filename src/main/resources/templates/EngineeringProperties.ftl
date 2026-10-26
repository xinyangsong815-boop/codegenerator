# 仅逐项合并到宿主配置。此文件不会自动加载，不包含数据源/端口/全局错误处理设置。
# 自定义 ObjectMapper 或多数据源配置须在宿主工程核验。
spring.jackson.deserialization.fail-on-unknown-properties=true
spring.jackson.deserialization.fail-on-ignored-properties=true
spring.jackson.deserialization.accept-float-as-int=false
# 将以下路径追加到已有 Mapper XML 配置，而非覆盖它；组件及 Mapper 扫描包含 ${package}。
# mybatis.mapper-locations=classpath*:mapper/${entity}Mapper.xml
