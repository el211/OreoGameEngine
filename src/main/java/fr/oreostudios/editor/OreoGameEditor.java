package fr.oreostudios.editor;

import fr.oreostudios.editor.ui.EditorLayout;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class OreoGameEditor extends Application {

    @Override
    public void start(Stage primaryStage) {
        EditorLayout layout = new EditorLayout();
        Scene scene = new Scene(layout.getRoot(), 1280, 720);

        primaryStage.setTitle("OreoGame Engine");

        // 🔹 Set window/taskbar icon
        Image icon = new Image(
                getClass().getResourceAsStream("/icons/oreo_logo.png")
        );
        primaryStage.getIcons().add(icon);

        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void launchEditor(String[] args) {
        launch(args);
    }
}
