package com.drm.sandbox.feedback.actuator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@SpringBootTest(properties = "management.endpoints.web.exposure.include=*")
@AutoConfigureWebTestClient
class ActuatorSecurityIT {

    @Autowired
    WebTestClient webTestClient;

    @Test
    void prometheusEndpoint_NoToken_ReturnsUnauthorized() {
        this.webTestClient.get().uri("/actuator/prometheus").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void prometheusEndpoint_TokenWithoutMetricsScope_ReturnsForbidden() {
        this.webTestClient
                .mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("SCOPE_view_catalogue")))
                .get().uri("/actuator/prometheus").exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void prometheusEndpoint_TokenWithMetricsScope_ReturnsMetrics() {
        this.webTestClient
                .mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("SCOPE_metrics")))
                .get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("jvm_"));
    }
}
