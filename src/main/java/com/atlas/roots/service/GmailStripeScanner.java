package com.atlas.roots.service;

import com.atlas.roots.dao.SubNodeDao;
import com.atlas.roots.model.SubNode;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Orchestrator for scanning a Gmail inbox for Stripe receipts and
 * turning them into review-ready subscription candidates.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>List message IDs matching {@code from:receipts@stripe.com
 *       newer_than:180d}</li>
 *   <li>Fetch each message's body + subject</li>
 *   <li>Parse each into a {@link StripeReceiptParser.ParsedReceipt}</li>
 *   <li>Dedupe by business name — keep only the most recent receipt
 *       per business (one row per actual subscription)</li>
 *   <li>Mark each candidate as NEW or EXISTING by checking against the
 *       user's existing SubNodes (case-insensitive name match)</li>
 * </ol>
 *
 * <h2>What this class does NOT do</h2>
 * <p>It doesn't write to the database. Import happens through a
 * separate call after the user confirms via the review screen. This
 * separation means the scanner is idempotent and re-runnable — a user
 * can scan, cancel, scan again, and no side effects leak between runs.</p>
 */
public final class GmailStripeScanner {

    /**
     * Gmail search query for receipt discovery.
     *
     * <h2>How this query works</h2>
     * <p>Two clauses OR'd together:</p>
     * <ol>
     *   <li><b>Known billing senders</b> — ~50 specific addresses for services
     *       most likely to bill a tech-forward user. Includes Stripe-generic
     *       receipts (which catches the long tail of indie SaaS), the major
     *       AI/dev tool vendors, streaming platforms, creative tools, and
     *       India-specific subscription services.</li>
     *   <li><b>Subject heuristics</b> — phrases that strongly indicate a
     *       receipt even when the sender isn't in our list. Casts a wider
     *       net at the cost of some false positives, which the user filters
     *       via the review modal.</li>
     * </ol>
     *
     * <p>The 12-month window is wide enough to catch annual subscriptions
     * that only send a receipt once per year.</p>
     *
     * <p>Gmail query operators: parentheses group clauses, {@code OR} is
     * case-sensitive, {@code from:} matches the sender field, and
     * {@code newer_than:} uses Gmail's relative-time shorthand.</p>
     */
    private static final String QUERY =
            "((from:(" + String.join(" OR ",
                    // --- Stripe-generic (catches long-tail indie SaaS) ---
                    "receipts@stripe.com",
                    // --- AI + dev tools ---
                    "noreply@anthropic.com", "support@anthropic.com",
                    "noreply@openai.com", "billing@openai.com",
                    "billing@cursor.com", "receipts@cursor.sh",
                    "noreply@github.com", "billing@github.com",
                    "team@mail.notion.so",
                    "noreply@linear.app",
                    "billing@figma.com",
                    "invoice+statements@vercel.com",
                    "billing@netlify.com",
                    "hello@raycast.com",
                    "billing@replit.com",
                    "noreply@supabase.io",
                    "billing@cloudflare.com",
                    "receipts@1password.com",
                    "hello@tana.inc",
                    // --- Streaming / audio / video ---
                    "no-reply@spotify.com",
                    "no_reply@email.apple.com",
                    "youtubepremium-noreply@youtube.com",
                    "info@account.netflix.com",
                    "no-reply@disneyplus.com",
                    "no-reply@hulumail.com",
                    "hbomaxmail@mail.hbomax.com",
                    "digital-no-reply@amazon.com",
                    "no-reply@tidal.com",
                    // --- Comms / social ---
                    "verify@x.com", "info@twitter.com",
                    "noreply@discord.com",
                    "feedback@slack.com",
                    "no-reply@zoom.us",
                    "noreply@telegram.org",
                    // --- Creative ---
                    "mail@email.adobe.com", "message@adobe.com",
                    "hello@canva.com",
                    "team@framer.com",
                    "no-reply@dropbox.com",
                    "googleplay-noreply@google.com",
                    // --- Writing / learning / news ---
                    "no-reply@substack.com",
                    "noreply@medium.com",
                    "hello@duolingo.com",
                    "nytdirect@nytimes.com",
                    "wsj@emails.wsj.com",
                    "do-not-reply@amazon.com",
                    // --- Fitness / lifestyle ---
                    "noreply@strava.com",
                    "help@headspace.com",
                    "care@calm.com",
                    "support@onepeloton.com",
                    // --- India-specific subscription services ---
                    "no-reply@hotstar.com",
                    "support@jiosaavn.com",
                    "no-reply@zomato.com",
                    "no-reply@swiggy.in",
                    "support@cred.club",
                    "noreply@cultfit.com",
                    "billing@razorpay.com",
                    // --- Payment processors (catch whatever they bill for) ---
                    "receipt@paypal.com", "service@paypal.com"
            ) + ")) OR (subject:(" + String.join(" OR ",
                    "\"your receipt\"",
                    "\"receipt from\"",
                    "\"payment received\"",
                    "\"payment successful\"",
                    "\"thanks for your payment\"",
                    "\"subscription confirmed\"",
                    "\"invoice\"",
                    "\"order confirmation\""
            ) + ")))"
            + " newer_than:365d";

