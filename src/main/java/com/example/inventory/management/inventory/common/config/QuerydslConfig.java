package com.example.inventory.management.inventory.common.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared {@link JPAQueryFactory} for QueryDSL-based query repositories. The injected
 * EntityManager is Spring's shared, transaction-bound proxy, so a single factory is safe to reuse
 * across threads and transactions.
 */
@Configuration(proxyBeanMethods = false)
public class QuerydslConfig {

    @Bean
    JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
