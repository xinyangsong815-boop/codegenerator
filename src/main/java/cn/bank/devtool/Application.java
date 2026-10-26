package cn.bank.devtool;

import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {
    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if (arg.startsWith("--verify-host=")) {
                System.exit(HostCompileCheck.run(args));
                return;
            }
        }

        SpringApplication.run(Application.class, args);
    }

    @Bean
    Store store(@Value("${tool.data-dir}") String dir) {
        return new Store(Paths.get(dir));
    }

    @Bean
    org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer exactJsonDecimals() {
        return builder -> builder.postConfigurer(mapper -> mapper
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false));
    }
}
