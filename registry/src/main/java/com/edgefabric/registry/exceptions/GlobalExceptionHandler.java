package com.edgefabric.registry.exceptions;
import com.edgefabric.registry.dto.ErrorResponses;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@RestControllerAdvice
public class GlobalExceptionHandler
{
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NodeAlreadyRegisteredException.class)
    public ResponseEntity<ErrorResponses> handleNodeAlreadyRegisteredException(
            NodeAlreadyRegisteredException e) {

        ErrorResponses error = new ErrorResponses(
                e.getMessage(),
                HttpStatus.CONFLICT.value()
        );

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error);
    }



    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponses> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException e
    ){
        ErrorResponses error = new ErrorResponses(
                "JSON parse error occured due to improper format",
                HttpStatus.BAD_REQUEST.value());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponses> handleConstraintViolationException(
            ConstraintViolationException ex) {

        String message = ex.getConstraintViolations()
                .stream()
                .map(v -> v.getMessage())
                .findFirst()
                .orElse("Validation error");

        ErrorResponses error = new ErrorResponses(
                message,
                HttpStatus.BAD_REQUEST.value()
        );

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponses> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException e) {

        ErrorResponses error = new ErrorResponses(
                e.getMessage(),
                HttpStatus.BAD_REQUEST.value()
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error);
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponses> handleException(Exception e) {

        logger.error("Unhandled exception occured", e);
        ErrorResponses error = new ErrorResponses(
                "Internal Server Error",
                HttpStatus.INTERNAL_SERVER_ERROR.value()
        );

        return  ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error);
    }

}