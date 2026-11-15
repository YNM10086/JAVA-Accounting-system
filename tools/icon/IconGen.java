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
 * 现行方案（唯一）：冷色深底 + 三层错位圆角方块 + 三条账目线。
 * 旧的暖色方案（光环 / 涟漪 / 极简）已移除，不要再往里加回。
 *
 * 用法：
 *   javac -encoding UTF-8 -d .build_tmp/icon tools/icon/IconGen.java
 *   java -cp .build_tmp/icon IconGen desktop/build/icon.ico
 *
 * 输出：多尺寸 PNG 压缩的 ICO（16/24/32/48/64/128/256），Windows Vista+ 原生支持。
 */
public class IconGen {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private static final Color INK = new Color(0x0B, 0x0C, 0x0F);
    private static final Color PAPER = new Color(0xF4, 0xF6, 0xF8);
    private static final Color GRAY_LIGHT = new Color(0xA9, 0xAF, 0xB9);
    private static final Color GRAY_MID = new Color(0x6B, 0x71, 0x7B);
    private static final Color GRAY_DARK = new Color(0x4B, 0x51, 0x5B);

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "desktop/build/icon.ico");
        out.getParentFile().mkdirs();

        List<byte[]> pngs = new ArrayList<>();
        for (int size : SIZES) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(render(size), "png", bos);
            pngs.add(bos.toByteArray());
            if (size == 256) {
                File png = new File(out.getParentFile(), "icon.png");
                ImageIO.write(render(256), "png", png);
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

        RoundRectangle2D plate = new RoundRectangle2D.Float(8, 8, 240, 240, 56, 56);
        g.setClip(plate);
        g.setPaint(new GradientPaint(0, 0, new Color(0x17, 0x19, 0x1D),
                                     0, 256, new Color(0x07, 0x08, 0x0A)));
        g.fill(plate);

        drawStackLines(g);

        g.dispose();
        return img;
    }

    /* ---------- 三层错位圆角方块（叠页） ---------- */
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

    /* ---------- 叠页 + 三条账目线（现行图标） ---------- */
    private static void drawStackLines(Graphics2D g) {
        drawStack(g);

        g.setColor(GRAY_LIGHT);
        g.fill(new RoundRectangle2D.Float(74, 80, 60, 10, 5, 5));
        g.setColor(GRAY_MID);
        g.fill(new RoundRectangle2D.Float(74, 101, 46, 10, 5, 5));
        g.setColor(GRAY_DARK);
        g.fill(new RoundRectangle2D.Float(74, 122, 32, 10, 5, 5));
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
