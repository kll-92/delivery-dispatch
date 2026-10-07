package com.kll.deliverydispatch.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiSecurityConfig {
    @Bean
    OpenAPI deliveryDispatchOpenApi() {
        String schemeName = "sessionCsrfToken";
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(schemeName,
                        new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-CSRF-TOKEN")
                                .description("先通过会话登录，再从 GET /api/v1/auth/csrf 获取 token。")))
                .addSecurityItem(new SecurityRequirement().addList(schemeName));
    }
}
