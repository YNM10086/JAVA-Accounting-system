package function;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;

public class Line {

    private static final Color BG = new Color(11, 15, 26);
    private static final Color AXIS = new Color(147, 160, 187);
    private static final Color GRID = new Color(45, 55, 78);
    private static final Color TEXT = new Color(230, 235, 247);

    // 绘制每日消费折线图并保存到The_Pic目录
    public static void drawLineChart(List<Integer> days, List<Double> totals, String table) {
        int w = 800, h = 500;
        int pad = 60;
        int chartW = w - 2 * pad;
        int chartH = h - 2 * pad;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        // 数据范围
        double minTotal = totals.stream().min(Double::compare).orElse(0.0);
        double maxTotal = totals.stream().max(Double::compare).orElse(1.0);
        if (maxTotal == 0) maxTotal = 1;
        minTotal = Math.min(minTotal * 0.9, 0);
        maxTotal *= 1.1;
        int minDay = days.stream().min(Integer::compare).orElse(1);
        int maxDay = days.stream().max(Integer::compare).orElse(31);

        // 坐标轴
        g.setColor(AXIS);
        g.setStroke(new BasicStroke(2));
        g.drawLine(pad, h - pad, w - pad, h - pad);
        g.drawLine(pad, pad, pad, h - pad);
        g.setFont(new Font("微软雅黑", Font.PLAIN, 11));

        // Y轴刻度
        int yTicks = 5;
        for (int i = 0; i <= yTicks; i++) {
            double val = minTotal + (maxTotal - minTotal) * i / yTicks;
            int y = h - pad - (int)(chartH * i / yTicks);
            g.setColor(GRID);
            g.drawLine(pad + 1, y, w - pad, y);
            g.setColor(AXIS);
            g.drawString(String.format("%.0f", val), 2, y + 4);
        }

        // X轴刻度
        for (int d = minDay; d <= maxDay; d++) {
            int x = pad + (int)(chartW * (double)(d - minDay) / (maxDay - minDay + 0.01));
            g.setColor(GRID);
            g.drawLine(x, h - pad - 1, x, pad);
            g.setColor(AXIS);
            g.drawString(String.valueOf(d), x - 4, h - pad + 16);
        }

        // 数据点坐标
        int n = days.size();
        int[] xs = new int[n], ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = pad + (int)(chartW * (double)(days.get(i) - minDay) / (maxDay - minDay + 0.01));
            ys[i] = h - pad - (int)(chartH * (totals.get(i) - minTotal) / (maxTotal - minTotal));
        }

