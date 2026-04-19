package com.atlas.roots.web;

import com.atlas.roots.bridge.ObsidianBridge;
import com.atlas.roots.bridge.VaultWriter;
import com.atlas.roots.dao.*;
import com.atlas.roots.db.DatabaseManager;
import com.atlas.roots.model.*;
import com.atlas.roots.service.*;
import com.atlas.roots.util.CryptoService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

/**
 * The Roots web server.
 *
 * <p>Boots an embedded Javalin HTTP server on port 8080, wires every
 * DAO and service, exposes a REST API for the web frontend, and serves
 * the static HTML/CSS/JS from the classpath at {@code /web/}.</p>
 *
 * <p>This replaces the old JavaFX {@code RootsApp}. The backend code
 * (model, dao, service, bridge) is 100% unchanged &mdash; only the
 * presentation layer changed from JavaFX views to HTTP endpoints that
 * return JSON.</p>
 *
 * <p>Run with: {@code mvn exec:java}</p>
 */
public class RootsServer {

    private static final int PORT = 3000;

    // Services — wired once at boot, shared across all requests.
    private static DatabaseManager     db;
    private static VaultGuardian       guardian;
    private static VitalityCalculator  vitality;
    private static JoyCostAnalyzer     joyCost;
    private static CognitiveHeatmap    heatmap;
    private static SubNodeDao          subDao;
    private static RepoNodeDao         repoDao;
    private static IdeaNodeDao         ideaDao;
    private static AuditDao            auditDao;
    private static UserDao             userDao;
    private static ObsidianBridge      obsidian;
    private static VaultWriter         vaultWriter;
    private static SkillDao            skillDao;
    private static SkillExtractor      skillExtractor;
    private static ContextExporter     contextExporter;
    private static GoogleOAuthService  googleOAuth;
    private static GmailClient         gmailClient;
    private static GmailStripeScanner  gmailScanner;
    // Per-user vault root paths. In-memory only for now — a persistent
    // setting would be a schema change we're deferring. Users reconfigure
    // once per session, which is acceptable for the demo.
    private static final java.util.Map<Long, Path> vaultRootsByUser = new java.util.HashMap<>();
    private static MarkdownExporter    mdExporter;
    private static MarkdownImporter    mdImporter;
    private static ObjectMapper        json;

    // Simple session store: token → User. Good enough for a local-only app.
    private static final Map<String, User> sessions = new HashMap<>();

