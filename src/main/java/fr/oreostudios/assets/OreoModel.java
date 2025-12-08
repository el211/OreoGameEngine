package fr.oreostudios.assets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OreoModel {

    private final String name;
    private final List<Mesh> meshes = new ArrayList<>();

    // Optional texture (absolute path on disk)
    private String texturePath;

    public OreoModel(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void addMesh(Mesh mesh) {
        if (mesh != null) {
            meshes.add(mesh);
        }
    }

    public List<Mesh> getMeshes() {
        return Collections.unmodifiableList(meshes);
    }

    public String getTexturePath() {
        return texturePath;
    }

    public void setTexturePath(String texturePath) {
        this.texturePath = texturePath;
    }

    @Override
    public String toString() {
        return "OreoModel{name='" + name + "', meshCount=" + meshes.size() +
                ", texturePath=" + texturePath + "}";
    }
}
