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
 * 生成应用图标（原创绘制，不依赖任何外部素材）。
 *
 * 画面：深海夜色圆角底 + 暖色灯火 + 两道海平线波纹，对应「人间烟火」与海洋背景。
 *
 * 用法：
 *   javac -encoding UTF-8 -d .build_tmp/icon tools/icon/IconGen.java
 *   java -cp .build_tmp/icon IconGen desktop/build/icon.ico
 *
 * 输出：多尺寸 PNG 压缩的 ICO（16/24/32/48/64/128/256），Windows Vista+ 原生支持。
 */
public class IconGen {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "desktop/build/icon.ico");
        out.getParentFile().mkdirs();

        List<byte[]> pngs = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage img = render(size);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            pngs.add(bos.toByteArray());
            if (size == 256) {
                File png = new File(out.getParentFile(), "icon.png");
                ImageIO.write(img, "png", png);
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
        System.out.println("ICO: " + out.getAbsolutePath() + "  (" + out.length() + " bytes)");

        // 尺寸检查图：浅底/深底各一排，确认小尺寸是否还认得出
        int[] check = {16, 24, 32, 48, 64, 128};
        BufferedImage strip = new BufferedImage(480, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = strip.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        sg.setColor(new Color(0xF2, 0xF4, 0xF7));
        sg.fillRect(0, 0, 480, 150);
        sg.setColor(new Color(0x1B, 0x1E, 0x24));
        sg.fillRect(0, 150, 480, 150);
        int x = 20;
        for (int size : check) {
            BufferedImage img = render(size);
            sg.drawImage(img, x, 75 - size / 2, null);
            sg.drawImage(img, x, 225 - size / 2, null);
            x += size + 24;
        }
        sg.dispose();
        File sizes = new File(out.getParentFile(), "icon-sizes.png");
        ImageIO.write(strip, "png", sizes);
        System.out.println("尺寸检查图: " + sizes.getAbsolutePath());
    }

    private static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 256.0, size / 256.0);

        // 圆角底板：深海夜色
        RoundRectangle2D plate = new RoundRectangle2D.Float(8, 8, 240, 240, 56, 56);
        g.setClip(plate);
        g.setPaint(new GradientPaint(0, 0, new Color(0x14, 0x2A, 0x48),
                                     0, 256, new Color(0x05, 0x0C, 0x18)));
        g.fill(plate);

        // 灯火光晕（径向渐变，避免出现同心圆色阶）
        float cx = 128, cy = 104;
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), 132,
                new float[]{0f, 0.45f, 1f},
                new Color[]{new Color(255, 178, 96, 70),
                            new Color(255, 160, 80, 26),
                            new Color(255, 150, 70, 0)}));
        g.fill(new Ellipse2D.Float(cx - 132, cy - 132, 264, 264));

        // 灯火本体
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy - 12), 46,
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(0xFF, 0xEC, 0xC4),
                            new Color(0xFF, 0xC1, 0x6B),
                            new Color(0xF2, 0x8C, 0x33)}));
        g.fill(new Ellipse2D.Float(cx - 40, cy - 44, 80, 80));

        // 水面暖光（椭圆径向，避免出现硬边）
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, 176), 120,
                new float[]{0f, 1f},
                new Color[]{new Color(255, 176, 100, 70),
                            new Color(255, 176, 100, 0)}));
        g.fill(new Ellipse2D.Float(cx - 120, 106, 240, 140));

        // 两道海平线
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x9C, 0xC2, 0xFF, 165));
        g.draw(wave(196, 11, 2.4f));
        g.setColor(new Color(0x7F, 0xA9, 0xEE, 95));
        g.draw(wave(222, 8, 2.0f));

        g.dispose();
        return img;
    }

    /** 一条正弦波纹，横跨整幅画布。 */
    private static Path2D wave(float baseY, float amp, float periods) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(-10, baseY);
        for (float x = -10; x <= 266; x += 4) {
            double y = baseY + Math.sin((x / 256.0) * periods * Math.PI * 2) * amp;
            p.lineTo(x, (float) y);
        }
        return p;
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
