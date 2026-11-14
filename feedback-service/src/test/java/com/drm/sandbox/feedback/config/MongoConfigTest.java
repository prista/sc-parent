package com.drm.sandbox.feedback.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.observability.MongoHandlerContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoConfigTest {

    private final ObservationPredicate predicate = new MongoConfig().mongoServiceCommandsPredicate();

    @Test
    void filtersMongoServiceCommands() {
        for (String command : new String[]{"ping", "hello", "isMaster", "buildInfo"}) {
            assertThat(predicate.test("spring.data.mongodb.command", mongoContext(command)))
                    .as("command %s", command)
                    .isFalse();
        }
    }

    @Test
    void keepsBusinessMongoCommands() {
        assertThat(predicate.test("spring.data.mongodb.command", mongoContext("find"))).isTrue();
        assertThat(predicate.test("spring.data.mongodb.command", mongoContext("insert"))).isTrue();
    }

    @Test
    void keepsNonMongoObservations() {
        assertThat(predicate.test("http.server.requests", new Observation.Context())).isTrue();
    }

    private static MongoHandlerContext mongoContext(String command) {
        MongoHandlerContext context = mock(MongoHandlerContext.class);
        when(context.getCommandName()).thenReturn(command);
        return context;
    }
}
