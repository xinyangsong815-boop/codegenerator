package ${package};

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** ${description}模块的独立开发启动入口。合入已有工程时复用原应用入口。 */
@SpringBootApplication
@MapperScan("${package}.mapper")
public class Application {

    /** 启动独立 Spring Boot 应用并扫描本模块 Mapper；args 为命令行配置，生产必须通过受控环境提供数据源配置。 */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
