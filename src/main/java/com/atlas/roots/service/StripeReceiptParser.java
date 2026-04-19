package com.atlas.roots.service;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generalized receipt parser — extracts subscription data from any
 * billing email, not just Stripe receipts.
 *
 * <h2>Design</h2>
 * <p>One parser handles 50+ services because we extract <em>structure</em>,
 * not vendor-specific formatting:</p>
 * <ol>
 *   <li><b>Business name</b> — derived in order of preference: subject-line
 *       mention first ("Your receipt from X"), then sender display name
 *       ("Cursor Billing &lt;billing@cursor.com&gt;"), then domain root
 *       ("cursor.com" → "Cursor").</li>
 *   <li><b>Amount + currency</b> — first currency-annotated number in the
 *       first 2000 chars of the stripped HTML body.</li>
 *   <li><b>Cadence</b> — keyword scan. Defaults to MONTHLY, upgrades to
 *       YEARLY or WEEKLY on strong signals.</li>
 * </ol>
 *
 * <p>Class name is kept as {@code StripeReceiptParser} for backwards
 * compatibility with the rest of the codebase; the parser is no longer
 * Stripe-specific.</p>
 */
public final class StripeReceiptParser {

    // Subject patterns — most-specific to least-specific. First match wins.
    private static final Pattern SUBJECT_FROM = Pattern.compile(
            "(?i)(?:your )?receipt from ([^#\\[\\n,]+?)(?:\\s*[\\[#,]|$)");
    private static final Pattern SUBJECT_PAYMENT_TO = Pattern.compile(
            "(?i)payment to ([^\\n,#\\[]+?)(?:\\s*[,#\\[]|\\s+(?:for|of)\\b|$)");
    private static final Pattern SUBJECT_YOUR_X = Pattern.compile(
            "(?i)(?:your|welcome to) ([A-Z][a-zA-Z0-9 .]+?)(?:\\s+(?:subscription|plan|account|team|pro|premium)|$)");
    private static final Pattern SUBJECT_INVOICE = Pattern.compile(
            "(?i)invoice (?:from|for) ([^#\\[,\\n]+?)(?:\\s*[,#\\[]|$)");

    // "Cursor Billing <billing@cursor.com>" → "Cursor Billing"
    private static final Pattern SENDER_DISPLAY_NAME = Pattern.compile(
            "^\\s*\"?([^\"<]+?)\"?\\s*<");

    // "billing@cursor.com" → "cursor.com"
    private static final Pattern SENDER_DOMAIN = Pattern.compile(
            "@([a-zA-Z0-9.\\-]+?)(?:>|\\s|$)");

