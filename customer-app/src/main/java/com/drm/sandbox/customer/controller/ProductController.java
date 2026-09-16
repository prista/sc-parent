package com.drm.sandbox.customer.controller;

import com.drm.sandbox.customer.client.ProductsClient;
import com.drm.sandbox.customer.entity.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Mono;


@Controller
@RequiredArgsConstructor
@RequestMapping("customer/products/{productId:\\d+}")
public class ProductController {

    private final ProductsClient productsClient;

    // Loads the product once for every handler in this controller, so each
    // method does not have to fetch it (and handle a missing product) itself.
    @ModelAttribute(name = "product", binding = false)
    public Mono<Product> loadProduct(@PathVariable("productId") int id) {
        return this.productsClient.findProduct(id);
    }

    @GetMapping
    public String getProductPage() {
        return "customer/products/product";
    }
}
