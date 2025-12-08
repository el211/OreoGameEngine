package fr.oreostudios.runtime;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferStrategy;

public class GameEngine extends Canvas implements Runnable {

    private boolean running = false;
    private Thread gameThread;

    private final int width = 800;
    private final int height = 600;

    private final TileMap map;
    private final Player player;

    public GameEngine() {
        JFrame frame = new JFrame("OreoGame Runtime");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);

        setPreferredSize(new Dimension(width, height));
        frame.add(this);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        this.map = new TileMap(30, 22, 32);
        this.player = new Player(2 * 32, 2 * 32, 32, 32, map);

        Input input = new Input();
        addKeyListener(input);
        setFocusable(true);
        requestFocusInWindow();
        player.setInput(input);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        gameThread = new Thread(this, "GameLoop");
        gameThread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (gameThread != null) gameThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        final double nsPerUpdate = 1_000_000_000.0 / 60.0;
        long lastTime = System.nanoTime();
        double delta = 0;

        while (running) {
            long now = System.nanoTime();
            delta += (now - lastTime) / nsPerUpdate;
            lastTime = now;

            while (delta >= 1) {
                update(1f / 60f);
                delta--;
            }

            render();
        }

        stop();
    }

    private void update(float dt) {
        player.update(dt);
    }

    private void render() {
        BufferStrategy bs = getBufferStrategy();
        if (bs == null) {
            createBufferStrategy(3);
            return;
        }

        Graphics g = bs.getDrawGraphics();

        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width, height);

        int camX = (int) (player.getX() - width / 2f);
        int camY = (int) (player.getY() - height / 2f);

        map.render(g, camX, camY, width, height);
        player.render(g, camX, camY);

        g.dispose();
        bs.show();
    }

    public static void main(String[] args) {
        new GameEngine().start();
    }
}
