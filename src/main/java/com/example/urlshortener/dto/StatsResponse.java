package com.example.urlshortener.dto;

import java.time.Instant;

public record StatsResponse(String code, String longUrl, Instant createdAt, long clicks, Long expiresInSeconds) {
}
