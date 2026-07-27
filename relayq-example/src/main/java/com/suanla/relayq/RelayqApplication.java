package com.suanla.relayq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RelayqApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelayqApplication.class, args);
    }

    @Bean
    ObjectMapper relayqCoreObjectMapper() {
        /*
         * Boot 4 的 Web 层使用 Jackson 3，core 仍使用 Jackson 2。
         * 两边必须显式共享 snake_case 契约，避免 API 入库后的参数无法反序列化到 handler DTO。
         */
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
