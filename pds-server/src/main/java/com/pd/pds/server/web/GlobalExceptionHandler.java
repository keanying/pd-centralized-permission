package com.pd.pds.server.web;

import com.pd.pds.common.BizException;
import com.pd.pds.common.ErrorCode;
import com.pd.pds.common.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice(basePackages = "com.pd.pds.server.web")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> biz(BizException e) {
        HttpStatus status = switch (e.getErrorCode()) {
            case UNAUTHENTICATED, TOKEN_REVOKED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, PERMISSION_DENIED, RESOURCE_DENIED, SCENIC_DENIED, GRANT_EXCEEDS_OWN, SEPARATION_OF_DUTIES -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE, CONFLICT -> HttpStatus.CONFLICT;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(R.fail(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + "：" + f.getDefaultMessage())
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest().body(R.fail(ErrorCode.VALIDATION_FAILED, msg));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            IllegalArgumentException.class})
    public ResponseEntity<R<Void>> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(R.fail(ErrorCode.BAD_REQUEST, "请求参数错误：" + e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> denied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(ErrorCode.FORBIDDEN, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> other(Exception e) {
        log.error("未处理的异常", e);
        return ResponseEntity.internalServerError().body(R.fail(ErrorCode.INTERNAL_ERROR, null));
    }
}
