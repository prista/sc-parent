package com.drm.sandbox.customer.controller.payload;

public record NewProductReviewPayload(
        Integer rating,
        String review) {
}
