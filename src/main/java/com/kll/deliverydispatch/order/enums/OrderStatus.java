package com.kll.deliverydispatch.order.enums;

public enum OrderStatus {
    PENDING_DISPATCH(0, "待派单"),
    ASSIGNED(1, "已派单"),
    DELIVERING(2, "配送中"),
    COMPLETED(3, "已完成"),
    CANCELED(4, "已取消");

    private final int code;
    private final String description;

    OrderStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
