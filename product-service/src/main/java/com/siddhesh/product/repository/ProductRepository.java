package com.siddhesh.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.siddhesh.product.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
