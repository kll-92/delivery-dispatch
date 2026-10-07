package com.kll.deliverydispatch.account.dto;

import jakarta.validation.constraints.NotNull;

public record SetAccountEnabledRequest(@NotNull Boolean enabled) {
}
