package com.drm.sandbox.feedback.config;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import org.bson.UuidRepresentation;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.observability.ContextProviderFactory;
import org.springframework.data.mongodb.observability.MongoHandlerContext;
import org.springframework.data.mongodb.observability.MongoObservationCommandListener;

import java.util.Set;

@Configuration
public class MongoConfig {

    /**
     * Commands the driver sends for handshakes and health checks rather than business queries.
     * Spring Data's command listener observes every command, so without filtering these would
     * create noise spans (and even root traces) on each health check and connection open.
     */
    private static final Set<String> MONGO_SERVICE_COMMANDS = Set.of("ping", "hello", "isMaster", "buildInfo");

    @Bean
    MongoClientSettingsBuilderCustomizer uuidRepresentationCustomizer() {
        return builder -> builder.uuidRepresentation(UuidRepresentation.STANDARD);
    }

    /**
     * Enables Spring Data MongoDB observations, which produce spans such as
     * {@code productReview.find} with {@code db.*} attributes. Spring Boot 4 does not
     * auto-configure this, so the context provider and command listener are registered here.
     */
    @Bean
    public MongoClientSettingsBuilderCustomizer mongoClientSettingsBuilderCustomizer(
            ObservationRegistry observationRegistry) {
        return builder -> builder.contextProvider(ContextProviderFactory.create(observationRegistry))
                .addCommandListener(new MongoObservationCommandListener(observationRegistry));
    }

    /**
     * Micrometer consults every {@link ObservationPredicate} before creating an observation:
     * {@code false} makes it a no-op (no span, no metric). Filter Mongo service commands only,
     * and keep returning {@code true} for all other contexts (HTTP, security, JDBC).
     */
    @Bean
    ObservationPredicate mongoServiceCommandsPredicate() {
        return (name, context) -> !(context instanceof MongoHandlerContext mongoContext)
                || !MONGO_SERVICE_COMMANDS.contains(mongoContext.getCommandName());
    }
}
