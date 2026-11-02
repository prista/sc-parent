package com.drm.sandbox.catalogue.security;

import de.codecentric.boot.admin.client.registration.RegistrationClient;
import de.codecentric.boot.admin.client.registration.RestClientRegistrationClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

/**
 * Регистрация catalogue-service в Spring Boot Admin.
 * <p>
 * Admin-сервер — OAuth2 resource server ("для регистрируемых клиентов админки"),
 * поэтому одного POST на его URL недостаточно: нужен access token. Штатный
 * {@link RestClientRegistrationClient} из SBA токен не добавляет, поэтому
 * подменяем его клиентом с interceptor'ом, который берёт токен по
 * client_credentials (Keycloak-клиент catalogue-service-metrics-client,
 * scope metrics_server) и кладёт его в {@code Authorization: Bearer}.
 */
@Configuration
public class ClientConfig {

    @Bean
    @ConditionalOnProperty(name = "spring.boot.admin.client.enabled", havingValue = "true")
    public RegistrationClient registrationClient(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService,
            RestClient.Builder restClientBuilder
    ) {
        // получает токен через client_credentials — без пользователя и сессии
        var authorizedClientManager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);

        var restClient = restClientBuilder
                .requestInterceptor((request, body, execution) -> {
                    if (!request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)) {
                        var authorizedClient = authorizedClientManager.authorize(OAuth2AuthorizeRequest
                                .withClientRegistrationId("keycloak")
                                .principal("catalogue-service-metrics-client")
                                .build());
                        request.getHeaders().setBearerAuth(authorizedClient.getAccessToken().getTokenValue());
                    }
                    return execution.execute(request, body);
                })
                .build();

        return new RestClientRegistrationClient(restClient);
    }
}
