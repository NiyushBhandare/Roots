package com.atlas.roots.ui;

import com.atlas.roots.dao.IdeaNodeDao;
import com.atlas.roots.dao.RepoNodeDao;
import com.atlas.roots.dao.SubNodeDao;
import com.atlas.roots.model.*;
import com.atlas.roots.service.CognitiveHeatmap;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.text.Text;

import java.sql.SQLException;
import java.util.*;

/**
 * The Cognitive Heatmap view.
 *
 * <p>Computes a TF-IDF similarity graph over every node owned by the
 * current user, then renders it as a force-directed layout: each node
 * is a glowing circle (typed colours), each edge is a line whose
 * opacity scales with similarity. A simple Fruchterman-Reingold
 * simulation runs continuously in an {@link AnimationTimer}, so the
 * layout slowly settles in front of the user instead of snapping into
 * place. The result feels alive &mdash; nodes drift, edges flex,
 * clusters form.</p>
 *
 * <p>This is the showpiece. The whole project's claim "the system
 * discovers what you didn't tell it" lives or dies on this screen.</p>
 */
public class HeatmapView {

    private final User              user;
    private final CognitiveHeatmap  engine;
    private final SubNodeDao        subDao;
    private final RepoNodeDao       repoDao;
    private final IdeaNodeDao       ideaDao;

    private final BorderPane root  = new BorderPane();
    private final Pane       canvas = new Pane();

    // Physics state
    private final List<Particle> particles = new ArrayList<>();
    private final List<EdgeRef>  edges     = new ArrayList<>();
    private double w = 1100;
    private double h = 620;

    public HeatmapView(User user, CognitiveHeatmap engine,
                       SubNodeDao subDao, RepoNodeDao repoDao, IdeaNodeDao ideaDao) {
        this.user   = user;
        this.engine = engine;
        this.subDao = subDao;
        this.repoDao = repoDao;
        this.ideaDao = ideaDao;
        build();
    }

    public BorderPane getRoot() { return root; }

    private void build() {
        try {
            List<RootNode> all = new ArrayList<>();
            all.addAll(subDao.findByOwner(user.getId()));
            all.addAll(repoDao.findByOwner(user.getId()));
            all.addAll(ideaDao.findByOwner(user.getId()));

            CognitiveHeatmap.Heatmap map = engine.compute(all);

            VBox container = new VBox(24);
            container.setPadding(new Insets(48, 64, 24, 64));

            // Header
            Text title = new Text("Cognitive map");
            title.setStyle("-fx-font-family: '" + Theme.FONT_DISPLAY.split(",")[0].trim() +
                           "'; -fx-font-size: 56px; -fx-fill: " + Theme.BONE_HEX + ";");

            Label subtitle = new Label(
                    "TF-IDF cosine similarity over " + all.size() + " nodes  ·  " +
                    map.edges().size() + " edges discovered");
            subtitle.setStyle("-fx-text-fill: " + Theme.ASH_HEX + "; -fx-font-style: italic;");

            container.getChildren().addAll(title, subtitle);

            // Canvas area
            canvas.setPrefSize(w, h);
            canvas.setStyle("-fx-background-color: " + Theme.SOIL_HEX + ";" +
                            "-fx-border-color: " + Theme.LOAM_HEX + ";" +
                            "-fx-border-width: 1;");
            container.getChildren().add(canvas);

            initParticles(map);
            renderCanvas(map);
            startSimulation();

            root.setCenter(container);
        } catch (SQLException e) {
            Label err = new Label("could not compute heatmap: " + e.getMessage());
            err.setStyle("-fx-text-fill: " + Theme.CORAL_HEX + ";");
            root.setCenter(err);
        }
    }

    // -----------------------------------------------------------------
    //  Physics — Fruchterman-Reingold force-directed layout
    // -----------------------------------------------------------------

    private void initParticles(CognitiveHeatmap.Heatmap map) {
        particles.clear();
        edges.clear();
        Random rng = new Random(7);
        Map<Long, Particle> byId = new HashMap<>();
        for (RootNode node : map.nodes()) {
            Particle p = new Particle();
            p.node = node;
            p.x    = w / 2 + rng.nextGaussian() * 80;
            p.y    = h / 2 + rng.nextGaussian() * 80;
            particles.add(p);
            byId.put(node.getId(), p);
        }
        for (CognitiveHeatmap.Edge e : map.edges()) {
            Particle a = byId.get(e.sourceId());
            Particle b = byId.get(e.targetId());
            if (a != null && b != null) {
                EdgeRef ref = new EdgeRef();
                ref.a = a; ref.b = b; ref.weight = e.similarity();
                edges.add(ref);
            }
        }
    }

