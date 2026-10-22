package com.hypeman.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Signs a request in as the admin when its bearer token is the admin token. Anything else passes through unsigned-in, and
 * the admin chain turns it away. Not a bean, so Spring Boot doesn't also register it for every request.
 */
final class AdminTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    /** Null when no admin token is set, so nothing matches. */
    private final byte[] token;

    AdminTokenFilter(String token) {
        this.token = StringUtils.hasText(token) ? token.getBytes(StandardCharsets.UTF_8) : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (token != null && header != null && header.startsWith(BEARER)
                && MessageDigest.isEqual(token, header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8))) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "admin", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
