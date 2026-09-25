package com.drm.sandbox.customer.service;

import com.drm.sandbox.customer.entity.FavouriteProduct;
import com.drm.sandbox.customer.repository.FavouriteProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultFavouriteProductsService implements FavouriteProductsService {

    private final FavouriteProductRepository favouriteProductRepository;

    @Override
    public Mono<FavouriteProduct> addProductToFavourites(int productId) {
        return this.favouriteProductRepository.save(new FavouriteProduct(UUID.randomUUID(), productId));
    }

//    @Override
//    public Mono<FavouriteProduct> addProductToFavourites(final int productId) {
//        return this.favouriteProductRepository.findByProductId(productId)
//                .switchIfEmpty(this.favouriteProductRepository.save(new FavouriteProduct(UUID.randomUUID(), productId)));
//    }

    @Override
    public Mono<Void> removeProductFromFavourites(final int productId) {
        return this.favouriteProductRepository.deleteByProductId(productId);
    }

    @Override
    public Mono<FavouriteProduct> findFavouriteProductByProduct(int productId) {
        return this.favouriteProductRepository.findByProductId(productId);
    }
}
