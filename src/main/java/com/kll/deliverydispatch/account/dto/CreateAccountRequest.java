package com.kll.deliverydispatch.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank String role) {
}
