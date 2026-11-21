package com.example.urlshortener.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window rate limiter per client IP, backed by Redis.
 * The INCR + EXPIRE pair runs inside one Lua script so it is atomic.
 * If Redis is unreachable the filter fails open (requests are allowed).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final int WINDOW_SECONDS = 60;

    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    private final StringRedisTemplate redis;
    private final ShortenerProperties.RateLimit cfg;

    public RateLimitFilter(StringRedisTemplate redis, ShortenerProperties props) {
        this.redis = redis;
        this.cfg = props.rateLimit();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!cfg.enabled()) {
            chain.doFilter(req, res);
            return;
        }

        String bucket;
        int limit;
        if ("POST".equals(req.getMethod()) && "/api/v1/shorten".equals(req.getRequestURI())) {
            bucket = "shorten";
            limit = cfg.shortenPerMinute();
        } else if ("GET".equals(req.getMethod())) {
            bucket = "read";
            limit = cfg.redirectPerMinute();
        } else {
            chain.doFilter(req, res);
            return;
        }

        // Note: behind a reverse proxy, getRemoteAddr() is the proxy's IP.
        String key = "rl:" + bucket + ":" + req.getRemoteAddr();

        long count;
        try {
            Long c = redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(WINDOW_SECONDS));
            count = (c == null) ? 0 : c;
        } catch (Exception e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.toString());
            chain.doFilter(req, res);
            return;
        }

        res.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        res.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - count)));

        if (count > limit) {
            long retry = WINDOW_SECONDS;
            try {
                Long ttl = redis.getExpire(key);
                if (ttl != null && ttl > 0) retry = ttl;
            } catch (Exception ignored) {
                // keep default
            }
            res.setStatus(429);
            res.setHeader("Retry-After", String.valueOf(retry));
            res.setContentType("application/json");
            res.getWriter().write("{\"status\":429,\"error\":\"Too many requests. Retry in " + retry + " seconds\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
