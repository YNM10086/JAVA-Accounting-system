import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成应用图标（原创几何图形，不依赖任何外部素材）。
 *
 * 风格：
 *   f 叠页 — 深色底板 + 三层错位圆角方块 + 账目线（默认）
 *   d 账目 — 深色底板 + 白色圆角外框 + 三条由白到灰的账目线
 *   e 叠页 — 深色底板 + 三层错位圆角方块（无账目线）
 *   a 光环  — 旧暖色方案（保留备用）
 *   b 涟漪  — 旧暖色方案（保留备用）
 *   c 极简  — 旧暖色方案（保留备用）
 *
 * 用法：
 *   javac -encoding UTF-8 -d .build_tmp/icon tools/icon/IconGen.java
 *   java -cp .build_tmp/icon IconGen desktop/build/icon.ico [f|d|e|a|b|c]
 *
 * 输出：多尺寸 PNG 压缩的 ICO（16/24/32/48/64/128/256），Windows Vista+ 原生支持。
 */
public class IconGen {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private static final Color[] AMBER = {
            new Color(0xFF, 0xEC, 0xC4),
            new Color(0xFF, 0xC1, 0x6B),
            new Color(0xF2, 0x8C, 0x33)
    };

    private static final Color INK = new Color(0x0B, 0x0C, 0x0F);
    private static final Color PAPER = new Color(0xF4, 0xF6, 0xF8);
    private static final Color GRAY_LIGHT = new Color(0xA9, 0xAF, 0xB9);
    private static final Color GRAY_MID = new Color(0x6B, 0x71, 0x7B);
    private static final Color GRAY_DARK = new Color(0x4B, 0x51, 0x5B);

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "desktop/build/icon.ico");
        String style = args.length > 1 ? args[1] : "f";
        out.getParentFile().mkdirs();

        List<byte[]> pngs = new ArrayList<>();
        for (int size : SIZES) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(render(size, style), "png", bos);
            pngs.add(bos.toByteArray());
            if (size == 256) {
                File png = new File(out.getParentFile(), "icon.png");
                ImageIO.write(render(256, style), "png", png);
                System.out.println("预览图: " + png.getAbsolutePath());
            }
        }

        try (OutputStream os = new FileOutputStream(out)) {
            writeLE16(os, 0);              // reserved
            writeLE16(os, 1);              // type = icon
            writeLE16(os, SIZES.length);   // count

            int offset = 6 + 16 * SIZES.length;
            for (int i = 0; i < SIZES.length; i++) {
                int s = SIZES[i];
                byte[] data = pngs.get(i);
                os.write(s == 256 ? 0 : s);  // width
                os.write(s == 256 ? 0 : s);  // height
                os.write(0);                 // palette
                os.write(0);                 // reserved
                writeLE16(os, 1);            // planes
                writeLE16(os, 32);           // bit depth
                writeLE32(os, data.length);  // bytes in resource
                writeLE32(os, offset);       // offset
                offset += data.length;
            }
            for (byte[] data : pngs) os.write(data);
        }
        System.out.println("ICO: " + out.getAbsolutePath() + "  (" + out.length() + " bytes, style " + style + ")");

        // 尺寸检查图：浅底/深底各一排，确认小尺寸是否还认得出
        int[] check = {16, 24, 32, 48, 64, 128};
        BufferedImage strip = new BufferedImage(480, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = strip.createGraphics();
        sg.setColor(new Color(0xF2, 0xF4, 0xF7));
        sg.fillRect(0, 0, 480, 150);
        sg.setColor(new Color(0x1B, 0x1E, 0x24));
        sg.fillRect(0, 150, 480, 150);
        int x = 20;
        for (int size : check) {
            BufferedImage img = render(size, style);
            sg.drawImage(img, x, 75 - size / 2, null);
            sg.drawImage(img, x, 225 - size / 2, null);
            x += size + 24;
        }
        sg.dispose();
        File sizes = new File(out.getParentFile(), "icon-sizes.png");
        ImageIO.write(strip, "png", sizes);
        System.out.println("尺寸检查图: " + sizes.getAbsolutePath());
    }

    private static BufferedImage render(int size, String style) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 256.0, size / 256.0);

        RoundRectangle2D plate = new RoundRectangle2D.Float(8, 8, 240, 240, 56, 56);
        g.setClip(plate);
        g.setPaint(new GradientPaint(0, 0, new Color(0x17, 0x19, 0x1D),
                                     0, 256, new Color(0x07, 0x08, 0x0A)));
        g.fill(plate);

        switch (style) {
            case "e": drawStack(g);  break;
            case "f": drawStackLines(g); break;
            case "a": drawHalo(g);   break;
            case "b": drawRipple(g); break;
            case "c": drawMinimal(g);break;
            default:  drawLedger(g); break;
        }

        g.dispose();
        return img;
    }

    /* ---------- d：白色圆角外框 + 三条账目线 ---------- */
    private static void drawLedger(Graphics2D g) {
        g.setColor(PAPER);
        g.setStroke(new BasicStroke(22f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Float(50, 50, 156, 156, 38, 38));

        g.setColor(PAPER);
        g.fill(new RoundRectangle2D.Float(76, 80, 104, 22, 8, 8));
        g.setColor(GRAY_LIGHT);
        g.fill(new RoundRectangle2D.Float(76, 117, 80, 22, 8, 8));
        g.setColor(GRAY_MID);
        g.fill(new RoundRectangle2D.Float(76, 154, 56, 22, 8, 8));
    }

    /* ---------- e：三层错位圆角方块（叠页） ---------- */
    private static void drawStack(Graphics2D g) {
        g.setStroke(new BasicStroke(12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        g.setColor(GRAY_DARK);
        g.fill(new RoundRectangle2D.Float(100, 100, 100, 100, 24, 24));
        g.setColor(INK);
        g.draw(new RoundRectangle2D.Float(100, 100, 100, 100, 24, 24));

        g.setColor(GRAY_LIGHT);
        g.fill(new RoundRectangle2D.Float(78, 78, 100, 100, 24, 24));
        g.setColor(INK);
        g.draw(new RoundRectangle2D.Float(78, 78, 100, 100, 24, 24));

        g.setColor(PAPER);
        g.fill(new RoundRectangle2D.Float(56, 56, 100, 100, 24, 24));
        g.setColor(INK);
        g.draw(new RoundRectangle2D.Float(56, 56, 100, 100, 24, 24));
    }

    /* ---------- f：叠页 + 账目线 ---------- */
    private static void drawStackLines(Graphics2D g) {
        drawStack(g);

        g.setColor(GRAY_LIGHT);
        g.fill(new RoundRectangle2D.Float(74, 80, 60, 10, 5, 5));
        g.setColor(GRAY_MID);
        g.fill(new RoundRectangle2D.Float(74, 101, 46, 10, 5, 5));
        g.setColor(GRAY_DARK);
        g.fill(new RoundRectangle2D.Float(74, 122, 32, 10, 5, 5));
    }

    /* ---------- a：实心圆 + 同心圆环 ---------- */
    private static void drawHalo(Graphics2D g) {
        float cx = 128, cy = 118;

        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), 150,
                new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(255, 178, 96, 46),
                            new Color(255, 160, 80, 16),
                            new Color(255, 150, 70, 0)}));
        g.fill(new Ellipse2D.Float(cx - 150, cy - 150, 300, 300));

        g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 186, 118, 225));
        g.draw(new Ellipse2D.Float(cx - 54, cy - 54, 108, 108));

        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 186, 118, 140));
        g.draw(new Ellipse2D.Float(cx - 80, cy - 80, 160, 160));

        g.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 181, 107, 52));
        g.draw(new Ellipse2D.Float(cx - 106, cy - 106, 212, 212));

        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy - 10), 36,
                new float[]{0f, 0.55f, 1f}, AMBER));
        g.fill(new Ellipse2D.Float(cx - 32, cy - 32, 64, 64));
    }

    /* ---------- b：实心圆 + 下方同心弧 ---------- */
    private static void drawRipple(Graphics2D g) {
        float cx = 128, cy = 96;

        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), 130,
                new float[]{0f, 1f},
                new Color[]{new Color(255, 178, 96, 50), new Color(255, 150, 70, 0)}));
        g.fill(new Ellipse2D.Float(cx - 130, cy - 130, 260, 260));

        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy - 14), 50,
                new float[]{0f, 0.55f, 1f}, AMBER));
        g.fill(new Ellipse2D.Float(cx - 46, cy - 46, 92, 92));

        g.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 186, 118, 225));
        g.draw(new Arc2D.Float(cx - 66, 100 - 66, 132, 132, 200, 140, Arc2D.OPEN));

        g.setStroke(new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 186, 118, 140));
        g.draw(new Arc2D.Float(cx - 96, 100 - 96, 192, 192, 200, 140, Arc2D.OPEN));

        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 186, 118, 80));
        g.draw(new Arc2D.Float(cx - 126, 100 - 126, 252, 252, 200, 140, Arc2D.OPEN));
    }

    /* ---------- c：实心圆 + 水平线 + 半圆倒影 ---------- */
    private static void drawMinimal(Graphics2D g) {
        float cx = 128, cy = 102;

        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), 120,
                new float[]{0f, 1f},
                new Color[]{new Color(255, 178, 96, 46), new Color(255, 150, 70, 0)}));
        g.fill(new Ellipse2D.Float(cx - 120, cy - 120, 240, 240));

        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy - 14), 50,
                new float[]{0f, 0.55f, 1f}, AMBER));
        g.fill(new Ellipse2D.Float(cx - 46, cy - 46, 92, 92));

        // 地平线
        g.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0xE8, 0xEE, 0xFA, 205));
        g.draw(new Line2D.Float(46, 172, 210, 172));

        // 倒影：半圆弧
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 186, 118, 215));
        g.draw(new Arc2D.Float(cx - 46, 172 - 46, 92, 92, 200, 140, Arc2D.OPEN));
    }

    private static void writeLE16(OutputStream os, int v) throws Exception {
        os.write(v & 0xFF);
        os.write((v >> 8) & 0xFF);
    }

    private static void writeLE32(OutputStream os, int v) throws Exception {
        os.write(v & 0xFF);
        os.write((v >> 8) & 0xFF);
        os.write((v >> 16) & 0xFF);
        os.write((v >> 24) & 0xFF);
    }
}
