package com.leasegrid.leasegrid.common;

import java.net.URI;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException e,HttpServletRequest request) {
        int status=e.getStatusCode().value();
        String code=switch(status) {
            case 404 -> "NOT_FOUND"; case 403 -> "FORBIDDEN"; case 401 -> "UNAUTHENTICATED";
            case 422 -> "LEASE_DURATION_LIMIT"; case 409 -> "LEASE_NOT_ACTIVE".equals(e.getReason())?"LEASE_NOT_ACTIVE":"CONFLICT";
            default -> "VALIDATION_ERROR";
        };
        return problem(status,code,request);
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,ConstraintViolationException.class})
    ProblemDetail invalid(Exception e,HttpServletRequest r) { return problem(400,"VALIDATION_ERROR",r); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(Exception e,HttpServletRequest r) { return problem(409,"CONFLICT",r); }
    @ExceptionHandler({DataAccessResourceFailureException.class,org.springframework.transaction.CannotCreateTransactionException.class})
    ProblemDetail unavailable(Exception e,HttpServletRequest r) { return problem(503,"SERVICE_UNAVAILABLE",r); }
    private ProblemDetail problem(int status,String code,HttpServletRequest request) {
        var detail=ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status),code);
        detail.setInstance(URI.create(request.getRequestURI())); detail.setProperty("code",code);
        detail.setProperty("traceId",UUID.randomUUID().toString()); return detail;
    }
}
