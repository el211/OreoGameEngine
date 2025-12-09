package fr.oreostudios.assets;

import com.google.gson.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.util.*;

/**
 * Blockbench .bbmodel importer with heavy debug logging.
 * - per-face UVs
 * - element rotation around origin
 * - simple bone hierarchy from "outliner" (accumulated transforms)
 */
public class BbModelImporter implements ModelImporter {

    // Flip this to false when things are stable
    private static final boolean DEBUG = true;

    private static void dbg(String msg) {
        if (DEBUG) System.out.println("[BbModelImporter] " + msg);
    }

    @Override
    public OreoModel importModel(File file) throws Exception {
        dbg("=== IMPORT START: " + file.getAbsolutePath() + " ===");

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
        dbg("Model name = " + name);

        OreoModel model = new OreoModel(name);

        // ----- resolution (texture size) -----
        int texWidth = 128;
        int texHeight = 128;
        if (root.has("resolution") && root.get("resolution").isJsonObject()) {
            JsonObject res = root.getAsJsonObject("resolution");
            if (res.has("width")) texWidth = res.get("width").getAsInt();
            if (res.has("height")) texHeight = res.get("height").getAsInt();
        }
        dbg("Initial texture resolution from .bbmodel: " + texWidth + "x" + texHeight);

        // ----- texture path: try sibling PNG first -----
        File siblingPng = new File(file.getParentFile(), baseName + ".png");
        String texturePath = null;

        if (siblingPng.exists()) {
            texturePath = siblingPng.getAbsolutePath();
            dbg("Using sibling texture: " + texturePath);
        } else if (root.has("textures") && root.get("textures").isJsonObject()) {
            JsonObject texturesObj = root.getAsJsonObject("textures");
            dbg("textures section found with " + texturesObj.entrySet().size() + " entries");
            for (Map.Entry<String, JsonElement> entry : texturesObj.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject tex = entry.getValue().getAsJsonObject();
                if (tex.has("path")) {
                    String p = tex.get("path").getAsString();
                    File texFile = new File(p);
                    if (!texFile.isAbsolute()) {
                        texFile = new File(file.getParentFile(), p);
                    }
                    dbg("Trying texture path from json: " + texFile.getAbsolutePath());
                    if (texFile.exists()) {
                        texturePath = texFile.getAbsolutePath();
                        dbg("Texture found: " + texturePath);
                        break;
                    } else {
                        dbg("Texture does not exist on disk.");
                    }
                }
            }
        }

        if (texturePath != null) {
            model.setTexturePath(texturePath);
            try {
                BufferedImage img = ImageIO.read(new File(texturePath));
                texWidth = img.getWidth();
                texHeight = img.getHeight();
                dbg("Texture size from image: " + texWidth + "x" + texHeight);
            } catch (Exception ex) {
                dbg("Failed to read texture image for size: " + ex.getMessage());
            }
        } else {
            dbg("No usable texture found for model " + name);
        }

        // ----- collect elements into a map (uuid -> element) -----
        Map<String, JsonObject> elementsById = new HashMap<>();
        JsonArray elementsArr = root.getAsJsonArray("elements");
        if (elementsArr != null) {
            dbg("elements array size = " + elementsArr.size());
            int idx = 0;
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

                    dbg("Element #" + idx + " id=" + id);
                    dbg("  from=" + elem.get("from"));
                    dbg("  to  =" + elem.get("to"));
                    if (elem.has("origin")) dbg("  origin=" + elem.get("origin"));
                    if (elem.has("rotation")) dbg("  rotation=" + elem.get("rotation"));
                } else {
                    dbg("Element #" + idx + " has no uuid/name, skipping map key.");
                }
                idx++;
            }
        } else {
            dbg("No 'elements' array present!");
        }

        // ----- build bone tree from "outliner" -----
        List<BoneNode> rootBones = new ArrayList<>();
        JsonArray outliner = root.getAsJsonArray("outliner");
        if (outliner != null) {
            dbg("outliner size = " + outliner.size());
            int i = 0;
            for (JsonElement entry : outliner) {
                dbg("Outliner[" + i + "] type = " + entry.getClass().getSimpleName());
                if (entry.isJsonPrimitive()) {
                    // direct element id at root
                    String id = entry.getAsString();
                    dbg("  primitive id = " + id);
                    BoneNode n = new BoneNode();
                    n.name = "rootElement:" + id;
                    n.elementIds.add(id);
                    rootBones.add(n);
                } else if (entry.isJsonObject()) {
                    BoneNode bn = parseBoneNode(entry.getAsJsonObject(), 0);
                    rootBones.add(bn);
                }
                i++;
            }
        } else {
            dbg("No 'outliner' array present!");
        }

        // geometry buffers
        List<Float> vertList = new ArrayList<>();
        List<Float> uvList = new ArrayList<>();
        List<Integer> indexList = new ArrayList<>();
        List<Integer> uvIndexList = new ArrayList<>();

        Set<String> visitedElements = new HashSet<>();

        // ----- bake geometry using bones if outliner exists -----
        if (!rootBones.isEmpty()) {
            dbg("Using outliner / bones to bake geometry. rootBones=" + rootBones.size());
            List<BoneTransform> emptyChain = new ArrayList<>();
            for (BoneNode rootBone : rootBones) {
                dbg("Bake root bone: " + rootBone.name);
                bakeNode(rootBone, emptyChain, elementsById,
                        texWidth, texHeight,
                        vertList, uvList, indexList, uvIndexList,
                        visitedElements);
            }
        } else {
            dbg("No bones/outliner -> all elements will be baked with only their own rotations.");
        }

        // ----- fallback: any elements not referenced in outliner -----
        List<BoneTransform> emptyChain = new ArrayList<>();
        for (Map.Entry<String, JsonObject> entry : elementsById.entrySet()) {
            if (visitedElements.contains(entry.getKey())) continue;
            dbg("Element not referenced by outliner, baking standalone: " + entry.getKey());
            buildCubeFromElement(entry.getValue(), emptyChain,
                    texWidth, texHeight,
                    vertList, uvList, indexList, uvIndexList);
        }

        // ----- finalize mesh -----
        if (vertList.isEmpty() || indexList.isEmpty()) {
            dbg("WARNING: no geometry produced, using stub triangle.");
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

            dbg("Built mesh: verts=" + vertices.length +
                    ", indices=" + indices.length +
                    ", uvs=" + (uvsArr == null ? 0 : uvsArr.length));

            model.addMesh(new Mesh(vertices, indices, uvsArr, uvIdxArr));
        }

        dbg("=== IMPORT END ===");
        return model;
    }

    // ------------------------------------------------------------------------
    //  Bone tree (simple, no matrices)
    // ------------------------------------------------------------------------

    private static class BoneNode {
        String name;
        float ox, oy, oz;
        float rx, ry, rz;
        List<String> elementIds = new ArrayList<>();
        List<BoneNode> children = new ArrayList<>();
    }

    private static class BoneTransform {
        final Vector3f origin;
        final Vector3f rotationDeg;

        BoneTransform(float ox, float oy, float oz, float rx, float ry, float rz) {
            this.origin = new Vector3f(ox, oy, oz);
            this.rotationDeg = new Vector3f(rx, ry, rz);
        }
    }

    private BoneNode parseBoneNode(JsonObject obj, int depth) {
        BoneNode node = new BoneNode();

        if (obj.has("name")) {
            node.name = obj.get("name").getAsString();
        } else if (obj.has("uuid")) {
            node.name = "bone-" + obj.get("uuid").getAsString();
        } else {
            node.name = "bone-depth-" + depth;
        }

        dbg(indent(depth) + "Parse bone '" + node.name + "'");

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

        dbg(indent(depth) + "  origin=(" + node.ox + "," + node.oy + "," + node.oz + ")");
        dbg(indent(depth) + "  rotation=(" + node.rx + "," + node.ry + "," + node.rz + ")");

        // if this node directly references an element
        if (obj.has("uuid") && !obj.has("children")) {
            String eid = obj.get("uuid").getAsString();
            node.elementIds.add(eid);
            dbg(indent(depth) + "  direct element uuid=" + eid);
        }

        // children
        if (obj.has("children") && obj.get("children").isJsonArray()) {
            JsonArray ch = obj.getAsJsonArray("children");
            dbg(indent(depth) + "  children count=" + ch.size());
            for (JsonElement ce : ch) {
                if (ce.isJsonPrimitive()) {
                    String id = ce.getAsString();
                    node.elementIds.add(id);
                    dbg(indent(depth) + "    child element id=" + id);
                } else if (ce.isJsonObject()) {
                    BoneNode child = parseBoneNode(ce.getAsJsonObject(), depth + 1);
                    node.children.add(child);
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
        dbg("BakeNode '" + node.name + "'  parentChainSize=" + parentChain.size());

        // extend transform chain with this bone
        List<BoneTransform> chain = new ArrayList<>(parentChain);
        chain.add(new BoneTransform(node.ox, node.oy, node.oz, node.rx, node.ry, node.rz));

        dbg("  Chain now:");
        int idx = 0;
        for (BoneTransform bt : chain) {
            dbg("    [" + idx + "] origin=(" + bt.origin.x + "," + bt.origin.y + "," + bt.origin.z
                    + ") rot=(" + bt.rotationDeg.x + "," + bt.rotationDeg.y + "," + bt.rotationDeg.z + ")");
            idx++;
        }

        // attach elements
        for (String elemId : node.elementIds) {
            JsonObject elem = elementsById.get(elemId);
            dbg("  Processing element id=" + elemId + " attached to bone '" + node.name + "'");
            if (elem == null) {
                dbg("    WARNING: element id not found in elementsById!");
                continue;
            }
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

    private String indent(int d) {
        return "  ".repeat(Math.max(0, d));
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
        if (from == null || to == null || from.size() < 3 || to.size() < 3) {
            dbg("buildCubeFromElement: element without valid from/to -> skipping");
            return;
        }

        float fx = from.get(0).getAsFloat();
        float fy = from.get(1).getAsFloat();
        float fz = from.get(2).getAsFloat();
        float tx = to.get(0).getAsFloat();
        float ty = to.get(1).getAsFloat();
        float tz = to.get(2).getAsFloat();

        dbg("buildCubeFromElement: from=(" + fx + "," + fy + "," + fz + ") to=(" + tx + "," + ty + "," + tz + ")");

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

        dbg("  local origin=(" + ox + "," + oy + "," + oz + ") local rot=(" + rotX + "," + rotY + "," + rotZ + ")");
        dbg("  parentChain size=" + parentChain.size());

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

        dbg("  cube corners BEFORE transform:");
        for (int i = 0; i < 8; i++) {
            dbg("    v" + i + " = (" + cube[i][0] + "," + cube[i][1] + "," + cube[i][2] + ")");
        }

        // === NEW: build combined transform matrix for the whole chain ===
        Matrix4f transform = new Matrix4f().identity();
        for (BoneTransform bt : fullChain) {
            float rxRad = (float) Math.toRadians(bt.rotationDeg.x);
            float ryRad = (float) Math.toRadians(bt.rotationDeg.y);
            float rzRad = (float) Math.toRadians(bt.rotationDeg.z);

            // translate to origin, rotate, translate back
            transform.translate(bt.origin);
            transform.rotateXYZ(rxRad, ryRad, rzRad);
            transform.translate(-bt.origin.x, -bt.origin.y, -bt.origin.z);
        }

        // Apply matrix to all cube corners
        for (int i = 0; i < 8; i++) {
            Vector3f v = new Vector3f(cube[i][0], cube[i][1], cube[i][2]);
            transform.transformPosition(v);
            cube[i][0] = v.x;
            cube[i][1] = v.y;
            cube[i][2] = v.z;
            dbg("    AFTER transform v" + i + " = (" + v.x + "," + v.y + "," + v.z + ")");
        }

        dbg("  cube corners AFTER transform (matrix baked)");

        // faces with UVs
        JsonObject facesObj = elem.getAsJsonObject("faces");
        if (facesObj == null) {
            dbg("  no 'faces' object, using whole cube without UVs");
            addWholeCubeWithoutUV(cube, vertList, indexList);
            return;
        }

        for (Map.Entry<String, JsonElement> faceEntry : facesObj.entrySet()) {
            String dir = faceEntry.getKey(); // north/south/east/west/up/down
            JsonObject face = faceEntry.getValue().getAsJsonObject();
            dbg("  face '" + dir + "'");
            if (!face.has("uv")) {
                dbg("    no uv -> skipped");
                continue;
            }
            JsonArray uvArr = face.getAsJsonArray("uv");
            if (uvArr.size() < 4) {
                dbg("    uv array < 4 -> skipped");
                continue;
            }

            float u1 = uvArr.get(0).getAsFloat() / texWidth;
            float v1 = uvArr.get(1).getAsFloat() / texHeight;
            float u2 = uvArr.get(2).getAsFloat() / texWidth;
            float v2 = uvArr.get(3).getAsFloat() / texHeight;

            dbg("    uvPixels=" + uvArr + "  uvNorm=(" + u1 + "," + v1 + ")-(" + u2 + "," + v2 + ")");

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
                    dbg("    unknown direction '" + dir + "' -> skipped");
                    continue;
            }

            dbg("    cornerIdx=" + Arrays.toString(cornerIdx));

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

                dbg("      add vert[" + (baseVertIndex + i) + "] pos=(" +
                        p[0] + "," + p[1] + "," + p[2] + ") uv=(" + uv[0] + "," + uv[1] + ")");
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
                dbg("      faceIndex add p=" + triVerts[i] + " t=" + triUvs[i]);
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

    // kept for reference / possible future use
    private float[] rotatePoint(float x, float y, float z,
                                float ox, float oy, float oz,
                                float rotX, float rotY, float rotZ) {
        double rx = Math.toRadians(rotX);
        double ry = Math.toRadians(rotY);
        double rz = Math.toRadians(rotZ);

        double px = x - ox;
        double py = y - oy;
        double pz = z - oz;

        // Z first
        double cz = Math.cos(rz), sz = Math.sin(rz);
        double px1 = px * cz - py * sz;
        double py1 = px * sz + py * cz;
        double pz1 = pz;

        // then Y
        double cy = Math.cos(ry), sy = Math.sin(ry);
        double pz2 = pz1 * cy - px1 * sy;
        double px2 = pz1 * sy + px1 * cy;
        double py2 = py1;

        // then X
        double cx = Math.cos(rx), sx = Math.sin(rx);
        double py3 = py2 * cx - pz2 * sx;
        double pz3 = py2 * sx + pz2 * cx;
        double px3 = px2;

        return new float[] {
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
