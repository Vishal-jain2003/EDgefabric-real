package com.edgefabric.caching.exception;

import com.edgefabric.caching.dto.ApiResponseDTO;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 404 - Cache Not Found & Cache Expired
    @ExceptionHandler(CacheNotFoundException.class)
    public ResponseEntity<ApiResponseDTO> handleNotFound(CacheNotFoundException ex) {
        log.warn("Cache lookup failed: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponseDTO.error(
                        HttpStatus.NOT_FOUND.value(),
                        ex.getMessage()
                ));
    }

    // 400 - Missing X-Quorum-Version header (write bypassed quorum coordinator)
    @ExceptionHandler(MissingVersionException.class)
    public ResponseEntity<ApiResponseDTO> handleMissingVersion(MissingVersionException ex) {
        log.warn("Rejected write without quorum version: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.error(
                        HttpStatus.BAD_REQUEST.value(),
                        ex.getMessage()
                ));
    }

    // 410 - Cache Expired
    @ExceptionHandler(CacheExpiredException.class)
    public ResponseEntity<ApiResponseDTO> handleCacheExpired(CacheExpiredException ex) {
        log.warn("Cache entry expired: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.GONE)
                .body(ApiResponseDTO.error(
                        HttpStatus.GONE.value(),
                        ex.getMessage()
                ));
    }

    // 400 - Validation Error
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponseDTO> handleValidation(ConstraintViolationException ex) {
        log.warn("Validation error: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.error(
                        HttpStatus.BAD_REQUEST.value(),
                        "Validation Error: " + ex.getMessage()
                ));
    }

    // 400 - Illegal Argument
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponseDTO> handleIllegalArgs(IllegalArgumentException ex) {
        log.warn("Invalid input: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.error(
                        HttpStatus.BAD_REQUEST.value(),
                        ex.getMessage()
                ));
    }

    // 400 - Type Mismatch
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponseDTO> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch for parameter '{}': {}", ex.getName(), ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.error(
                        HttpStatus.BAD_REQUEST.value(),
                        "Invalid value for parameter: " + ex.getName()
                ));
    }

    // 404 - Endpoint Not Found
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponseDTO> handleNoHandlerFound(NoHandlerFoundException ex) {
        log.warn("Endpoint not found: {}", ex.getRequestURL());

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponseDTO.error(
                        HttpStatus.NOT_FOUND.value(),
                        "Endpoint not found"
                ));
    }

    // 500 - Generic Exception
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseDTO> handleGeneralException(Exception ex) {
        log.error("Unexpected error occurred", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseDTO.error(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Unexpected internal server error"
                ));
    }
}