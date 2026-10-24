package com.hypeman.auth.config;

import com.hypeman.auth.service.IdentityTokenVerifier;
import com.hypeman.common.ApiError;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AuthProperties.class, AdminProperties.class})
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final int MIN_ADMIN_TOKEN_LENGTH = 32;

    /** The admin endpoints take only the shared admin token; user access tokens don't work there. */
    @Bean
    @Order(1)
    SecurityFilterChain adminSecurity(HttpSecurity http, JsonMapper json, AdminProperties properties) throws Exception {
        if (!StringUtils.hasText(properties.token())) {
            log.info("No admin token set; the admin endpoints turn every request away.");
        } else if (properties.token().length() < MIN_ADMIN_TOKEN_LENGTH) {
            throw new IllegalStateException("HYPEMAN_ADMIN_TOKEN must be at least " + MIN_ADMIN_TOKEN_LENGTH + " characters.");
        }
        AuthenticationEntryPoint unauthorized = (request, response, e) ->
                write(response, json, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "This needs the admin token.");
        http.securityMatcher("/v1/admin/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new AdminTokenFilter(properties.token()), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().hasRole("ADMIN"))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(HttpSecurity http, JsonMapper json) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, e) ->
                write(response, json, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Sign in to do this.");
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/v1/auth/apple", "/v1/auth/google", "/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/packs").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/packs/*/media").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/app-store/notifications").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()).authenticationEntryPoint(unauthorized))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((request, response, e) ->
                                write(response, json, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "You can't do this.")));
        return http.build();
    }

    /** The key our access tokens are signed with. */
    @Bean
    RSAKey accessTokenSigningKey(AuthProperties properties) throws JOSEException, GeneralSecurityException {
        if (StringUtils.hasText(properties.signingKey())) {
            // Accept a key pasted with literal \n, as some hosts' environment variable fields need.
            String pem = properties.signingKey().replace("\\n", "\n");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) RsaKeyConverters.pkcs8()
                    .convert(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
        }
        if (!properties.allowEphemeralSigningKey()) {
            throw new IllegalStateException("Set HYPEMAN_AUTH_SIGNING_KEY to a PEM-encoded PKCS#8 RSA private key.");
        }
        log.warn("No access token signing key set; using a throwaway one. Every restart signs everyone out.");
        return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
    }

    @Bean
    JwtEncoder accessTokenEncoder(RSAKey accessTokenSigningKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(accessTokenSigningKey)));
    }

    /** Checks our own access tokens on every request. */
    @Bean
    JwtDecoder accessTokenDecoder(RSAKey accessTokenSigningKey, AuthProperties properties) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(accessTokenSigningKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    @Bean
    IdentityTokenVerifier identityTokenVerifier(AuthProperties properties) {
        return IdentityTokenVerifier.forProviders(properties);
    }

    private static void write(HttpServletResponse response, JsonMapper json, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(ApiError.of(code, message)));
    }
}
