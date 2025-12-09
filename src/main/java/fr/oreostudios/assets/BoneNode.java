package fr.oreostudios.assets;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class BoneNode {

    public String name;

    /** Blockbench bone origin (pivot) */
    public Vector3f pivot = new Vector3f();        // (0,0,0) default

    /** Bone rotation in degrees (Blockbench rotation) */
    public Vector3f rotation = new Vector3f();     // (0,0,0) default

    /** Elements (cube uuids / names) attached to this bone */
    public final List<String> elementIds = new ArrayList<>();

    /** Child bones in the outliner hierarchy */
    public final List<BoneNode> children = new ArrayList<>();

    /** Final accumulated matrix for this bone */
    public Matrix4f worldMatrix = new Matrix4f().identity();

    @Override
    public String toString() {
        return "BoneNode{" +
                "name='" + name + '\'' +
                ", pivot=" + pivot +
                ", rotation=" + rotation +
                ", elementIds=" + elementIds +
                ", children=" + children.size() +
                '}';
    }
}
