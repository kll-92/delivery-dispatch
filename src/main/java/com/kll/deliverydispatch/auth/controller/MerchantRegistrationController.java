package com.kll.deliverydispatch.auth.controller;

import com.kll.deliverydispatch.auth.dto.RegisterMerchantRequest;
import com.kll.deliverydispatch.auth.dto.RegisterMerchantResponse;
import com.kll.deliverydispatch.auth.service.MerchantRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 公开注册入口。 */
@RestController
@RequestMapping("/api/v1/auth/register")
@RequiredArgsConstructor
public class MerchantRegistrationController {

    private final MerchantRegistrationService merchantRegistrationService;

    @PostMapping("/merchant")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterMerchantResponse registerMerchant(@Valid @RequestBody RegisterMerchantRequest request) {
        return merchantRegistrationService.register(request);
    }
}
