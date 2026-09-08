package com.arpan.ledger_api.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Ledger API",
                version = "1.0",
                description = """
                        A double-entry transaction ledger. Every transfer writes a debit and a \
                        credit that sum to exactly zero, enforced by a deferred database trigger.

                        To try it: register, log in, copy the token into Authorize below, set a \
                        transaction PIN, create an account, deposit into it, then transfer.

                        Amounts are integer minor units. 15075 is Rs 150.75."""),
        servers = {
                @Server(url = "https://arpan-ledger.duckdns.org", description = "Live"),
                @Server(url = "http://localhost:8081", description = "Local")
        })
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT", in = SecuritySchemeIn.HEADER,
                description = "Paste the token returned by /api/auth/login")
public class OpenApiConfig {
    
}