package com.drm.sandbox.feedback.config;

import de.codecentric.boot.admin.client.registration.RegistrationClient;
import de.codecentric.boot.admin.client.registration.RestClientRegistrationClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.web.client.RestClient;

/**
 * Регистрация feedback-service в Spring Boot Admin.
 * <p>
 * В SBA 4.0 реактивного registration-клиента больше нет: регистрация всегда идёт
 * через блокирующий {@link RestClientRegistrationClient}. Поэтому берём
 * {@link RestClient} с interceptor'ом, который получает токен по client_credentials
 * через реактивный {@link AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager}
 * (вызов блокирующий — регистрация всё равно выполняется в отдельном
 * планировщике SBA, а не на event loop Reactor'а).
 */
@Configuration
public class ClientConfig {

    @Bean
    @ConditionalOnProperty(name = "spring.boot.admin.client.enabled", havingValue = "true")
    public RegistrationClient registrationClient(
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            ReactiveOAuth2AuthorizedClientService authorizedClientService,
            RestClient.Builder restClientBuilder
    ) {
        var authorizedClientManager = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);

        var restClient = restClientBuilder
                .requestInterceptor((request, body, execution) -> {
                    if (!request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)) {
                        var authorizedClient = authorizedClientManager.authorize(OAuth2AuthorizeRequest
                                .withClientRegistrationId("keycloak")
                                .principal("feedback-service-metrics-client")
                                .build()).block();
                        request.getHeaders().setBearerAuth(authorizedClient.getAccessToken().getTokenValue());
                    }
                    return execution.execute(request, body);
                })
                .build();

        return new RestClientRegistrationClient(restClient);
    }
}
