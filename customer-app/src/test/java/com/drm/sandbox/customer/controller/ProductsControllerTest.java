package com.drm.sandbox.customer.controller;

import com.drm.sandbox.customer.client.FavouriteProductsClient;
import com.drm.sandbox.customer.client.ProductsClient;
import com.drm.sandbox.customer.entity.FavouriteProduct;
import com.drm.sandbox.customer.entity.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductsControllerTest {

    @Mock
    ProductsClient productsClient;

    @Mock
    FavouriteProductsClient favouriteProductsClient;

    @InjectMocks
    ProductsController controller;

    @Test
    void getProductsListPage_ReturnsProductsListPage() {
        // given
        var model = new ConcurrentModel();
        var filter = "Товар";
        var products = List.of(
                new Product(1, "Товар №1", "Описание товара №1"),
                new Product(2, "Товар №2", "Описание товара №2"),
                new Product(3, "Товар №3", "Описание товара №3"));

        doReturn(Flux.fromIterable(products)).when(this.productsClient).findAllProducts(filter);

        // when
        var result = this.controller.getProductsListPage(model, filter);

        // then
        StepVerifier.create(result)
                .expectNext("customer/products/list")
                .verifyComplete();

        assertEquals(filter, model.getAttribute("filter"));
        assertEquals(products, model.getAttribute("products"));

        verify(this.productsClient).findAllProducts(filter);
        verifyNoMoreInteractions(this.productsClient);
        verifyNoInteractions(this.favouriteProductsClient);
    }

    @Test
    void getProductsListPage_FilterIsNotSpecified_ReturnsProductsListPageWithEmptyList() {
        // given
        var model = new ConcurrentModel();

        doReturn(Flux.empty()).when(this.productsClient).findAllProducts(null);

        // when
        var result = this.controller.getProductsListPage(model, null);

        // then
        StepVerifier.create(result)
                .expectNext("customer/products/list")
                .verifyComplete();

        assertNull(model.getAttribute("filter"));
        assertEquals(List.of(), model.getAttribute("products"));

        verify(this.productsClient).findAllProducts(null);
        verifyNoMoreInteractions(this.productsClient);
        verifyNoInteractions(this.favouriteProductsClient);
    }

    @Test
    void getFavouriteProductsPage_ReturnsFavouriteProductsListPage() {
        // given
        var model = new ConcurrentModel();
        var filter = "Товар";
        var favouriteProducts = List.of(
                new FavouriteProduct(UUID.randomUUID(), 2),
                new FavouriteProduct(UUID.randomUUID(), 3));
        var products = List.of(
                new Product(1, "Товар №1", "Описание товара №1"),
                new Product(2, "Товар №2", "Описание товара №2"),
                new Product(3, "Товар №3", "Описание товара №3"),
                new Product(4, "Товар №4", "Описание товара №4"));

        doReturn(Flux.fromIterable(favouriteProducts))
                .when(this.favouriteProductsClient).findFavouriteProducts();
        doReturn(Flux.fromIterable(products)).when(this.productsClient).findAllProducts(filter);

        // when
        var result = this.controller.getFavouriteProductsPage(model, filter);

        // then
        StepVerifier.create(result)
                .expectNext("customer/products/favourites")
                .verifyComplete();

        assertEquals(filter, model.getAttribute("filter"));
        assertEquals(List.of(products.get(1), products.get(2)), model.getAttribute("products"));

        verify(this.favouriteProductsClient).findFavouriteProducts();
        verifyNoMoreInteractions(this.favouriteProductsClient);
        verify(this.productsClient).findAllProducts(filter);
        verifyNoMoreInteractions(this.productsClient);
    }

    @Test
    void getFavouriteProductsPage_ThereAreNoFavouriteProducts_ReturnsEmptyProductsList() {
        // given
        var model = new ConcurrentModel();

        doReturn(Flux.empty()).when(this.favouriteProductsClient).findFavouriteProducts();
        doReturn(Flux.just(new Product(1, "Товар №1", "Описание товара №1")))
                .when(this.productsClient).findAllProducts(null);

        // when
        var result = this.controller.getFavouriteProductsPage(model, null);

        // then
        StepVerifier.create(result)
                .expectNext("customer/products/favourites")
                .verifyComplete();

        assertNull(model.getAttribute("filter"));
        assertEquals(List.of(), model.getAttribute("products"));

        verify(this.favouriteProductsClient).findFavouriteProducts();
        verifyNoMoreInteractions(this.favouriteProductsClient);
        verify(this.productsClient).findAllProducts(null);
        verifyNoMoreInteractions(this.productsClient);
    }

    @Test
    void getFavouriteProductsPage_FavouriteProductIsNotInCatalogue_ReturnsEmptyProductsList() {
        // given
        var model = new ConcurrentModel();
        var favouriteProduct = new FavouriteProduct(UUID.randomUUID(), 99);
        var products = List.of(
                new Product(1, "Товар №1", "Описание товара №1"),
                new Product(2, "Товар №2", "Описание товара №2"));

        doReturn(Flux.just(favouriteProduct))
                .when(this.favouriteProductsClient).findFavouriteProducts();
        doReturn(Flux.fromIterable(products)).when(this.productsClient).findAllProducts(null);

        // when
        var result = this.controller.getFavouriteProductsPage(model, null);

        // then
        StepVerifier.create(result)
                .expectNext("customer/products/favourites")
                .verifyComplete();

        assertEquals(List.of(), model.getAttribute("products"));

        verify(this.favouriteProductsClient).findFavouriteProducts();
        verifyNoMoreInteractions(this.favouriteProductsClient);
        verify(this.productsClient).findAllProducts(null);
        verifyNoMoreInteractions(this.productsClient);
    }
}
