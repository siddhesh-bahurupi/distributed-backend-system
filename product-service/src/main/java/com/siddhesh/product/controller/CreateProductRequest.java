package com.siddhesh.product.controller;

import java.math.BigDecimal;

public record CreateProductRequest(String name, BigDecimal price, Integer inventory) {
}
