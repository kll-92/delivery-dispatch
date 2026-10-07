package com.kll.deliverydispatch.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kll.deliverydispatch.account.entity.Account;
import com.kll.deliverydispatch.account.mapper.AccountMapper;
import com.kll.deliverydispatch.account.service.AccountService;
import com.kll.deliverydispatch.account.controller.AccountController;
import com.kll.deliverydispatch.auth.controller.AuthController;
import com.kll.deliverydispatch.order.controller.DeliveryOrderController;
import com.kll.deliverydispatch.order.service.DeliveryOrderService;
import com.kll.deliverydispatch.rider.controller.RiderController;
import com.kll.deliverydispatch.rider.service.RiderService;
import com.kll.deliverydispatch.controller.PingController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, DeliveryOrderController.class, RiderController.class,
        AccountController.class, PingController.class})
@Import({SecurityConfig.class, JsonSecurityErrorWriter.class, SessionAccountEnabledFilter.class,
        AccountUserDetailsService.class})
class AuthSecurityMockMvcTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AccountMapper accountMapper;
    @MockBean private DeliveryOrderService orderService;
    @MockBean private RiderService riderService;
    @MockBean private AccountService accountService;

    @Test
    void anonymousCanOnlyReachPublicEntryPointsAndGetsJson401ForBusinessRead() throws Exception {
        mvc.perform(get("/api/v1/ping")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("请先登录"));
    }

    @Test
    void loginPersistsSessionAndLogoutInvalidatesIt() throws Exception {
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        Account account = account(8L, "dispatcher", true);
        account.setPasswordHash(encoder.encode("secret-password"));
        when(accountMapper.selectByUsername("dispatcher")).thenReturn(account);
        when(accountMapper.selectById(8L)).thenReturn(account);

        MvcResult csrfResult = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode csrfJson = objectMapper.readTree(csrfResult.getResponse().getContentAsString());
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        String token = csrfJson.get("token").asText();
        String header = csrfJson.get("headerName").asText();

        MvcResult loginResult = mvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .header(header, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"dispatcher","password":"secret-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("dispatcher"))
                .andExpect(jsonPath("$.role").value("DISPATCHER"))
                .andReturn();

        MockHttpSession authenticatedSession = (MockHttpSession) loginResult.getRequest().getSession(false);
        mvc.perform(get("/api/v1/auth/me").session(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("dispatcher"));

        MvcResult nextCsrf = mvc.perform(get("/api/v1/auth/csrf").session(authenticatedSession))
                .andExpect(status().isOk()).andReturn();
        JsonNode nextToken = objectMapper.readTree(nextCsrf.getResponse().getContentAsString());
        mvc.perform(post("/api/v1/auth/logout")
                        .session(authenticatedSession)
                        .header(nextToken.get("headerName").asText(), nextToken.get("token").asText()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").session(authenticatedSession))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dispatcherCanReadAndOperateOrdersButCannotUseAdminOperations() throws Exception {
        mvc.perform(get("/api/v1/orders").with(user("dispatcher").roles("DISPATCHER")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/orders").with(user("dispatcher").roles("DISPATCHER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"merchantName":"便利店","merchantPhone":"01012345678",
                                 "merchantAddress":"商家地址","merchantLongitude":116.3971280,
                                 "merchantLatitude":39.9165270,"receiverName":"李四",
                                 "receiverPhone":"13800000002","receiverAddress":"收货地址",
                                 "receiverLongitude":116.4,"receiverLatitude":39.92,"deliveryFee":5.00}
                                """))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/orders/9/fee").with(user("dispatcher").roles("DISPATCHER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deliveryFee\":5.00}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("没有权限执行此操作"));
        mvc.perform(get("/api/v1/accounts").with(user("dispatcher").roles("DISPATCHER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/orders").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void unsafeRequestsRequireCsrfEvenForAuthenticatedUsers() throws Exception {
        mvc.perform(post("/api/v1/orders").with(user("dispatcher").roles("DISPATCHER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void disabledAccountSessionIsRejectedBeforeAuthorization() throws Exception {
        Account enabled = account(8L, "dispatcher", true);
        when(accountMapper.selectByUsername("dispatcher")).thenReturn(enabled);
        when(accountMapper.selectById(8L)).thenReturn(enabled);
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        enabled.setPasswordHash(encoder.encode("secret-password"));
        MvcResult csrfResult = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        JsonNode csrfJson = objectMapper.readTree(csrfResult.getResponse().getContentAsString());
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        MvcResult login = mvc.perform(post("/api/v1/auth/login").session(session)
                        .header(csrfJson.get("headerName").asText(), csrfJson.get("token").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dispatcher\",\"password\":\"secret-password\"}"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession loggedIn = (MockHttpSession) login.getRequest().getSession(false);

        Account disabled = account(8L, "dispatcher", false);
        when(accountMapper.selectById(8L)).thenReturn(disabled);
        mvc.perform(get("/api/v1/orders").session(loggedIn))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号已停用，请重新登录"));
    }

    private Account account(Long id, String username, boolean enabled) {
        Account account = new Account();
        account.setId(id);
        account.setUsername(username);
        account.setRole("DISPATCHER");
        account.setEnabled(enabled);
        return account;
    }
}
