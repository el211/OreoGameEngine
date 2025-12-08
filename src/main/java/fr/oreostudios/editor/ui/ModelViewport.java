package fr.oreostudios.editor.ui;

import fr.oreostudios.assets.Mesh;
import fr.oreostudios.assets.OreoModel;
import javafx.scene.*;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.DrawMode;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Shape3D;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.scene.AmbientLight;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;

import java.io.File;
import java.io.FileInputStream;

public class ModelViewport {

    // UI root
    private final StackPane root = new StackPane();

    // 3D root
    private final Group root3D = new Group();

    // camera & controls
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final Group cameraPivot = new Group();
    private final Rotate rotateX = new Rotate(-20, Rotate.X_AXIS);
    private final Rotate rotateY = new Rotate(-30, Rotate.Y_AXIS);
    private final Translate cameraTranslate = new Translate(0, 0, -800); // zoom distance

    private double anchorX, anchorY;
    private double anchorAngleX, anchorAngleY2;

    private SubScene subScene;

    public ModelViewport() {
        // camera setup
        camera.setNearClip(0.1);
        camera.setFarClip(10_000);

        cameraPivot.getChildren().add(camera);
        cameraPivot.getTransforms().addAll(rotateX, rotateY, cameraTranslate);

        root3D.getChildren().add(cameraPivot);

        // === STRONGER LIGHTING ===
        AmbientLight ambient = new AmbientLight(Color.rgb(220, 220, 220));

        PointLight front = new PointLight(Color.WHITE);
        front.setTranslateX(0);
        front.setTranslateY(-100);
        front.setTranslateZ(-300);

        PointLight side = new PointLight(Color.WHITE);
        side.setTranslateX(200);
        side.setTranslateY(-200);
        side.setTranslateZ(-200);

        root3D.getChildren().addAll(ambient, front, side);

        // SubScene
        subScene = new SubScene(root3D, 800, 600, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.rgb(30, 30, 30));
        subScene.setCamera(camera);

        // SubScene follows container size
        subScene.widthProperty().bind(root.widthProperty());
        subScene.heightProperty().bind(root.heightProperty());

        installMouseHandlers();

        root.getChildren().add(subScene);
    }

    public StackPane getRoot() {
        return root;
    }

