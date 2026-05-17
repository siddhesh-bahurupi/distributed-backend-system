package com.siddhesh.gateway.config;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
public class GatewayRoutesConfig {

    @Bean
    RouterFunction<ServerResponse> serviceRoutes(
            @Value("${services.product.url}") String productServiceUrl,
            @Value("${services.order.url}") String orderServiceUrl) {

        return route("product-service")
                .route(request -> request.path().startsWith("/api/products"), http())
                .before(uri(productServiceUrl))
                .build()
                .and(route("order-service")
                        .route(request -> request.path().startsWith("/api/orders"), http())
                        .before(uri(orderServiceUrl))
                        .build());
    }
}
