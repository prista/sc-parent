package com.drm.sandbox.feedback.config;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.mockito.Mockito.mock;

@Configuration
public class TestConfig {

    @Bean(initMethod = "start", destroyMethod = "stop")
    // Spring Boot reads host/port from the container and wires them into
    // spring.data.mongodb.* automatically — no manual URI needed in test properties.
    @ServiceConnection
    public MongoDBContainer mongoDBContainer() {
        return new MongoDBContainer("mongo:8");
    }

    // The app runs as an OAuth2 resource server, so it needs a ReactiveJwtDecoder.
    // Tests have no Keycloak, so we give it a mock one.
    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        return mock(ReactiveJwtDecoder.class);
    }
}
