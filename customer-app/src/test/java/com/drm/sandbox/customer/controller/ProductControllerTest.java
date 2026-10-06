package com.drm.sandbox.customer.controller;

import com.drm.sandbox.customer.client.FavouriteProductsClient;
import com.drm.sandbox.customer.client.ProductReviewsClient;
import com.drm.sandbox.customer.client.ProductsClient;
import com.drm.sandbox.customer.client.exception.ClientBadRequestException;
import com.drm.sandbox.customer.controller.payload.NewProductReviewPayload;
import com.drm.sandbox.customer.entity.FavouriteProduct;
import com.drm.sandbox.customer.entity.Product;
import com.drm.sandbox.customer.entity.ProductReview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    ProductsClient productsClient;

    @Mock
    FavouriteProductsClient favouriteProductsClient;

    @Mock
    ProductReviewsClient productReviewsClient;

    @InjectMocks
    ProductController controller;

    @Test
    void loadProduct_ProductExists_ReturnsProduct() {
        // given
        var product = new Product(1, "Товар №1", "Описание товара №1");

        doReturn(Mono.just(product)).when(this.productsClient).findProduct(1);

        // when
        var result = this.controller.loadProduct(1);

        // then
        StepVerifier.create(result)
                .expectNext(product)
                .verifyComplete();

        verify(this.productsClient).findProduct(1);
        verifyNoMoreInteractions(this.productsClient);
    }

    @Test
    void loadProduct_ProductDoesNotExist_ThrowsNoSuchElementException() {
        // given
        doReturn(Mono.empty()).when(this.productsClient).findProduct(1);

        // when
        var result = this.controller.loadProduct(1);

        // then
        StepVerifier.create(result)
                .expectErrorSatisfies(exception -> {
                    assertEquals(NoSuchElementException.class, exception.getClass());
                    assertEquals("customer.products.error.not_found", exception.getMessage());
                })
                .verify();

        verify(this.productsClient).findProduct(1);
        verifyNoMoreInteractions(this.productsClient);
    }

    @Test
    void getProductPage_ProductIsFavourite_ReturnsProductPage() {
        // given
        var model = new ConcurrentModel();
        var reviews = List.of(
                new ProductReview(UUID.randomUUID(), 1, 5, "Отзыв №1"),
                new ProductReview(UUID.randomUUID(), 1, 4, "Отзыв №2"));
        var favouriteProduct = new FavouriteProduct(UUID.randomUUID(), 1);

        doReturn(Flux.fromIterable(reviews))
                .when(this.productReviewsClient).findProductReviewsByProductId(1);
        doReturn(Mono.just(favouriteProduct))
                .when(this.favouriteProductsClient).findFavouriteProductByProductId(1);

        // when
        var result = this.controller.getProductPage(1, model);

        // then
        StepVerifier.create(result)
                .expectNext("customer/products/product")
                .verifyComplete();

        assertEquals(reviews, model.getAttribute("reviews"));
        assertEquals(Boolean.TRUE, model.getAttribute("inFavourite"));

        verify(this.productReviewsClient).findProductReviewsByProductId(1);
        verifyNoMoreInteractions(this.productReviewsClient);
        verify(this.favouriteProductsClient).findFavouriteProductByProductId(1);
        verifyNoMoreInteractions(this.favouriteProductsClient);
        verifyNoInteractions(this.productsClient);
    }

    @Test
    void getProductPage_ProductIsNotFavourite_ReturnsProductPage() {
        // given
        var model = new ConcurrentModel();

        doReturn(Flux.empty())
                .when(this.productReviewsClient).findProductReviewsByProductId(1);
        doReturn(Mono.empty())
                .when(this.favouriteProductsClient).findFavouriteProductByProductId(1);

        // when
        var result = this.controller.getProductPage(1, model);

        // then
        StepVerifier.create(result)
                .expectNext("customer/products/product")
                .verifyComplete();

        assertEquals(List.of(), model.getAttribute("reviews"));
        assertEquals(Boolean.FALSE, model.getAttribute("inFavourite"));

        verify(this.productReviewsClient).findProductReviewsByProductId(1);
        verifyNoMoreInteractions(this.productReviewsClient);
        verify(this.favouriteProductsClient).findFavouriteProductByProductId(1);
        verifyNoMoreInteractions(this.favouriteProductsClient);
        verifyNoInteractions(this.productsClient);
    }

    @Test
    void addProductToFavourites_RedirectsToProductPage() {
        // given
        var product = new Product(1, "Товар №1", "Описание товара №1");
        var favouriteProduct = new FavouriteProduct(UUID.randomUUID(), 1);

        doReturn(Mono.just(favouriteProduct))
                .when(this.favouriteProductsClient).addProductToFavourites(1);

        // when
        var result = this.controller.addProductToFavourites(Mono.just(product));

        // then
        StepVerifier.create(result)
                .expectNext("redirect:/customer/products/1")
                .verifyComplete();

        verify(this.favouriteProductsClient).addProductToFavourites(1);
        verifyNoMoreInteractions(this.favouriteProductsClient);
    }

    @Test
    void addProductToFavourites_ClientThrowsException_StillRedirectsToProductPage() {
        // given
        var product = new Product(1, "Товар №1", "Описание товара №1");

        doReturn(Mono.error(new RuntimeException("Ошибка добавления в избранное")))
                .when(this.favouriteProductsClient).addProductToFavourites(1);

        // when
        var result = this.controller.addProductToFavourites(Mono.just(product));

        // then
        StepVerifier.create(result)
                .expectNext("redirect:/customer/products/1")
                .verifyComplete();

        verify(this.favouriteProductsClient).addProductToFavourites(1);
        verifyNoMoreInteractions(this.favouriteProductsClient);
    }

    @Test
    void addProductToFavourites_ProductDoesNotExist_CompletesWithoutResult() {
        // given

        // when
        var result = this.controller.addProductToFavourites(Mono.empty());

        // then
        StepVerifier.create(result)
                .verifyComplete();

        verifyNoInteractions(this.favouriteProductsClient);
    }

    @Test
    void removeProductFromFavourites_RedirectsToProductPage() {
        // given
        var product = new Product(1, "Товар №1", "Описание товара №1");

        doReturn(Mono.empty()).when(this.favouriteProductsClient).removeProductFromFavourites(1);

        // when
        var result = this.controller.removeProductFromFavourites(Mono.just(product));

        // then
        StepVerifier.create(result)
                .expectNext("redirect:/customer/products/1")
                .verifyComplete();

        verify(this.favouriteProductsClient).removeProductFromFavourites(1);
        verifyNoMoreInteractions(this.favouriteProductsClient);
    }

    @Test
    void createReview_RequestIsValid_RedirectsToProductPage() {
        // given
        var payload = new NewProductReviewPayload(5, "Отличный товар");
        var model = new ConcurrentModel();
        var review = new ProductReview(UUID.randomUUID(), 1, 5, "Отличный товар");

        doReturn(Mono.just(review))
                .when(this.productReviewsClient).createProductReview(1, 5, "Отличный товар");

        // when
        var result = this.controller.createReview(1, payload, model);

        // then
        StepVerifier.create(result)
                .expectNext("redirect:/customer/products/1")
                .verifyComplete();

        verify(this.productReviewsClient).createProductReview(1, 5, "Отличный товар");
        verifyNoMoreInteractions(this.productReviewsClient);
        verifyNoInteractions(this.favouriteProductsClient);
    }

    @Test
    void createReview_RequestIsInvalid_ReturnsProductPage() {
        // given
        var payload = new NewProductReviewPayload(6, "   ");
        var model = new ConcurrentModel();

        doReturn(Mono.error(new ClientBadRequestException(new RuntimeException("Bad request"),
                List.of("Ошибка 1", "Ошибка 2"))))
                .when(this.productReviewsClient).createProductReview(1, 6, "   ");
        doReturn(Mono.empty())
                .when(this.favouriteProductsClient).findFavouriteProductByProductId(1);

        // when
        var result = this.controller.createReview(1, payload, model);

        // then
        StepVerifier.create(result)
                .expectNext("customer/products/product")
                .verifyComplete();

        assertEquals(payload, model.getAttribute("payload"));
        assertEquals(List.of("Ошибка 1", "Ошибка 2"), model.getAttribute("errors"));
        assertEquals(Boolean.FALSE, model.getAttribute("inFavourite"));

        verify(this.productReviewsClient).createProductReview(1, 6, "   ");
        verifyNoMoreInteractions(this.productReviewsClient);
        verify(this.favouriteProductsClient).findFavouriteProductByProductId(1);
        verifyNoMoreInteractions(this.favouriteProductsClient);
    }

    @Test
    void createReview_RequestIsInvalidAndProductIsFavourite_InFavouriteIsTrue() {
        // given
        var payload = new NewProductReviewPayload(null, null);
        var model = new ConcurrentModel();
        var favouriteProduct = new FavouriteProduct(UUID.randomUUID(), 1);

        doReturn(Mono.error(new ClientBadRequestException(new RuntimeException("Bad request"),
                List.of("Ошибка 1"))))
                .when(this.productReviewsClient).createProductReview(1, null, null);
        doReturn(Mono.just(favouriteProduct))
                .when(this.favouriteProductsClient).findFavouriteProductByProductId(1);

        // when
        var result = this.controller.createReview(1, payload, model);

        // then
        StepVerifier.create(result)
                .expectNext("customer/products/product")
                .verifyComplete();

        assertEquals(payload, model.getAttribute("payload"));
        assertEquals(List.of("Ошибка 1"), model.getAttribute("errors"));
        assertEquals(Boolean.TRUE, model.getAttribute("inFavourite"));

        verify(this.productReviewsClient).createProductReview(1, null, null);
        verifyNoMoreInteractions(this.productReviewsClient);
        verify(this.favouriteProductsClient).findFavouriteProductByProductId(1);
        verifyNoMoreInteractions(this.favouriteProductsClient);
    }

    @Test
    void handleNoSuchElementException_Returns404ErrorPage() {
        // given
        var exception = new NoSuchElementException("customer.products.error.not_found");
        var model = new ConcurrentModel();

        // when
        var result = this.controller.handleNoSuchElementException(exception, model);

        // then
        assertEquals("errors/404", result);
        assertEquals("customer.products.error.not_found", model.getAttribute("error"));

        verifyNoInteractions(this.productsClient, this.favouriteProductsClient,
                this.productReviewsClient);
    }
}
