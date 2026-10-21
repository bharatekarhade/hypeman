package com.hypeman.auth;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stands in for appleid.apple.com's token and revoke endpoints. Like Apple, it rejects a client secret that isn't an
 * ES256 JWT signed with the developer's key, with the right team, key, app and audience. An authorization code
 * "code-for-SUBJECT" exchanges for that Apple account's tokens; any other code is refused.
 */
final class FakeAppleServer {

    static final String TEAM_ID = "TEAM123456";
    static final String KEY_ID = "KEY1234567";

    final Set<String> revokedTokens = ConcurrentHashMap.newKeySet();

    private final ECKey signingKey;
    private final String clientId;
    private final Function<String, String> idTokenFor;
    private final HttpServer server;

    /** @param idTokenFor mints the ID token Apple would return for an account */
    FakeAppleServer(String clientId, Function<String, String> idTokenFor) {
        this.clientId = clientId;
        this.idTokenFor = idTokenFor;
        try {
            this.signingKey = new ECKeyGenerator(Curve.P_256).keyID(KEY_ID).generate();
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/auth/token", this::token);
        server.createContext("/auth/revoke", this::revoke);
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** The developer's Sign in with Apple key, as the .p8 file holds it. */
    String privateKeyPem() {
        try {
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes(UTF_8)).encodeToString(signingKey.toECPrivateKey().getEncoded());
            return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(exchange);
        String code = form.getOrDefault("code", "");
        if (!validClient(form)) {
            respond(exchange, 401, "{\"error\": \"invalid_client\"}");
        } else if (!"authorization_code".equals(form.get("grant_type")) || !code.startsWith("code-for-")) {
            respond(exchange, 400, "{\"error\": \"invalid_grant\"}");
        } else {
            String subject = code.substring("code-for-".length());
            respond(exchange, 200, """
                    {"access_token": "apple-access", "token_type": "Bearer", "expires_in": 3600,
                     "refresh_token": "apple-refresh-%s", "id_token": "%s"}""".formatted(subject, idTokenFor.apply(subject)));
        }
    }

    private void revoke(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(exchange);
        if (!validClient(form)) {
            respond(exchange, 401, "{\"error\": \"invalid_client\"}");
            return;
        }
        revokedTokens.add(form.get("token"));
        respond(exchange, 200, "");
    }

    private boolean validClient(Map<String, String> form) {
        try {
            SignedJWT secret = SignedJWT.parse(form.get("client_secret"));
            return clientId.equals(form.get("client_id"))
                    && secret.verify(new ECDSAVerifier(signingKey.toECPublicKey()))
                    && KEY_ID.equals(secret.getHeader().getKeyID())
                    && TEAM_ID.equals(secret.getJWTClaimsSet().getIssuer())
                    && clientId.equals(secret.getJWTClaimsSet().getSubject())
                    && secret.getJWTClaimsSet().getAudience().contains("https://appleid.apple.com");
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
        return Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], UTF_8), pair -> pair.length > 1 ? URLDecoder.decode(pair[1], UTF_8) : ""));
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
