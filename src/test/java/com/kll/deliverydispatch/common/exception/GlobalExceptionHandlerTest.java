package com.kll.deliverydispatch.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ErrorScenarioController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void malformedJsonReturnsReadableBadRequest() throws Exception {
        mockMvc.perform(post("/test/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("请求内容格式错误，请检查 JSON 格式及字段类型"));
    }

    @Test
    void pathVariableTypeMismatchReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/test/items/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("请求参数[id]格式不正确"));
    }

    @Test
    void missingRequiredQueryParameterReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/test/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("缺少必要的请求参数，请检查请求内容"));
    }

    @Test
    void invalidRequestBodyReturnsFieldValidationMessage() throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("字段[name]不能为空"));
    }

    @Test
    void businessIllegalArgumentRemainsBadRequestWithOriginalMessage() throws Exception {
        mockMvc.perform(get("/test/business-error"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("订单当前状态不允许此操作"));
    }

    @Test
    void unexpectedExceptionReturnsGenericInternalServerError() throws Exception {
        mockMvc.perform(get("/test/unexpected-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("系统异常，请稍后重试"));
    }

    @RestController
    static class ErrorScenarioController {

        @PostMapping("/test/json")
        String parseJson(@RequestBody ProbeRequest request) {
            return request.name();
        }

        @GetMapping("/test/items/{id}")
        long getItem(@PathVariable Long id) {
            return id;
        }

        @GetMapping("/test/search")
        String search(@RequestParam String keyword) {
            return keyword;
        }

        @PostMapping("/test/validated")
        String validate(@Valid @RequestBody ProbeRequest request) {
            return request.name();
        }

        @GetMapping("/test/business-error")
        void businessError() {
            throw new IllegalArgumentException("订单当前状态不允许此操作");
        }

        @GetMapping("/test/unexpected-error")
        void unexpectedError() {
            throw new IllegalStateException("internal details must not be returned");
        }
    }

    record ProbeRequest(@NotBlank(message = "不能为空") String name) {
    }
}
