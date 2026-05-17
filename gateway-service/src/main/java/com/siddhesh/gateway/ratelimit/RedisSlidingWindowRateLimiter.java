package com.siddhesh.gateway.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RedisSlidingWindowRateLimiter {

    private static final int MAX_REQUESTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;

    public RedisSlidingWindowRateLimiter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = new DefaultRedisScript<>(buildRateLimitScript(), Long.class);
    }

    public boolean isAllowed(String clientIp) {
        long now = System.currentTimeMillis();
        String redisKey = "rate_limit:ip:" + clientIp;
        String requestId = now + ":" + UUID.randomUUID();

        Long allowed = redisTemplate.execute(
                rateLimitScript,
                List.of(redisKey),
                String.valueOf(now - WINDOW.toMillis()),
                String.valueOf(now),
                requestId,
                String.valueOf(MAX_REQUESTS),
                String.valueOf(WINDOW.toSeconds()));

        return Long.valueOf(1L).equals(allowed);
    }

    private String buildRateLimitScript() {
        return """
                redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])

                local request_count = redis.call('ZCARD', KEYS[1])
                if request_count >= tonumber(ARGV[4]) then
                    return 0
                end

                redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
                return 1
                """;
    }
}
