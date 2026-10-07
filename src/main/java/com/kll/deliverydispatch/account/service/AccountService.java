package com.kll.deliverydispatch.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kll.deliverydispatch.account.AccountRole;
import com.kll.deliverydispatch.account.dto.AccountResponse;
import com.kll.deliverydispatch.account.dto.CreateAccountRequest;
import com.kll.deliverydispatch.account.entity.Account;
import com.kll.deliverydispatch.account.mapper.AccountMapper;
import com.kll.deliverydispatch.common.audit.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class AccountService {
    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;
    private final OperationLogService operationLogService;

    public List<AccountResponse> list() {
        return accountMapper.selectList(Wrappers.<Account>lambdaQuery()
                        .orderByAsc(Account::getId).last("LIMIT 200")).stream()
                .map(AccountService::toResponse)
                .toList();
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        if (!StringUtils.hasText(request.username()) || !StringUtils.hasText(request.password())) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        validatePassword(request.password());
        AccountRole role = parseRole(request.role());
        String username = request.username().trim();
        if (accountMapper.selectByUsername(username) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }
        Account account = new Account();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setRole(role.name());
        account.setEnabled(true);
        try {
            if (accountMapper.insert(account) != 1) {
                throw new IllegalStateException("账号创建失败");
            }
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("用户名已存在");
        }
        AccountResponse response = toResponse(account);
        operationLogService.record("ACCOUNT_CREATE", "ACCOUNT", account.getId(), null, response,
                "管理员创建账号");
        return response;
    }

    @Transactional
    public AccountResponse setEnabled(Long id, boolean enabled, Long currentAccountId) {
        List<Account> lockedAdmins = accountMapper.selectAdminsForUpdate();
        Account account = lockedAdmins.stream().filter(candidate -> candidate.getId().equals(id))
                .findFirst().orElseGet(() -> accountMapper.selectById(id));
        if (account == null) {
            throw new IllegalArgumentException("账号不存在");
        }
        if (!enabled && id.equals(currentAccountId)) {
            throw new IllegalArgumentException("不能停用当前登录账号");
        }
        AccountResponse before = toResponse(account);
        if (!enabled && AccountRole.ADMIN.name().equals(account.getRole())
                && Boolean.TRUE.equals(account.getEnabled())) {
            // All admin rows are locked in stable order above. This serializes competing
            // disable requests, so only the request that preserves an enabled admin passes.
            long enabledAdminCount = lockedAdmins.stream()
                    .filter(admin -> Boolean.TRUE.equals(admin.getEnabled()))
                    .count();
            if (enabledAdminCount <= 1) {
                throw new IllegalArgumentException("不能停用最后一个启用的管理员");
            }
        }
        account.setEnabled(enabled);
        if (accountMapper.updateById(account) != 1) {
            throw new IllegalStateException("账号状态更新失败");
        }
        AccountResponse after = toResponse(account);
        operationLogService.record(enabled ? "ACCOUNT_ENABLE" : "ACCOUNT_DISABLE", "ACCOUNT", id,
                before, after, enabled ? "管理员启用账号" : "管理员停用账号");
        return after;
    }

    public Account getEnabledById(Long id) {
        Account account = accountMapper.selectById(id);
        return account != null && Boolean.TRUE.equals(account.getEnabled()) ? account : null;
    }

    private static AccountRole parseRole(String role) {
        if (!StringUtils.hasText(role)) {
            throw new IllegalArgumentException("角色不能为空");
        }
        try {
            return AccountRole.valueOf(role.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("角色只能是 ADMIN 或 DISPATCHER");
        }
    }

    public static void validatePassword(String password) {
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < 8) {
            throw new IllegalArgumentException("密码至少需要 8 个字符");
        }
        if (bytes > 72) {
            throw new IllegalArgumentException("密码 UTF-8 编码不能超过 72 字节");
        }
    }

    public static AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId(), account.getUsername(), account.getRole(),
                Boolean.TRUE.equals(account.getEnabled()), account.getCreatedAt(), account.getUpdatedAt());
    }
}
