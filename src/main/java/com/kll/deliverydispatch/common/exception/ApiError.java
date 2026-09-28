package com.kll.deliverydispatch.common.exception;

public record ApiError(int status, String message) {
}