        // 折线
        g.setStroke(new BasicStroke(2.5f));
        g.setColor(new Color(220, 50, 50));
        for (int i = 0; i < n - 1; i++) {
            g.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1]);
        }

        // 数据点
        for (int i = 0; i < n; i++) {
            g.setColor(new Color(220, 50, 50));
            g.fillOval(xs[i] - 5, ys[i] - 5, 10, 10);
            g.setColor(BG);
            g.fillOval(xs[i] - 2, ys[i] - 2, 4, 4);
            g.setColor(AXIS);
            g.setFont(new Font("微软雅黑", Font.PLAIN, 10));
            g.drawString(String.format("%.1f", totals.get(i)), xs[i] - 12, ys[i] - 10);
        }

        // 标题
        g.setFont(new Font("微软雅黑", Font.BOLD, 16)); g.setColor(TEXT);
        g.drawString(table + " 每日消费折线图", w / 2 - 80, pad / 2);

        g.dispose();
        try {
            new File("The_Pic").mkdirs();
            ImageIO.write(img, "png", new File("The_Pic/" + table + "_chart.png"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 绘制「日常 / 特殊」双折线对比图。
     * days 为并集日期；daily、special 与 days 一一对应，缺失日记 0。
     */
    public static void drawCompareChart(List<Integer> days, List<Double> daily, List<Double> special,
                                        String title, String fileName) {
        int w = 800, h = 500;
        int pad = 60;
        int chartW = w - 2 * pad;
        int chartH = h - 2 * pad;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, w, h);

        if (days == null || days.isEmpty()) { g.dispose(); return; }

        // 数据范围（两条线共用一套坐标）
        double maxTotal = 1;
        for (double v : daily) maxTotal = Math.max(maxTotal, v);
        for (double v : special) maxTotal = Math.max(maxTotal, v);
        double minTotal = 0;
        maxTotal *= 1.15;
        int minDay = days.stream().min(Integer::compare).orElse(1);
        int maxDay = days.stream().max(Integer::compare).orElse(31);
        double daySpan = Math.max(maxDay - minDay, 1);

        // 坐标轴
        g.setColor(AXIS);
        g.setStroke(new BasicStroke(2));
        g.drawLine(pad, h - pad, w - pad, h - pad);
        g.drawLine(pad, pad, pad, h - pad);
        g.setFont(new Font("微软雅黑", Font.PLAIN, 11));

        // Y轴刻度
        int yTicks = 5;
        for (int i = 0; i <= yTicks; i++) {
            double val = minTotal + (maxTotal - minTotal) * i / yTicks;
            int y = h - pad - (int)(chartH * i / yTicks);
            g.setColor(GRID);
            g.drawLine(pad + 1, y, w - pad, y);
            g.setColor(AXIS);
            g.drawString(String.format("%.0f", val), 2, y + 4);
        }

        // X轴刻度
        for (int d = minDay; d <= maxDay; d++) {
            int x = pad + (int)(chartW * (d - minDay) / daySpan);
            g.setColor(GRID);
            g.drawLine(x, h - pad - 1, x, pad);
            g.setColor(AXIS);
            g.drawString(String.valueOf(d), x - 4, h - pad + 16);
        }

        Color cDaily = new Color(220, 50, 50);
        Color cSpecial = new Color(111, 155, 255);
        drawSeries(g, days, daily, minDay, daySpan, pad, chartW, chartH, h, w, minTotal, maxTotal, cDaily, "日常");
        drawSeries(g, days, special, minDay, daySpan, pad, chartW, chartH, h, w, minTotal, maxTotal, cSpecial, "特殊");

        // 图例
        int lx = w - pad - 150, ly = pad + 6;
        g.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        g.setColor(cDaily); g.fillRect(lx, ly, 18, 4);
        g.setColor(AXIS); g.drawString("日常消费", lx + 24, ly + 5);
        g.setColor(cSpecial); g.fillRect(lx, ly + 18, 18, 4);
        g.setColor(AXIS); g.drawString("特殊消费", lx + 24, ly + 23);

        // 标题
        g.setFont(new Font("微软雅黑", Font.BOLD, 16)); g.setColor(TEXT);
        g.drawString(title, w / 2 - 100, pad / 2);

        g.dispose();
        try {
            new File("The_Pic").mkdirs();
            ImageIO.write(img, "png", new File("The_Pic/" + fileName));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 画一条折线及其数据点、数值标签 */
    private static void drawSeries(Graphics2D g, List<Integer> days, List<Double> values,
                                   int minDay, double daySpan, int pad, int chartW, int chartH, int h, int w,
                                   double minTotal, double maxTotal, Color color, String label) {
        int n = days.size();
        int[] xs = new int[n], ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = pad + (int)(chartW * (days.get(i) - minDay) / daySpan);
            ys[i] = h - pad - (int)(chartH * (values.get(i) - minTotal) / (maxTotal - minTotal));
        }

        g.setStroke(new BasicStroke(2.5f));
        g.setColor(color);
        for (int i = 0; i < n - 1; i++) {
            g.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1]);
        }

        g.setFont(new Font("微软雅黑", Font.PLAIN, 10));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < n; i++) {
            g.setColor(color);
            g.fillOval(xs[i] - 4, ys[i] - 4, 8, 8);
            g.setColor(BG);
            g.fillOval(xs[i] - 1, ys[i] - 1, 2, 2);
            if (values.get(i) > 0) {
                String txt = String.format("%.1f", values.get(i));
                int tw = fm.stringWidth(txt);
                int tx = Math.max(2, Math.min(xs[i] - tw / 2, w - 2 - tw));
                int ty = ys[i] - 9;
                if (ty < 12) ty = ys[i] + 15;
                g.setColor(BG);
                g.fillRect(tx - 2, ty - 10, tw + 4, 12);
                g.setColor(AXIS);
                g.drawString(txt, tx, ty);
            }
        }
    }
}
