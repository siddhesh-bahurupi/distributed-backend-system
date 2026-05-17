package com.siddhesh.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.siddhesh.order.entity.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
