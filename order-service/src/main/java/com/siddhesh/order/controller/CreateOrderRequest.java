package com.siddhesh.order.controller;

public record CreateOrderRequest(Long productId, Integer quantity) {
}
