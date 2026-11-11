package com.kll.deliverydispatch.common.exception;

/** 业务冲突，映射为 HTTP 409。 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
