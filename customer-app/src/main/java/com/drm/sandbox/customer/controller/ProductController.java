package com.drm.sandbox.customer.controller;

import com.drm.sandbox.customer.client.ProductsClient;
import com.drm.sandbox.customer.entity.Product;
import com.drm.sandbox.customer.service.FavouriteProductsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;


@Controller
@RequiredArgsConstructor
@RequestMapping("customer/products/{productId:\\d+}")
public class ProductController {

    private final ProductsClient productsClient;

    private final FavouriteProductsService favouriteProductsService;

    // Loads the product once for every handler in this controller, so each
    // method does not have to fetch it (and handle a missing product) itself.
    @ModelAttribute(name = "product", binding = false)
    public Mono<Product> loadProduct(@PathVariable("productId") int id) {
        return this.productsClient.findProduct(id);
    }

    @GetMapping
    public Mono<String> getProductPage(@PathVariable("productId") int id, Model model) {
        model.addAttribute("inFavourite", false);
        return this.favouriteProductsService.findFavouriteProductByProduct(id)
                .doOnNext(favouriteProduct -> model.addAttribute("inFavourite", true))
                .thenReturn("customer/products/product");
    }

    @PostMapping("add-to-favourites")
    public Mono<String> addProductToFavourites(@ModelAttribute("product") Mono<Product> productMono) {
        return productMono
                .map(Product::id)                                   // Mono<Product> -> Mono<Integer>
                .flatMap(productId -> this.favouriteProductsService.addProductToFavourites(productId)   // lambda returns Mono<String>
                        .thenReturn("redirect:/customer/products/%d".formatted(productId)));
    }

    @PostMapping("remove-from-favourites")
    public Mono<String> removeProductFromFavourites(@ModelAttribute("product") Mono<Product> productMono) {
        return productMono
                .map(Product::id)                                   // Mono<Product> -> Mono<Integer>
                .flatMap(productId -> this.favouriteProductsService.removeProductFromFavourites(productId)   // lambda returns Mono<String>
                        .thenReturn("redirect:/customer/products/%d".formatted(productId)));
    }
}
