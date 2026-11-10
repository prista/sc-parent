package com.drm.sandbox.manager.actuator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.endpoints.web.exposure.include=*")
@AutoConfigureMockMvc
class ActuatorSecurityIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void prometheusEndpoint_NoToken_ReturnsUnauthorized() throws Exception {
        this.mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheusEndpoint_TokenWithoutMetricsScope_ReturnsForbidden() throws Exception {
        this.mockMvc.perform(get("/actuator/prometheus")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_view_catalogue"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void prometheusEndpoint_TokenWithMetricsScope_ReturnsMetrics() throws Exception {
        this.mockMvc.perform(get("/actuator/prometheus")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_metrics"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("jvm_")));
    }
}
