package com.unicarona.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> application(ApiException error, HttpServletRequest request) {
        return response(error.status(), error.code(), error.getMessage(), error.details(), request);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException error, HttpServletRequest request) {
        var fields = error.getBindingResult().getFieldErrors().stream()
            .map(field -> Map.of("field", field.getField(), "message", field.getDefaultMessage() == null ? "Campo inválido." : field.getDefaultMessage())).toList();
        return response(422, "VALIDATION_ERROR", "Confira os campos informados.", Map.of("fields", fields), request);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalidBody(HttpServletRequest request) {
        return response(422, "VALIDATION_ERROR", "Confira os campos informados.", Map.of(), request);
    }
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidParameter(HttpServletRequest request) {
        return response(422,"VALIDATION_ERROR","Confira os campos informados.",Map.of(),request);
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> accessDenied(HttpServletRequest request) {
        return response(403,"FORBIDDEN","Acesso administrativo necessário.",Map.of(),request);
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge(HttpServletRequest request) {
        return response(413,"INVALID_REQUEST","Requisição inválida.",Map.of(),request);
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> invalidMediaType(HttpServletRequest request) {
        return response(415,"INVALID_REQUEST","Requisição inválida.",Map.of(),request);
    }
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ApiError> invalidMultipart(HttpServletRequest request) {
        return response(400,"INVALID_REQUEST","Requisição inválida.",Map.of(),request);
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(HttpServletRequest request) {
        return response(404, "NOT_FOUND", "Recurso não encontrado.", Map.of(), request);
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> database(DataAccessException error, HttpServletRequest request) {
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof SQLException)) cause = cause.getCause();
        String state = cause instanceof SQLException sql ? sql.getSQLState() : "";
        return switch (state == null ? "" : state) {
            case "23505" -> response(409, "CONFLICT", "Este registro já existe ou foi atualizado.", Map.of(), request);
            case "23514" -> response(409, "INVARIANT_VIOLATION", "A operação não respeita as regras de vagas ou estados.", Map.of(), request);
            case "57014", "55P03", "53300", "57P01", "57P02", "57P03", "08001", "08003", "08004", "08006" -> response(503, "SERVICE_UNAVAILABLE", "Serviço temporariamente indisponível. Tente novamente.", Map.of(), request);
            default -> response(500, "INTERNAL_ERROR", "Não foi possível concluir. Tente novamente.", Map.of(), request);
        };
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(HttpServletRequest request) {
        return response(500, "INTERNAL_ERROR", "Não foi possível concluir. Tente novamente.", Map.of(), request);
    }
    public static ResponseEntity<ApiError> response(int status, String code, String message, Map<String, Object> details, HttpServletRequest request) {
        var builder=ResponseEntity.status(status);
        if(status==429 && details.get("retryAfterSeconds") instanceof Integer seconds && seconds>0)
            builder.header("Retry-After",Integer.toString(seconds));
        return builder.body(new ApiError(code, message, details, String.valueOf(request.getAttribute("requestId"))));
    }
}
