package com.example.urlshortener.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        @DefaultValue("http://localhost:8080") String baseUrl,
        @DefaultValue("30d") Duration defaultTtl,
        @DefaultValue RateLimit rateLimit) {

    /** Fixed-window limits, per client IP, per minute. */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10") int shortenPerMinute,
            @DefaultValue("120") int redirectPerMinute) {
    }
}
