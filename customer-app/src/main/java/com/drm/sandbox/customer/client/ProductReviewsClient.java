package com.drm.sandbox.customer.client;

import com.drm.sandbox.customer.entity.ProductReview;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProductReviewsClient {

    Flux<ProductReview> findProductReviewsByProductId(int productId);

    Mono<ProductReview> createProductReview(int productId, int rating, String review);
}
