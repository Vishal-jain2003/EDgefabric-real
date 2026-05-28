package com.edgefabric.loadbalancer.exception;

import com.edgefabric.loadbalancer.config.CacheProperties;
import com.edgefabric.loadbalancer.dto.response.ErrorResponse;
import com.edgefabric.loadbalancer.util.StructuredLogContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final CacheProperties cacheProperties;

    public GlobalExceptionHandler(CacheProperties cacheProperties) {
        this.cacheProperties = cacheProperties;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxSizeException(MaxUploadSizeExceededException exc) {
        try (var logCtx = StructuredLogContext.create()
                .errorType("MaxUploadSizeExceededException")
                .errorMessage(exc.getMessage())
                .statusCode(HttpStatus.PAYLOAD_TOO_LARGE.value())) {
            LOGGER.warn("Upload rejected: File size exceeds limit", exc);
        }

        long maxSizeBytes = cacheProperties.getMaxCacheEntrySizeBytes();
        String maxSizeFormatted = formatBytes(maxSizeBytes);
        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.PAYLOAD_TOO_LARGE)
                .message("File too large. Maximum allowed size is " + maxSizeFormatted + ".")
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error);
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return (bytes / (1024 * 1024)) + "MB";
        } else if (bytes >= 1024) {
            return (bytes / 1024) + "KB";
        }
        return bytes + "B";
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        LOGGER.warn("Request body validation failed: {}", ex.getMessage());

        Map<String, Object> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.INVALID_INPUT)
                .message("Validation failed for request body.")
                .details(errors)
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex) {

        LOGGER.warn("Request rejected due to invalid input argument: {}",
                ex.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.INVALID_INPUT)
                .message(ex.getMessage() != null
                        ? ex.getMessage()
                        : "Invalid input provided.")
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }


    @ExceptionHandler(CacheValidationException.class)
    public ResponseEntity<ErrorResponse> handleCacheValidationException(CacheValidationException ex){
        LOGGER.warn("Cache validation failed. Actual: {}, Max Allowed: {}",
                ex.getActualValue(),
                ex.getMaxAllowedValue());

        Map<String, Object> details = Map.of(
                "actualValue", ex.getActualValue(),
                "maxAllowedValue", ex.getMaxAllowedValue()
        );

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.PAYLOAD_TOO_LARGE)
                .message(ex.getMessage())
                .details(details)
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex) {

        String paramName = ex.getName();
        Object value = ex.getValue();
        String requiredType = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName()
                : "unknown";

        LOGGER.warn("Request rejected: parameter '{}' with value '{}' is not of expected type '{}'.",
                paramName, value, requiredType);

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.TYPE_MISMATCH)
                .message("Invalid parameter type.")
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleHttpMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException ex){
        LOGGER.warn("Request rejected due to unsupported Content-Type. Provided: {}. {}",
                ex.getContentType(), ex.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.UNSUPPORTED_MEDIA_TYPE)
                .message("Unsupported Content-Type. Please use a supported media type.")
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(error);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex) {

        LOGGER.warn("Constraint validation failed: {}", ex.getMessage());

        Map<String, Object> errors = new HashMap<>();

        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String fieldName = violation.getPropertyPath().toString();
            String message = violation.getMessage();
            errors.put(fieldName, message);
        }

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.INVALID_INPUT)
                .message("Validation failed for request parameters.")
                .details(errors)
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        LOGGER.warn("No handler found: {}", ex.getMessage());
        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.NOT_FOUND)
                .message("The requested resource was not found.")
                .timestamp(Instant.now())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception exc) {
        LOGGER.error("Internal Server Error", exc);
        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.INTERNAL_ERROR)
                .message("An unexpected error occurred.")
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error);
    }
    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    public ResponseEntity<ErrorResponse> handleHttpClientNotFound(HttpClientErrorException.NotFound ex) {
        LOGGER.warn("Cache entry not found on node: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.NOT_FOUND)
                .message("Cache entry not found")
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error);
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResponse> handleRestClientException(RestClientException ex) {
        LOGGER.error("Failed to communicate with cache node: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.SERVICE_UNAVAILABLE)
                .message("Failed to communicate with cache node")
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error);
    }

    @ExceptionHandler(QuorumNotMetException.class)
    public ResponseEntity<ErrorResponse> handleQuorumNotMetException(QuorumNotMetException ex) {
        LOGGER.error("Quorum not met: {}", ex.getMessage());

        Map<String, Object> details = Map.of(
                "operation", ex.getOperation(),
                "required", ex.getRequired(),
                "achieved", ex.getAchieved()
        );

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.QUORUM_NOT_MET)
                .message(ex.getMessage())
                .details(details)
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error);
    }

    @ExceptionHandler(CacheKeyNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCacheKeyNotFoundException(CacheKeyNotFoundException ex) {
        LOGGER.warn("Cache key not found: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.NOT_FOUND)
                .message(ex.getMessage())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error);
    }

    @ExceptionHandler(CacheKeyExpiredException.class)
    public ResponseEntity<ErrorResponse> handleCacheKeyExpiredException(CacheKeyExpiredException ex) {
        LOGGER.warn("Cache key expired: {}", ex.getMessage());

        Map<String, Object> details = new HashMap<>();
        details.put("key", ex.getKey());
        if (ex.getExpiresAt() > 0) {
            details.put("expiredAt", ex.getExpiresAt());
        }

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.GONE)
                .message("Cache key has expired")
                .details(details)
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.GONE)
                .body(error);
    }

    @ExceptionHandler(ClusterBootstrapException.class)
    public ResponseEntity<ErrorResponse> handleClusterBootstrapException(ClusterBootstrapException ex) {
        LOGGER.error("Cluster bootstrap failed: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.CLUSTER_BOOTSTRAP_FAILED)
                .message("Cluster bootstrap failed: " + ex.getMessage())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error);
    }

    @ExceptionHandler(ClusterCommunicationException.class)
    public ResponseEntity<ErrorResponse> handleClusterCommunicationException(ClusterCommunicationException ex) {
        LOGGER.error("Cluster communication failed: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.CLUSTER_COMMUNICATION_FAILED)
                .message("Cluster communication failed: " + ex.getMessage())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error);
    }

}



