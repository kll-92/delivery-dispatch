package com.kll.deliverydispatch.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kll.deliverydispatch.common.exception.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JsonSecurityErrorWriter {
    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ApiError(status.value(), message));
    }
}
