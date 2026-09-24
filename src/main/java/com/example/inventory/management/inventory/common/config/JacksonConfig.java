package com.example.inventory.management.inventory.common.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Strict request JSON. By default Jackson turns {@code "quantity": "5"} into 5; integer fields
 * (quantity, productId) must be sent as JSON numbers, so String → integer coercion fails and the
 * request is rejected with 400 VALIDATION_FAILED. (Floats are rejected via
 * {@code spring.jackson.deserialization.accept-float-as-int=false}, unknown fields via
 * {@code fail-on-unknown-properties=true} in application.yml.)
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer strictIntegerCoercionCustomizer() {
        return builder -> builder.withCoercionConfig(LogicalType.Integer,
                config -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail));
    }
}
