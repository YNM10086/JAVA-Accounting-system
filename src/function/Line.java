package function;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;

public class Line {

    // 绘制每日消费折线图并保存到The_Pic目录
    public static void drawLineChart(List<Integer> days, List<Double> totals, String table) {
        int w = 800, h = 500;
        int pad = 60;
        int chartW = w - 2 * pad;
        int chartH = h - 2 * pad;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
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
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2));
        g.drawLine(pad, h - pad, w - pad, h - pad);
        g.drawLine(pad, pad, pad, h - pad);
        g.setFont(new Font("微软雅黑", Font.PLAIN, 11));

        // Y轴刻度
        int yTicks = 5;
        for (int i = 0; i <= yTicks; i++) {
            double val = minTotal + (maxTotal - minTotal) * i / yTicks;
            int y = h - pad - (int)(chartH * i / yTicks);
            g.setColor(Color.LIGHT_GRAY);
            g.drawLine(pad + 1, y, w - pad, y);
            g.setColor(Color.BLACK);
            g.drawString(String.format("%.0f", val), 2, y + 4);
        }

        // X轴刻度
        for (int d = minDay; d <= maxDay; d++) {
            int x = pad + (int)(chartW * (double)(d - minDay) / (maxDay - minDay + 0.01));
            g.setColor(Color.LIGHT_GRAY);
            g.drawLine(x, h - pad - 1, x, pad);
            g.setColor(Color.BLACK);
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
            g.setColor(Color.WHITE);
            g.fillOval(xs[i] - 2, ys[i] - 2, 4, 4);
            g.setColor(Color.BLACK);
            g.setFont(new Font("微软雅黑", Font.PLAIN, 10));
            g.drawString(String.format("%.1f", totals.get(i)), xs[i] - 12, ys[i] - 10);
        }

        // 标题
        g.setFont(new Font("微软雅黑", Font.BOLD, 16));
        g.drawString(table + " 每日消费折线图", w / 2 - 80, pad / 2);

        g.dispose();
        try {
            new File("The_Pic").mkdirs();
            ImageIO.write(img, "png", new File("The_Pic/" + table + "_chart.png"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