    /** Cap — broader query means more hits; we still dedupe heavily. */
    private static final int MAX_MESSAGES = 150;

    /** Small sleep between message fetches to be polite to the API. */
    private static final long FETCH_DELAY_MS = 40L;

    private final GmailClient gmail;
    private final SubNodeDao  subDao;

    public GmailStripeScanner(GmailClient gmail, SubNodeDao subDao) {
        this.gmail  = gmail;
        this.subDao = subDao;
    }

    /**
     * Run a full scan for one user and return a list of candidates,
     * deduped and classified as NEW or EXISTING.
     *
     * @param accessToken valid Gmail OAuth access token
     * @param ownerId     the Roots user whose inbox we're scanning
     * @return scan result containing candidates + summary counts
     */
    public ScanResult scan(String accessToken, long ownerId) throws IOException, SQLException {
        // Step 1: list matching message IDs
        List<String> ids = gmail.listMessageIds(accessToken, QUERY, MAX_MESSAGES);

        // Step 2+3: fetch and parse each message
        List<StripeReceiptParser.ParsedReceipt> parsed = new ArrayList<>();
        int fetchFailures  = 0;
        int parseFailures  = 0;
        for (String id : ids) {
            try {
                GmailClient.Message msg = gmail.fetchMessage(accessToken, id);
                var receipt = StripeReceiptParser.parse(msg);
                if (receipt.isPresent()) {
                    parsed.add(receipt.get());
                } else {
                    parseFailures++;
                }
                Thread.sleep(FETCH_DELAY_MS);
            } catch (InterruptedException intr) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException fetchFail) {
                fetchFailures++;
            }
        }

        // Step 4: dedupe by business name, keep newest receipt per business
        Map<String, StripeReceiptParser.ParsedReceipt> bestByName = new LinkedHashMap<>();
        for (var r : parsed) {
            String key = r.businessName().toLowerCase().trim();
            var existing = bestByName.get(key);
            if (existing == null || r.receiptDateMillis() > existing.receiptDateMillis()) {
                bestByName.put(key, r);
            }
        }

        // Step 5: classify as NEW or EXISTING against Roots' current subs
        List<SubNode> currentSubs = subDao.findByOwner(ownerId);
        Set<String> existingNames = new HashSet<>();
        for (SubNode s : currentSubs) {
            if (s.getName() != null) existingNames.add(s.getName().toLowerCase().trim());
        }

        List<Candidate> candidates = new ArrayList<>();
        for (var r : bestByName.values()) {
            boolean exists = existingNames.contains(r.businessName().toLowerCase().trim());
            candidates.add(new Candidate(
                    r.businessName(),
                    r.amount(),
                    r.currency(),
                    r.cadence().name(),
                    r.receiptDateMillis(),
                    exists
            ));
        }

        // Order candidates: new ones first (they're the interesting ones to import)
        candidates.sort((a, b) -> {
            if (a.alreadyExists != b.alreadyExists) return a.alreadyExists ? 1 : -1;
            return b.receiptDateMillis - a.receiptDateMillis > 0 ? 1 : -1;
        });

        return new ScanResult(
                ids.size(),
                parsed.size(),
                bestByName.size(),
                fetchFailures,
                parseFailures,
                candidates
        );
    }

    /** A subscription candidate, ready for the review screen. */
    public record Candidate(
            String  name,
            double  monthlyCost,
            String  currency,
            String  cadence,
            long    receiptDateMillis,
            boolean alreadyExists
    ) {}

    /** Summary of a scan run. */
    public record ScanResult(
            int messagesFound,
            int messagesParsed,
            int uniqueBusinesses,
            int fetchFailures,
            int parseFailures,
            List<Candidate> candidates
    ) {}
}
