package com.example.urlshortener.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ShortenRequest(
        @NotBlank @Size(max = 2048) String url,
        @Pattern(regexp = "^[A-Za-z0-9_-]{3,32}$", message = "alias must be 3-32 chars: letters, digits, _ or -")
        String customAlias,
        @Min(value = 60, message = "ttlSeconds must be at least 60") Long ttlSeconds) {
}
