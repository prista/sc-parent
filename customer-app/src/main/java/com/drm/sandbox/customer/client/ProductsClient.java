package com.drm.sandbox.customer.client;

import com.drm.sandbox.customer.entity.Product;
import reactor.core.publisher.Flux;

public interface ProductsClient {

    Flux<Product> findAllProducts(String filter);


}
