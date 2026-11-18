import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public class Orbes {

    static final int[][] PALETTE = {
        {40, 160, 255}, {40, 255, 150}, {255, 70, 200}, {255, 160, 40}, {160, 100, 255}, {255, 235, 80}
    };
    static final String[] NAMES = {"Bleu", "Vert", "Magenta", "Orange", "Violet", "Jaune"};
    static final Path DIR = Paths.get(System.getProperty("java.io.tmpdir"), "orbes");
    static final long STALE = 3000;
    static final int N_POINTS = 700;
    static final double[][] PTS = new double[N_POINTS][3];
    static final Set<String> MY_IDS = Collections.synchronizedSet(new HashSet<>());
    static int openWindows = 0;

    // Sphère de Fibonacci : points répartis uniformément sur une sphère
    static {
        for (int i = 0; i < N_POINTS; i++) {
            double y = 1 - (i / (double) (N_POINTS - 1)) * 2, r = Math.sqrt(1 - y * y), th = i * 2.399963;
            PTS[i][0] = Math.cos(th) * r;
            PTS[i][1] = y;
            PTS[i][2] = Math.sin(th) * r;
        }
    }

    static final class Node {
        String id;
        int c;
        long born, ts;
        double x, y, left, top, r;   // coordonnées sur l'écran physique
        double lx, ly;

        String serialize() {
            return id + ";" + c + ";" + born + ";" + ts + ";" + x + ";" + y + ";" + left + ";" + top + ";" + r;
        }

        static Node parse(String s) {
            String[] p = s.trim().split(";");
            if (p.length < 9) return null;
            Node n = new Node();
            n.id = p[0];
            n.c = Integer.parseInt(p[1]);
            n.born = Long.parseLong(p[2]);
            n.ts = Long.parseLong(p[3]);
            n.x = Double.parseDouble(p[4]);
            n.y = Double.parseDouble(p[5]);
            n.left = Double.parseDouble(p[6]);
            n.top = Double.parseDouble(p[7]);
            n.r = Double.parseDouble(p[8]);
            return n;
        }
    }

    // ---------- Communication by files ----------
    static void publish(Node n) {
        try {
            Files.createDirectories(DIR);
            Path tmp = DIR.resolve(n.id + ".tmp"), dst = DIR.resolve(n.id + ".orb");
            Files.write(tmp, n.serialize().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) { }
    }

    static void removeNode(String id) {
        try { Files.deleteIfExists(DIR.resolve(id + ".orb")); } catch (Exception ignored) { }
        try { Files.deleteIfExists(DIR.resolve(id + ".tmp")); } catch (Exception ignored) { }
    }

    static List<Node> readOthers(String selfId) {
        List<Node> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (!Files.isDirectory(DIR)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DIR, "*.orb")) {
            for (Path p : ds) {
                try {
                    Node n = Node.parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                    if (n == null || n.id.equals(selfId)) continue;
                    if (now - n.ts > STALE) {
                        if (now - n.ts > STALE * 5) Files.deleteIfExists(p); // fenêtre plantée
                        continue;
                    }
                    out.add(n);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return out;
    }

    static double clamp(double v, double a, double b) { return v < a ? a : v > b ? b : v; }

    static double[] mix(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    static double hash(double a, double b) {
        double n = Math.sin(a * 127.1 + b * 311.7) * 43758.5453;
        return n - Math.floor(n);
    }

    static Color color(double[] c, double a) {
        return new Color((int) clamp(c[0], 0, 255), (int) clamp(c[1], 0, 255), (int) clamp(c[2], 0, 255),
                (int) clamp(a * 255, 0, 255));
    }

    // ---------- Windows aspect ----------
    static final class OrbWindow extends JFrame {
        final Node me = new Node();
        final JPanel panel;
        javax.swing.Timer timer;
        BufferedImage img;
        int[] px;
        int W, H;
        List<Node> others = new ArrayList<>();
        long lastWrite = 0;

        OrbWindow(int x, int y, int w, int h) {
            super("Orbes multi-fenêtres");
            me.id = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            me.born = System.currentTimeMillis();
            MY_IDS.add(me.id);

            // Choose a color
            Set<Integer> used = new HashSet<>();
            for (Node n : readOthers(me.id)) used.add(n.c);
            me.c = 0;
            while (me.c < PALETTE.length && used.contains(me.c)) me.c++;
            if (me.c >= PALETTE.length) me.c = new Random().nextInt(PALETTE.length);

            panel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    if (img != null) g.drawImage(img, 0, 0, null);
                }
            };
            panel.setBackground(new Color(2, 4, 10));
            panel.setPreferredSize(new Dimension(w, h));
            setContentPane(panel);
            pack();
            setLocation(x, y);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);

            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    if (timer != null) timer.stop();
                    removeNode(me.id);
                    MY_IDS.remove(me.id);
                    if (--openWindows <= 0) System.exit(0);
                }
            });

            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_N)
                        new OrbWindow(getX() + 80, getY() + 80, Math.max(400, (int) (W * 0.8)), Math.max(300, (int) (H * 0.8)));
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) dispose();
                }
            });

            openWindows++;
            setVisible(true);
            timer = new javax.swing.Timer(16, e -> tick());
            timer.start();
        }

        void tick() {
            if (!panel.isShowing()) return;
            int w = Math.max(1, panel.getWidth()), h = Math.max(1, panel.getHeight());
            if (img == null || w != W || h != H) {
                W = w;
                H = h;
                img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
                px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            }
            Point p;
            try {
                p = panel.getLocationOnScreen();   // coin haut-gauche du contenu, sur l'écran
            } catch (IllegalComponentStateException ex) {
                return;
            }
            long now = System.currentTimeMillis();
            me.left = p.x;
            me.top = p.y;
            me.x = p.x + W / 2.0;
            me.y = p.y + H / 2.0;
            me.r = clamp(Math.min(W, H) * 0.2, 50, 170);
            me.ts = now;
            if (now - lastWrite > 50) {
                publish(me);
                lastWrite = now;
            }
            others = readOthers(me.id);
            render(now / 1000.0);   // horloge commune à toutes les fenêtres
            panel.repaint();
        }

        void add(int x, int y, double[] c, double a) {
            if (x < 0 || y < 0 || x >= W || y >= H || a <= 0) return;
            int i = y * W + x, v = px[i];
            int r = Math.min(255, ((v >> 16) & 255) + (int) (c[0] * a));
            int g = Math.min(255, ((v >> 8) & 255) + (int) (c[1] * a));
            int b = Math.min(255, (v & 255) + (int) (c[2] * a));
            px[i] = (r << 16) | (g << 8) | b;
        }

        void addSquare(double cx, double cy, double size, double[] c, double a) {
            int s = Math.max(1, (int) Math.round(size));
            if (size < s) a *= (size * size) / (s * s);
            int x0 = (int) Math.floor(cx - s / 2.0), y0 = (int) Math.floor(cy - s / 2.0);
            for (int dy = 0; dy < s; dy++)
                for (int dx = 0; dx < s; dx++) add(x0 + dx, y0 + dy, c, a);
        }

        void fade() {
            for (int i = 0; i < px.length; i++) {
                int v = px[i];
                int r = (((v >> 16) & 255) * 158) >> 8, g = (((v >> 8) & 255) * 158) >> 8, b = ((v & 255) * 158) >> 8;
                px[i] = ((r + 1) << 16) | ((g + 2) << 8) | (b + 4);
            }
        }

        void stars() {
            double[] sc = {156, 200, 255};
            int cell = 110;
            int x0 = (int) Math.floor(me.left / cell), y0 = (int) Math.floor(me.top / cell);
            int x1 = (int) Math.ceil((me.left + W) / cell), y1 = (int) Math.ceil((me.top + H) / cell);
            for (int gx = x0; gx <= x1; gx++)
                for (int gy = y0; gy <= y1; gy++) {
                    int sx = (int) ((gx + hash(gx, gy)) * cell - me.left), sy = (int) ((gy + hash(gy, gx)) * cell - me.top);
                    add(sx, sy, sc, 0.15 + hash(gx * 3, gy * 7) * 0.35);
                }
        }

        void halo(double cx, double cy, double R, double[] c) {
            double Rh = R * 1.7;
            int x0 = Math.max(0, (int) (cx - Rh)), x1 = Math.min(W - 1, (int) (cx + Rh));
            int y0 = Math.max(0, (int) (cy - Rh)), y1 = Math.min(H - 1, (int) (cy + Rh));
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) {
                    double d = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / Rh;
                    if (d >= 1) continue;
                    double a = d < .35 ? .28 + (.09 - .28) * d / .35 : .09 * (1 - (d - .35) / .65);
                    add(x, y, c, a);
                }
        }

        void render(double t) {
            List<Node> orbs = new ArrayList<>(others);
            orbs.add(me);
            orbs.sort(Comparator.comparingLong(n -> n.born));
            for (Node o : orbs) {
                o.lx = o.x - me.left;
                o.ly = o.y - me.top;
            }
            int n = orbs.size();

            // Color fusion
            double[][] base = new double[n][], cols = new double[n][];
            for (int i = 0; i < n; i++) {
                int[] p = PALETTE[orbs.get(i).c % PALETTE.length];
                base[i] = new double[]{p[0], p[1], p[2]};
            }
            for (int i = 0; i < n; i++) {
                double[] c = base[i];
                Node o = orbs.get(i);
                for (int j = 0; j < n; j++) {
                    if (i == j) continue;
                    Node q = orbs.get(j);
                    double w = clamp(1 - Math.hypot(q.x - o.x, q.y - o.y) / ((o.r + q.r) * 1.8), 0, 1);
                    c = mix(c, base[j], w * 0.5);
                }
                cols[i] = c;
            }

            fade();
            stars();
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            for (int i = 0; i < n; i++)
                for (int j = i + 1; j < n; j++)
                    drawLink(g, orbs.get(i), orbs.get(j), cols[i], cols[j], t, (orbs.get(i).born + orbs.get(j).born) % 97);

            for (int i = 0; i < n; i++) {
                Node o = orbs.get(i);
                List<double[]> pulls = new ArrayList<>();
                for (int j = 0; j < n; j++) {
                    if (i == j) continue;
                    Node q = orbs.get(j);
                    double dx = q.x - o.x, dy = q.y - o.y, D = Math.max(1, Math.hypot(dx, dy));
                    double k = clamp(1 - D / ((o.r + q.r) * 2.4), 0, 1);
                    if (k > 0) pulls.add(new double[]{dx / D, dy / D, k});
                }
                drawOrb(g, o, cols[i], t, pulls);
            }

            hud(g, orbs);
            g.dispose();
        }

        // ---------- Jarvis sphere ----------
        void drawOrb(Graphics2D g, Node o, double[] col, double t, List<double[]> pulls) {
            double R = o.r, ph = o.c * 1.7, cx = o.lx, cy = o.ly;
            halo(cx, cy, R, col);

            double ay = t * 0.35 + ph, ax = 0.45 + Math.sin(t * 0.23 + ph) * 0.35;
            double cY = Math.cos(ay), sY = Math.sin(ay), cX = Math.cos(ax), sX = Math.sin(ax);
            for (int i = 0; i < N_POINTS; i++) {
                double[] p = PTS[i];
                double r = 1 + 0.05 * Math.sin(t * 2.1 + i * 0.37) + 0.03 * Math.sin(t * 3.7 + i * 1.3);
                double x = p[0] * r, y = p[1] * r, z = p[2] * r;
                double x1 = x * cY - z * sY, z1 = x * sY + z * cY;
                double y1 = y * cX - z1 * sX, z2 = y * sX + z1 * cX;
                double s = 2.4 / (2.4 + z2);
                double ppx = x1 * R * s, ppy = y1 * R * s;
                for (double[] q : pulls) {   // les points s'étirent vers les autres sphères
                    double d = (ppx * q[0] + ppy * q[1]) / R;
                    if (d > 0) {
                        double f = q[2] * d * d * d * R * 0.7;
                        ppx += q[0] * f;
                        ppy += q[1] * f;
                    }
                }
                double front = 1 - (z2 + 1) / 2;
                addSquare(cx + ppx, cy + ppy, 0.7 + 1.7 * front, col, 0.18 + 0.8 * front);
            }

            for (int k = 0; k < 2; k++) {
                double rr = R * (1.35 + 0.28 * k), tilt = k == 1 ? -0.5 : 0.35, flat = k == 1 ? 0.32 : 0.22;
                double spin = t * (k == 1 ? -0.55 : 0.45) + ph, ct = Math.cos(tilt), st = Math.sin(tilt);
                for (int j = 0; j < 180; j++) {
                    if (j % 22 < 3) continue;
                    double a = j / 180.0 * Math.PI * 2 + spin;
                    double ex = Math.cos(a) * rr, ey = Math.sin(a) * rr * flat;
                    addSquare(cx + ex * ct - ey * st, cy + ex * st + ey * ct, 1.4, col, 0.25 + 0.45 * (Math.sin(a) * 0.5 + 0.5));
                }
            }

            g.setStroke(new BasicStroke(1f));
            g.setColor(color(col, .45));
            arc(g, cx, cy, R * 1.95, t * .3 + ph, 1.1);
            arc(g, cx, cy, R * 1.95, t * .3 + ph + Math.PI, .6);
            g.setColor(color(col, .3));
            arc(g, cx, cy, R * 2.12, -t * .18, 2.2);
            Path2D ticks = new Path2D.Double();
            for (int j = 0; j < 72; j++) {
                double a = j / 72.0 * Math.PI * 2 - t * 0.08, l = j % 6 == 0 ? 10 : 4;
                ticks.moveTo(cx + Math.cos(a) * R * 2.25, cy + Math.sin(a) * R * 2.25);
                ticks.lineTo(cx + Math.cos(a) * (R * 2.25 + l), cy + Math.sin(a) * (R * 2.25 + l));
            }
            g.setColor(color(col, .22));
            g.draw(ticks);
        }

        void arc(Graphics2D g, double cx, double cy, double r, double start, double len) {
            start %= Math.PI * 2;   // garde l'angle petit (l'horloge commune donne de très grands nombres)
            g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, -Math.toDegrees(start), -Math.toDegrees(len), Arc2D.OPEN));
        }

        // ---------- Link between two windows----------
        void drawLink(Graphics2D g, Node A, Node B, double[] cA, double[] cB, double t, double seed) {
            double dx = B.lx - A.lx, dy = B.ly - A.ly, D = Math.hypot(dx, dy);
            if (D < 2) return;
            double nx = -dy / D, ny = dx / D, mx = (A.lx + B.lx) / 2, my = (A.ly + B.ly) / 2;

            for (int k = 0; k < 4; k++) {
                double off = Math.sin(t * (0.9 + k * 0.37) + seed + k * 2.1) * D * (0.04 + k * 0.035);
                Path2D path = new Path2D.Double();
                for (int s = 0; s <= 40; s++) {
                    double[] p = curve(A, B, mx, my, nx, ny, s / 40.0, off);
                    if (s == 0) path.moveTo(p[0], p[1]); else path.lineTo(p[0], p[1]);
                }
                if (k == 0) {
                    g.setPaint(gradient(A, B, cA, cB, .07));
                    g.setStroke(new BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(path);
                    g.setPaint(gradient(A, B, cA, cB, .75));
                    g.setStroke(new BasicStroke(1.6f));
                } else {
                    g.setPaint(gradient(A, B, cA, cB, .28));
                    g.setStroke(new BasicStroke(.8f));
                }
                g.draw(path);
            }

            // dynamics effects
            double off0 = Math.sin(t * 0.9 + seed) * D * 0.04;
            for (int m = 0; m < 46; m++) {
                double u = (t * 0.22 + m / 46.0 + hash(m, seed) * 0.1) % 1;
                if (m % 2 == 1) u = 1 - u;
                double[] p = curve(A, B, mx, my, nx, ny, u, off0);
                double w = Math.sin(u * Math.PI * 7 + t * 4 + m) * 7;
                addSquare(p[0] + nx * w, p[1] + ny * w, 2.4, mix(cA, cB, u), Math.sin(u * Math.PI) * 0.9);
            }
        }

        static double[] curve(Node A, Node B, double mx, double my, double nx, double ny, double u, double off) {
            double qx = mx + nx * off, qy = my + ny * off, v = 1 - u;
            return new double[]{v * v * A.lx + 2 * v * u * qx + u * u * B.lx, v * v * A.ly + 2 * v * u * qy + u * u * B.ly};
        }

        static GradientPaint gradient(Node A, Node B, double[] cA, double[] cB, double a) {
            return new GradientPaint((float) A.lx, (float) A.ly, color(cA, a), (float) B.lx, (float) B.ly, color(cB, a));
        }

        // ---------- Information part on windows ----------
        void hud(Graphics2D g, List<Node> orbs) {
            int[] p = PALETTE[me.c % PALETTE.length];
            double[] c = {p[0], p[1], p[2]};
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            g.setColor(color(c, 1));
            g.fillOval(16, 16, 9, 9);
            g.drawString("Nœud " + NAMES[me.c % NAMES.length] + " · " + me.id, 32, 25);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            g.setColor(new Color(191, 230, 255, 220));
            g.drawString("Fenêtres liées : " + orbs.size(), 16, 46);
            double best = Double.MAX_VALUE;
            for (Node q : others) best = Math.min(best, Math.hypot(q.x - me.x, q.y - me.y));
            g.drawString(best < Double.MAX_VALUE ? "Distance au plus proche : " + Math.round(best) + " px"
                    : "En attente d'une autre fenêtre…", 16, 66);
            g.setColor(new Color(191, 230, 255, 150));
            g.drawString("N : nouvelle fenêtre   ·   Échap : fermer", 16, H - 16);
        }
    }

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (String id : new ArrayList<>(MY_IDS)) removeNode(id);
        }));
        SwingUtilities.invokeLater(() -> {
            Dimension sc = Toolkit.getDefaultToolkit().getScreenSize();
            int w = 900, h = 620;
            Random rnd = new Random();
            int x = rnd.nextInt(Math.max(1, sc.width - w)), y = rnd.nextInt(Math.max(1, sc.height - h));
            new OrbWindow(x, y, w, h);
        });
    }
}
