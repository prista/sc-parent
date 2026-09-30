package com.drm.sandbox.feedback.controller;

import com.drm.sandbox.feedback.controller.payload.NewFavouriteProductPayload;
import com.drm.sandbox.feedback.entity.FavouriteProduct;
import com.drm.sandbox.feedback.service.FavouriteProductsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/feedback-api/favourite-products")
@RequiredArgsConstructor
public class FavouriteProductsRestController {
    private final FavouriteProductsService favouriteProductsService;

    @GetMapping
    public Flux<FavouriteProduct> findFavouriteProducts() {
        return this.favouriteProductsService.findFavouriteProducts();
    }

    @GetMapping("by-product-id/{productId:\\d+}")
    public Mono<FavouriteProduct> findFavouriteProductByProductId(@PathVariable("productId") int productId) {
        return this.favouriteProductsService.findFavouriteProductByProduct(productId);
    }

    @PostMapping
    public Mono<ResponseEntity<FavouriteProduct>> addProductToFavourites(
            @Valid @RequestBody Mono<NewFavouriteProductPayload> payloadMono,
            UriComponentsBuilder uriComponentsBuilder
    ) {
        return payloadMono                                                    // Mono<NewFavouriteProductPayload>
                .flatMap(payload ->                  // лямбда возвращает Mono<FavouriteProduct>
                        this.favouriteProductsService
                                .addProductToFavourites(payload.productId())) // -> Mono<FavouriteProduct>  (сплющили!)
                .map(favouriteProduct ->                       // -> Mono<ResponseEntity<FavouriteProduct>>
                        ResponseEntity
                                .created(uriComponentsBuilder.replacePath("feedback-api/favourite-products/{id}")
                                        .build(favouriteProduct.getId()))
                                .body(favouriteProduct));
    }

    @DeleteMapping("by-product-id/{productId:\\d+}")
    public Mono<ResponseEntity<Void>> removeProductFromFavourites(@PathVariable("productId") int productId) {
        return this.favouriteProductsService.removeProductFromFavourites(productId)
                .then(Mono.just(ResponseEntity.noContent().build()));
    }
}
