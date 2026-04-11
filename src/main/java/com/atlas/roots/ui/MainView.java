package com.atlas.roots.ui;

import com.atlas.roots.bridge.ObsidianBridge;
import com.atlas.roots.dao.*;
import com.atlas.roots.model.User;
import com.atlas.roots.service.*;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.util.Duration;

/**
 * The main window after login.
 *
 * <p>Layout: a 220px left nav rail with section labels, a 60px top
 * bar with the wordmark and a logout button, and a content area that
 * swaps between Dashboard / Subscriptions / Repositories / Ideas /
 * Heatmap / Reports / Audit / Vault. Each section is a separate view
 * class to keep this shell file readable.</p>
 */
public class MainView {

    private final RootsApp           app;
    private final User               user;
    private final VaultGuardian      guardian;
    private final VitalityCalculator vitality;
    private final JoyCostAnalyzer    joyCost;
    private final CognitiveHeatmap   heatmap;
    private final SubNodeDao         subDao;
    private final RepoNodeDao        repoDao;
    private final IdeaNodeDao        ideaDao;
    private final AuditDao           auditDao;
    private final ObsidianBridge     obsidian;

    private final BorderPane root    = new BorderPane();
    private final StackPane  content = new StackPane();
    private final VBox       nav     = new VBox();

    private Button activeNavItem;

    public MainView(RootsApp app, User user,
                    VaultGuardian guardian, VitalityCalculator vitality,
                    JoyCostAnalyzer joyCost, CognitiveHeatmap heatmap,
                    SubNodeDao subDao, RepoNodeDao repoDao,
                    IdeaNodeDao ideaDao, AuditDao auditDao,
                    ObsidianBridge obsidian) {
        this.app      = app;
        this.user     = user;
        this.guardian = guardian;
        this.vitality = vitality;
        this.joyCost  = joyCost;
        this.heatmap  = heatmap;
        this.subDao   = subDao;
        this.repoDao  = repoDao;
        this.ideaDao  = ideaDao;
        this.auditDao = auditDao;
        this.obsidian = obsidian;
        build();
    }

    public Parent getRoot() { return root; }

    private void build() {
        root.setStyle("-fx-background-color: " + Theme.SOIL_HEX + ";");
        root.setTop(buildTopBar());
        root.setLeft(buildNavRail());
        root.setCenter(content);

        // Default landing view
        showDashboard();
    }

    // -----------------------------------------------------------------
    //  Top bar
    // -----------------------------------------------------------------

    private HBox buildTopBar() {
        HBox bar = new HBox();
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(20, 32, 20, 32));
        bar.setStyle("-fx-border-color: transparent transparent " + Theme.LOAM_HEX + " transparent;" +
                     "-fx-border-width: 0 0 1 0;");

        Label wordmark = new Label("ROOTS");
        wordmark.setStyle("-fx-font-family: '" + Theme.FONT_DISPLAY.split(",")[0].trim() +
                          "'; -fx-font-size: 28px; -fx-text-fill: " + Theme.BONE_HEX + ";");

        Label sep = new Label("·");
        sep.setStyle("-fx-text-fill: " + Theme.DUST_HEX + "; -fx-padding: 0 14 0 14;");

        Label tagline = new Label("a cognitive and financial os");
        tagline.getStyleClass().add("label-small");
        tagline.setStyle("-fx-text-fill: " + Theme.ASH_HEX + ";");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label userLabel = new Label("@" + user.getUsername() + "  ·  " + user.getRole());
        userLabel.getStyleClass().add("label-small");
        userLabel.setStyle("-fx-text-fill: " + Theme.ASH_HEX + ";");

        Button logout = new Button("LOGOUT");
        logout.getStyleClass().add("button-ghost");
        logout.setOnAction(e -> {
            obsidian.close();
            guardian.logout();
            app.showLogin();
        });

        bar.getChildren().addAll(wordmark, sep, tagline, spacer, userLabel,
                                 spacerOf(20), logout);
        return bar;
    }

    private Region spacerOf(double w) {
        Region r = new Region();
        r.setPrefWidth(w);
        return r;
    }

    // -----------------------------------------------------------------
    //  Nav rail
    // -----------------------------------------------------------------

    private VBox buildNavRail() {
        nav.getStyleClass().add("nav-rail");
        nav.setPrefWidth(220);
        nav.setSpacing(2);

        nav.getChildren().add(navHeader("ECOSYSTEM"));
        nav.getChildren().add(navItem("Dashboard",       this::showDashboard));
        nav.getChildren().add(navItem("Cognitive Map",   this::showHeatmap));

        nav.getChildren().add(navHeader("NODES"));
        nav.getChildren().add(navItem("Subscriptions",   this::showSubscriptions));
        nav.getChildren().add(navItem("Repositories",    this::showRepositories));
        nav.getChildren().add(navItem("Ideas",           this::showIdeas));

        nav.getChildren().add(navHeader("REPORTS"));
        nav.getChildren().add(navItem("Joy / Cost",      this::showJoyCost));
        nav.getChildren().add(navItem("Vitality",        this::showVitality));

        nav.getChildren().add(navHeader("SYSTEM"));
        nav.getChildren().add(navItem("Obsidian Vault",  this::showVault));
        nav.getChildren().add(navItem("Audit Log",       this::showAudit));

        return nav;
    }

    private Label navHeader(String text) {
        Label l = new Label("  " + text);
        l.getStyleClass().add("label-small");
        l.setStyle("-fx-text-fill: " + Theme.DUST_HEX + ";" +
                   "-fx-padding: 24 0 8 24;");
        return l;
    }

    private Button navItem(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("nav-item");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> {
            setActive(b);
            action.run();
        });
        return b;
    }

    private void setActive(Button b) {
        if (activeNavItem != null) {
            activeNavItem.getStyleClass().remove("nav-item-active");
        }
        activeNavItem = b;
        if (!b.getStyleClass().contains("nav-item-active")) {
            b.getStyleClass().add("nav-item-active");
        }
    }

    // -----------------------------------------------------------------
    //  Content swap with fade
    // -----------------------------------------------------------------

    private void swap(Parent next) {
        content.getChildren().setAll(next);
        next.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(220), next);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    // -----------------------------------------------------------------
    //  Section routes — each delegates to its own view class
    // -----------------------------------------------------------------

    private void showDashboard() {
        swap(new DashboardView(user, vitality, joyCost,
                               subDao, repoDao, ideaDao).getRoot());
    }

    private void showHeatmap() {
        swap(new HeatmapView(user, heatmap,
                             subDao, repoDao, ideaDao).getRoot());
    }

    private void showSubscriptions() {
        swap(new SubscriptionsView(user, subDao, auditDao).getRoot());
    }

    private void showRepositories() {
        swap(new RepositoriesView(user, repoDao, auditDao).getRoot());
    }

    private void showIdeas() {
        swap(new IdeasView(user, ideaDao, auditDao).getRoot());
    }

    private void showJoyCost() {
        swap(new JoyCostReportView(user, joyCost, subDao).getRoot());
    }

    private void showVitality() {
        swap(new VitalityReportView(user, vitality,
                                    subDao, repoDao, ideaDao).getRoot());
    }

    private void showVault() {
        swap(new VaultView(user, obsidian, ideaDao).getRoot());
    }

    private void showAudit() {
        swap(new AuditView(auditDao).getRoot());
    }
}
