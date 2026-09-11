package com.drm.sandbox.catalogue.controller;

import com.drm.sandbox.catalogue.controller.payload.NewProductPayload;
import com.drm.sandbox.catalogue.entity.Product;
import com.drm.sandbox.catalogue.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductsRestControllerTest {

    @Mock
    ProductService productService;

    @InjectMocks
    ProductsRestController underTest;

    @Test
    void findProduct_ReturnsProductsList() {
        // given
        var filter = "product";
        var principal = new JwtAuthenticationToken(Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("email", "j.dewar@example.com")
                .build());

        doReturn(List.of(new Product(1, "First Product", "Description of the first product"),
                new Product(2, "Second Product", "Description of the second product")))
                .when(this.productService).findAllProducts("product");

        // when
        var result = this.underTest.findProducts(filter, principal);

        // then
        assertEquals(List.of(new Product(1, "First Product", "Description of the first product"),
                new Product(2, "Second Product", "Description of the second product")), result);
    }

    @Test
    void createProduct_RequestIsValid_ReturnsNoContent() throws BindException {
        // given
        var payload = new NewProductPayload("New Product", "New Product Description");
        var bindingResult = new MapBindingResult(Map.of(), "payload");
        var uriComponentsBuilder = UriComponentsBuilder.fromUriString("http://localhost");

        doReturn(new Product(1, "New Product", "New Product Description"))
                .when(this.productService).createProduct("New Product", "New Product Description");

        // when
        var result = this.underTest.createProduct(payload, bindingResult, uriComponentsBuilder);

        // then
        assertNotNull(result);
        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(URI.create("http://localhost/catalogue-api/products/1"), result.getHeaders().getLocation());
        assertEquals(new Product(1, "New Product", "New Product Description"), result.getBody());

        verify(this.productService).createProduct("New Product", "New Product Description");
        verifyNoMoreInteractions(this.productService);
    }

    @Test
    void createProduct_RequestIsInvalid_ReturnsBadRequest() {
        // given
        var payload = new NewProductPayload("   ", null);
        var bindingResult = new MapBindingResult(Map.of(), "payload");
        bindingResult.addError(new FieldError("payload", "title", "error"));
        var uriComponentsBuilder = UriComponentsBuilder.fromUriString("http://localhost");

        // when
        var exception = assertThrows(BindException.class,
                () -> this.underTest.createProduct(payload, bindingResult, uriComponentsBuilder));

        // then
        assertEquals(List.of(new FieldError("payload", "title", "error")), exception.getAllErrors());
        verifyNoInteractions(this.productService);
    }

    @Test
    void createProduct_RequestIsInvalidAndBindResultIsBindException_ReturnsBadRequest() {
        // given
        var payload = new NewProductPayload("   ", null);
        var bindingResult = new BindException(new MapBindingResult(Map.of(), "payload"));
        bindingResult.addError(new FieldError("payload", "title", "error"));
        var uriComponentsBuilder = UriComponentsBuilder.fromUriString("http://localhost");

        // when
        var exception = assertThrows(BindException.class,
                () -> this.underTest.createProduct(payload, bindingResult, uriComponentsBuilder));

        // then
        assertEquals(List.of(new FieldError("payload", "title", "error")), exception.getAllErrors());
        verifyNoInteractions(this.productService);
    }
}
