package com.kll.deliverydispatch.account.dto;

import java.time.LocalDateTime;

public record AccountResponse(Long id, String username, String role, boolean enabled,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
}
