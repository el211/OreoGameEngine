package fr.oreostudios.assets;

public class Mesh {

    private final float[] vertices;   // xyz xyz ...
    private final int[] indices;      // point indices for triangles

    // NEW: uv data
    private final float[] uvs;        // u v u v ...
    private final int[] uvIndices;    // texCoord indices, parallel to indices

    // Old constructor still works (no UVs)
    public Mesh(float[] vertices, int[] indices) {
        this(vertices, indices, null, null);
    }

    public Mesh(float[] vertices, int[] indices, float[] uvs, int[] uvIndices) {
        this.vertices = vertices;
        this.indices = indices;
        this.uvs = uvs;
        this.uvIndices = uvIndices;
    }

    public float[] getVertices() {
        return vertices;
    }

    public int[] getIndices() {
        return indices;
    }

    public float[] getUvs() {
        return uvs;
    }

    public int[] getUvIndices() {
        return uvIndices;
    }
}
