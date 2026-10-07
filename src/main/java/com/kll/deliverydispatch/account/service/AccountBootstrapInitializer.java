package com.kll.deliverydispatch.account.service;

import com.kll.deliverydispatch.account.AccountRole;
import com.kll.deliverydispatch.account.entity.Account;
import com.kll.deliverydispatch.account.mapper.AccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class AccountBootstrapInitializer implements ApplicationRunner {
    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${BOOTSTRAP_ADMIN_USERNAME:admin}")
    private String username;

    @Value("${BOOTSTRAP_ADMIN_PASSWORD:}")
    private String password;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (accountMapper.selectCount(null) > 0) {
            return;
        }
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException("账号表尚无账号。首次启动请设置 BOOTSTRAP_ADMIN_PASSWORD 环境变量后重启。");
        }
        try {
            AccountService.validatePassword(password);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD 至少 8 个字符且 UTF-8 编码不能超过 72 字节。");
        }
        if (!StringUtils.hasText(username)) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_USERNAME 不能为空。");
        }
        Account admin = new Account();
        admin.setUsername(username.trim());
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setRole(AccountRole.ADMIN.name());
        admin.setEnabled(true);
        accountMapper.insert(admin);
    }
}
