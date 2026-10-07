package com.kll.deliverydispatch.security;

import com.kll.deliverydispatch.account.entity.Account;
import com.kll.deliverydispatch.account.mapper.AccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccountUserDetailsService implements UserDetailsService {
    private final AccountMapper accountMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Account account = accountMapper.selectByUsername(username);
        if (account == null || !Boolean.TRUE.equals(account.getEnabled())) {
            throw new UsernameNotFoundException("用户名或密码错误");
        }
        return new AccountPrincipal(account.getId(), account.getUsername(), account.getPasswordHash(),
                account.getRole(), true);
    }
}
