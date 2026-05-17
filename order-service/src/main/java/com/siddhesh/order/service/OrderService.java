package com.siddhesh.order.service;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.siddhesh.order.client.ProductClient;
import com.siddhesh.order.client.ProductClient.ProductDetails;
import com.siddhesh.order.controller.CreateOrderRequest;
import com.siddhesh.order.entity.Order;
import com.siddhesh.order.repository.OrderRepository;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductClient productClient;

    public OrderService(OrderRepository orderRepository, ProductClient productClient) {
        this.orderRepository = orderRepository;
        this.productClient = productClient;
    }

    public List<Order> getOrders() {
        return orderRepository.findAll();
    }

    public Order createOrder(CreateOrderRequest request) {
        if (request.quantity() == null || request.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be greater than 0");
        }

        ProductDetails product = productClient.getProduct(request.productId());
        if (product.inventory() == null || product.inventory() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product is out of stock");
        }

        if (request.quantity() > product.inventory()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Requested quantity exceeds inventory");
        }

        Order order = new Order(request.productId(), request.quantity(), Instant.now());
        return orderRepository.save(order);
    }
}
