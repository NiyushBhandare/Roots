package com.atlas.roots.ui;

import com.atlas.roots.bridge.ObsidianBridge;
import com.atlas.roots.dao.*;
import com.atlas.roots.db.DatabaseManager;
import com.atlas.roots.model.User;
import com.atlas.roots.service.*;

import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * The Roots JavaFX application.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Bootstrap the database before any view is built.</li>
 *   <li>Construct every DAO and service exactly once and hand them to
 *       the views that need them &mdash; this is a poor-man's DI
 *       container, sufficient for a single-window desktop app.</li>
 *   <li>Own the {@link Stage} and provide {@link #showLogin} and
 *       {@link #showMain} for view-to-view navigation, with a fade
 *       transition between roots so the app feels alive.</li>
 *   <li>Clean up the {@link ObsidianBridge} watcher on shutdown.</li>
 * </ul>
 */
public class RootsApp extends Application {

    /** The container the active view is swapped into. */
    private final StackPane shell = new StackPane();

    private DatabaseManager     db;
    private VaultGuardian       guardian;
    private VitalityCalculator  vitality;
    private JoyCostAnalyzer     joyCost;
    private CognitiveHeatmap    heatmap;
    private SubNodeDao          subDao;
    private RepoNodeDao         repoDao;
    private IdeaNodeDao         ideaDao;
    private AuditDao            auditDao;
    private ObsidianBridge      obsidian;

    @Override
    public void start(Stage stage) throws Exception {
        // ----- Boot the backend ---------------------------------------
        this.db = DatabaseManager.getInstance();
        db.bootstrapIfNeeded();

        UserDao userDao = new UserDao(db);
        this.auditDao = new AuditDao(db);
        this.guardian = new VaultGuardian(userDao, auditDao);
        this.subDao   = new SubNodeDao(db);
        this.repoDao  = new RepoNodeDao(db);
        this.ideaDao  = new IdeaNodeDao(db);
        this.vitality = new VitalityCalculator();
        this.joyCost  = new JoyCostAnalyzer();
        this.heatmap  = new CognitiveHeatmap();
        this.obsidian = new ObsidianBridge(ideaDao);

        // ----- Stage setup --------------------------------------------
        Scene scene = new Scene(shell, Theme.WINDOW_W, Theme.WINDOW_H);
        scene.getStylesheets().add(
                getClass().getResource("/css/swiss-bioluminescent.css").toExternalForm());

        stage.setTitle("ROOTS");
        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        stage.setOnCloseRequest(e -> obsidian.close());
        stage.show();

        showLogin();
    }

    // -----------------------------------------------------------------
    //  Navigation.
    // -----------------------------------------------------------------

    public void showLogin() {
        LoginView view = new LoginView(this, guardian);
        swap(view.getRoot());
    }

    public void showMain(User user) {
        MainView view = new MainView(this, user,
                guardian, vitality, joyCost, heatmap,
                subDao, repoDao, ideaDao, auditDao, obsidian);
        swap(view.getRoot());
    }

    private void swap(Parent next) {
        shell.getChildren().setAll(next);
        next.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(280), next);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    // -----------------------------------------------------------------
    //  Service accessors — used by views that need them on demand.
    // -----------------------------------------------------------------

    public ObsidianBridge obsidian()  { return obsidian; }
    public IdeaNodeDao    ideaDao()   { return ideaDao;  }

    // -----------------------------------------------------------------
    //  Entry point.
    // -----------------------------------------------------------------

    public static void main(String[] args) {
        launch(args);
    }
}
