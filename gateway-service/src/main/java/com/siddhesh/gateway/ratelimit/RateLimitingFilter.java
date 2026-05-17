package com.siddhesh.gateway.ratelimit;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RedisSlidingWindowRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;

    public RateLimitingFilter(RedisSlidingWindowRateLimiter rateLimiter, MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        Timer.Sample latencySample = Timer.start(meterRegistry);

        try {
            if (!rateLimiter.isAllowed(getClientIp(request))) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("text/plain");
                response.getWriter().write("Too many requests. Please try again later.");

                meterRegistry.counter("gateway.requests.rate_limited", requestTags(request, response)).increment();
                return;
            }

            filterChain.doFilter(request, response);
        } finally {
            Tags tags = requestTags(request, response);
            meterRegistry.counter("gateway.requests.total", tags).increment();
            latencySample.stop(Timer.builder("gateway.request.latency")
                    .description("Gateway request latency including Redis rate-limit checks")
                    .tags(tags)
                    .register(meterRegistry));
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || !path.startsWith("/api/");
    }

    private String getClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private Tags requestTags(HttpServletRequest request, HttpServletResponse response) {
        return Tags.of(
                "method", request.getMethod(),
                "uri", getUriTag(request),
                "status", String.valueOf(response.getStatus()),
                "outcome", getOutcome(response.getStatus()));
    }

    private String getUriTag(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/products")) {
            return "/api/products/**";
        }
        if (path.startsWith("/api/orders")) {
            return "/api/orders/**";
        }
        return "/api/**";
    }

    private String getOutcome(int status) {
        if (status >= 200 && status < 300) {
            return "success";
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return "rate_limited";
        }
        if (status >= 400 && status < 500) {
            return "client_error";
        }
        if (status >= 500) {
            return "server_error";
        }
        return "unknown";
    }
}
