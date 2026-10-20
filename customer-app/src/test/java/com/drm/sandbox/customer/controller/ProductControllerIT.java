package com.drm.sandbox.customer.controller;


import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;

@SpringBootTest
@AutoConfigureWebTestClient
@WireMockTest(httpPort = 54321) // WireMock to mock external services
class ProductControllerIT {

    @Autowired
    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        stubFor(get("/catalogue-api/products/1")
                .willReturn(okJson("""
                        {
                            "id": 1,
                            "title": "Product name №1",
                            "details": "Product description №1"
                        }""")
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)));
    }

    @Test
    void addProductToFavourites_RequestIsValid_ReturnsRedirectionToProductPage() {
        // given
        stubFor(post("/api/v1/feedback-api/favourite-products")
                .withRequestBody(equalToJson("""
                        {
                            "productId": 1
                        }"""))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo(MediaType.APPLICATION_JSON_VALUE))
                .willReturn(created()
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                    "id": "2ecc74c2-cb17-11ee-b719-e35a0e241f11",
                                    "productId": 1
                                }""")));

        // when
        this.webTestClient
                .mutateWith(mockUser())
                .mutateWith(csrf())
                .post()
                .uri("/customer/products/1/add-to-favourites")
                .exchange()
                // then
                .expectStatus().is3xxRedirection()
                .expectHeader().location("/customer/products/1");

        verify(getRequestedFor(urlPathMatching("/catalogue-api/products/1")));
        verify(postRequestedFor(urlPathMatching("/api/v1/feedback-api/favourite-products"))
                .withRequestBody(equalToJson("""
                        {
                            "productId": 1
                        }""")));
    }

    @Test
    void addProductToFavourites_ProductDoesNotExist_ReturnsNotFoundPage() {
        // given

        // when
        this.webTestClient
                .mutateWith(mockUser())
                .mutateWith(csrf())
                .post()
                .uri("/customer/products/404/add-to-favourites")
                .exchange()
                // then
                .expectStatus().isNotFound();

        verify(getRequestedFor(urlPathMatching("/catalogue-api/products/404")));
    }

    @Test
    void addProductToFavourites_UserIsNotAuthorized_RedirectsToLoginPage() {
        // given

        // when
        this.webTestClient
                .mutateWith(csrf())
                .post()
                .uri("/customer/products/1/add-to-favourites")
                .exchange()
                // then
                .expectStatus().isFound()
                .expectHeader().location("/login");
    }
}