    /** Right-click drag = orbit, scroll = zoom */
    private void installMouseHandlers() {
        // Orbit with RIGHT mouse button
        subScene.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                anchorX = event.getSceneX();
                anchorY = event.getSceneY();
                anchorAngleX = rotateX.getAngle();
                anchorAngleY2 = rotateY.getAngle();
            }
        });

        subScene.setOnMouseDragged(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                double dx = event.getSceneX() - anchorX;
                double dy = event.getSceneY() - anchorY;

                rotateY.setAngle(anchorAngleY2 + dx * 0.5);   // left/right
                rotateX.setAngle(anchorAngleX - dy * 0.5);    // up/down
            }
        });

        // Zoom with mouse wheel
        subScene.setOnScroll(event -> {
            double delta = event.getDeltaY(); // positive = wheel up
            double newZ = cameraTranslate.getZ() + delta * 0.5;
            // clamp so you don’t go too far in/out
            newZ = Math.min(-200, Math.max(newZ, -5000));
            cameraTranslate.setZ(newZ);
        });
    }

    /** Try to show the first mesh from the model. Fallback to cube if anything is wrong. */
    public void showModel(OreoModel model) {
        // remove previous meshes
        root3D.getChildren().removeIf(n -> n instanceof Shape3D);

        java.util.List<Mesh> meshes = (model != null) ? model.getMeshes() : null;

        if (model == null || meshes == null || meshes.isEmpty()) {
            System.out.println("[Viewport] Model is null OR meshes list is null/empty -> show test cube");
            root3D.getChildren().add(createTestCube());
            return;
        }

        Mesh mesh = meshes.get(0);
        float[] verts = mesh.getVertices();
        int[] indices = mesh.getIndices();
        float[] uvs = mesh.getUvs();
        int[] uvIdx = mesh.getUvIndices();

        System.out.println("[Viewport] showModel: name=" + model.getName() +
                ", verts=" + (verts == null ? "null" : verts.length) +
                ", indices=" + (indices == null ? "null" : indices.length) +
                ", texturePath=" + model.getTexturePath());

        if (verts == null || verts.length == 0 || indices == null || indices.length == 0) {
            System.out.println("[Viewport] Empty mesh data -> show test cube");
            root3D.getChildren().add(createTestCube());
            return;
        }

        try {
            MeshView mv = createMeshViewFromData(verts, indices, uvs, uvIdx);

            // --- material: texture if available, else flat color ---
            PhongMaterial mat = new PhongMaterial();

            String texPath = model.getTexturePath();
            if (texPath != null && !texPath.isBlank()) {
                File texFile = new File(texPath);
                if (texFile.exists()) {
                    try (FileInputStream fis = new FileInputStream(texFile)) {
                        Image img = new Image(fis);
                        mat.setDiffuseMap(img);
                        mat.setDiffuseColor(Color.WHITE);
                        mat.setSpecularColor(Color.WHITE);
                        System.out.println("[Viewport] Applied texture " + texFile.getAbsolutePath());
                    } catch (Exception texEx) {
                        texEx.printStackTrace();
                        System.out.println("[Viewport] Failed to load texture, using flat color.");
                        mat.setDiffuseColor(Color.SKYBLUE);
                    }
                } else {
                    System.out.println("[Viewport] Texture file does not exist: " + texFile.getAbsolutePath());
                    mat.setDiffuseColor(Color.SKYBLUE);
                }
            } else {
                System.out.println("[Viewport] No texturePath on model, using flat color.");
                mat.setDiffuseColor(Color.SKYBLUE);
            }

            mv.setMaterial(mat);
            root3D.getChildren().add(mv);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.out.println("[Viewport] Error building mesh -> show test cube");
            root3D.getChildren().add(createTestCube());
        }
    }

    // Build a MeshView from raw data and auto-center/scale it
    private MeshView createMeshViewFromData(float[] verts, int[] indices,
                                            float[] uvs, int[] uvIndices) {
        TriangleMesh fxMesh = new TriangleMesh();

        // points
        for (int i = 0; i < verts.length; i += 3) {
            fxMesh.getPoints().addAll(
                    verts[i],
                    verts[i + 1],
                    verts[i + 2]
            );
        }

        // texCoords
        boolean hasUVs = (uvs != null && uvs.length >= 2 &&
                uvIndices != null && uvIndices.length == indices.length);

        if (!hasUVs) {
            // fallback: single dummy UV
            fxMesh.getTexCoords().addAll(0, 0);
        } else {
            for (int i = 0; i < uvs.length; i += 2) {
                fxMesh.getTexCoords().addAll(
                        uvs[i],
                        uvs[i + 1]
                );
            }
        }

        // faces
        for (int i = 0; i < indices.length; i++) {
            int pIndex = indices[i];
            int tIndex = hasUVs ? uvIndices[i] : 0;
            fxMesh.getFaces().addAll(pIndex, tIndex);
        }

        MeshView view = new MeshView(fxMesh);
        view.setCullFace(CullFace.BACK);
        view.setDrawMode(DrawMode.FILL);

        // --- auto center + scale ---
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        for (int i = 0; i < verts.length; i += 3) {
            float x = verts[i];
            float y = verts[i + 1];
            float z = verts[i + 2];

            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }

        float centerX = (minX + maxX) / 2f;
        float centerY = (minY + maxY) / 2f;
        float centerZ = (minZ + maxZ) / 2f;

        float sizeX = maxX - minX;
        float sizeY = maxY - minY;
        float sizeZ = maxZ - minZ;
        float maxSize = Math.max(sizeX, Math.max(sizeY, sizeZ));

        view.setTranslateX(-centerX);
        view.setTranslateY(-centerY);
        view.setTranslateZ(-centerZ);

        float targetSize = 150f; // slightly smaller to avoid "inside the model" look
        float scale = (maxSize > 0.0001f) ? (targetSize / maxSize) : 1f;
        view.setScaleX(scale);
        view.setScaleY(scale);
        view.setScaleZ(scale);

        // rotate to stand upright like Blockbench
        view.setRotationAxis(Rotate.X_AXIS);
        view.setRotate(-90);

        return view;
    }

    // Simple orange cube to prove viewport works
    private Box createTestCube() {
        Box box = new Box(100, 100, 100);
        box.setMaterial(new PhongMaterial(Color.ORANGE));
        box.setCullFace(CullFace.BACK);
        box.setDrawMode(DrawMode.FILL);
        return box;
    }
}
