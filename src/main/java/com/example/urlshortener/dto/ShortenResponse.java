package com.example.urlshortener.dto;

import java.time.Instant;

public record ShortenResponse(String code, String shortUrl, String longUrl, Instant expiresAt) {
}
