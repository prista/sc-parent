package com.drm.sandbox.customer.service;

import com.drm.sandbox.customer.entity.FavouriteProduct;
import reactor.core.publisher.Mono;

public interface FavouriteProductsService {

    Mono<FavouriteProduct> addProductToFavourites(int productId);

    Mono<Void> removeProductFromFavourites(int productId);

}