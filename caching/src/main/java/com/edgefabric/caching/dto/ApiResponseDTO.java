package com.edgefabric.caching.dto;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ApiResponseDTO {

    private String timestamp;
    private int status;
    private boolean success;
    private String message;
    private String key;
    private Long expiresAt;

    public static ApiResponseDTO success(String key, String message) {
        return ApiResponseDTO.builder()
                .timestamp(Instant.now().toString())
                .status(200)
                .success(true)
                .message(message)
                .key(key)
                .build();
    }

    public static ApiResponseDTO error(int status, String message) {
        return ApiResponseDTO.builder()
                .timestamp(Instant.now().toString())
                .status(status)
                .success(false)
                .message(message)
                .key(null)
                .build();
    }
}