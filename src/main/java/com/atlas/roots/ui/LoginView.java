package com.atlas.roots.ui;

import com.atlas.roots.model.User;
import com.atlas.roots.service.VaultGuardian;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.Optional;

/**
 * The login view.
 *
 * <p>A centred form on a near-black background, with a procedurally
 * generated guilloché pattern (overlapping sine curves) drawn into a
 * canvas behind the form. Guilloché is the pattern used on currency,
 * passports, and stock certificates &mdash; a quiet visual cue that
 * the user has stepped into a high-trust, private space.</p>
 *
 * <p>The pattern slowly drifts via a JavaFX {@link Timeline}, so the
 * background feels alive without ever distracting from the form.</p>
 */
public class LoginView {

    private final RootsApp     app;
    private final VaultGuardian guardian;
    private final StackPane    root;

    private final TextField     usernameField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final Label         errorLabel    = new Label();

    public LoginView(RootsApp app, VaultGuardian guardian) {
        this.app = app;
        this.guardian = guardian;
        this.root = new StackPane();
        this.root.setStyle("-fx-background-color: " + Theme.SOIL_HEX + ";");
        build();
    }

    public StackPane getRoot() { return root; }

    private void build() {
        // ----- Layered background -----------------------------------
        Canvas guilloche = new Canvas(Theme.WINDOW_W, Theme.WINDOW_H);
        drawGuilloche(guilloche, 0);
        animateGuilloche(guilloche);

        // ----- Form ---------------------------------------------------
        VBox form = new VBox(28);
        form.setAlignment(Pos.CENTER);
        form.setMaxWidth(360);
        form.setPadding(new Insets(48));

        Text wordmark = new Text("ROOTS");
        wordmark.setFont(Font.font(Theme.FONT_DISPLAY.split(",")[0].trim(), 84));
        wordmark.setFill(Theme.BONE);
        wordmark.getStyleClass().add("display-large");

        Label tagline = new Label("A   COGNITIVE   AND   FINANCIAL   OS");
        tagline.getStyleClass().add("label-small");
        tagline.setStyle("-fx-text-fill: " + Theme.ASH_HEX + ";");

        VBox header = new VBox(8, wordmark, tagline);
        header.setAlignment(Pos.CENTER);

        Label userLabel = sectionLabel("USERNAME");
        usernameField.setPromptText("draco");
        usernameField.setMaxWidth(Double.MAX_VALUE);

        Label passLabel = sectionLabel("PASSWORD");
        passwordField.setPromptText("••••••••");
        passwordField.setMaxWidth(Double.MAX_VALUE);

        VBox userField = new VBox(6, userLabel, usernameField);
        VBox passField = new VBox(6, passLabel, passwordField);

        Button enter = new Button("ENTER  →");
        enter.getStyleClass().add("button-accent");
        enter.setMaxWidth(Double.MAX_VALUE);
        enter.setOnAction(e -> attemptLogin());

        // Submit on Enter from either field.
        usernameField.setOnAction(e -> passwordField.requestFocus());
        passwordField.setOnAction(e -> attemptLogin());

        errorLabel.setStyle("-fx-text-fill: " + Theme.CORAL_HEX + ";");
        errorLabel.getStyleClass().add("label-small");
        errorLabel.setVisible(false);

        Label hint = new Label("seed: draco / roots2026   ·   viewer / viewer2026");
        hint.getStyleClass().add("label-small");
        hint.setStyle("-fx-text-fill: " + Theme.DUST_HEX + ";");

        form.getChildren().addAll(header, new Region(), userField, passField,
                                  enter, errorLabel, new Region(), hint);
        VBox.setVgrow(form.getChildren().get(1), Priority.ALWAYS);

        // Wrap form in a card-like surface so it visually rises above
        // the guilloché background.
        VBox card = new VBox(form);
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(440);
        card.setMaxHeight(620);
        card.setStyle("-fx-background-color: " + Theme.SOIL_HEX + "EE;" +
                      "-fx-border-color: " + Theme.LOAM_HEX + ";" +
                      "-fx-border-width: 1;");

        StackPane stack = new StackPane(guilloche, card);
        StackPane.setAlignment(card, Pos.CENTER);

        root.getChildren().add(stack);
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("label-small");
        l.setStyle("-fx-text-fill: " + Theme.ASH_HEX + ";");
        return l;
    }

    private void attemptLogin() {
        errorLabel.setVisible(false);
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        Optional<User> session = guardian.login(username, password);
        if (session.isEmpty()) {
            errorLabel.setText("invalid credentials");
            errorLabel.setVisible(true);
            return;
        }
        app.showMain(session.get());
    }

    // -----------------------------------------------------------------
    //  Guilloché pattern — procedural sine curves at low opacity.
    // -----------------------------------------------------------------

    private void drawGuilloche(Canvas canvas, double phase) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);

        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineWidth(0.6);

        int curveCount = 28;
        for (int i = 0; i < curveCount; i++) {
            double t = i / (double) curveCount;
            double opacity = 0.04 + 0.04 * Math.sin(t * Math.PI);
            g.setStroke(Color.web(Theme.BONE_HEX, opacity));

            double freq      = 0.012 + 0.004 * Math.sin(t * Math.PI * 2 + phase);
            double amplitude = 80 + 40 * Math.sin(t * Math.PI + phase * 0.5);
            double yBase     = h * t;

            g.beginPath();
            for (double x = 0; x <= w; x += 6) {
                double y = yBase + Math.sin(x * freq + phase + i * 0.3) * amplitude;
                if (x == 0) g.moveTo(x, y);
                else        g.lineTo(x, y);
            }
            g.stroke();
        }
    }

    private void animateGuilloche(Canvas canvas) {
        Timeline tl = new Timeline();
        // Drift the phase forever; redraw on each step.
        for (int i = 0; i <= 600; i++) {
            final double phase = i * 0.05;
            tl.getKeyFrames().add(new KeyFrame(
                    Duration.millis(i * 50),
                    e -> drawGuilloche(canvas, phase)));
        }
        tl.setCycleCount(Timeline.INDEFINITE);
        tl.play();
    }
}
