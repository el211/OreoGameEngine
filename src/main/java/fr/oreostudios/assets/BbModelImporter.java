package fr.oreostudios.assets;

import com.google.gson.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BbModelImporter implements ModelImporter {

    @Override
    public OreoModel importModel(File file) throws Exception {
        JsonParser parser = new JsonParser();
        JsonObject root;
        try (FileReader reader = new FileReader(file)) {
            root = parser.parse(reader).getAsJsonObject();
        }

        // ----- name -----
        String baseName = file.getName().replaceFirst("\\.bbmodel$", "");
        String name = baseName;
        if (root.has("name") && root.get("name").isJsonPrimitive()) {
            name = root.get("name").getAsString();
        }

        OreoModel model = new OreoModel(name);

        // ----- resolution (texture size) -----
        int texWidth = 128;
        int texHeight = 128;
        if (root.has("resolution") && root.get("resolution").isJsonObject()) {
            JsonObject res = root.getAsJsonObject("resolution");
            if (res.has("width")) texWidth = res.get("width").getAsInt();
            if (res.has("height")) texHeight = res.get("height").getAsInt();
        }

        // ----- texture path: try sibling PNG first -----
        File siblingPng = new File(file.getParentFile(), baseName + ".png");
        String texturePath = null;

        if (siblingPng.exists()) {
            texturePath = siblingPng.getAbsolutePath();
            System.out.println("[BbModelImporter] Using sibling texture: " + texturePath);
        } else if (root.has("textures") && root.get("textures").isJsonObject()) {
            // basic support for texture path from .bbmodel
            JsonObject texturesObj = root.getAsJsonObject("textures");
            for (Map.Entry<String, JsonElement> entry : texturesObj.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject tex = entry.getValue().getAsJsonObject();
                if (tex.has("path")) {
                    String p = tex.get("path").getAsString();
                    File texFile = new File(p);
                    if (!texFile.isAbsolute()) {
                        texFile = new File(file.getParentFile(), p);
                    }
                    if (texFile.exists()) {
                        texturePath = texFile.getAbsolutePath();
                        System.out.println("[BbModelImporter] Texture from .bbmodel = " + texturePath);
                        break;
                    }
                }
            }
        }

        if (texturePath != null) {
            model.setTexturePath(texturePath);
            // if resolution not set, try reading from image
            if (texWidth <= 0 || texHeight <= 0) {
                try {
                    BufferedImage img = ImageIO.read(new File(texturePath));
                    texWidth = img.getWidth();
                    texHeight = img.getHeight();
                } catch (Exception ignored) {}
            }
        } else {
            System.out.println("[BbModelImporter] No usable texture found for model " + name);
        }

        // ----- geometry -----
        List<Float> vertList = new ArrayList<>();
        List<Float> uvList = new ArrayList<>();
        List<Integer> indexList = new ArrayList<>();
        List<Integer> uvIndexList = new ArrayList<>();

        JsonArray elements = root.getAsJsonArray("elements");
        if (elements != null) {
            System.out.println("[BbModelImporter] elements count = " + elements.size());
            for (JsonElement el : elements) {
                if (!el.isJsonObject()) continue;
                JsonObject elem = el.getAsJsonObject();

                JsonArray from = elem.getAsJsonArray("from");
                JsonArray to   = elem.getAsJsonArray("to");
                if (from == null || to == null || from.size() < 3 || to.size() < 3) continue;

                float fx = from.get(0).getAsFloat();
                float fy = from.get(1).getAsFloat();
                float fz = from.get(2).getAsFloat();
                float tx = to.get(0).getAsFloat();
                float ty = to.get(1).getAsFloat();
                float tz = to.get(2).getAsFloat();

                // rotation around origin
                float ox = fx;
                float oy = fy;
                float oz = fz;
                if (elem.has("origin") && elem.get("origin").isJsonArray()) {
                    JsonArray o = elem.getAsJsonArray("origin");
                    if (o.size() >= 3) {
                        ox = o.get(0).getAsFloat();
                        oy = o.get(1).getAsFloat();
                        oz = o.get(2).getAsFloat();
                    }
                }

                float rotX = 0, rotY = 0, rotZ = 0;
                if (elem.has("rotation") && elem.get("rotation").isJsonArray()) {
                    JsonArray r = elem.getAsJsonArray("rotation");
                    if (r.size() >= 3) {
                        rotX = r.get(0).getAsFloat();
                        rotY = r.get(1).getAsFloat();
                        rotZ = r.get(2).getAsFloat();
                    }
                }

                // build 8 cube corners
                float[][] cube = new float[8][3];
                cube[0] = rotatePoint(fx, fy, fz, ox, oy, oz, rotX, rotY, rotZ);
                cube[1] = rotatePoint(tx, fy, fz, ox, oy, oz, rotX, rotY, rotZ);
                cube[2] = rotatePoint(tx, ty, fz, ox, oy, oz, rotX, rotY, rotZ);
                cube[3] = rotatePoint(fx, ty, fz, ox, oy, oz, rotX, rotY, rotZ);
                cube[4] = rotatePoint(fx, fy, tz, ox, oy, oz, rotX, rotY, rotZ);
                cube[5] = rotatePoint(tx, fy, tz, ox, oy, oz, rotX, rotY, rotZ);
                cube[6] = rotatePoint(tx, ty, tz, ox, oy, oz, rotX, rotY, rotZ);
                cube[7] = rotatePoint(fx, ty, tz, ox, oy, oz, rotX, rotY, rotZ);

                // faces with UVs
                JsonObject facesObj = elem.getAsJsonObject("faces");
                if (facesObj == null) {
                    // fallback: old cube with no UVs
                    addWholeCubeWithoutUV(cube, vertList, indexList);
                    continue;
                }

                for (Map.Entry<String, JsonElement> faceEntry : facesObj.entrySet()) {
                    String dir = faceEntry.getKey(); // north/south/east/west/up/down
                    JsonObject face = faceEntry.getValue().getAsJsonObject();
                    if (!face.has("uv")) continue;
                    JsonArray uvArr = face.getAsJsonArray("uv");
                    if (uvArr.size() < 4) continue;

                    float u1 = uvArr.get(0).getAsFloat() / texWidth;
                    float v1 = uvArr.get(1).getAsFloat() / texHeight;
                    float u2 = uvArr.get(2).getAsFloat() / texWidth;
                    float v2 = uvArr.get(3).getAsFloat() / texHeight;

                    // 4 UV corners of this quad
                    float[][] quadUV = new float[][]{
                            {u1, v1},
                            {u2, v1},
                            {u2, v2},
                            {u1, v2}
                    };

                    // choose which cube corners are used for this direction
                    int[] cornerIdx;
                    switch (dir) {
                        case "north": // -Z
                            cornerIdx = new int[]{0, 1, 2, 3};
                            break;
                        case "south": // +Z
                            cornerIdx = new int[]{5, 4, 7, 6};
                            break;
                        case "west":  // -X
                            cornerIdx = new int[]{4, 0, 3, 7};
                            break;
                        case "east":  // +X
                            cornerIdx = new int[]{1, 5, 6, 2};
                            break;
                        case "up":    // +Y
                            cornerIdx = new int[]{3, 2, 6, 7};
                            break;
                        case "down":  // -Y
                            cornerIdx = new int[]{4, 5, 1, 0};
                            break;
                        default:
                            continue;
                    }

                    int baseVertIndex = vertList.size() / 3;
                    int baseUvIndex = uvList.size() / 2;

                    // add 4 vertices + 4 uvs
                    for (int i = 0; i < 4; i++) {
                        float[] p = cube[cornerIdx[i]];
                        vertList.add(p[0]);
                        vertList.add(p[1]);
                        vertList.add(p[2]);

                        float[] uv = quadUV[i];
                        uvList.add(uv[0]);
                        uvList.add(uv[1]);
                    }

                    // two triangles: 0-1-2 / 2-3-0
                    int[] triVerts = {
                            baseVertIndex, baseVertIndex + 1, baseVertIndex + 2,
                            baseVertIndex + 2, baseVertIndex + 3, baseVertIndex
                    };
                    int[] triUvs = {
                            baseUvIndex, baseUvIndex + 1, baseUvIndex + 2,
                            baseUvIndex + 2, baseUvIndex + 3, baseUvIndex
                    };

                    for (int i = 0; i < triVerts.length; i++) {
                        indexList.add(triVerts[i]);
                        uvIndexList.add(triUvs[i]);
                    }
                }
            }
        }

        if (vertList.isEmpty() || indexList.isEmpty()) {
            System.out.println("[BbModelImporter] WARNING: no geometry produced, using stub triangle.");
            float[] vertices = {0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f};
            int[] indices = {0, 1, 2};
            model.addMesh(new Mesh(vertices, indices));
        } else {
            float[] vertices = new float[vertList.size()];
            for (int i = 0; i < vertList.size(); i++) vertices[i] = vertList.get(i);

            int[] indices = new int[indexList.size()];
            for (int i = 0; i < indexList.size(); i++) indices[i] = indexList.get(i);

            float[] uvsArr = null;
            int[] uvIdxArr = null;
            if (!uvList.isEmpty() && uvIndexList.size() == indexList.size()) {
                uvsArr = new float[uvList.size()];
                for (int i = 0; i < uvList.size(); i++) uvsArr[i] = uvList.get(i);

                uvIdxArr = new int[uvIndexList.size()];
                for (int i = 0; i < uvIndexList.size(); i++) uvIdxArr[i] = uvIndexList.get(i);
            }

            System.out.println("[BbModelImporter] Built mesh: verts=" +
                    vertices.length + ", indices=" + indices.length +
                    ", uvs=" + (uvsArr == null ? 0 : uvsArr.length));

            model.addMesh(new Mesh(vertices, indices, uvsArr, uvIdxArr));
        }

        System.out.println("[BbModelImporter] Imported Blockbench model from " + file.getAbsolutePath());
        // TODO: bones / skeleton – would require reading "outliner" and building a rig.
        return model;
    }

    private void addWholeCubeWithoutUV(float[][] cube,
                                       List<Float> vertList,
                                       List<Integer> indexList) {
        int baseIndex = vertList.size() / 3;
        for (float[] p : cube) {
            vertList.add(p[0]);
            vertList.add(p[1]);
            vertList.add(p[2]);
        }
        int[] faces = {
                0,1,2,  2,3,0,  // back
                4,5,6,  6,7,4,  // front
                0,4,7,  7,3,0,  // left
                1,5,6,  6,2,1,  // right
                3,2,6,  6,7,3,  // top
                0,1,5,  5,4,0   // bottom
        };
        for (int f : faces) {
            indexList.add(baseIndex + f);
        }
    }

    // rotate (x,y,z) around origin (ox,oy,oz) with degrees rotX/Y/Z
    private float[] rotatePoint(float x, float y, float z,
                                float ox, float oy, float oz,
                                float rotX, float rotY, float rotZ) {
        double rx = Math.toRadians(rotX);
        double ry = Math.toRadians(rotY);
        double rz = Math.toRadians(rotZ);

        double px = x - ox;
        double py = y - oy;
        double pz = z - oz;

        // X
        double cy = Math.cos(rx), sy = Math.sin(rx);
        double py1 = py * cy - pz * sy;
        double pz1 = py * sy + pz * cy;
        double px1 = px;

        // Y
        double cz = Math.cos(ry), sz = Math.sin(ry);
        double pz2 = pz1 * cz - px1 * sz;
        double px2 = pz1 * sz + px1 * cz;
        double py2 = py1;

        // Z
        double cx = Math.cos(rz), sx = Math.sin(rz);
        double px3 = px2 * cx - py2 * sx;
        double py3 = px2 * sx + py2 * cx;
        double pz3 = pz2;

        return new float[]{
                (float) (px3 + ox),
                (float) (py3 + oy),
                (float) (pz3 + oz)
        };
    }

    @Override
    public String getDescription() {
        return "Blockbench Model (*.bbmodel)";
    }

    @Override
    public String[] getSupportedExtensions() {
        return new String[]{"*.bbmodel"};
    }
}
