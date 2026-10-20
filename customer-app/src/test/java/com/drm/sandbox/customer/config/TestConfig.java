package com.drm.sandbox.customer.config;

import com.drm.sandbox.customer.client.WebClientFavouriteProductsClient;
import com.drm.sandbox.customer.client.WebClientProductReviewsClient;
import com.drm.sandbox.customer.client.WebClientProductsClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.web.reactive.function.client.WebClient;

import static org.mockito.Mockito.mock;

@Configuration
public class TestConfig {

    @Bean
    public ReactiveClientRegistrationRepository clientRegistrationRepository() {
        return mock();
    }

    @Bean
    public ServerOAuth2AuthorizedClientRepository authorizedClientRepository() {
        return mock();
    }

    // Integration tests talk to WireMock directly, so the token-relay filter is not needed.
    @Bean
    @Primary // to override the production bean
    public WebClientProductsClient mockWebClientProductsClient() {
        return new WebClientProductsClient(WebClient.builder()
                .baseUrl("http://localhost:54321")
                .build());
    }

    @Bean
    @Primary // to override the production bean
    public WebClientFavouriteProductsClient mockWebClientFavouriteProductsClient() {
        return new WebClientFavouriteProductsClient(WebClient.builder()
                .baseUrl("http://localhost:54321")
                .build());
    }

    @Bean
    @Primary // to override the production bean
    public WebClientProductReviewsClient mockWebClientProductReviewsClient() {
        return new WebClientProductReviewsClient(WebClient.builder()
                .baseUrl("http://localhost:54321")
                .build());
    }
}