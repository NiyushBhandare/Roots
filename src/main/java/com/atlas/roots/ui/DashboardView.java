package com.atlas.roots.ui;

import com.atlas.roots.dao.IdeaNodeDao;
import com.atlas.roots.dao.RepoNodeDao;
import com.atlas.roots.dao.SubNodeDao;
import com.atlas.roots.model.*;
import com.atlas.roots.service.JoyCostAnalyzer;
import com.atlas.roots.service.VitalityCalculator;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.paint.CycleMethod;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The dashboard.
 *
 * <p>Top: a stat strip with three large numerals — ecosystem vitality,
 * total monthly burn, total node count. Below: an "ecosystem field" of
 * glowing nodes laid out in a flowing grid, sized by vitality, coloured
 * by type, with each node pulsing on its own phase so the whole field
 * breathes. Hover any node to see its name and band.</p>
 */
public class DashboardView {

    private final User                 user;
    private final VitalityCalculator   vitality;
    private final JoyCostAnalyzer      joyCost;
    private final SubNodeDao           subDao;
    private final RepoNodeDao          repoDao;
    private final IdeaNodeDao          ideaDao;

    private final BorderPane root = new BorderPane();

    public DashboardView(User user, VitalityCalculator vitality,
                         JoyCostAnalyzer joyCost,
                         SubNodeDao subDao, RepoNodeDao repoDao, IdeaNodeDao ideaDao) {
        this.user     = user;
        this.vitality = vitality;
        this.joyCost  = joyCost;
        this.subDao   = subDao;
        this.repoDao  = repoDao;
        this.ideaDao  = ideaDao;
        build();
    }

    public BorderPane getRoot() { return root; }

    private void build() {
        try {
            List<SubNode>  subs  = subDao.findByOwner(user.getId());
            List<RepoNode> repos = repoDao.findByOwner(user.getId());
            List<IdeaNode> ideas = ideaDao.findByOwner(user.getId());

            List<RootNode> all = new ArrayList<>();
            all.addAll(subs); all.addAll(repos); all.addAll(ideas);

            VBox container = new VBox(40);
            container.setPadding(new Insets(48, 64, 48, 64));

            // ----- Header -----
            container.getChildren().add(buildHeader(all, subs));

            // ----- The field -----
            container.getChildren().add(buildField(all));

            root.setCenter(container);
        } catch (SQLException e) {
            Label err = new Label("could not load ecosystem: " + e.getMessage());
            err.setStyle("-fx-text-fill: " + Theme.CORAL_HEX + ";");
            root.setCenter(err);
        }
    }

    // -----------------------------------------------------------------
    //  Stat strip header
    // -----------------------------------------------------------------

    private VBox buildHeader(List<RootNode> all, List<SubNode> subs) {
        Text title = new Text("Your ecosystem");
        title.setStyle("-fx-font-family: '" + Theme.FONT_DISPLAY.split(",")[0].trim() +
                       "'; -fx-font-size: 56px; -fx-fill: " + Theme.BONE_HEX + ";");

        Label subtitle = new Label("a living read on what's growing, what's draining, what's been forgotten");
        subtitle.setStyle("-fx-text-fill: " + Theme.ASH_HEX + "; -fx-font-style: italic;");

        VBox titleBox = new VBox(8, title, subtitle);

        // Three big stats
        HBox stats = new HBox(0);
        stats.setAlignment(Pos.CENTER_LEFT);
        stats.setPadding(new Insets(36, 0, 0, 0));

        double avgVitality = vitality.averageVitality(all);
        double monthlyBurn = joyCost.totalMonthlyBurn(subs);

        stats.getChildren().add(statBlock("ECOSYSTEM VITALITY",
                String.format("%.2f", avgVitality),
                vitalityBand(avgVitality),
                Theme.BONE_HEX));
        stats.getChildren().add(divider());
        stats.getChildren().add(statBlock("MONTHLY BURN",
                String.format("₹%,.0f", monthlyBurn),
                "across " + subs.size() + " subscriptions",
                Theme.MINT_HEX));
        stats.getChildren().add(divider());
        stats.getChildren().add(statBlock("LIVING NODES",
                String.valueOf(all.size()),
                subs.size() + " sub  ·  " +
                (all.size() - subs.size() - ideaCount(all)) + " repo  ·  " +
                ideaCount(all) + " idea",
                Theme.LILAC_HEX));

        return new VBox(0, titleBox, stats);
    }

    private int ideaCount(List<RootNode> all) {
        return (int) all.stream().filter(n -> n.getType() == NodeType.IDEA).count();
    }

    private String vitalityBand(double v) {
        if (v >= 0.75) return "thriving";
        if (v >= 0.50) return "steady";
        if (v >= 0.25) return "fading";
        return "dormant";
    }

