package com.atlas.roots.debug;

import com.atlas.roots.dao.*;
import com.atlas.roots.db.DatabaseManager;
import com.atlas.roots.model.*;
import com.atlas.roots.service.*;

import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end smoke test for the Roots backend.
 *
 * <p>This is not a JUnit test &mdash; it's a runnable main class that
 * exercises every layer of the application in sequence and prints what
 * happens. Run with:</p>
 *
 * <pre>mvn -q exec:java -Dexec.mainClass=com.atlas.roots.debug.SmokeTest</pre>
 *
 * <p>If this class runs cleanly end to end, it proves that the database
 * bootstrap, the DAO layer, the auth service, the vitality/joy/cost
 * services, and the TF-IDF cognitive heatmap all work together on a
 * real SQLite file. Once proven, we delete this whole package and
 * build the UI on top.</p>
 */
public final class SmokeTest {

    private static final String RULE = "─".repeat(72);

    public static void main(String[] args) throws Exception {
        section("BOOT");
        DatabaseManager db = DatabaseManager.getInstance();
        System.out.println("database file: " + db.getDbPath());
        db.bootstrapIfNeeded();
        System.out.println("schema + seed OK");

        section("AUTH");
        UserDao        userDao   = new UserDao(db);
        AuditDao       auditDao  = new AuditDao(db);
        VaultGuardian  guardian  = new VaultGuardian(userDao, auditDao);

        var session = guardian.login("draco", "roots2026");
        if (session.isEmpty()) {
            System.err.println("login FAILED");
            System.exit(1);
        }
        User me = session.get();
        System.out.printf("logged in as %s (id=%d, role=%s)%n",
                me.getUsername(), me.getId(), me.getRole());

        section("LOAD NODES");
        SubNodeDao  subDao  = new SubNodeDao(db);
        RepoNodeDao repoDao = new RepoNodeDao(db);
        IdeaNodeDao ideaDao = new IdeaNodeDao(db);

        List<SubNode>  subs  = subDao.findByOwner(me.getId());
        List<RepoNode> repos = repoDao.findByOwner(me.getId());
        List<IdeaNode> ideas = ideaDao.findByOwner(me.getId());

        System.out.printf("subscriptions: %d%n", subs.size());
        System.out.printf("repositories:  %d%n", repos.size());
        System.out.printf("ideas:         %d%n", ideas.size());

        // Heterogeneous list — the polymorphism pay-off lives here.
        List<RootNode> all = new ArrayList<>();
        all.addAll(subs);
        all.addAll(repos);
        all.addAll(ideas);
        System.out.printf("total nodes:   %d%n", all.size());

        section("VITALITY — polymorphic ranking");
        VitalityCalculator vital = new VitalityCalculator();
        List<RootNode> ranked = vital.rankByVitality(all);
        System.out.printf("ecosystem average vitality: %.3f%n%n", vital.averageVitality(all));
        System.out.printf("%-5s  %-32s  %6s  %-9s%n", "TYPE", "NAME", "VITAL", "BAND");
        System.out.println(RULE);
        for (RootNode n : ranked) {
            System.out.printf("%-5s  %-32s  %6.3f  %-9s%n",
                    n.displayToken(),
                    truncate(n.getName(), 32),
                    n.getVitality(),
                    ((Vitalizable) n).getVitalityBand());
        }

        section("JOY-TO-COST — financial drain");
        JoyCostAnalyzer joy = new JoyCostAnalyzer();
        double burn = joy.totalMonthlyBurn(subs);
        System.out.printf("total monthly burn: ₹%.2f%n", burn);
        System.out.printf("weighted joy score: %.3f%n%n", joy.weightedJoyScore(subs));

        System.out.println("worst offenders:");
        System.out.printf("  %-24s  %8s  %6s%n", "NAME", "₹/MONTH", "J/C");
        System.out.println("  " + RULE.substring(2));
        for (Drainable d : joy.worstOffenders(subs, 3)) {
            SubNode s = (SubNode) d;
            System.out.printf("  %-24s  %8.2f  %6.3f%n",
                    truncate(s.getName(), 24),
                    s.getMonthlyDrain(),
                    s.getJoyToCostRatio());
        }

        System.out.println("\nbest value:");
        for (Drainable d : joy.bestValue(subs, 3)) {
            SubNode s = (SubNode) d;
            System.out.printf("  %-24s  %8.2f  %6.3f%n",
                    truncate(s.getName(), 24),
                    s.getMonthlyDrain(),
                    s.getJoyToCostRatio());
        }

        section("COGNITIVE HEATMAP — TF-IDF similarity");
        CognitiveHeatmap heatmap = new CognitiveHeatmap();
        CognitiveHeatmap.Heatmap map = heatmap.compute(all);
        System.out.printf("edges above threshold: %d%n%n", map.edges().size());

        if (!map.edges().isEmpty()) {
            // Sort edges descending for nicer output
            var sortedEdges = new ArrayList<>(map.edges());
            sortedEdges.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));

            System.out.printf("  %-28s  %-28s  %s%n", "SOURCE", "TARGET", "SIM");
            System.out.println("  " + RULE.substring(2));
            for (var e : sortedEdges) {
                String src = findName(all, e.sourceId());
                String tgt = findName(all, e.targetId());
                System.out.printf("  %-28s  %-28s  %.3f%n",
                        truncate(src, 28),
                        truncate(tgt, 28),
                        e.similarity());
            }
        } else {
            System.out.println("(no edges above 0.15 — vault may be too small or too diverse)");
        }

        section("LOGOUT");
        guardian.logout();
        System.out.println("session ended.");

        System.out.println("\n" + RULE);
        System.out.println("SMOKE TEST PASSED — every layer is live.");
        System.out.println(RULE);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println(RULE);
        System.out.println("  " + title);
        System.out.println(RULE);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String findName(List<RootNode> nodes, long id) {
        for (RootNode n : nodes) if (n.getId() == id) return n.getName();
        return "?" + id;
    }
}
