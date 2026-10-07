package com.kll.deliverydispatch.account.controller;

import com.kll.deliverydispatch.account.dto.AccountResponse;
import com.kll.deliverydispatch.account.dto.CreateAccountRequest;
import com.kll.deliverydispatch.account.dto.SetAccountEnabledRequest;
import com.kll.deliverydispatch.account.service.AccountService;
import com.kll.deliverydispatch.security.AccountPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {
    private final AccountService accountService;

    @GetMapping
    public List<AccountResponse> list() {
        return accountService.list();
    }

    @PostMapping
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        return accountService.create(request);
    }

    @PatchMapping("/{id}/enabled")
    public AccountResponse setEnabled(@PathVariable Long id,
                                      @Valid @RequestBody SetAccountEnabledRequest request,
                                      Authentication authentication) {
        AccountPrincipal principal = (AccountPrincipal) authentication.getPrincipal();
        return accountService.setEnabled(id, request.enabled(), principal.accountId());
    }
}
