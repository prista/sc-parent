package com.drm.sandbox.customer.client;

import com.drm.sandbox.customer.client.exception.ClientBadRequestException;
import com.drm.sandbox.customer.client.payload.NewProductReviewPayload;
import com.drm.sandbox.customer.entity.ProductReview;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RequiredArgsConstructor
public class WebClientProductReviewsClient implements ProductReviewsClient {

    private final WebClient webClient;

    @Override
    public Flux<ProductReview> findProductReviewsByProductId(int productId) {
        return this.webClient
                .get()
                .uri("/api/v1/feedback-api/product-reviews/by-product-id/{productId}", productId)
                .retrieve()
                .bodyToFlux(ProductReview.class);
    }

    @Override
    public Mono<ProductReview> createProductReview(int productId, int rating, String review) {
        return this.webClient
                .post()
                .uri("/api/v1/feedback-api/product-reviews")
                .bodyValue(new NewProductReviewPayload(productId, rating, review)) // we're building body here
                .retrieve()
                .bodyToMono(ProductReview.class)
                .onErrorMap(WebClientResponseException.BadRequest.class,
                        exception -> new ClientBadRequestException(exception,
                                ((List<String>) exception.getResponseBodyAs(ProblemDetail.class)
                                        .getProperties().get("errors"))));
    }
}
