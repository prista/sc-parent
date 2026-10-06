package com.drm.sandbox.feedback.controller;

import com.drm.sandbox.feedback.controller.payload.NewProductReviewPayload;
import com.drm.sandbox.feedback.entity.ProductReview;
import com.drm.sandbox.feedback.service.ProductReviewsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@RestController
@RequestMapping("/api/v1/feedback-api/product-reviews")
@RequiredArgsConstructor
public class ProductReviewsRestController {

    private final ProductReviewsService productReviewsService;
    private final ReactiveMongoTemplate reactiveMongoTemplate;

    // The \\d+ regex restricts the path variable to digits,
    // so non-numeric IDs fail to match the route (404) instead of hitting type conversion (400).
    @GetMapping("by-product-id/{productId:\\d+}")
    public Flux<ProductReview> findProductReviewsByProductId(@PathVariable("productId") int productId) {
        //return this.productReviewsService.findProductReviewsByProduct(productId);
        return reactiveMongoTemplate
                .find(query(where("productId").is(productId)),
                        ProductReview.class);
    }

    @PostMapping
    public Mono<ResponseEntity<ProductReview>> createProductReview(
            @Valid @RequestBody Mono<NewProductReviewPayload> payloadMono,
            UriComponentsBuilder uriComponentsBuilder) {
        return payloadMono
                .flatMap(payload -> this.productReviewsService.createProductReview(payload.productId(),
                        payload.rating(), payload.review()))
                .map(productReview -> ResponseEntity
                        .created(uriComponentsBuilder.replacePath("/api/v1/feedback-api/product-reviews/{id}")
                                .build(productReview.getId()))
                        .body(productReview));
    }

}
