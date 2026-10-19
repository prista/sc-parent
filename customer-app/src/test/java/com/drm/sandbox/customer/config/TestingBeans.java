package com.drm.sandbox.customer.config;

import com.drm.sandbox.customer.client.WebClientFavouriteProductsClient;
import com.drm.sandbox.customer.client.WebClientProductReviewsClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.web.reactive.function.client.WebClient;

import static org.mockito.Mockito.mock;

@Configuration
public class TestingBeans {

    @Bean
    public ReactiveClientRegistrationRepository clientRegistrationRepository() {
        return mock(ReactiveClientRegistrationRepository.class);
    }

    @Bean
    public ServerOAuth2AuthorizedClientRepository authorizedClientRepository() {
        return mock(ServerOAuth2AuthorizedClientRepository.class);
    }

    // Integration tests talk to WireMock directly, so the token-relay filter is not needed.
    @Bean
    @Primary
    public WebClient.Builder testWebClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    @Primary
    public WebClientFavouriteProductsClient mockWebClientFavouriteProductsClient() {
        return new WebClientFavouriteProductsClient(WebClient.builder()
                .baseUrl("http://localhost:54321")
                .build());
    }

    @Bean
    @Primary
    public WebClientProductReviewsClient mockWebClientProductReviewsClient() {
        return new WebClientProductReviewsClient(WebClient.builder()
                .baseUrl("http://localhost:54321")
                .build());
    }
}
