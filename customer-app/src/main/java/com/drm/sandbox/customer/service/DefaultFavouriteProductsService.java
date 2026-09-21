package com.drm.sandbox.customer.service;

import com.drm.sandbox.customer.entity.FavouriteProduct;
import com.drm.sandbox.customer.repository.FavouriteProductRepository;
import reactor.core.publisher.Mono;

public class DefaultFavouriteProductsService implements FavouriteProductsService {

    private final FavouriteProductRepository favouriteProductRepository;

    @Override
    public Mono<FavouriteProduct> addProductToFavourites(final int productId) {
        return null;
    }

    @Override
    public Mono<Void> removeProductFromFavourites(final int productId) {
        return null;
    }
}