    public static void main(String[] args) throws Exception {
        // ----- Boot backend -----------------------------------------
        db = DatabaseManager.getInstance();
        db.bootstrapIfNeeded();

        userDao   = new UserDao(db);
        auditDao  = new AuditDao(db);
        guardian  = new VaultGuardian(userDao, auditDao);
        subDao   = new SubNodeDao(db);
        repoDao  = new RepoNodeDao(db);
        ideaDao  = new IdeaNodeDao(db);
        vitality = new VitalityCalculator();
        joyCost  = new JoyCostAnalyzer();
        heatmap  = new CognitiveHeatmap();
        obsidian = new ObsidianBridge(ideaDao);
        vaultWriter = new VaultWriter();
        // Reconstruct the bridge with the writer so the FileWatcher can
        // debounce self-writes when we eventually enable live watch.
        obsidian = new ObsidianBridge(ideaDao, vaultWriter);
        skillDao = new SkillDao(db);
        skillExtractor = new SkillExtractor(ideaDao, skillDao);
        contextExporter = new ContextExporter(skillDao, subDao, ideaDao);

        // Google OAuth — credentials loaded from the project's .env file
        // or real environment variables. See .env.example for the template.
        // We deliberately do NOT hardcode a fallback secret: that would
        // leak into public commits and defeat the point of .env separation.
        String googleClientId     = com.atlas.roots.util.DotenvLoader.get("GOOGLE_CLIENT_ID", "");
        String googleClientSecret = com.atlas.roots.util.DotenvLoader.get("GOOGLE_CLIENT_SECRET", "");
        String googleRedirectUri  = "http://127.0.0.1:3000/auth/google/callback";
        if (googleClientId.isBlank() || googleClientSecret.isBlank()) {
            System.err.println("[roots] Google OAuth credentials not configured — " +
                    "the \"Continue with Google\" button will be hidden. " +
                    "Copy .env.example to .env and fill in GOOGLE_CLIENT_ID + GOOGLE_CLIENT_SECRET.");
        }
        googleOAuth = new GoogleOAuthService(googleClientId, googleClientSecret, googleRedirectUri);
        gmailClient = new GmailClient();
        gmailScanner = new GmailStripeScanner(gmailClient, subDao);
        mdExporter = new MarkdownExporter(subDao, repoDao, ideaDao);
        mdImporter = new MarkdownImporter(subDao, repoDao, ideaDao);

        json = new ObjectMapper();
        json.registerModule(new JavaTimeModule());
        json.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // ----- Boot Javalin -----------------------------------------
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/web", Location.CLASSPATH);
        });

        // ----- Auth endpoints ---------------------------------------
        app.post("/api/login", RootsServer::handleLogin);
        app.get("/api/auth/google/status", RootsServer::handleGoogleStatus);
        app.get("/auth/google/start",      RootsServer::handleGoogleStart);
        app.get("/auth/google/callback",   RootsServer::handleGoogleCallback);
        app.post("/api/gmail/scan",        RootsServer::handleGmailScan);
        app.post("/api/gmail/import",      RootsServer::handleGmailImport);
        app.post("/api/signup", RootsServer::handleSignup);
        app.post("/api/logout", RootsServer::handleLogout);

        // ----- Node CRUD endpoints ----------------------------------
        // Subscriptions
        app.get("/api/subs",       ctx -> handleList(ctx, "SUB"));
        app.post("/api/subs",      RootsServer::handleCreateSub);
        app.put("/api/subs/{id}",  RootsServer::handleUpdateSub);
        app.delete("/api/subs/{id}", ctx -> handleDelete(ctx, "SUB"));

        // Repos
        app.get("/api/repos",      ctx -> handleList(ctx, "REPO"));
        app.post("/api/repos",     RootsServer::handleCreateRepo);
        app.put("/api/repos/{id}", RootsServer::handleUpdateRepo);
        app.delete("/api/repos/{id}", ctx -> handleDelete(ctx, "REPO"));

        // Ideas
        app.get("/api/ideas",      ctx -> handleList(ctx, "IDEA"));
        app.post("/api/ideas",     RootsServer::handleCreateIdea);
        app.put("/api/ideas/{id}", RootsServer::handleUpdateIdea);
        app.delete("/api/ideas/{id}", ctx -> handleDelete(ctx, "IDEA"));

        // ----- Aggregation endpoints --------------------------------
        app.get("/api/dashboard",  RootsServer::handleDashboard);
        app.get("/api/heatmap",    RootsServer::handleHeatmap);
        app.get("/api/joy-cost",   RootsServer::handleJoyCost);
        app.get("/api/audit",      RootsServer::handleAudit);
        app.get("/api/audit/verify", RootsServer::handleAuditVerify);
        app.get("/api/skills",     RootsServer::handleSkillsList);
        app.post("/api/skills/recompute", RootsServer::handleSkillsRecompute);
        app.post("/api/skills/{id}/rename", RootsServer::handleSkillRename);
        app.post("/api/skills/{id}/link/{subId}",   RootsServer::handleSkillLink);
        app.delete("/api/skills/{id}/link/{subId}", RootsServer::handleSkillUnlink);
        app.get("/api/subs/{id}/skill-suggestions", RootsServer::handleSubSuggestions);
        app.get("/api/subs/{id}/skills",            RootsServer::handleSubSkills);
        app.get("/api/context",         RootsServer::handleContextExport);
        app.get("/api/context/prompt",  RootsServer::handleContextExportRaw);

        // ----- Vault bridge + Markdown export/import ----------------
        app.post("/api/vault/scan", RootsServer::handleVaultScan);
        app.get("/api/vault/config", RootsServer::handleVaultConfigGet);
        app.post("/api/vault/config", RootsServer::handleVaultConfigSet);
        app.post("/api/vault/watch", RootsServer::handleVaultWatchToggle);
        app.get("/api/export",      RootsServer::handleExport);
        app.post("/api/import",     RootsServer::handleImport);

        // ----- Start ------------------------------------------------
        // Bind to 127.0.0.1 only. Local-first means local — this server
        // is never reachable from other machines on the network, even
        // on a hostile WiFi. If the user wants remote access, they have
        // to change this line and understand what they're opting into.
        app.start("127.0.0.1", PORT);
        System.out.println();
        System.out.println("  ROOTS is running at http://localhost:" + PORT);
        System.out.println("  (bound to 127.0.0.1 — not reachable from other machines)");
        System.out.println("  Ctrl+C to stop.");
        System.out.println();

        // Try to open the browser automatically.
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI("http://localhost:" + PORT));
            }
        } catch (Exception ignored) {
            // Not critical — the user can open the URL manually.
        }
    }

    // =================================================================
    //  Auth
    // =================================================================

    private static void handleLogin(Context ctx) throws Exception {
        var body = json.readTree(ctx.body());
        String username = body.path("username").asText("");
        String password = body.path("password").asText("");

        var session = guardian.login(username, password);
        if (session.isEmpty()) {
            ctx.status(401).json(Map.of("error", "invalid credentials"));
            return;
        }
        User user = session.get();
        String token = UUID.randomUUID().toString();
        sessions.put(token, user);

        ctx.json(Map.of(
                "token",    token,
                "username", user.getUsername(),
                "role",     user.getRole().name(),
                "userId",   user.getId()
        ));
    }

    /**
     * GET /api/auth/google/status — tells the login page whether the
     * Google OAuth button should be shown (true only when credentials
     * are configured on the server).
     */
    private static void handleGoogleStatus(Context ctx) {
        ctx.json(Map.of("configured", googleOAuth.isConfigured()));
    }

    /**
     * GET /auth/google/start — kicks off the OAuth flow by redirecting
     * the browser to Google's consent URL. Not an API endpoint — the
     * browser navigates here directly from the "Continue with Google"
     * button so Google can redirect back with the auth code.
     */
    private static void handleGoogleStart(Context ctx) {
        if (!googleOAuth.isConfigured()) {
            ctx.status(503).result("Google OAuth is not configured on this server.");
            return;
        }
        String[] urlAndState = googleOAuth.buildConsentUrl();
        ctx.redirect(urlAndState[0]);
    }

    /**
     * GET /auth/google/callback — Google redirects here with ?code=&state=
     * after the user consents. We verify the state, exchange the code for
     * tokens, fetch the userinfo, match to an existing Roots user by
     * email (or fail with an explanatory page), encrypt and persist
     * tokens, issue a Roots session, and redirect back to the app root
     * with the session token in the URL fragment.
     */
    private static void handleGoogleCallback(Context ctx) {
        try {
            String code  = ctx.queryParam("code");
            String state = ctx.queryParam("state");
            String error = ctx.queryParam("error");

            if (error != null && !error.isBlank()) {
                ctx.status(400).result("Google returned an error: " + error
                        + "\n\nThis usually means you declined consent. Close this tab and try again.");
                return;
            }
            if (code == null || code.isBlank()) {
                ctx.status(400).result("No authorization code received. Try starting the flow again.");
                return;
            }
            if (!googleOAuth.consumeState(state)) {
                ctx.status(400).result("Invalid or expired state token. This protects against CSRF — "
                        + "please start the sign-in flow again from the Roots login page.");
                return;
            }

            // Step 1: exchange the code for tokens
            var tokens = googleOAuth.exchangeCode(code);
            if (tokens.accessToken == null) {
                ctx.status(500).result("Google did not return an access token.");
                return;
            }

            // Step 2: fetch the user's identity
            var userInfo = googleOAuth.fetchUserInfo(tokens.accessToken);
            if (userInfo.sub() == null || userInfo.sub().isBlank()) {
                ctx.status(500).result("Google did not return a user ID.");
                return;
            }

            // Step 3: find or create the matching Roots user.
            // Preference order:
            //   (a) already linked by google_sub — the user has OAuthed before
            //   (b) existing Roots user whose username matches the email
            //   (c) existing Roots user whose username matches the email's local part
            //   (d) no match → create a fresh Roots account for this Gmail
            Optional<User> linked = userDao.findByGoogleSub(userInfo.sub());
            User rootsUser;
            boolean isFreshAccount = false;
            if (linked.isPresent()) {
                rootsUser = linked.get();
            } else {
                String email = userInfo.email() == null ? "" : userInfo.email().toLowerCase();
                String localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;

                Optional<User> byFullEmail = userDao.findByUsername(email);
                Optional<User> byLocal     = byFullEmail.isPresent() ? byFullEmail : userDao.findByUsername(localPart);

                if (byLocal.isPresent()) {
                    rootsUser = byLocal.get();
                } else {
                    // Fresh account path: the Gmail has no Roots user yet.
                    // Pick the local-part as the default username; if that's
                    // already taken (edge case: someone registered with that
                    // name but different Gmail), fall back to the full email.
                    String newUsername = localPart.isEmpty() ? email : localPart;
                    if (userDao.findByUsername(newUsername).isPresent()) {
                        newUsername = email;  // collides → use the full email
                    }
                    // Generate a random high-entropy shadow password. It's
                    // required by the schema and is used as salt for deriving
                    // the user's data encryption key — never for login.
                    String shadowPassword = java.util.UUID.randomUUID().toString()
                            + ":" + java.util.UUID.randomUUID().toString();
                    String bcryptHash = com.atlas.roots.util.CryptoService.hashPassword(shadowPassword);

                    // Create with Google linkage + temporary null tokens;
                    // we'll update them once the session is activated and
                    // the data key is available for encryption.
                    rootsUser = userDao.createFromGoogle(
                            newUsername, bcryptHash, User.Role.ADMIN,
                            userInfo.sub(), userInfo.email(),
                            null, null,  // tokens encrypted after session activation
                            null);
                    isFreshAccount = true;
                    auditDao.log(null, rootsUser.getId(), AuditEvent.Action.CREATE,
                            "auto-created account for Google sign-in: " + userInfo.email());
                }
            }

            // Step 4: activate the Google session. This derives the user's
            // data encryption key from their stable Google sub, so encrypted
            // data (descriptions, tokens) is readable for the rest of the
            // session. Deterministic: same sub → same key, every time.
            guardian.activateGoogleSession(rootsUser);

            // Step 5: encrypt and persist the tokens. Now that the data
            // key is cached by the guardian, encryption works for both
            // fresh accounts and existing ones.
            boolean canEncrypt = guardian.getDataKey(rootsUser.getId()) != null;
            String encAccess  = canEncrypt ? encryptDesc(tokens.accessToken, rootsUser) : null;
            String encRefresh = (canEncrypt && tokens.refreshToken != null)
                    ? encryptDesc(tokens.refreshToken, rootsUser)
                    : null;
            java.time.LocalDateTime expiresAt = tokens.expiresAt == null
                    ? java.time.LocalDateTime.now().plusHours(1)
                    : java.time.LocalDateTime.ofInstant(tokens.expiresAt, java.time.ZoneId.systemDefault());
            userDao.linkGoogleAccount(rootsUser.getId(), userInfo.sub(), userInfo.email(),
                    encAccess, encRefresh, expiresAt);
            rootsUser.setGoogleSub(userInfo.sub());
            rootsUser.setGoogleEmail(userInfo.email());
            auditDao.log(null, rootsUser.getId(), AuditEvent.Action.LOGIN,
                    (isFreshAccount ? "first Google sign-in for " : "signed in via Google (")
                            + userInfo.email() + (isFreshAccount ? "" : ")"));

            // Step 6: issue a session token
            String sessionToken = UUID.randomUUID().toString();
            sessions.put(sessionToken, rootsUser);

            // Step 7: redirect back to the app with the token in the fragment.
            // Fragments stay client-side (never sent to servers), which is
            // the standard way to pass auth tokens from a redirect.
            String redirect = "/#google-auth="
                    + java.net.URLEncoder.encode(sessionToken, java.nio.charset.StandardCharsets.UTF_8)
                    + "&username="
                    + java.net.URLEncoder.encode(rootsUser.getUsername(), java.nio.charset.StandardCharsets.UTF_8)
                    + "&role="
                    + java.net.URLEncoder.encode(rootsUser.getRole().name(), java.nio.charset.StandardCharsets.UTF_8)
                    + (isFreshAccount ? "&fresh=1" : "");
            ctx.redirect(redirect);
        } catch (Exception e) {
            ctx.status(500).contentType("text/html").result(
                    "<h1>OAuth callback failed</h1><pre>" + e.getMessage() + "</pre>"
                    + "<p>Close this tab and try again from the Roots login page.</p>");
        }
    }

    /**
     * POST /api/gmail/scan — scan the signed-in user's Gmail for Stripe
     * receipts and return candidate subscriptions (not yet saved).
     * The caller can then POST /api/gmail/import with the chosen
     * candidates to actually create the SubNodes.
     */
    private static void handleGmailScan(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;

        // Decrypt the stored access token. If the user isn't actually
        // Google-linked (no stored token) or we can't decrypt, fail
        // gracefully with a message the UI can render.
        String accessToken = getDecryptedAccessToken(user);
        if (accessToken == null) {
            ctx.status(400).json(Map.of(
                    "error", "Gmail not linked",
                    "hint",  "sign in with Google to enable inbox scanning"
            ));
            return;
        }

        try {
            var result = gmailScanner.scan(accessToken, user.getId());
            // Log the scan itself so the audit trail shows what Roots
            // did on the user's behalf. Number of candidates, not their
            // details — no leak of subscription names to the log file.
            auditDao.log(null, user.getId(), AuditEvent.Action.UPDATE,
                    "gmail scan: " + result.messagesFound() + " emails, "
                    + result.uniqueBusinesses() + " unique subs found");

            ctx.json(Map.of(
                    "messagesFound",    result.messagesFound(),
                    "messagesParsed",   result.messagesParsed(),
                    "uniqueBusinesses", result.uniqueBusinesses(),
                    "fetchFailures",    result.fetchFailures(),
                    "parseFailures",    result.parseFailures(),
                    "candidates",       result.candidates()
            ));
        } catch (java.io.IOException apiFail) {
            ctx.status(502).json(Map.of(
                    "error", "Gmail API error",
                    "detail", apiFail.getMessage(),
                    "hint", "your access token may have expired; sign out and sign in with Google again"
            ));
        }
    }

    /**
     * POST /api/gmail/import — given a list of scanned candidates the
     * user confirmed on the review screen, create the corresponding
     * SubNodes. Body: {@code {"candidates": [{name, monthlyCost,
     * currency, cadence}, ...]}}. Dedup happens here too in case the
     * user scanned twice and clicked import on the same candidate.
     */
    private static void handleGmailImport(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;

        var body = json.readTree(ctx.body());
        var arr = body.path("candidates");
        if (!arr.isArray() || arr.size() == 0) {
            ctx.status(400).json(Map.of("error", "no candidates provided"));
            return;
        }

        // Build a fast lookup of existing sub names to skip duplicates
        var existing = subDao.findByOwner(user.getId());
        java.util.Set<String> existingNames = new java.util.HashSet<>();
        for (var s : existing) {
            if (s.getName() != null) existingNames.add(s.getName().toLowerCase().trim());
        }

        int created = 0;
        int skipped = 0;
        for (var item : arr) {
            String name     = item.path("name").asText("").trim();
            double cost     = item.path("monthlyCost").asDouble(0);
            String currency = item.path("currency").asText("USD");
            String cadence  = item.path("cadence").asText("MONTHLY");
            if (name.isEmpty()) { skipped++; continue; }
            if (existingNames.contains(name.toLowerCase())) { skipped++; continue; }

            SubNode node = new SubNode(
                    name,
                    encryptDesc("imported from Stripe receipt via Gmail", user),
                    user.getId(),
                    cost,
                    currency,
                    SubNode.Cadence.valueOf(cadence),
                    7,  // default joy: the user wouldn't keep paying if they hated it
                    java.time.LocalDate.now(),
                    null
            );
            subDao.insert(node);
            auditDao.log(node.getId(), user.getId(), AuditEvent.Action.CREATE,
                    "imported sub from Gmail: " + name);

            // Apply the same auto-tag logic as manual creation so the
            // imported subs get linked to matching skills right away.
            try {
                var allSkills = skillDao.findByOwner(user.getId());
                if (!allSkills.isEmpty()) {
                    var suggestions = SkillSuggester.suggestForSub(node, allSkills);
                    for (var sug : suggestions) {
                        if (sug.score() >= 0.5) {
                            skillDao.linkSubToSkill(sug.skillId(), node.getId());
                        }
                    }
                }
            } catch (Exception ignored) { /* auto-tag is best-effort */ }

            created++;
            existingNames.add(name.toLowerCase()); // in case the same name repeats in the batch
        }

        ctx.json(Map.of(
                "created",  created,
                "skipped",  skipped
        ));
    }

    /**
     * Decrypt the stored Google access token for a user. Returns null
     * if the user has no linked Google account or the token can't be
     * decrypted (e.g. data key not in memory for this session).
     */
    private static String getDecryptedAccessToken(User user) {
        if (user == null) return null;
        // Reload the user to get the freshest stored fields — the in-session
        // User may be stale if someone refreshed the token mid-session.
        try {
            var fresh = userDao.findById(user.getId());
            if (fresh.isEmpty()) return null;
            String enc = fresh.get().getGoogleAccessToken();
            if (enc == null || enc.isBlank()) return null;
            byte[] key = guardian.getDataKey(user.getId());
            if (key == null) return null;
            return CryptoService.decryptString(enc, key);
        } catch (SQLException sqlFail) {
            return null;
        }
    }

    /**
     * Build a minimal HTML page explaining why we couldn't link a Google
     * account — helpful for the demo, since the professor might try it
     * and see an unhelpful error otherwise.
     */
    private static String googleLinkFailurePage(String email) {
        return "<!DOCTYPE html><html><head><style>"
                + "body{background:#0a0a0a;color:#e8e6e1;font-family:system-ui,sans-serif;max-width:560px;margin:80px auto;padding:40px;line-height:1.6}"
                + "h1{color:#7fd6a8;font-weight:400}code{background:#1a1a1a;padding:2px 6px;border-radius:2px}"
                + "a{color:#7fd6a8}</style></head><body>"
                + "<h1>No matching Roots account</h1>"
                + "<p>You authenticated with Google as <code>" + (email == null ? "" : email) + "</code>, "
                + "but there's no Roots user whose username matches that email.</p>"
                + "<p>To link Google to an existing account, the Roots username must match either:</p>"
                + "<ul><li>the full email address, or</li>"
                + "<li>the part before the <code>@</code></li></ul>"
                + "<p>For this demo, you can sign in with password (e.g. <code>draco</code> / <code>roots2026</code>) and link Google from your profile later, "
                + "or create a Roots user whose username matches your Gmail's local part.</p>"
                + "<p><a href='/'>← Back to Roots</a></p>"
                + "</body></html>";
    }

    private static void handleSignup(Context ctx) throws Exception {
        var body = json.readTree(ctx.body());
        String username = body.path("username").asText("").trim();
        String password = body.path("password").asText("");
        // Role from public signup is always VIEWER. Creating admin
        // accounts is an internal operation only — we don't let the
        // world mint admins through a public endpoint.
        User.Role role = User.Role.VIEWER;

        try {
            var created = guardian.register(username, password, role);
            if (created.isEmpty()) {
                ctx.status(409).json(Map.of("error", "username already exists"));
                return;
            }
            User user = created.get();
            // Write a CREATE audit row for the new account so it shows
            // up in the log alongside node creations.
            try {
                auditDao.log(null, user.getId(), AuditEvent.Action.CREATE,
                        "user signup: " + user.getUsername() + " (" + role + ")");
            } catch (SQLException ignored) { /* audit failure shouldn't block signup */ }

            // Auto-login the newly created account so the user lands
            // inside the app without a second form submission.
            String token = UUID.randomUUID().toString();
            sessions.put(token, user);
            ctx.status(201).json(Map.of(
                    "token",    token,
                    "username", user.getUsername(),
                    "role",     user.getRole().name(),
                    "userId",   user.getId()
            ));
        } catch (IllegalArgumentException bad) {
            ctx.status(400).json(Map.of("error", bad.getMessage()));
        } catch (SQLException sqlFail) {
            ctx.status(500).json(Map.of("error", "signup failed: " + sqlFail.getMessage()));
        }
    }

    private static void handleLogout(Context ctx) {
        String token = tokenFrom(ctx);
        if (token != null) {
            User u = sessions.remove(token);
            if (u != null) guardian.logout();
        }
        ctx.json(Map.of("ok", true));
    }

    // =================================================================
    //  Node CRUD
    // =================================================================

    private static void handleList(Context ctx, String type) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        List<?> nodes = switch (type) {
            case "SUB" -> {
                var list = subDao.findByOwner(user.getId());
                decryptSubList(list, user);
                yield list;
            }
            case "REPO" -> {
                var list = repoDao.findByOwner(user.getId());
                decryptRepoList(list, user);
                yield list;
            }
            case "IDEA" -> {
                var list = ideaDao.findByOwner(user.getId());
                decryptIdeaList(list, user);
                yield list;
            }
            default -> List.of();
        };
        ctx.result(json.writeValueAsString(nodes));
        ctx.contentType("application/json");
    }

    /** Minimum score for auto-linking a skill suggestion to a new sub. */
    private static final double AUTO_LINK_THRESHOLD = 0.5;

    private static void handleCreateSub(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        var body = json.readTree(ctx.body());
        SubNode node = new SubNode(
                body.path("name").asText(),
                encryptDesc(body.path("description").asText(""), user),
                user.getId(),
                body.path("monthlyCost").asDouble(0),
                body.path("currency").asText("INR"),
                SubNode.Cadence.valueOf(body.path("cadence").asText("MONTHLY")),
                body.path("joyRating").asInt(5),
                java.time.LocalDate.parse(body.path("startedOn").asText(java.time.LocalDate.now().toString())),
                null
        );
        subDao.insert(node);
        auditDao.log(node.getId(), user.getId(), AuditEvent.Action.CREATE, "Created sub: " + node.getName());
        // Decrypt before returning so the UI sees the plaintext it just sent
        node.setDescription(decryptDesc(node.getDescription(), user));

        // Auto-link any skill suggestion that scores above the confident
        // threshold. This means creating "Claude Pro" with a description
        // about LLMs automatically tags it with the AI skill — no manual
        // click required. Low-confidence matches are left for the user
        // to curate via SUGGEST SKILLS on the edit form.
        int autoLinked = 0;
        try {
            var allSkills = skillDao.findByOwner(user.getId());
            if (!allSkills.isEmpty()) {
                var suggestions = SkillSuggester.suggestForSub(node, allSkills);
                for (var s : suggestions) {
                    if (s.score() >= AUTO_LINK_THRESHOLD) {
                        skillDao.linkSubToSkill(s.skillId(), node.getId());
                        autoLinked++;
                    }
                }
            }
        } catch (Exception autoFail) {
            // Non-fatal: the sub was created successfully, auto-linking
            // is best-effort polish. User can tag manually.
        }
        // Note the auto-link count in the audit log for demo transparency
        if (autoLinked > 0) {
            auditDao.log(node.getId(), user.getId(), AuditEvent.Action.UPDATE,
                    "auto-linked " + autoLinked + " skill(s) to " + node.getName());
        }

        ctx.status(201).result(json.writeValueAsString(node));
        ctx.contentType("application/json");
    }

    private static void handleUpdateSub(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        long id = Long.parseLong(ctx.pathParam("id"));
        var existing = subDao.findById(id);
        if (existing.isEmpty()) { ctx.status(404).json(Map.of("error", "not found")); return; }
        SubNode node = existing.get();
        var body = json.readTree(ctx.body());
        if (body.has("name"))        node.setName(body.get("name").asText());
        if (body.has("description")) node.setDescription(encryptDesc(body.get("description").asText(), user));
        if (body.has("monthlyCost")) node.setMonthlyCost(body.get("monthlyCost").asDouble());
        if (body.has("currency"))    node.setCurrency(body.get("currency").asText());
        if (body.has("cadence"))     node.setCadence(SubNode.Cadence.valueOf(body.get("cadence").asText()));
        if (body.has("joyRating"))   node.setJoyRating(body.get("joyRating").asInt());
        if (body.has("startedOn"))   node.setStartedOn(java.time.LocalDate.parse(body.get("startedOn").asText()));
        node.touch();
        subDao.update(node);
        auditDao.log(id, user.getId(), AuditEvent.Action.UPDATE, "Updated sub: " + node.getName());
        node.setDescription(decryptDesc(node.getDescription(), user));
        ctx.result(json.writeValueAsString(node));
        ctx.contentType("application/json");
    }

    private static void handleCreateRepo(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        var body = json.readTree(ctx.body());
        RepoNode node = new RepoNode(
                body.path("name").asText(),
                encryptDesc(body.path("description").asText(""), user),
                user.getId(),
                body.path("localPath").asText(""),
                body.path("remoteUrl").asText(null),
                body.path("primaryLanguage").asText(null),
                body.path("commitCount").asInt(0),
                java.time.LocalDateTime.now(),
                body.path("staleThresholdDays").asInt(30)
        );
        repoDao.insert(node);
        auditDao.log(node.getId(), user.getId(), AuditEvent.Action.CREATE, "Created repo: " + node.getName());
        node.setDescription(decryptDesc(node.getDescription(), user));
        ctx.status(201).result(json.writeValueAsString(node));
        ctx.contentType("application/json");
    }

    private static void handleUpdateRepo(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        long id = Long.parseLong(ctx.pathParam("id"));
        var existing = repoDao.findById(id);
        if (existing.isEmpty()) { ctx.status(404).json(Map.of("error", "not found")); return; }
        RepoNode node = existing.get();
        var body = json.readTree(ctx.body());
        if (body.has("name"))              node.setName(body.get("name").asText());
        if (body.has("description"))       node.setDescription(encryptDesc(body.get("description").asText(), user));
        if (body.has("localPath"))         node.setLocalPath(body.get("localPath").asText());
        if (body.has("remoteUrl"))         node.setRemoteUrl(body.get("remoteUrl").asText());
        if (body.has("primaryLanguage"))   node.setPrimaryLanguage(body.get("primaryLanguage").asText());
        if (body.has("commitCount"))       node.setCommitCount(body.get("commitCount").asInt());
        if (body.has("staleThresholdDays")) node.setStaleThresholdDays(body.get("staleThresholdDays").asInt());
        node.touch();
        repoDao.update(node);
        auditDao.log(id, user.getId(), AuditEvent.Action.UPDATE, "Updated repo: " + node.getName());
        node.setDescription(decryptDesc(node.getDescription(), user));
        ctx.result(json.writeValueAsString(node));
        ctx.contentType("application/json");
    }

    private static void handleCreateIdea(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        var body = json.readTree(ctx.body());
        IdeaNode node = new IdeaNode(
                body.path("name").asText(),
                encryptDesc(body.path("description").asText(""), user),
                user.getId(),
                body.path("vaultPath").asText(""),
                body.path("wordCount").asInt(0),
                body.path("backlinkCount").asInt(0),
                java.time.LocalDateTime.now(),
                body.path("tags").asText("")
        );
        ideaDao.insert(node);
        auditDao.log(node.getId(), user.getId(), AuditEvent.Action.CREATE, "Created idea: " + node.getName());
        node.setDescription(decryptDesc(node.getDescription(), user));
        // Sync back to the vault if the user has one configured. We write
        // the decrypted description so the .md file is human-readable —
        // the vault is the user's own file on their own disk, encryption
        // would defeat the point of "Obsidian is the source of truth".
        writeIdeaToVaultIfConfigured(node, user);
        ctx.status(201).result(json.writeValueAsString(node));
        ctx.contentType("application/json");
    }

    private static void handleUpdateIdea(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        long id = Long.parseLong(ctx.pathParam("id"));
        var existing = ideaDao.findById(id);
        if (existing.isEmpty()) { ctx.status(404).json(Map.of("error", "not found")); return; }
        IdeaNode node = existing.get();
        var body = json.readTree(ctx.body());
        if (body.has("name"))          node.setName(body.get("name").asText());
        if (body.has("description"))   node.setDescription(encryptDesc(body.get("description").asText(), user));
        if (body.has("vaultPath"))     node.setVaultPath(body.get("vaultPath").asText());
        if (body.has("wordCount"))     node.setWordCount(body.get("wordCount").asInt());
        if (body.has("backlinkCount")) node.setBacklinkCount(body.get("backlinkCount").asInt());
        if (body.has("tags"))          node.setTags(body.get("tags").asText());
        node.touch();
        ideaDao.update(node);
        auditDao.log(id, user.getId(), AuditEvent.Action.UPDATE, "Updated idea: " + node.getName());
        node.setDescription(decryptDesc(node.getDescription(), user));
        // Sync back to the vault — same reasoning as in create.
        writeIdeaToVaultIfConfigured(node, user);
        ctx.result(json.writeValueAsString(node));
        ctx.contentType("application/json");
    }

    private static void handleDelete(Context ctx, String type) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        long id = Long.parseLong(ctx.pathParam("id"));
        switch (type) {
            case "SUB"  -> subDao.delete(id);
            case "REPO" -> repoDao.delete(id);
            case "IDEA" -> ideaDao.delete(id);
        }
        auditDao.log(id, user.getId(), AuditEvent.Action.DELETE, "Deleted " + type + " id=" + id);
        ctx.json(Map.of("ok", true));
    }

    // =================================================================
    //  Aggregation
    // =================================================================

    private static void handleDashboard(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;

        List<SubNode>  subs  = subDao.findByOwner(user.getId());
        List<RepoNode> repos = repoDao.findByOwner(user.getId());
        List<IdeaNode> ideas = ideaDao.findByOwner(user.getId());

        List<RootNode> all = new java.util.ArrayList<>();
        all.addAll(subs); all.addAll(repos); all.addAll(ideas);

        List<Map<String, Object>> nodeList = new java.util.ArrayList<>();
        for (RootNode n : all) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id",        n.getId());
            m.put("type",      n.getType().name());
            m.put("name",      n.getName());
            m.put("vitality",  Math.round(n.getVitality() * 1000.0) / 1000.0);
            m.put("joyScore",  Math.round(n.getJoyScore() * 1000.0) / 1000.0);
            m.put("band",      ((Vitalizable) n).getVitalityBand());
            m.put("accent",    n.getType().getAccentHex());
            nodeList.add(m);
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("avgVitality",  Math.round(vitality.averageVitality(all) * 1000.0) / 1000.0);
        result.put("monthlyBurn",  Math.round(joyCost.totalMonthlyBurn(subs) * 100.0) / 100.0);
        result.put("totalNodes",   all.size());
        result.put("subCount",     subs.size());
        result.put("repoCount",    repos.size());
        result.put("ideaCount",    ideas.size());
        result.put("nodes",        nodeList);

        ctx.result(json.writeValueAsString(result));
        ctx.contentType("application/json");
    }

    private static void handleHeatmap(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;

        List<RootNode> all = new java.util.ArrayList<>();
        all.addAll(subDao.findByOwner(user.getId()));
        all.addAll(repoDao.findByOwner(user.getId()));
        all.addAll(ideaDao.findByOwner(user.getId()));

        CognitiveHeatmap.Heatmap map = heatmap.compute(all);

        List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        for (RootNode n : map.nodes()) {
            nodes.add(Map.of(
                    "id",       n.getId(),
                    "type",     n.getType().name(),
                    "name",     n.getName(),
                    "vitality", Math.round(n.getVitality() * 1000.0) / 1000.0,
                    "accent",   n.getType().getAccentHex()
            ));
        }

        List<Map<String, Object>> edges = new java.util.ArrayList<>();
        for (CognitiveHeatmap.Edge e : map.edges()) {
            edges.add(Map.of(
                    "source",     e.sourceId(),
                    "target",     e.targetId(),
                    "similarity", Math.round(e.similarity() * 1000.0) / 1000.0
            ));
        }

        ctx.result(json.writeValueAsString(Map.of("nodes", nodes, "edges", edges)));
        ctx.contentType("application/json");
    }

    private static void handleJoyCost(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;

        List<SubNode> subs = subDao.findByOwner(user.getId());
        // Pull 30-day touch counts once from the audit log — used by
        // JoyScoreCalculator's activity signal.
        java.util.Map<Long, Integer> touches = auditDao.touchCountByNode(user.getId(), 30);

        // Compute v2.1 score + breakdown for each subscription
        List<java.util.Map<String, Object>> subsWithScores = new java.util.ArrayList<>();
        double v2Sum = 0;
        double v2Weight = 0;
        for (SubNode s : subs) {
            int touchCount = touches.getOrDefault(s.getId(), 0);
            var b = JoyScoreCalculator.computeWithBreakdown(s, touchCount);
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id",               s.getId());
            row.put("name",             s.getName());
            row.put("description",      decryptDesc(s.getDescription(), user));
            row.put("monthlyCost",      s.getMonthlyCost());
            row.put("currency",         s.getCurrency());
            row.put("cadence",          s.getCadence().name());
            row.put("joyRating",        s.getJoyRating());
            row.put("lastTouched",      s.getLastTouched());
            row.put("touchCount30d",    touchCount);
            row.put("joyScoreV2",       round3(b.finalScore()));
            row.put("joyBreakdown",     java.util.Map.of(
                    "userRating",        round3(b.userRating()),
                    "recencyFactor",     round3(b.recencyFactor()),
                    "usageIntensity",    round3(b.usageIntensity()),
                    "activity",          round3(b.activity()),
                    "core",              round3(b.core()),
                    "engagementPenalty", round3(b.engagementPenalty()),
                    "costPenalty",       round3(b.costPenalty())
            ));
            subsWithScores.add(row);
            // Ecosystem-level mean, cost-weighted so expensive subs dominate
            v2Sum    += b.finalScore() * Math.max(s.getMonthlyCost(), 1);
            v2Weight += Math.max(s.getMonthlyCost(), 1);
        }
        double ecosystemV2 = v2Weight > 0 ? v2Sum / v2Weight : 0;

        ctx.result(json.writeValueAsString(java.util.Map.of(
                "monthlyBurn",       Math.round(joyCost.totalMonthlyBurn(subs) * 100.0) / 100.0,
                "weightedJoyScore",  round3(joyCost.weightedJoyScore(subs)),
                "ecosystemJoyV2",    round3(ecosystemV2),
                "burnByCurrency",    joyCost.burnByCurrency(subs),
                "subs",              subsWithScores,
                "formula", java.util.Map.of(
                        "version", "v2.1",
                        "core",    java.util.Map.of(
                                "userRatingWeight", JoyScoreCalculator.W_CORE_USER_RATING,
                                "activityWeight",   JoyScoreCalculator.W_CORE_ACTIVITY
                        ),
                        "penalties", java.util.Map.of(
                                "engagementFloor", JoyScoreCalculator.ENGAGEMENT_FLOOR,
                                "costFloor",       JoyScoreCalculator.COST_PENALTY_FLOOR,
                                "costCapInr",      JoyScoreCalculator.COST_CAP_INR
                        )
                )
        )));
        ctx.contentType("application/json");
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static void handleAudit(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);
        List<AuditEvent> events = user.isAdmin()
                ? auditDao.recent(limit)
                : auditDao.recentForUser(user.getId(), limit);
        ctx.result(json.writeValueAsString(events));
        ctx.contentType("application/json");
    }

    /**
     * GET /api/audit/verify — walk the audit log from the earliest row
     * and recompute every hash in the chain. Returns {@code intact:true}
     * if the whole log is unmodified, or {@code intact:false} with the
     * exact row ID where the chain was broken.
     *
     * <p>This endpoint is the proof mechanism for the tamper-evident
     * audit log: a professor can open DB Browser, manually mutate an
     * audit row, click "VERIFY CHAIN" in the UI, and see the exact
     * row ID where the tampering was detected.</p>
     */
    private static void handleAuditVerify(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        var result = auditDao.verifyChain();
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("intact",      result.intact);
        response.put("rowsChecked", result.rowsChecked);
        response.put("brokenAtId",  result.brokenAtId);
        response.put("reason",      result.reason);
        ctx.json(response);
    }

    /**
     * GET /api/skills — return the current user's extracted skills.
     * If no extraction has run yet, returns an empty list + a hint to
     * trigger a recompute.
     */
    private static void handleSkillsList(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        var skills = skillDao.findByOwner(user.getId());
        java.util.List<java.util.Map<String, Object>> payload = new java.util.ArrayList<>();
        for (var s : skills) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id",         s.getId());
            row.put("name",       s.getName());
            row.put("tokens",     s.getTokens());
            row.put("ideaCount",  s.getIdeaCount());
            row.put("confidence", round3(s.getConfidence()));
            // Signature lets the frontend send a stable key back for renames
            row.put("signature",  SkillDao.signatureOf(s.getTokens()));
            payload.add(row);
        }
        ctx.json(java.util.Map.of(
                "skills", payload,
                "count",  payload.size(),
                "hint",   payload.isEmpty()
                        ? "no skills extracted yet — click 'RE-EXTRACT' to build from your vault"
                        : ""
        ));
    }

    /**
     * POST /api/skills/recompute — atomically rebuild the user's
     * skill set from the current Idea corpus.
     */
    private static void handleSkillsRecompute(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        var skills = skillExtractor.recomputeForUser(user.getId());
        auditDao.log(null, user.getId(), AuditEvent.Action.UPDATE,
                "recomputed " + skills.size() + " skills from vault");
        ctx.json(java.util.Map.of(
                "count",  skills.size(),
                "ok",     true
        ));
    }

    /**
     * POST /api/skills/{id}/rename — user-initiated rename of a skill.
     * Persists to skill_overrides keyed by cluster signature so the
     * rename survives the next recompute.
     */
    private static void handleSkillRename(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        long id = Long.parseLong(ctx.pathParam("id"));
        var body = json.readTree(ctx.body());
        String newName = body.path("name").asText("").trim();
        if (newName.isEmpty()) {
            ctx.status(400).json(Map.of("error", "name is required"));
            return;
        }
        if (newName.length() > 60) {
            ctx.status(400).json(Map.of("error", "name too long (max 60 characters)"));
            return;
        }
        // Fetch the existing skill to get its signature
        var skills = skillDao.findByOwner(user.getId());
        var target = skills.stream().filter(s -> s.getId() == id).findFirst();
        if (target.isEmpty()) {
            ctx.status(404).json(Map.of("error", "skill not found"));
            return;
        }
        String sig = SkillDao.signatureOf(target.get().getTokens());
        skillDao.upsertOverride(user.getId(), sig, newName);
        // Apply the new name immediately so the next GET reflects it.
        // Atomic replace keeps the rest of the user's skills intact.
        java.util.List<com.atlas.roots.model.Skill> updated = new java.util.ArrayList<>();
        for (var s : skills) {
            if (s.getId() == id) s.setName(newName);
            updated.add(s);
        }
        skillDao.replaceAllForOwner(user.getId(), updated);
        auditDao.log(null, user.getId(), AuditEvent.Action.UPDATE,
                "renamed skill to '" + newName + "'");
        ctx.json(java.util.Map.of("ok", true, "name", newName));
    }

    /**
     * POST /api/skills/{id}/link/{subId} — link a subscription to a skill.
     * Idempotent: linking the same pair twice is a no-op.
     */
    private static void handleSkillLink(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        long skillId = Long.parseLong(ctx.pathParam("id"));
        long subId   = Long.parseLong(ctx.pathParam("subId"));
        // Ownership gate: the skill must belong to this user, and so must the sub.
        var skills = skillDao.findByOwner(user.getId());
        if (skills.stream().noneMatch(s -> s.getId() == skillId)) {
            ctx.status(404).json(Map.of("error", "skill not found"));
            return;
        }
        var subOpt = subDao.findById(subId);
        if (subOpt.isEmpty() || subOpt.get().getOwnerId() != user.getId()) {
            ctx.status(404).json(Map.of("error", "subscription not found"));
            return;
        }
        skillDao.linkSubToSkill(skillId, subId);
        ctx.json(Map.of("ok", true));
    }

    /** DELETE /api/skills/{id}/link/{subId} — unlink a sub from a skill. */
    private static void handleSkillUnlink(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        long skillId = Long.parseLong(ctx.pathParam("id"));
        long subId   = Long.parseLong(ctx.pathParam("subId"));
        // Same ownership gate as link
        var skills = skillDao.findByOwner(user.getId());
        if (skills.stream().noneMatch(s -> s.getId() == skillId)) {
            ctx.status(404).json(Map.of("error", "skill not found"));
            return;
        }
        skillDao.unlinkSubFromSkill(skillId, subId);
        ctx.json(Map.of("ok", true));
    }

    /**
     * GET /api/subs/{id}/skill-suggestions — return up to 3 skills that
     * likely match this subscription, based on token overlap.
     */
    private static void handleSubSuggestions(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        long subId = Long.parseLong(ctx.pathParam("id"));
        var subOpt = subDao.findById(subId);
        if (subOpt.isEmpty() || subOpt.get().getOwnerId() != user.getId()) {
            ctx.status(404).json(Map.of("error", "subscription not found"));
            return;
        }
        var sub = subOpt.get();
        // Decrypt the description so the suggester can match against real content
        sub.setDescription(decryptDesc(sub.getDescription(), user));
        var skills = skillDao.findByOwner(user.getId());
        var suggestions = SkillSuggester.suggestForSub(sub, skills);
        java.util.List<java.util.Map<String, Object>> payload = new java.util.ArrayList<>();
        for (var s : suggestions) {
            payload.add(java.util.Map.of(
                    "skillId",        s.skillId(),
                    "skillName",      s.skillName(),
                    "score",          round3(s.score()),
                    "matchedTokens",  s.matchedTokens()
            ));
        }
        ctx.json(Map.of("suggestions", payload));
    }

    /**
     * GET /api/subs/{id}/skills — return the skills currently linked
     * to this subscription.
     */
    private static void handleSubSkills(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        long subId = Long.parseLong(ctx.pathParam("id"));
        var subOpt = subDao.findById(subId);
        if (subOpt.isEmpty() || subOpt.get().getOwnerId() != user.getId()) {
            ctx.status(404).json(Map.of("error", "subscription not found"));
            return;
        }
        var skillIdsBySub = skillDao.skillIdsBySub(user.getId());
        var linkedIds = skillIdsBySub.getOrDefault(subId, java.util.List.of());
        var allSkills = skillDao.findByOwner(user.getId());
        java.util.List<java.util.Map<String, Object>> payload = new java.util.ArrayList<>();
        for (var skill : allSkills) {
            if (linkedIds.contains(skill.getId())) {
                payload.add(java.util.Map.of(
                        "id",   skill.getId(),
                        "name", skill.getName()
                ));
            }
        }
        ctx.json(Map.of("skills", payload));
    }

    /**
     * GET /api/context — return the full context export as JSON: the
     * Markdown prompt plus summary numbers for the Context page UI.
     */
    private static void handleContextExport(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        String prompt = contextExporter.exportPrompt(user, desc -> decryptDesc(desc, user));
        // Summary stats for the UI — skills count, subs count, token estimate
        int skillsCount = skillDao.findByOwner(user.getId()).size();
        int subsCount   = subDao.findByOwner(user.getId()).size();
        int ideasCount  = ideaDao.findByOwner(user.getId()).size();
        int tokenEst    = estimateTokens(prompt);
        auditDao.log(null, user.getId(), AuditEvent.Action.UPDATE,
                "exported context (~" + tokenEst + " tokens)");
        ctx.json(java.util.Map.of(
                "prompt",        prompt,
                "charCount",     prompt.length(),
                "estimatedTokens", tokenEst,
                "skillsCount",   skillsCount,
                "subsCount",     subsCount,
                "ideasCount",    ideasCount
        ));
    }

    /**
     * GET /api/context/prompt — return the raw Markdown prompt as
     * text/plain. Useful for piping (curl | pbcopy).
     */
    private static void handleContextExportRaw(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        String prompt = contextExporter.exportPrompt(user, desc -> decryptDesc(desc, user));
        ctx.contentType("text/plain; charset=utf-8");
        ctx.result(prompt);
    }

    /**
     * Rough token count estimate — LLMs average ~4 chars per token for
     * English prose. Good enough for a UI indicator; the actual count
     * depends on the tokenizer the target model uses.
     */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.round(text.length() / 4.0f);
    }

    private static void handleVaultScan(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        var body = json.readTree(ctx.body());
        String vaultPath = body.path("vaultPath").asText("");
        if (vaultPath.isBlank()) {
            ctx.status(400).json(Map.of("error", "vaultPath is required"));
            return;
        }
        Path vaultRoot = resolveVaultPath(vaultPath);
        if (!Files.isDirectory(vaultRoot)) {
            ctx.status(400).json(Map.of("error",
                    "not a directory: " + vaultRoot + " (input: '" + vaultPath + "')"));
            return;
        }
        var result = obsidian.scanVault(vaultRoot, user.getId());
        // Scanning a vault implicitly configures it — saves a manual step.
        vaultRootsByUser.put(user.getId(), vaultRoot);

        // Auto-re-extract skills after every scan. This keeps Skills
        // (and by extension, Context Export) always in sync with the
        // current vault without requiring the user to click a second
        // button. Failures here are non-fatal — the scan itself succeeded.
        int skillsCount = -1;
        try {
            skillsCount = skillExtractor.recomputeForUser(user.getId()).size();
        } catch (Exception autoFail) {
            // Deliberate soft-fail: the scan data is valuable on its own,
            // and skill extraction can be retried manually if it fails.
        }

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("totalFiles",   result.totalFiles());
        response.put("created",      result.created());
        response.put("updated",      result.updated());
        response.put("failed",       result.failed());
        response.put("skillsCount",  skillsCount);  // -1 means extraction failed
        ctx.json(response);
    }

    /**
     * Resolve a user-supplied vault path into a real filesystem Path.
     * Expands a leading tilde into the user's home directory (so
     * "~/Desktop/vault" works the way users expect from a terminal)
     * and converts to an absolute path.
     */
    private static Path resolveVaultPath(String input) {
        if (input == null || input.isBlank()) return null;
        String trimmed = input.trim();
        if (trimmed.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home"), trimmed.substring(2));
        }
        return Path.of(trimmed).toAbsolutePath();
    }

    /**
     * GET /api/vault/config — report this user's configured vault root,
     * if any, and whether the live FileWatcher is active.
     */
    private static void handleVaultConfigGet(Context ctx) {
        User user = requireAuth(ctx);
        if (user == null) return;
        Path vaultRoot = vaultRootsByUser.get(user.getId());
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("vaultPath", vaultRoot == null ? null : vaultRoot.toString());
        response.put("watching",  obsidian.isWatching());
        ctx.json(response);
    }

    /**
     * POST /api/vault/config — set this user's vault root. Does not
     * scan — use {@code POST /api/vault/scan} to trigger a full ingest.
     * Starting/stopping live watch is a separate call, below.
     */
    private static void handleVaultConfigSet(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        var body = json.readTree(ctx.body());
        String vaultPath = body.path("vaultPath").asText("");
        if (vaultPath.isBlank()) {
            vaultRootsByUser.remove(user.getId());
            ctx.json(Map.of("ok", true, "vaultPath", (Object) null));
            return;
        }
        Path vaultRoot = resolveVaultPath(vaultPath);
        if (!Files.isDirectory(vaultRoot)) {
            ctx.status(400).json(Map.of("error", "not a directory: " + vaultRoot));
            return;
        }
        vaultRootsByUser.put(user.getId(), vaultRoot);
        ctx.json(Map.of("ok", true, "vaultPath", vaultRoot.toString()));
    }

    /**
     * POST /api/vault/watch — toggle the FileWatcher. Request body:
     * {@code {enable: true}} or {@code {enable: false}}. The user must
     * have already configured a vault path via scan or config/set.
     */
    private static void handleVaultWatchToggle(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        var body = json.readTree(ctx.body());
        boolean enable = body.path("enable").asBoolean(true);
        Path vaultRoot = vaultRootsByUser.get(user.getId());
        if (vaultRoot == null) {
            ctx.status(400).json(Map.of("error", "configure a vault path first"));
            return;
        }
        if (enable) {
            obsidian.startWatching(vaultRoot, user.getId());
        } else {
            obsidian.close();
            // Reopen the bridge so subsequent scans still work — close()
            // nulls out the WatchService but leaves the DAO wiring intact.
        }
        ctx.json(Map.of("ok", true, "watching", obsidian.isWatching()));
    }

    /**
     * If the caller has a configured vault, write the given IdeaNode's
     * markdown back to the vault. Failures are logged but don't block
     * the calling mutation — the DB has already been updated and the
     * vault write is best-effort.
     */
    private static void writeIdeaToVaultIfConfigured(IdeaNode node, User user) {
        Path vaultRoot = vaultRootsByUser.get(user.getId());
        if (vaultRoot == null) return;
        if (node.getVaultPath() == null || node.getVaultPath().isBlank()) return;
        try {
            vaultWriter.writeNode(vaultRoot, node);
        } catch (java.io.IOException ioFail) {
            // Best-effort — record it in the audit log so we can trace
            // why a vault file wasn't updated, but don't fail the request.
            try {
                auditDao.log(node.getId(), user.getId(), AuditEvent.Action.UPDATE,
                        "vault write failed: " + ioFail.getMessage());
            } catch (SQLException ignored) {}
        }
    }

    /**
     * GET /api/export — stream the entire database back as a single
     * structured markdown file. Browser sees a download with a dated
     * filename so users can keep multiple snapshots.
     */
    private static void handleExport(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        String markdown = mdExporter.exportAll(user.getUsername());
        String filename = "roots-export-" +
                java.time.LocalDate.now() + ".md";
        ctx.header("Content-Type", "text/markdown; charset=utf-8");
        ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        ctx.result(markdown);
        try {
            auditDao.log(null, user.getId(), AuditEvent.Action.CREATE,
                    "exported database to markdown");
        } catch (SQLException ignored) {}
    }

    /**
     * POST /api/import — accept a markdown payload + a merge mode
     * (OVERWRITE / SKIP / REPLACE) and apply it under the current user.
     */
    private static void handleImport(Context ctx) throws Exception {
        User user = requireAuth(ctx);
        if (user == null) return;
        var body = json.readTree(ctx.body());
        String markdown = body.path("markdown").asText("");
        String modeStr  = body.path("mode").asText("OVERWRITE");
        if (markdown.isBlank()) {
            ctx.status(400).json(Map.of("error", "markdown is required"));
            return;
        }
        MarkdownImporter.Mode mode;
        try {
            mode = MarkdownImporter.Mode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException bad) {
            ctx.status(400).json(Map.of("error", "mode must be OVERWRITE, SKIP, or REPLACE"));
            return;
        }
        var result = mdImporter.importMarkdown(markdown, user.getId(), mode);
        try {
            auditDao.log(null, user.getId(), AuditEvent.Action.CREATE,
                    "imported markdown (" + mode + "): " + result.created + " new, " + result.updated + " updated");
        } catch (SQLException ignored) {}
        ctx.json(Map.of(
                "created", result.created,
                "updated", result.updated,
                "skipped", result.skipped,
                "failed",  result.failed,
                "errors",  result.errors
        ));
    }

    // =================================================================
    //  Auth helpers
    // =================================================================

    private static User requireAuth(Context ctx) {
        User user = sessions.get(tokenFrom(ctx));
        if (user == null) {
            ctx.status(401).json(Map.of("error", "not authenticated"));
            return null;
        }
        return user;
    }

    private static String tokenFrom(Context ctx) {
        String header = ctx.header("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    // =================================================================
    //  Encryption helpers — wrap/unwrap node descriptions with the
    //  session's per-user AES key. Other fields are left in plaintext
    //  because they need to be queryable (costs, dates, tags) and
    //  field-level encryption of queryable columns defeats the point.
    // =================================================================

    private static String encryptDesc(String desc, User user) {
        if (user == null) return desc;
        byte[] key = guardian.getDataKey(user.getId());
        return CryptoService.encryptString(desc, key);
    }

    private static String decryptDesc(String desc, User user) {
        if (user == null) return desc;
        byte[] key = guardian.getDataKey(user.getId());
        return CryptoService.decryptString(desc, key);
    }

    private static void decryptSubList(java.util.List<SubNode> list, User user) {
        for (SubNode s : list) s.setDescription(decryptDesc(s.getDescription(), user));
    }
    private static void decryptRepoList(java.util.List<RepoNode> list, User user) {
        for (RepoNode r : list) r.setDescription(decryptDesc(r.getDescription(), user));
    }
    private static void decryptIdeaList(java.util.List<IdeaNode> list, User user) {
        for (IdeaNode i : list) i.setDescription(decryptDesc(i.getDescription(), user));
    }
}
