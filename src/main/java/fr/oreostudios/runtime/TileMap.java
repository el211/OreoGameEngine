package fr.oreostudios.runtime;

import java.awt.*;

public class TileMap {

    private final int width;
    private final int height;
    private final int tileSize;
    private final int[][] tiles; // 0 floor, 1 wall

    public TileMap(int width, int height, int tileSize) {
        this.width = width;
        this.height = height;
        this.tileSize = tileSize;
        this.tiles = new int[height][width];

        generateTestMap();
    }

    private void generateTestMap() {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    tiles[y][x] = 1;
                } else {
                    tiles[y][x] = 0;
                }
            }
        }

        for (int x = 5; x < 15; x++) tiles[5][x] = 1;
        for (int y = 8; y < 15; y++) tiles[y][10] = 1;
    }

    public boolean isWallAt(float worldX, float worldY) {
        int tx = (int) (worldX / tileSize);
        int ty = (int) (worldY / tileSize);

        if (tx < 0 || ty < 0 || tx >= width || ty >= height) return true;
        return tiles[ty][tx] == 1;
    }

    public int getTileSize() {
        return tileSize;
    }

    public void render(Graphics g, int camX, int camY, int screenW, int screenH) {
        int startX = Math.max(0, camX / tileSize);
        int startY = Math.max(0, camY / tileSize);

        int endX = Math.min(width, (camX + screenW) / tileSize + 2);
        int endY = Math.min(height, (camY + screenH) / tileSize + 2);

        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int tile = tiles[y][x];
                g.setColor(tile == 1 ? Color.DARK_GRAY : Color.LIGHT_GRAY);

                int drawX = x * tileSize - camX;
                int drawY = y * tileSize - camY;
                g.fillRect(drawX, drawY, tileSize, tileSize);
            }
        }
    }
}
