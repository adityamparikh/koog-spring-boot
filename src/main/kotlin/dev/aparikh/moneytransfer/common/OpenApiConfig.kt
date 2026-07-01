package dev.aparikh.moneytransfer.common

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** OpenAPI metadata; springdoc serves the spec at /v3/api-docs and Swagger UI at /swagger-ui.html. */
@Configuration
class OpenApiConfig {

    @Bean
    fun moneyTransferOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Money Transfer API")
            .version("v1")
            .description("Persisted money-transfer domain (step 1) — accounts, contacts, and transfers."),
    )
}
