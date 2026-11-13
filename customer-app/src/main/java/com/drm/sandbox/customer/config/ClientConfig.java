package com.drm.sandbox.customer.config;

import com.drm.sandbox.customer.client.WebClientFavouriteProductsClient;
import com.drm.sandbox.customer.client.WebClientProductReviewsClient;
import com.drm.sandbox.customer.client.WebClientProductsClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class ClientConfig {

    /**
     * Rest Client (WebClient) for {@code catalogue-service}: product list/search and a single product by id.
     * <p>
     * Built from the auto-configured prototype {@link WebClient.Builder} (observations, codecs,
     * HTTP connector) plus the token-relay filter, so every call carries the signed-in user's
     * access token as {@code Authorization: Bearer ...}.
     */
    @Bean
    public WebClientProductsClient webClientProductsClient(
            @Value("${selmag.services.catalogue.uri:http://localhost:8081}") String catalogueBaseUrl,
            WebClient.Builder webClientBuilder,
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            ServerOAuth2AuthorizedClientRepository authorizedClientRepository
    ) {
        return new WebClientProductsClient(webClientBuilder
                .baseUrl(catalogueBaseUrl)
                .filter(keycloakTokenRelay(clientRegistrationRepository, authorizedClientRepository))
                .build());
    }

    /**
     * Rest Client (WebClient) for the favourites API of {@code feedback-service}: read the current user's
     * favourites, check a single product, add and remove.
     * <p>
     * The relayed user token is what makes favourites per user: {@code feedback-service} scopes
     * every read and write by the token's {@code sub}.
     */
    @Bean
    public WebClientFavouriteProductsClient webClientFavouriteProductsClient(
            @Value("${selmag.services.feedback.uri:http://localhost:8084}") String feedbackBaseUrl,
            WebClient.Builder webClientBuilder,
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            ServerOAuth2AuthorizedClientRepository authorizedClientRepository
    ) {
        return new WebClientFavouriteProductsClient(webClientBuilder
                .baseUrl(feedbackBaseUrl)
                .filter(keycloakTokenRelay(clientRegistrationRepository, authorizedClientRepository))
                .build());
    }

    /**
     * Rest Client (WebClient) for the product-reviews API of {@code feedback-service}: list reviews of a product
     * and create a new one.
     * <p>
     * {@code feedback-service} requires authentication on every exchange, so the token-relay
     * filter is needed here too, even though reviews are not user-specific.
     */
    @Bean
    public WebClientProductReviewsClient webClientProductReviewsClient(
            @Value("${selmag.services.feedback.uri:http://localhost:8084}") String feedbackBaseUrl,
            WebClient.Builder webClientBuilder,
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            ServerOAuth2AuthorizedClientRepository authorizedClientRepository
    ) {
        return new WebClientProductReviewsClient(webClientBuilder
                .baseUrl(feedbackBaseUrl)
                .filter(keycloakTokenRelay(clientRegistrationRepository, authorizedClientRepository))
                .build());
    }

    /**
     * The auto-configured {@link WebClient.Builder} (prototype scope — every injection gets a fresh
     * instance) already carries observations: client metrics, client spans and traceparent
     * propagation. A bare {@code WebClient.builder()} would lose all of that.
     */
    private static ServerOAuth2AuthorizedClientExchangeFilterFunction keycloakTokenRelay(
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            ServerOAuth2AuthorizedClientRepository authorizedClientRepository
    ) {
        var filter = new ServerOAuth2AuthorizedClientExchangeFilterFunction(
                clientRegistrationRepository, authorizedClientRepository);
        filter.setDefaultClientRegistrationId("keycloak"); // from application.yml
        return filter;
    }
}
