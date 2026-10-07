package com.kll.deliverydispatch.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.beans.TypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final ResponseEntity<ApiError> BAD_REQUEST = ResponseEntity.badRequest()
            .body(new ApiError(HttpStatus.BAD_REQUEST.value(), "请求参数格式不正确，请检查提交内容"));

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableMessage(HttpMessageNotReadableException exception) {
        return badRequest("请求内容格式错误，请检查 JSON 格式及字段类型");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return badRequest("请求参数[" + exception.getName() + "]格式不正确");
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(TypeMismatchException exception) {
        return BAD_REQUEST;
    }

    @ExceptionHandler(MissingRequestValueException.class)
    public ResponseEntity<ApiError> handleMissingRequestValue(MissingRequestValueException exception) {
        return badRequest("缺少必要的请求参数，请检查请求内容");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        if (fieldError == null) {
            return badRequest("请求字段校验失败，请检查提交内容");
        }

        String detail = fieldError.getDefaultMessage();
        String message = detail == null || detail.isBlank()
                ? "请求字段校验失败"
                : "字段[" + fieldError.getField() + "]" + detail;
        return badRequest(message);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> handleBindException(BindException exception) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        if (fieldError == null) {
            return badRequest("请求字段校验失败，请检查提交内容");
        }

        String detail = fieldError.getDefaultMessage();
        String message = detail == null || detail.isBlank()
                ? "字段[" + fieldError.getField() + "]格式不正确"
                : "字段[" + fieldError.getField() + "]" + detail;
        return badRequest(message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException exception) {
        return badRequest("请求参数校验失败，请检查提交内容");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException exception) {
        return badRequest("请求参数校验失败，请检查提交内容");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBusinessException(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(HttpStatus.BAD_REQUEST.value(), exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(Exception exception, HttpServletRequest request) {
        log.error("Unexpected exception while handling {} {}", request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(HttpStatus.INTERNAL_SERVER_ERROR.value(), "系统异常，请稍后重试"));
    }

    private ResponseEntity<ApiError> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(HttpStatus.BAD_REQUEST.value(), message));
    }
}
