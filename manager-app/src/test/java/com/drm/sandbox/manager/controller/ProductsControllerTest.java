package com.drm.sandbox.manager.controller;

import com.drm.sandbox.manager.client.BadRequestException;
import com.drm.sandbox.manager.client.ProductsRestClient;
import com.drm.sandbox.manager.controller.payload.NewProductPayload;
import com.drm.sandbox.manager.entity.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ConcurrentModel;

import java.security.Principal;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit tests for ProductsController")
class ProductsControllerTest {

    @Mock
    ProductsRestClient productsRestClient;
    @InjectMocks
    ProductsController underTest;

    @Test
    void getProductsList_ReturnsProductsListPage() {
        // given
        var model = new ConcurrentModel();
        var filter = "товар";
        var principal = mock(Principal.class);

        var products = IntStream.range(1, 4)
                .mapToObj(i -> new Product(i, "Prod №%d".formatted(i),
                        "Description of product №%d".formatted(i)))
                .toList();

        doReturn(products).when(this.productsRestClient).findAllProducts(filter);

        // when
        var result = this.underTest.getProductsList(model, filter, principal);

        // then
        assertEquals("catalogue/products/list", result);
        assertEquals(filter, model.getAttribute("filter"));
        assertEquals(products, model.getAttribute("products"));
    }

    @Test
    void getNewProductPage_ReturnsNewProductPage () {
        // given

        // when
        var result = this.underTest.getNewProductPage();

        // then
        assertEquals("catalogue/products/new_product", result);
    }

    @Test
    @DisplayName("createProduct creates a new product and redirects to the product page")
    void createProduct_RequestIsValid_ReturnsRedirectionToProductPage() {
        // given
        var payload = new NewProductPayload("Test Product", "Test Product Details");
        var model = new ConcurrentModel(); // to emulate the model when calling the controller method
        var response = new MockHttpServletResponse();

        doReturn(new Product(1, "Test Product", "Test Product Details"))
                .when(productsRestClient).createProduct("Test Product", "Test Product Details");
        // when
        var result = this.underTest.createProduct(payload, model, response);
        // then
        assertEquals("redirect:/catalogue/products/1", result);
        verify(this.productsRestClient).createProduct("Test Product", "Test Product Details");
        verifyNoMoreInteractions(this.productsRestClient);
    }

    @Test
    @DisplayName("createProduct returns error page, if the request is invalid")
    void createProduct_RequestIsInvalid_ReturnsProductFormWithErrors() {
        // given
        var payload = new NewProductPayload(" ", null);
        var model = new ConcurrentModel(); // to emulate the model when calling the controller method
        var response = new MockHttpServletResponse();

        doThrow(new BadRequestException(List.of("Error 1", "Error 2")))
                .when(productsRestClient).createProduct(" ", null);
        // when
        var result = this.underTest.createProduct(payload, model, response);
        // then
        assertEquals("catalogue/products/new_product", result);
        assertEquals(payload, model.getAttribute("payload"));
        assertEquals(List.of("Error 1", "Error 2"), model.getAttribute("errors"));
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatus());

        verify(this.productsRestClient).createProduct(" ", null);
        verifyNoMoreInteractions(this.productsRestClient);
    }
}