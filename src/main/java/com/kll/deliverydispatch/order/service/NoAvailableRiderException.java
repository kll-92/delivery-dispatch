package com.kll.deliverydispatch.order.service;

/** No currently eligible rider was found; a later recovery attempt may succeed. */
public class NoAvailableRiderException extends IllegalArgumentException {
    public NoAvailableRiderException() {
        super("附近暂无可用骑手");
    }
}
