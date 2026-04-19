package com.atlas.roots.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google OAuth 2.0 flow, minimal and self-contained.
 *
 * <h2>What this class does</h2>
 * <ol>
 *   <li>Builds the Google consent URL the user's browser gets redirected to</li>
 *   <li>Exchanges the authorization code (returned in the callback) for
 *       access + refresh tokens via a POST to Google's token endpoint</li>
 *   <li>Fetches the user's email + Google subject ID via the userinfo endpoint</li>
 *   <li>Refreshes expired access tokens using the stored refresh token</li>
 * </ol>
 *
 * <h2>Why no OAuth library</h2>
 * <p>The Google OAuth Java client has 20+ transitive dependencies and needs
 * a lot of configuration. This implementation uses only {@link HttpURLConnection}
 * and the Jackson we already have. About 150 lines, zero new Maven deps,
 * trivial to audit. OAuth 2.0's Authorization Code flow is simple enough
 * that a hand-written implementation is the right call here.</p>
 *
 * <h2>State parameter + CSRF</h2>
 * <p>The {@code state} parameter is a random token generated when the user
 * starts the flow and verified when they return via the callback. This
 * prevents CSRF attacks where a malicious site could trick a logged-in
 * user into linking an attacker's Google account. States are held in
 * memory with a 10-minute TTL — long enough for the consent flow,
 * short enough to bound memory use.</p>
 *
 * <h2>Credentials</h2>
 * <p>Client ID and secret are read from environment variables. In production
 * these would live in a secrets manager; for a local dev demo, environment
 * variables are fine. The {@link #isConfigured} check lets the UI hide the
 * "Continue with Google" button gracefully when credentials aren't present.</p>
 */
public final class GoogleOAuthService {

    private static final String AUTH_ENDPOINT     = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT    = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_ENDPOINT = "https://openidconnect.googleapis.com/v1/userinfo";

    /** Scopes: identify the user for login + read-only access to Gmail for receipt scanning. */
    private static final String LOGIN_SCOPES =
            "openid email profile https://www.googleapis.com/auth/gmail.readonly";

    /** State TTL — how long the user has to complete the consent flow. */
    private static final long STATE_TTL_MS = 10 * 60 * 1000L; // 10 minutes

    private final String       clientId;
    private final String       clientSecret;
    private final String       redirectUri;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    /** In-memory CSRF state store: stateToken → expiryMillis. */
    private final Map<String, Long> issuedStates = new ConcurrentHashMap<>();

    public GoogleOAuthService(String clientId, String clientSecret, String redirectUri) {
        this.clientId     = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri  = redirectUri;
    }

    /**
     * True if both client ID and secret are present. Used by the login
     * page to decide whether to show the "Continue with Google" button.
     */
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
            && clientSecret != null && !clientSecret.isBlank();
    }

    /**
     * Build the consent URL the user's browser should visit. Generates a
     * CSRF-protective state token and stashes it in memory.
     *
     * @return a two-element array: [consentUrl, stateToken]. The caller
     *         should redirect to the URL and remember the state token is
     *         already tracked server-side for verification.
     */
    public String[] buildConsentUrl() {
        String state = randomState();
        issuedStates.put(state, System.currentTimeMillis() + STATE_TTL_MS);
        // Prune expired states opportunistically so memory doesn't grow
        // unbounded for an app running a long time.
        pruneExpiredStates();

        String url = AUTH_ENDPOINT
                + "?client_id="     + enc(clientId)
                + "&redirect_uri="  + enc(redirectUri)
                + "&response_type=code"
                + "&scope="         + enc(LOGIN_SCOPES)
                + "&state="         + enc(state)
                + "&access_type=offline"          // request a refresh token
                + "&prompt=consent";              // always show the consent screen
        return new String[] { url, state };
    }

    /**
     * Verify that the given state token was issued recently and hasn't
     * been consumed. Returns true exactly once per issued state; subsequent
     * calls with the same state return false (defends against replay).
     */
    public boolean consumeState(String state) {
        if (state == null) return false;
        Long expiry = issuedStates.remove(state);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    /**
     * Exchange an authorization code (from the callback URL) for an
     * access + refresh token pair.
     *
     * @return the parsed response containing access_token, refresh_token,
     *         expires_in, etc.
     */
    public TokenResponse exchangeCode(String code) throws IOException {
        String body = "code=" + enc(code)
                + "&client_id="     + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&redirect_uri="  + enc(redirectUri)
                + "&grant_type=authorization_code";
        JsonNode resp = postForm(TOKEN_ENDPOINT, body);
        return TokenResponse.fromJson(resp);
    }

    /**
     * Use a refresh token to mint a fresh access token. The refresh
     * token itself does not change. Used when a stored access token
     * is near expiry and we need to talk to a Google API.
     */
    public TokenResponse refreshAccessToken(String refreshToken) throws IOException {
        String body = "refresh_token=" + enc(refreshToken)
                + "&client_id="        + enc(clientId)
                + "&client_secret="    + enc(clientSecret)
                + "&grant_type=refresh_token";
        JsonNode resp = postForm(TOKEN_ENDPOINT, body);
        TokenResponse tr = TokenResponse.fromJson(resp);
        // The refresh response does not include the refresh_token itself;
        // reuse the one the caller passed in so the caller can persist a
        // complete record.
        if (tr.refreshToken == null) tr.refreshToken = refreshToken;
        return tr;
    }

    /**
     * Fetch the user's Google identity using a valid access token.
     *
     * @return a {@link UserInfo} with sub (stable Google user ID), email,
     *         and a display name.
     */
    public UserInfo fetchUserInfo(String accessToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(USERINFO_ENDPOINT).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new IOException("userinfo endpoint returned " + status);
        }
        try (var in = conn.getInputStream()) {
            JsonNode body = json.readTree(in);
            return new UserInfo(
                    body.path("sub").asText(),
                    body.path("email").asText(""),
                    body.path("name").asText("")
            );
        }
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    private JsonNode postForm(String endpoint, String formBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        byte[] bytes = formBody.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        int status = conn.getResponseCode();
        var stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) throw new IOException("no response body from " + endpoint);
        JsonNode body = json.readTree(stream);
        if (status >= 400) {
            String err = body.path("error").asText("unknown");
            String desc = body.path("error_description").asText("");
            throw new IOException("OAuth error from " + endpoint + ": " + err
                    + (desc.isEmpty() ? "" : " — " + desc));
        }
        return body;
    }

    private String randomState() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private void pruneExpiredStates() {
        long now = System.currentTimeMillis();
        issuedStates.entrySet().removeIf(e -> e.getValue() < now);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------
    //  Value types
    // -----------------------------------------------------------------

    /** Parsed token response from Google. */
    public static final class TokenResponse {
        public String accessToken;
        public String refreshToken;
        public long   expiresInSeconds;
        public Instant expiresAt;

        static TokenResponse fromJson(JsonNode node) {
            TokenResponse t = new TokenResponse();
            t.accessToken      = node.path("access_token").asText(null);
            t.refreshToken     = node.path("refresh_token").asText(null);
            t.expiresInSeconds = node.path("expires_in").asLong(3600);
            t.expiresAt        = Instant.now().plusSeconds(t.expiresInSeconds);
            return t;
        }
    }

    /** Identity info returned by the Google userinfo endpoint. */
    public record UserInfo(String sub, String email, String name) {}
}
