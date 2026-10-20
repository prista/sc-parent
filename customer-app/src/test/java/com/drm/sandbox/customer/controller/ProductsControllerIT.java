package com.drm.sandbox.customer.controller;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;

@SpringBootTest
@AutoConfigureWebTestClient
@WireMockTest(httpPort = 54321) // WireMock to mock external services
class ProductsControllerIT {

    private static final String CATALOGUE_PRODUCTS_PATH = "/catalogue-api/products";

    private static final String FAVOURITE_PRODUCTS_PATH = "/api/v1/feedback-api/favourite-products";

    @Autowired
    WebTestClient webTestClient;

    @Test
    void getProductsListPage_FilterIsSpecified_ReturnsPageWithFilteredProducts() {
        // given
        stubFor(get(urlPathEqualTo(CATALOGUE_PRODUCTS_PATH))
                .withQueryParam("filter", equalTo("Товар"))
                .willReturn(okJson("""
                        [
                            {
                                "id": 1,
                                "title": "Товар №1",
                                "details": "Описание товара №1"
                            }
                        ]""")
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)));

        // when
        this.webTestClient
                .mutateWith(mockUser())
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/customer/products/list")
                        .queryParam("filter", "Товар")
                        .build())
                .exchange()
                // then
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(html -> assertThat(html)
                        .contains("<h1>Наши товары</h1>")
                        .contains("name=\"filter\"")
                        .contains("value=\"Товар\"")
                        .contains("Товар №1")
                        .contains("Описание товара №1")
                        .doesNotContain("Товар №2"));

        verify(getRequestedFor(urlPathEqualTo(CATALOGUE_PRODUCTS_PATH))
                .withQueryParam("filter", equalTo("Товар")));
    }

    @Test
    void getProductsListPage_FilterIsNotSpecified_ReturnsPageWithAllProducts() {
        // given
        stubFor(get(urlPathEqualTo(CATALOGUE_PRODUCTS_PATH))
                .willReturn(okJson("""
                        [
                            {
                                "id": 1,
                                "title": "Товар №1",
                                "details": "Описание товара №1"
                            },
                            {
                                "id": 2,
                                "title": "Товар №2",
                                "details": "Описание товара №2"
                            }
                        ]""")
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)));

        // when
        this.webTestClient
                .mutateWith(mockUser())
                .get()
                .uri("/customer/products/list")
                .exchange()
                // then
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(html -> assertThat(html)
                        .contains("<h1>Наши товары</h1>")
                        .contains("name=\"filter\"")
                        .contains("value=\"\"")
                        .contains("Товар №1")
                        .contains("Описание товара №1")
                        .contains("Товар №2")
                        .contains("Описание товара №2"));

        // null filter is expanded to an empty query value: ?filter=
        verify(getRequestedFor(urlPathEqualTo(CATALOGUE_PRODUCTS_PATH))
                .withQueryParam("filter", equalTo("")));
    }

    @Test
    void getProductsListPage_UserIsNotAuthorized_RedirectsToLoginPage() {
        // given

        // when
        this.webTestClient
                .get()
                .uri("/customer/products/list")
                .exchange()
                // then
                .expectStatus().isFound()
                .expectHeader().location("/login");

        verify(0, getRequestedFor(urlPathEqualTo(CATALOGUE_PRODUCTS_PATH)));
    }

    @Test
    void getFavouriteProductsPage_ReturnsPageWithOnlyFavouriteProducts() {
        // given
        stubFor(get(urlPathEqualTo(FAVOURITE_PRODUCTS_PATH))
                .willReturn(okJson("""
                        [
                            {
                                "id": "2ecc74c2-cb17-11ee-b719-e35a0e241f11",
                                "productId": 2
                            }
                        ]""")
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)));

        stubFor(get(urlPathEqualTo(CATALOGUE_PRODUCTS_PATH))
                .willReturn(okJson("""
                        [
                            {
                                "id": 1,
                                "title": "Товар №1",
                                "details": "Описание товара №1"
                            },
                            {
                                "id": 2,
                                "title": "Товар №2",
                                "details": "Описание товара №2"
                            },
                            {
                                "id": 3,
                                "title": "Товар №3",
                                "details": "Описание товара №3"
                            }
                        ]""")
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)));

        // when
        this.webTestClient
                .mutateWith(mockUser())
                .get()
                .uri("/customer/products/favourites")
                .exchange()
                // then
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(html -> assertThat(html)
                        .contains("<h1>Избранные товары</h1>")
                        .contains("Товар №2")
                        .contains("Описание товара №2")
                        .doesNotContain("Товар №1")
                        .doesNotContain("Товар №3"));

        verify(getRequestedFor(urlPathEqualTo(FAVOURITE_PRODUCTS_PATH)));
        verify(getRequestedFor(urlPathEqualTo(CATALOGUE_PRODUCTS_PATH)));
    }
}
