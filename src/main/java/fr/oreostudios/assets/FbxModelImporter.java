package fr.oreostudios.assets;

import java.io.File;

public class FbxModelImporter implements ModelImporter {

    @Override
    public OreoModel importModel(File file) throws Exception {
        // TODO: integrate a proper FBX importer (Assimp, jAssimp…) later
        String baseName = file.getName().replaceFirst("\\.fbx$", "");
        OreoModel model = new OreoModel(baseName);

        // Dummy cube-like mesh
        float[] vertices = {
                -0.5f, -0.5f, -0.5f,
                0.5f, -0.5f, -0.5f,
                0.5f,  0.5f, -0.5f,
                -0.5f,  0.5f, -0.5f,
                -0.5f, -0.5f,  0.5f,
                0.5f, -0.5f,  0.5f,
                0.5f,  0.5f,  0.5f,
                -0.5f,  0.5f,  0.5f
        };

        int[] indices = {
                0, 1, 2, 2, 3, 0,
                4, 5, 6, 6, 7, 4
        };

        model.addMesh(new Mesh(vertices, indices));
        System.out.println("[FbxModelImporter] Imported stub model from " + file.getAbsolutePath());
        return model;
    }

    @Override
    public String getDescription() {
        return "FBX Model (*.fbx)";
    }

    @Override
    public String[] getSupportedExtensions() {
        return new String[]{"*.fbx"};
    }
}
