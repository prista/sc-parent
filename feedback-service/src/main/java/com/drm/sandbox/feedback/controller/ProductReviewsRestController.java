package com.drm.sandbox.feedback.controller;

import com.drm.sandbox.feedback.controller.payload.NewProductReviewPayload;
import com.drm.sandbox.feedback.entity.ProductReview;
import com.drm.sandbox.feedback.service.ProductReviewsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/feedback-api/product-reviews")
@RequiredArgsConstructor
public class ProductReviewsRestController {

    private final ProductReviewsService productReviewsService;

    // The \\d+ regex restricts the path variable to digits,
    // so non-numeric IDs fail to match the route (404) instead of hitting type conversion (400).
    @GetMapping("by-product-id/{productId:\\d+}")
    public Flux<ProductReview> findProductReviewsByProductId(@PathVariable("productId") int productId) {
        return this.productReviewsService.findProductReviewsByProduct(productId);
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

    // Shapes @Valid body validation failures (WebExchangeBindException, the WebFlux analogue of
    // MethodArgumentNotValidException) into a structured RFC 9457 problem+json response: 400 status
    // with an "errors" array of validation messages instead of Spring's default error body.
    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ProblemDetail>> handleWebExchangeBindException(WebExchangeBindException ex) {
        var problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problemDetail.setProperty("errors", ex.getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .toList());
        return Mono.just(ResponseEntity.badRequest()
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(problemDetail));
    }

}
