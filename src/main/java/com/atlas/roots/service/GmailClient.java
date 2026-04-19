package com.atlas.roots.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Minimal Gmail API client — just enough to list messages matching a
 * query and fetch their decoded bodies.
 *
 * <h2>Why hand-rolled instead of the Google Gmail client library</h2>
 * <p>The google-api-services-gmail library brings in ~40 transitive
 * dependencies and expects a full HttpRequestFactory + Credential stack.
 * For this project's single use case (list receipts, fetch bodies) it's
 * wildly overweight. About 200 lines of {@link HttpURLConnection} here
 * is cleaner, auditable, and adds zero to the Maven tree.</p>
 *
 * <h2>Auth</h2>
 * <p>All methods take a fresh access token. The caller is responsible
 * for knowing when to refresh — this class doesn't persist state.</p>
 *
 * <h2>Rate limiting</h2>
 * <p>Gmail API allows ~250 requests per user per second. Our scanner
 * fetches one list call + one message per result, so a 50-receipt scan
 * is 51 requests. Well under any limit, but we add a small sleep between
 * message fetches anyway to be polite.</p>
 */
public final class GmailClient {

    private static final String API_BASE = "https://gmail.googleapis.com/gmail/v1/users/me";

    private final ObjectMapper json = new ObjectMapper();

    /**
     * List message IDs matching a Gmail search query. Paginates
     * automatically until the cap is reached.
     *
     * @param accessToken valid OAuth access token with gmail.readonly
     * @param query       Gmail search query, e.g. "from:receipts@stripe.com newer_than:180d"
     * @param maxResults  stop after this many IDs; 100 is a sensible cap for a demo
     * @return list of message IDs, most-recent first (Gmail's default order)
     */
    public List<String> listMessageIds(String accessToken, String query, int maxResults) throws IOException {
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        do {
            StringBuilder url = new StringBuilder(API_BASE + "/messages");
            url.append("?q=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            url.append("&maxResults=").append(Math.min(100, maxResults - ids.size()));
            if (pageToken != null) url.append("&pageToken=").append(pageToken);

            JsonNode resp = get(url.toString(), accessToken);
            if (resp.has("messages")) {
                for (JsonNode m : resp.get("messages")) {
                    ids.add(m.path("id").asText());
                    if (ids.size() >= maxResults) return ids;
                }
            }
            pageToken = resp.path("nextPageToken").asText(null);
        } while (pageToken != null && !pageToken.isEmpty() && ids.size() < maxResults);
        return ids;
    }

    /**
     * Fetch a single message with full headers + decoded body.
     *
     * @return a {@link Message} with subject, from, date, and plain-text body
     */
    public Message fetchMessage(String accessToken, String messageId) throws IOException {
        String url = API_BASE + "/messages/" + URLEncoder.encode(messageId, StandardCharsets.UTF_8)
                + "?format=full";
        JsonNode resp = get(url, accessToken);

        String subject = "";
        String from    = "";
        String dateStr = "";
        if (resp.has("payload") && resp.get("payload").has("headers")) {
            for (JsonNode h : resp.get("payload").get("headers")) {
                String name = h.path("name").asText("").toLowerCase();
                String val  = h.path("value").asText("");
                switch (name) {
                    case "subject" -> subject = val;
                    case "from"    -> from    = val;
                    case "date"    -> dateStr = val;
                }
            }
        }

        String body = extractPlainTextBody(resp.path("payload"));
        long internalDate = resp.path("internalDate").asLong(0);

        return new Message(messageId, subject, from, dateStr, internalDate, body);
    }

    // -----------------------------------------------------------------
    //  Internal — HTTP + body decoding
    // -----------------------------------------------------------------

    private JsonNode get(String url, String accessToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        int status = conn.getResponseCode();
        var stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) throw new IOException("no response body from " + url);
        JsonNode body = json.readTree(stream);
        if (status >= 400) {
            String err = body.path("error").path("message").asText("unknown");
            throw new IOException("Gmail API " + status + ": " + err);
        }
        return body;
    }

    /**
     * Walk a Gmail payload tree looking for the plain-text body. Falls
     * back to decoded HTML if no plain part is present. Returns "" if
     * nothing can be extracted.
     */
    private String extractPlainTextBody(JsonNode payload) {
        // Single-part messages have body.data directly
        if (payload.has("body") && payload.get("body").has("data")) {
            String mime = payload.path("mimeType").asText("");
            if (mime.equals("text/plain") || mime.equals("text/html")) {
                return decodeBase64Url(payload.get("body").path("data").asText(""));
            }
        }
        // Multipart messages have parts[]
        if (payload.has("parts")) {
            // Prefer text/plain over text/html
            for (JsonNode part : payload.get("parts")) {
                String mime = part.path("mimeType").asText("");
                if ("text/plain".equals(mime) && part.path("body").has("data")) {
                    return decodeBase64Url(part.get("body").path("data").asText(""));
                }
            }
            // No text/plain? Try text/html
            for (JsonNode part : payload.get("parts")) {
                String mime = part.path("mimeType").asText("");
                if ("text/html".equals(mime) && part.path("body").has("data")) {
                    return decodeBase64Url(part.get("body").path("data").asText(""));
                }
            }
            // Recurse into nested multipart/*
            for (JsonNode part : payload.get("parts")) {
                String result = extractPlainTextBody(part);
                if (!result.isEmpty()) return result;
            }
        }
        return "";
    }

    private String decodeBase64Url(String data) {
        if (data == null || data.isEmpty()) return "";
        try {
            return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException bad) {
            return "";
        }
    }

    /** A fetched Gmail message. */
    public record Message(
            String id,
            String subject,
            String from,
            String date,
            long   internalDateMillis,
            String body
    ) {}
}
