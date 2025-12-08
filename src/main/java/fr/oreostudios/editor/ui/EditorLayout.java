package fr.oreostudios.editor.ui;

import fr.oreostudios.assets.BbModelImporter;
import fr.oreostudios.assets.FbxModelImporter;
import fr.oreostudios.assets.ModelImporter;
import fr.oreostudios.assets.OreoModel;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;

public class EditorLayout {

    private final BorderPane root = new BorderPane();

    private final TreeView<String> sceneTree = new TreeView<>();
    private final TextArea inspector = new TextArea();
    private final Label statusLabel = new Label("Ready.");
    private final ModelViewport viewport = new ModelViewport(); // 3D viewport

    public EditorLayout() {
        createLayout();
    }

    public BorderPane getRoot() {
        return root;
    }

    private void createLayout() {
        root.setTop(createMenuBar());
        root.setLeft(createHierarchyPanel());
        root.setCenter(createViewportPanel());
        root.setRight(createInspectorPanel());
        root.setBottom(createStatusBar());
    }

    private MenuBar createMenuBar() {
        MenuBar menuBar = new MenuBar();

        // FILE
        Menu fileMenu = new Menu("File");
        MenuItem newSceneItem = new MenuItem("New Scene");
        MenuItem openSceneItem = new MenuItem("Open Scene...");
        MenuItem saveSceneItem = new MenuItem("Save Scene");
        MenuItem exitItem = new MenuItem("Exit");

        exitItem.setOnAction(e -> {
            Scene scene = root.getScene();
            if (scene != null) {
                Window window = scene.getWindow();
                if (window != null) {
                    window.hide();
                }
            }
        });

        fileMenu.getItems().addAll(
                newSceneItem,
                openSceneItem,
                saveSceneItem,
                new SeparatorMenuItem(),
                exitItem
        );

        // IMPORT
        Menu importMenu = new Menu("Import");
        MenuItem importBbItem = new MenuItem("Blockbench Model (.bbmodel)");
        importBbItem.setOnAction(e -> openImportDialog(new BbModelImporter()));

        MenuItem importFbxItem = new MenuItem("FBX Model (.fbx)");
        importFbxItem.setOnAction(e -> openImportDialog(new FbxModelImporter()));

        importMenu.getItems().addAll(importBbItem, importFbxItem);

        // VIEW (placeholder)
        Menu viewMenu = new Menu("View");

        // HELP
        Menu helpMenu = new Menu("Help");
        MenuItem aboutItem = new MenuItem("About OreoGame Engine");
        aboutItem.setOnAction(e ->
                new Alert(Alert.AlertType.INFORMATION,
                        "OreoGame Engine\nA Java-based engine/editor prototype by OreoStudios.")
                        .showAndWait()
        );
        helpMenu.getItems().add(aboutItem);

        menuBar.getMenus().addAll(fileMenu, importMenu, viewMenu, helpMenu);
        return menuBar;
    }

    private void openImportDialog(ModelImporter importer) {
        Scene scene = root.getScene();
        Window window = scene != null ? scene.getWindow() : null;

        FileChooser chooser = new FileChooser();
        chooser.setInitialDirectory(new File(System.getProperty("user.home")));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(importer.getDescription(), importer.getSupportedExtensions())
        );

        File file = chooser.showOpenDialog(window);
        if (file == null) {
            setStatus("Import cancelled.");
            return;
        }

        try {
            OreoModel model = importer.importModel(file);
            setStatus("Imported model: " + model.getName() + " (" + importer.getDescription() + ")");
            updateHierarchyWithModel(model);
            inspector.setText("Imported model:\n" + model);

            // 🔹 THIS was missing: send the model to the viewport
            viewport.showModel(model);

        } catch (Exception ex) {
            ex.printStackTrace();
            setStatus("Failed to import model: " + ex.getMessage());
            new Alert(Alert.AlertType.ERROR,
                    "Error importing model:\n" + ex.getMessage())
                    .showAndWait();
        }
    }

    private void updateHierarchyWithModel(OreoModel model) {
        TreeItem<String> rootItem = new TreeItem<>("Scene");
        TreeItem<String> modelItem = new TreeItem<>("Model: " + model.getName());
        rootItem.getChildren().add(modelItem);
        rootItem.setExpanded(true);
        sceneTree.setRoot(rootItem);
    }

    private VBox createHierarchyPanel() {
        VBox box = new VBox();
        box.setPadding(new Insets(5));
        box.setSpacing(5);

        Label title = new Label("Scene Hierarchy");
        title.setStyle("-fx-font-weight: bold;");

        TreeItem<String> rootItem = new TreeItem<>("Scene");
        rootItem.setExpanded(true);
        sceneTree.setRoot(rootItem);

        VBox.setVgrow(sceneTree, Priority.ALWAYS);
        box.getChildren().addAll(title, sceneTree);
        box.setPrefWidth(220);
        return box;
    }

    private StackPane createViewportPanel() {
        StackPane pane = new StackPane();
        pane.setPadding(new Insets(5));

        // Use the 3D viewport instead of a label
        pane.getChildren().add(viewport.getRoot());
        return pane;
    }

    private VBox createInspectorPanel() {
        VBox box = new VBox();
        box.setPadding(new Insets(5));
        box.setSpacing(5);

        Label title = new Label("Inspector");
        title.setStyle("-fx-font-weight: bold;");

        inspector.setEditable(false);
        inspector.setWrapText(true);

        VBox.setVgrow(inspector, Priority.ALWAYS);
        box.getChildren().addAll(title, inspector);
        box.setPrefWidth(250);
        return box;
    }

    private HBox createStatusBar() {
        HBox bar = new HBox();
        bar.setPadding(new Insets(3, 10, 3, 10));
        bar.setStyle("-fx-background-color: #333;");
        statusLabel.setStyle("-fx-text-fill: #ddd;");
        bar.getChildren().add(statusLabel);
        return bar;
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }
}
