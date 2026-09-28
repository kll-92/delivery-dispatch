package com.kll.deliverydispatch.rider.enums;

public enum RiderStatus {
    AVAILABLE(1, "可接单"),
    DISABLED(3, "已停用");

    private final int code;
    private final String description;

    RiderStatus(int code, String description) {
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
