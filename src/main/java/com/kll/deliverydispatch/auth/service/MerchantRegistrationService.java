package com.kll.deliverydispatch.auth.service;

import com.kll.deliverydispatch.account.AccountRole;
import com.kll.deliverydispatch.account.entity.Account;
import com.kll.deliverydispatch.account.mapper.AccountMapper;
import com.kll.deliverydispatch.account.service.AccountService;
import com.kll.deliverydispatch.auth.dto.RegisterMerchantRequest;
import com.kll.deliverydispatch.auth.dto.RegisterMerchantResponse;
import com.kll.deliverydispatch.common.exception.ConflictException;
import com.kll.deliverydispatch.merchant.entity.Merchant;
import com.kll.deliverydispatch.merchant.mapper.MerchantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 商家自助注册：一个事务里同时建账号和店铺，任一步失败全部回滚。 */
@Service
@RequiredArgsConstructor
public class MerchantRegistrationService {

    private final AccountMapper accountMapper;
    private final MerchantMapper merchantMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public RegisterMerchantResponse register(RegisterMerchantRequest request) {
        String username = request.username().trim();
        AccountService.validatePassword(request.password());

        if (accountMapper.selectByUsername(username) != null) {
            throw new ConflictException("用户名已存在");
        }

        Account account = new Account();
        account.setUsername(username);
        account.setDisplayName(request.shopName().trim());
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setRole(AccountRole.MERCHANT.name());
        account.setEnabled(true);
        try {
            if (accountMapper.insert(account) != 1) {
                throw new IllegalStateException("账号创建失败");
            }
        } catch (DuplicateKeyException exception) {
            // 并发注册同名账号时由唯一索引兜底
            throw new ConflictException("用户名已存在");
        }

        Merchant merchant = new Merchant();
        merchant.setAccountId(account.getId());
        merchant.setName(request.shopName().trim());
        merchant.setPhone(request.phone().trim());
        merchant.setAddress(request.address().trim());
        merchant.setLongitude(request.longitude());
        merchant.setLatitude(request.latitude());
        merchant.setEnabled(true);
        if (merchantMapper.insert(merchant) != 1) {
            throw new IllegalStateException("店铺创建失败");
        }

        return new RegisterMerchantResponse(account.getId(), account.getUsername(),
                account.getRole(), merchant.getId(), merchant.getName());
    }
}
