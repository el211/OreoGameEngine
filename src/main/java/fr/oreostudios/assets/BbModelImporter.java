package fr.oreostudios.assets;

import com.google.gson.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.util.*;

/**
 * Blockbench .bbmodel importer with:
 *  - per-face UVs
 *  - element "rotation" around "origin"
 *  - bone hierarchy from "outliner" (accumulated transforms)
 */
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

        // ----- collect elements into a map (uuid -> element) -----
        Map<String, JsonObject> elementsById = new HashMap<>();
        JsonArray elementsArr = root.getAsJsonArray("elements");
        if (elementsArr != null) {
            for (JsonElement el : elementsArr) {
                if (!el.isJsonObject()) continue;
                JsonObject elem = el.getAsJsonObject();
                String id = null;
                if (elem.has("uuid")) {
                    id = elem.get("uuid").getAsString();
                } else if (elem.has("name")) {
                    id = elem.get("name").getAsString();
                }
                if (id != null) {
                    elementsById.put(id, elem);
                }
            }
        }

        // ----- build bone tree from "outliner" -----
        List<BoneNode> rootBones = new ArrayList<>();
        JsonArray outliner = root.getAsJsonArray("outliner");
        if (outliner != null) {
            for (JsonElement entry : outliner) {
                if (entry.isJsonPrimitive()) {
                    // direct element id at root
                    String id = entry.getAsString();
                    BoneNode n = new BoneNode();
                    n.elementIds.add(id);
                    rootBones.add(n);
                } else if (entry.isJsonObject()) {
                    rootBones.add(parseBoneNode(entry.getAsJsonObject()));
                }
            }
        }

        // geometry buffers
        List<Float> vertList = new ArrayList<>();
        List<Float> uvList = new ArrayList<>();
        List<Integer> indexList = new ArrayList<>();
        List<Integer> uvIndexList = new ArrayList<>();

        Set<String> visitedElements = new HashSet<>();

        // ----- bake geometry using bones if outliner exists -----
        if (!rootBones.isEmpty()) {
            System.out.println("[BbModelImporter] Using outliner / bones");
            List<BoneTransform> emptyChain = new ArrayList<>();
            for (BoneNode rootBone : rootBones) {
                bakeNode(rootBone, emptyChain, elementsById,
                        texWidth, texHeight,
                        vertList, uvList, indexList, uvIndexList,
                        visitedElements);
            }
        }

        // ----- fallback: any elements not referenced in outliner -----
        if (visitedElements.isEmpty()) {
            System.out.println("[BbModelImporter] No outliner, importing elements as-is");
        } else {
            System.out.println("[BbModelImporter] Elements referenced by outliner: " + visitedElements.size());
        }

        List<BoneTransform> emptyChain = new ArrayList<>();
        for (Map.Entry<String, JsonObject> entry : elementsById.entrySet()) {
            if (visitedElements.contains(entry.getKey())) continue;
            buildCubeFromElement(entry.getValue(), emptyChain,
                    texWidth, texHeight,
                    vertList, uvList, indexList, uvIndexList);
        }

        // ----- finalize mesh -----
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
        return model;
    }

    // ------------------------------------------------------------------------
    //  Bone tree
    // ------------------------------------------------------------------------

    private static class BoneNode {
        String name;
        float ox, oy, oz;
        float rx, ry, rz;
        List<String> elementIds = new ArrayList<>();
        List<BoneNode> children = new ArrayList<>();
    }

    private static class BoneTransform {
        final float ox, oy, oz;
        final float rx, ry, rz;

        BoneTransform(float ox, float oy, float oz, float rx, float ry, float rz) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
        }
    }

    private BoneNode parseBoneNode(JsonObject obj) {
        BoneNode node = new BoneNode();

        if (obj.has("name")) {
            node.name = obj.get("name").getAsString();
        }

        // bone origin
        if (obj.has("origin") && obj.get("origin").isJsonArray()) {
            JsonArray o = obj.getAsJsonArray("origin");
            if (o.size() >= 3) {
                node.ox = o.get(0).getAsFloat();
                node.oy = o.get(1).getAsFloat();
                node.oz = o.get(2).getAsFloat();
            }
        }

        // bone rotation
        if (obj.has("rotation") && obj.get("rotation").isJsonArray()) {
            JsonArray r = obj.getAsJsonArray("rotation");
            if (r.size() >= 3) {
                node.rx = r.get(0).getAsFloat();
                node.ry = r.get(1).getAsFloat();
                node.rz = r.get(2).getAsFloat();
            }
        }

        // if this node directly references an element
        if (obj.has("uuid")) {
            node.elementIds.add(obj.get("uuid").getAsString());
        }

        // children
        if (obj.has("children") && obj.get("children").isJsonArray()) {
            JsonArray ch = obj.getAsJsonArray("children");
            for (JsonElement ce : ch) {
                if (ce.isJsonPrimitive()) {
                    node.elementIds.add(ce.getAsString());
                } else if (ce.isJsonObject()) {
                    node.children.add(parseBoneNode(ce.getAsJsonObject()));
                }
            }
        }

        return node;
    }

    private void bakeNode(
            BoneNode node,
            List<BoneTransform> parentChain,
            Map<String, JsonObject> elementsById,
            int texWidth, int texHeight,
            List<Float> vertList, List<Float> uvList,
            List<Integer> indexList, List<Integer> uvIndexList,
            Set<String> visitedElements
    ) {
        // extend transform chain with this bone
        List<BoneTransform> chain = new ArrayList<>(parentChain);
        chain.add(new BoneTransform(node.ox, node.oy, node.oz, node.rx, node.ry, node.rz));

        // attach elements
        for (String elemId : node.elementIds) {
            JsonObject elem = elementsById.get(elemId);
            if (elem == null) continue;
            buildCubeFromElement(elem, chain, texWidth, texHeight,
                    vertList, uvList, indexList, uvIndexList);
            visitedElements.add(elemId);
        }

        // recurse
        for (BoneNode child : node.children) {
            bakeNode(child, chain, elementsById,
                    texWidth, texHeight,
                    vertList, uvList, indexList, uvIndexList,
                    visitedElements);
        }
    }

    // ------------------------------------------------------------------------
    //  Geometry from one Blockbench element (cube)
    // ------------------------------------------------------------------------

    private void buildCubeFromElement(
            JsonObject elem,
            List<BoneTransform> parentChain,
            int texWidth, int texHeight,
            List<Float> vertList, List<Float> uvList,
            List<Integer> indexList, List<Integer> uvIndexList
    ) {
        JsonArray from = elem.getAsJsonArray("from");
        JsonArray to   = elem.getAsJsonArray("to");
        if (from == null || to == null || from.size() < 3 || to.size() < 3) return;

        float fx = from.get(0).getAsFloat();
        float fy = from.get(1).getAsFloat();
        float fz = from.get(2).getAsFloat();
        float tx = to.get(0).getAsFloat();
        float ty = to.get(1).getAsFloat();
        float tz = to.get(2).getAsFloat();

        // element local origin / rotation
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

        // full transform chain = bones + this element
        List<BoneTransform> fullChain = new ArrayList<>(parentChain);
        fullChain.add(new BoneTransform(ox, oy, oz, rotX, rotY, rotZ));

        // build local cube corners (unrotated)
        float[][] cube = new float[8][3];
        cube[0] = new float[]{fx, fy, fz};
        cube[1] = new float[]{tx, fy, fz};
        cube[2] = new float[]{tx, ty, fz};
        cube[3] = new float[]{fx, ty, fz};
        cube[4] = new float[]{fx, fy, tz};
        cube[5] = new float[]{tx, fy, tz};
        cube[6] = new float[]{tx, ty, tz};
        cube[7] = new float[]{fx, ty, tz};

        // apply all bone + element rotations in order
        for (int i = 0; i < 8; i++) {
            float[] p = cube[i];
            for (BoneTransform bt : fullChain) {
                p = rotatePoint(p[0], p[1], p[2],
                        bt.ox, bt.oy, bt.oz,
                        bt.rx, bt.ry, bt.rz);
            }
            cube[i] = p;
        }

        // faces with UVs
        JsonObject facesObj = elem.getAsJsonObject("faces");
        if (facesObj == null) {
            // fallback: cube without UVs
            addWholeCubeWithoutUV(cube, vertList, indexList);
            return;
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

            float[][] quadUV = new float[][]{
                    {u1, v1},
                    {u2, v1},
                    {u2, v2},
                    {u1, v2}
            };

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

            // triangles 0-1-2 / 2-3-0
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
        double cx = Math.cos(rx), sx = Math.sin(rx);
        double py1 = py * cx - pz * sx;
        double pz1 = py * sx + pz * cx;
        double px1 = px;

        // Y
        double cy = Math.cos(ry), sy = Math.sin(ry);
        double pz2 = pz1 * cy - px1 * sy;
        double px2 = pz1 * sy + px1 * cy;
        double py2 = py1;

        // Z
        double cz = Math.cos(rz), sz = Math.sin(rz);
        double px3 = px2 * cz - py2 * sz;
        double py3 = px2 * sz + py2 * cz;
        double pz3 = pz2;

        return new float[] {
                (float)(px3 + ox),
                (float)(py3 + oy),
                (float)(pz3 + oz)
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
