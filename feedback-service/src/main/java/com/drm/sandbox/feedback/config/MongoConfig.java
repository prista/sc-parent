package com.drm.sandbox.feedback.config;

import org.bson.UuidRepresentation;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MongoConfig {

    @Bean
    MongoClientSettingsBuilderCustomizer uuidRepresentationCustomizer() {
        return builder -> builder.uuidRepresentation(UuidRepresentation.STANDARD);
    }
}