    // "$20.00", "₹1,599.00", "€9.99"
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "([₹$€£¥])\\s?([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{2})?)");

    // "USD 20.00", "INR 1599.00"
    private static final Pattern CURRENCY_CODE_AMOUNT = Pattern.compile(
            "(USD|INR|EUR|GBP|JPY|CAD|AUD)\\s+([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{2})?)");

    // Platforms that aren't the actual subscription
    private static final Set<String> GENERIC_PLATFORM_DOMAINS = Set.of(
            "stripe.com", "paypal.com", "razorpay.com", "squareup.com",
            "gmail.com", "google.com", "amazonaws.com");

    // Non-receipt subjects we actively skip
    private static final Set<String> NEGATIVE_SUBJECT_HINTS = Set.of(
            "reminder", "expires soon", "renewal", "welcome", "action required",
            "payment failed", "update your", "verify your", "confirm your");

    private StripeReceiptParser() {}

    /**
     * Parse a {@link GmailClient.Message} into a {@link ParsedReceipt}.
     * Returns empty if the message can't be confidently classified.
     */
    public static Optional<ParsedReceipt> parse(GmailClient.Message msg) {
        if (msg == null) return Optional.empty();

        String subject = msg.subject() == null ? "" : msg.subject();
        // Early reject: reminder / promotional / dunning emails
        String subjectLower = subject.toLowerCase();
        for (String negative : NEGATIVE_SUBJECT_HINTS) {
            if (subjectLower.contains(negative)) return Optional.empty();
        }

        String businessName = extractBusinessName(subject, msg.from());
        if (businessName == null || businessName.isBlank()) return Optional.empty();

        String body = stripHtml(msg.body() == null ? "" : msg.body());
        AmountCurrency ac = extractAmount(subject + " " + body);
        if (ac == null) return Optional.empty();

        // Skip $0 receipts (free trial confirmations) and absurd amounts
        if (ac.amount <= 0 || ac.amount > 100000) return Optional.empty();

        Cadence cadence = detectCadence(body);

        return Optional.of(new ParsedReceipt(
                businessName.trim(),
                ac.amount,
                ac.currency,
                cadence,
                msg.internalDateMillis(),
                msg.id()
        ));
    }

    // -----------------------------------------------------------------
    //  Business name extraction
    // -----------------------------------------------------------------

    static String extractBusinessName(String subject, String from) {
        // Try each subject pattern in order of confidence
        for (Pattern p : new Pattern[] {
                SUBJECT_FROM, SUBJECT_PAYMENT_TO, SUBJECT_INVOICE, SUBJECT_YOUR_X
        }) {
            Matcher m = p.matcher(subject == null ? "" : subject);
            if (m.find()) {
                String candidate = cleanName(m.group(1));
                if (!candidate.isEmpty() && !isPlatformNoise(candidate)) return candidate;
            }
        }

        // Fall back to sender display name
        if (from != null && !from.isBlank()) {
            Matcher display = SENDER_DISPLAY_NAME.matcher(from);
            if (display.find()) {
                String name = cleanName(display.group(1));
                name = name.replaceAll("(?i)\\s+(billing|team|support|receipts?|no[- ]?reply)$", "").trim();
                if (!name.isEmpty() && !isPlatformNoise(name)) return name;
            }
        }

        // Last resort: sender domain
        if (from != null) {
            Matcher domain = SENDER_DOMAIN.matcher(from);
            if (domain.find()) {
                String fullDomain = domain.group(1).toLowerCase();
                fullDomain = fullDomain.replaceAll("^(email|mail|account|send|notify|t|e)\\.", "");
                if (GENERIC_PLATFORM_DOMAINS.contains(fullDomain)) return null;
                String root = fullDomain.split("\\.")[0];
                if (root.isEmpty()) return null;
                return capitalize(root);
            }
        }
        return null;
    }

    private static String cleanName(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("[\\s,]+$", "").replaceAll("^[\\s,]+", "");
    }

    private static boolean isPlatformNoise(String candidate) {
        if (candidate == null) return true;
        String lc = candidate.toLowerCase();
        return lc.equals("inc") || lc.equals("llc") || lc.equals("ltd")
            || lc.equals("team") || lc.equals("billing") || lc.equals("support")
            || lc.equals("stripe");
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // -----------------------------------------------------------------
    //  Amount + cadence
    // -----------------------------------------------------------------

    static AmountCurrency extractAmount(String text) {
        if (text == null || text.isEmpty()) return null;
        String region = text.length() > 2000 ? text.substring(0, 2000) : text;

        Matcher sym = AMOUNT_PATTERN.matcher(region);
        if (sym.find()) {
            return new AmountCurrency(
                    parseDouble(sym.group(2)),
                    symbolToCode(sym.group(1))
            );
        }
        Matcher code = CURRENCY_CODE_AMOUNT.matcher(region);
        if (code.find()) {
            return new AmountCurrency(
                    parseDouble(code.group(2)),
                    code.group(1)
            );
        }
        return null;
    }

    static Cadence detectCadence(String body) {
        if (body == null) return Cadence.MONTHLY;
        String lower = body.toLowerCase();
        if (lower.contains("yearly") || lower.contains("annual") || lower.contains("per year")
                || lower.contains("/year") || lower.contains("annually")) return Cadence.YEARLY;
        if (lower.contains("weekly") || lower.contains("per week") || lower.contains("/week")) return Cadence.WEEKLY;
        return Cadence.MONTHLY;
    }

    private static double parseDouble(String formattedAmount) {
        if (formattedAmount == null) return 0;
        return Double.parseDouble(formattedAmount.replace(",", ""));
    }

    private static String symbolToCode(String symbol) {
        return switch (symbol) {
            case "$" -> "USD";
            case "₹" -> "INR";
            case "€" -> "EUR";
            case "£" -> "GBP";
            case "¥" -> "JPY";
            default  -> "USD";
        };
    }

    static String stripHtml(String html) {
        if (html == null) return "";
        return html
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&#8217;", "'")
                .replaceAll("&#8216;", "'")
                .replaceAll("&#36;", "$")
                .replaceAll("\\s+", " ");
    }

    public enum Cadence { MONTHLY, YEARLY, WEEKLY }

    public record ParsedReceipt(
            String  businessName,
            double  amount,
            String  currency,
            Cadence cadence,
            long    receiptDateMillis,
            String  gmailMessageId
    ) {}

    record AmountCurrency(double amount, String currency) {}
}
