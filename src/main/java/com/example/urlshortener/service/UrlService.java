package com.example.urlshortener.service;

import com.example.urlshortener.config.ShortenerProperties;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.dto.StatsResponse;
import com.example.urlshortener.exception.ApiException;
import com.example.urlshortener.util.Base62;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Redis data model:
 *   counter:url-id  -> STRING (INCR) global ID generator, Base62-encoded into the short code
 *   url:{code}      -> HASH { longUrl, createdAt, clicks } with a TTL
 */
@Service
public class UrlService {

    private static final String COUNTER_KEY = "counter:url-id";
    private static final String KEY_PREFIX = "url:";
    // Offset so generated codes start at 6 characters instead of 1.
    private static final long ID_OFFSET = 1_000_000_000L;

    private final StringRedisTemplate redis;
    private final ShortenerProperties props;

    public UrlService(StringRedisTemplate redis, ShortenerProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public ShortenResponse shorten(ShortenRequest req) {
        String longUrl = normalize(req.url());
        Duration ttl = req.ttlSeconds() != null ? Duration.ofSeconds(req.ttlSeconds()) : props.defaultTtl();

        String code;
        if (req.customAlias() != null && !req.customAlias().isBlank()) {
            code = req.customAlias();
            if (!tryCreate(code, longUrl, ttl)) {
                throw new ApiException(HttpStatus.CONFLICT, "Alias already in use: " + code);
            }
        } else {
            do {
                long id = redis.opsForValue().increment(COUNTER_KEY) + ID_OFFSET;
                code = Base62.encode(id);
            } while (!tryCreate(code, longUrl, ttl));
        }
        return new ShortenResponse(code, props.baseUrl() + "/" + code, longUrl, Instant.now().plus(ttl));
    }

    /** Returns the original URL and records a click. */
    public String resolve(String code) {
        String key = KEY_PREFIX + code;
        Object longUrl = redis.opsForHash().get(key, "longUrl");
        if (longUrl == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Short URL not found or expired: " + code);
        }
        redis.opsForHash().increment(key, "clicks", 1);
        return longUrl.toString();
    }

    public StatsResponse stats(String code) {
        String key = KEY_PREFIX + code;
        Map<Object, Object> h = redis.opsForHash().entries(key);
        if (h.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Short URL not found or expired: " + code);
        }
        Long ttl = redis.getExpire(key);
        return new StatsResponse(
                code,
                (String) h.get("longUrl"),
                Instant.parse((String) h.get("createdAt")),
                Long.parseLong((String) h.getOrDefault("clicks", "0")),
                ttl);
    }

    /** Atomically claims the code (HSETNX). Returns false if it already exists. */
    private boolean tryCreate(String code, String longUrl, Duration ttl) {
        String key = KEY_PREFIX + code;
        Boolean created = redis.opsForHash().putIfAbsent(key, "longUrl", longUrl);
        if (!Boolean.TRUE.equals(created)) {
            return false;
        }
        redis.opsForHash().put(key, "createdAt", Instant.now().toString());
        redis.opsForHash().put(key, "clicks", "0");
        redis.expire(key, ttl);
        return true;
    }

    private String normalize(String raw) {
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "url must be a valid http(s) URL");
        }
    }
}
