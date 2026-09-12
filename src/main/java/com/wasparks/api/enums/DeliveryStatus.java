package com.wasparks.api.enums;

/** FAILED is a single attempt that did not return 2xx; EXHAUSTED is the whole retry schedule spent. */
public enum DeliveryStatus {
    PENDING,
    SUCCESS,
    FAILED,
    EXHAUSTED
}