    private VBox statBlock(String label, String value, String sub, String accentHex) {
        Label l = new Label(label);
        l.getStyleClass().add("label-small");
        l.setStyle("-fx-text-fill: " + Theme.ASH_HEX + ";");

        Text v = new Text(value);
        v.setStyle("-fx-font-family: '" + Theme.FONT_DISPLAY.split(",")[0].trim() +
                   "'; -fx-font-size: 64px; -fx-fill: " + accentHex + ";");

        Label s = new Label(sub);
        s.setStyle("-fx-text-fill: " + Theme.DUST_HEX + "; -fx-font-size: 11px;");

        VBox box = new VBox(6, l, v, s);
        box.setPadding(new Insets(0, 56, 0, 0));
        return box;
    }

    private Region divider() {
        Region r = new Region();
        r.setPrefWidth(1);
        r.setPrefHeight(96);
        r.setStyle("-fx-background-color: " + Theme.LOAM_HEX + ";");
        HBox.setMargin(r, new Insets(20, 56, 0, 0));
        return r;
    }

    // -----------------------------------------------------------------
    //  Ecosystem field — flowing layout of glowing nodes
    // -----------------------------------------------------------------

    private VBox buildField(List<RootNode> all) {
        Label header = new Label("THE FIELD");
        header.getStyleClass().add("label-small");
        header.setStyle("-fx-text-fill: " + Theme.ASH_HEX + ";");

        Label legend = new Label("size = vitality   ·   colour = type   ·   dim = forgotten");
        legend.setStyle("-fx-text-fill: " + Theme.DUST_HEX + "; -fx-font-size: 11px;");

        FlowPane flow = new FlowPane(28, 28);
        flow.setPadding(new Insets(20, 0, 0, 0));
        flow.setPrefWrapLength(1100);

        // Create one glowing node per RootNode
        for (RootNode node : all) {
            flow.getChildren().add(buildEcosystemNode(node));
        }

        return new VBox(8, header, legend, flow);
    }

    private VBox buildEcosystemNode(RootNode node) {
        double vital = node.getVitality();
        double minSize = 28;
        double maxSize = 96;
        double size = minSize + (maxSize - minSize) * vital;

        Color base = Theme.accentFor(node.displayToken());

        // Stage 1 — outer glow halo (dimmer outer, brighter inner via radial gradient)
        Circle halo = new Circle(size / 2 + 18);
        halo.setFill(new RadialGradient(0, 0, 0.5, 0.5, 1.0, true,
                CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(base.getRed(), base.getGreen(), base.getBlue(), 0.25 * vital + 0.05)),
                new Stop(1.0, Color.color(base.getRed(), base.getGreen(), base.getBlue(), 0))));

        // Stage 2 — main body
        Circle body = new Circle(size / 2);
        Color bodyFill = Color.color(base.getRed(), base.getGreen(), base.getBlue(),
                                     0.15 + 0.45 * vital);
        body.setFill(bodyFill);
        body.setStroke(base);
        body.setStrokeWidth(1.0);

        DropShadow glow = new DropShadow();
        glow.setColor(base);
        glow.setRadius(18 + 12 * vital);
        glow.setSpread(0.1 + 0.25 * vital);
        body.setEffect(glow);

        // Stage 3 — pulse animation (each node breathes on its own phase)
        Timeline pulse = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(glow.radiusProperty(), 16 + 12 * vital, Interpolator.EASE_BOTH),
                        new KeyValue(body.opacityProperty(), 0.85, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.seconds(2.4),
                        new KeyValue(glow.radiusProperty(), 28 + 18 * vital, Interpolator.EASE_BOTH),
                        new KeyValue(body.opacityProperty(), 1.0, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.seconds(4.8),
                        new KeyValue(glow.radiusProperty(), 16 + 12 * vital, Interpolator.EASE_BOTH),
                        new KeyValue(body.opacityProperty(), 0.85, Interpolator.EASE_BOTH))
        );
        pulse.setCycleCount(Timeline.INDEFINITE);
        // Random offset so the field doesn't pulse in unison
        pulse.setDelay(Duration.seconds(Math.random() * 2.4));
        pulse.play();

        Group glyph = new Group(halo, body);

        // Caption
        Label name = new Label(truncate(node.getName(), 18));
        name.setStyle("-fx-text-fill: " + Theme.BONE_HEX + "; -fx-font-size: 10px;");
        name.setMaxWidth(120);
        name.setAlignment(Pos.CENTER);

        Label band = new Label(node.displayToken() + " · " +
                ((Vitalizable) node).getVitalityBand());
        band.setStyle("-fx-text-fill: " + Theme.DUST_HEX + "; -fx-font-size: 9px;");

        VBox cell = new VBox(6, glyph, name, band);
        cell.setAlignment(Pos.CENTER);
        cell.setPrefWidth(120);
        cell.setPrefHeight(maxSize + 56);
        return cell;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
