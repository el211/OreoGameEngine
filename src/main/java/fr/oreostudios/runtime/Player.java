package fr.oreostudios.runtime;

import java.awt.*;

public class Player {

    private float x, y;
    private final int width, height;
    private final float speed = 150f;

    private final TileMap map;
    private Input input;

    public Player(float x, float y, int width, int height, TileMap map) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.map = map;
    }

    public void setInput(Input input) {
        this.input = input;
    }

    public void update(float dt) {
        if (input == null) return;

        float dx = 0;
        float dy = 0;

        if (input.isUp()) dy -= 1;
        if (input.isDown()) dy += 1;
        if (input.isLeft()) dx -= 1;
        if (input.isRight()) dx += 1;

        if (dx != 0 && dy != 0) {
            float inv = (float) (1 / Math.sqrt(2));
            dx *= inv;
            dy *= inv;
        }

        float newX = x + dx * speed * dt;
        float newY = y + dy * speed * dt;

        if (!collides(newX, y)) x = newX;
        if (!collides(x, newY)) y = newY;
    }

    private boolean collides(float testX, float testY) {
        int tileSize = map.getTileSize();

        float left = testX;
        float right = testX + width;
        float top = testY;
        float bottom = testY + height;

        return map.isWallAt(left, top)
                || map.isWallAt(right, top)
                || map.isWallAt(left, bottom)
                || map.isWallAt(right, bottom);
    }

    public void render(Graphics g, int camX, int camY) {
        g.setColor(Color.BLUE);
        int drawX = (int) (x - camX);
        int drawY = (int) (y - camY);
        g.fillRect(drawX, drawY, width, height);
    }

    public float getX() { return x; }
    public float getY() { return y; }
}
