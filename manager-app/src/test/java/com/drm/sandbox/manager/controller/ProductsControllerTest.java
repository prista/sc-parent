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
import org.springframework.ui.ConcurrentModel;

import java.util.List;

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
    @DisplayName("createProduct creates a new product and redirects to the product page")
    void createProduct_RequestIsValid_ReturnsRedirectionToProductPage() {
        // given
        var payload = new NewProductPayload("Test Product", "Test Product Details");
        var model = new ConcurrentModel(); // to emulate the model when calling the controller method

        doReturn(new Product(1, "Test Product", "Test Product Details"))
                .when(productsRestClient).createProduct("Test Product", "Test Product Details");
        // when
        var result = this.underTest.createProduct(payload, model);
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

        doThrow(new BadRequestException(List.of("Error 1", "Error 2")))
                .when(productsRestClient).createProduct(" ", null);
        // when
        var result = this.underTest.createProduct(payload, model);
        // then
        assertEquals("catalogue/products/new_product", result);
        assertEquals(payload, model.getAttribute("payload"));
        assertEquals(List.of("Error 1", "Error 2"), model.getAttribute("errors"));

        verify(this.productsRestClient).createProduct(" ", null);
        verifyNoMoreInteractions(this.productsRestClient);
    }
}