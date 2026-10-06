package com.drm.sandbox.feedback.repository;

import com.drm.sandbox.feedback.entity.FavouriteProduct;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface FavouriteProductRepository
        extends ReactiveCrudRepository<FavouriteProduct, UUID> {

    Mono<Void> deleteByProductId(int productId);

    Mono<FavouriteProduct> findByProductId(int productId);

}
