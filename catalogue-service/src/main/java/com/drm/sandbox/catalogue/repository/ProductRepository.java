package com.drm.sandbox.catalogue.repository;


import com.drm.sandbox.catalogue.entity.Product;
import org.springframework.data.repository.CrudRepository;

public interface ProductRepository
        extends CrudRepository<Product, Integer> {
    Iterable<Product> findAllByTitleLikeIgnoreCase(String filter);
}