    private void renderCanvas(CognitiveHeatmap.Heatmap map) {
        canvas.getChildren().clear();

        // Edges first so they go behind nodes
        for (EdgeRef e : edges) {
            Line line = new Line();
            line.setStartX(e.a.x); line.setStartY(e.a.y);
            line.setEndX(e.b.x);   line.setEndY(e.b.y);
            // Opacity scales with similarity; brighter = more related
            double alpha = Math.min(0.7, 0.15 + e.weight * 1.2);
            line.setStroke(Color.web(Theme.LILAC_HEX, alpha));
            line.setStrokeWidth(0.6 + e.weight * 1.6);
            e.line = line;
            canvas.getChildren().add(line);
        }

        // Nodes
        for (Particle p : particles) {
            double r = 6 + 14 * p.node.getVitality();
            Circle c = new Circle(p.x, p.y, r);
            Color base = Theme.accentFor(p.node.displayToken());
            c.setFill(Color.color(base.getRed(), base.getGreen(), base.getBlue(), 0.35));
            c.setStroke(base);
            c.setStrokeWidth(1.0);
            p.circle = c;

            Label tag = new Label(truncate(p.node.getName(), 22));
            tag.setStyle("-fx-text-fill: " + Theme.BONE_HEX + ";" +
                         "-fx-font-size: 10px;" +
                         "-fx-background-color: " + Theme.SOIL_HEX + "CC;" +
                         "-fx-padding: 1 4 1 4;");
            tag.setLayoutX(p.x + r + 4);
            tag.setLayoutY(p.y - 7);
            p.label = tag;

            canvas.getChildren().addAll(c, tag);
        }
    }

    private void startSimulation() {
        AnimationTimer timer = new AnimationTimer() {
            long lastNs = 0;
            int  ticks = 0;

            @Override
            public void handle(long now) {
                if (lastNs == 0) { lastNs = now; return; }
                lastNs = now;

                step();

                // Update positions of bound nodes
                for (Particle p : particles) {
                    if (p.circle != null) {
                        p.circle.setCenterX(p.x);
                        p.circle.setCenterY(p.y);
                    }
                    if (p.label != null) {
                        double r = p.circle.getRadius();
                        p.label.setLayoutX(p.x + r + 4);
                        p.label.setLayoutY(p.y - 7);
                    }
                }
                for (EdgeRef e : edges) {
                    if (e.line != null) {
                        e.line.setStartX(e.a.x); e.line.setStartY(e.a.y);
                        e.line.setEndX(e.b.x);   e.line.setEndY(e.b.y);
                    }
                }

                ticks++;
                // The system slowly cools so it eventually settles.
                if (ticks > 1200) stop();
            }
        };
        timer.start();
    }

    /** One Fruchterman-Reingold step. */
    private void step() {
        double area = w * h;
        double k = Math.sqrt(area / Math.max(1, particles.size())) * 0.55;

        // Repulsive forces (every pair)
        for (int i = 0; i < particles.size(); i++) {
            Particle pi = particles.get(i);
            pi.vx = 0; pi.vy = 0;
            for (int j = 0; j < particles.size(); j++) {
                if (i == j) continue;
                Particle pj = particles.get(j);
                double dx = pi.x - pj.x;
                double dy = pi.y - pj.y;
                double dist = Math.max(1, Math.sqrt(dx*dx + dy*dy));
                double force = (k * k) / dist;
                pi.vx += (dx / dist) * force;
                pi.vy += (dy / dist) * force;
            }
        }

        // Attractive forces along edges
        for (EdgeRef e : edges) {
            double dx = e.a.x - e.b.x;
            double dy = e.a.y - e.b.y;
            double dist = Math.max(1, Math.sqrt(dx*dx + dy*dy));
            double force = (dist * dist) / k * (0.6 + e.weight);
            double fx = (dx / dist) * force;
            double fy = (dy / dist) * force;
            e.a.vx -= fx; e.a.vy -= fy;
            e.b.vx += fx; e.b.vy += fy;
        }

        // Centring + clamp
        double cx = w / 2, cy = h / 2;
        double damping = 0.04;
        for (Particle p : particles) {
            p.vx += (cx - p.x) * 0.005;
            p.vy += (cy - p.y) * 0.005;
            // Cap velocity for stability
            double max = 8;
            p.vx = Math.max(-max, Math.min(max, p.vx)) * damping;
            p.vy = Math.max(-max, Math.min(max, p.vy)) * damping;
            p.x += p.vx;
            p.y += p.vy;
            // Soft wall
            p.x = Math.max(20, Math.min(w - 20, p.x));
            p.y = Math.max(20, Math.min(h - 20, p.y));
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // Local mutable state classes
    private static final class Particle {
        RootNode node;
        double   x, y, vx, vy;
        Circle   circle;
        Label    label;
    }
    private static final class EdgeRef {
        Particle a, b;
        double   weight;
        Line     line;
    }
}
