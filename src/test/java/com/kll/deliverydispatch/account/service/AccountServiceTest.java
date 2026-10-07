package com.kll.deliverydispatch.account.service;

import com.kll.deliverydispatch.account.dto.CreateAccountRequest;
import com.kll.deliverydispatch.account.entity.Account;
import com.kll.deliverydispatch.account.mapper.AccountMapper;
import com.kll.deliverydispatch.common.audit.OperationLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {
    @Mock private AccountMapper accountMapper;
    @Mock private OperationLogService operationLogService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountMapper, passwordEncoder, operationLogService);
    }

    @Test
    void newAccountsStoreOnlyBcryptHashAndReturnSafeDto() {
        when(accountMapper.selectByUsername("dispatcher")).thenReturn(null);
        when(accountMapper.insert(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(12L);
            return 1;
        });

        var response = accountService.create(new CreateAccountRequest("dispatcher", "secret-password", "DISPATCHER"));

        var captor = org.mockito.ArgumentCaptor.forClass(Account.class);
        verify(accountMapper).insert(captor.capture());
        assertNotEquals("secret-password", captor.getValue().getPasswordHash());
        org.junit.jupiter.api.Assertions.assertTrue(passwordEncoder.matches(
                "secret-password", captor.getValue().getPasswordHash()));
        assertEquals("dispatcher", response.username());
        assertEquals("DISPATCHER", response.role());
        org.junit.jupiter.api.Assertions.assertFalse(response.toString().contains("password"));
    }

    @Test
    void cannotDisableSelfOrTheLastEnabledAdministrator() {
        Account admin = new Account();
        admin.setId(3L);
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        when(accountMapper.selectAdminsForUpdate()).thenReturn(java.util.List.of(admin));

        assertThrows(IllegalArgumentException.class, () -> accountService.setEnabled(3L, false, 3L));
        assertThrows(IllegalArgumentException.class, () -> accountService.setEnabled(3L, false, 9L));
        verify(accountMapper, never()).updateById(any(Account.class));
    }
}
