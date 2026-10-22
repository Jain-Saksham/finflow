package com.finflow.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI finflowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FinFlow API")
                        .version("v1")
                        .description("""
                                Payment orchestration **simulator**. No real money moves: providers are simulated \
                                and payment methods are fake tokens (see `GET /api/v1/simulator/scenarios`). \
                                Get a demo API key from `POST /api/v1/demo/session`, then click *Authorize*."""))
                .components(new Components().addSecuritySchemes("apiKey", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyAuthenticationFilter.HEADER)))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }
}
