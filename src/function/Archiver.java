package function;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨年归档子系统。
 *
 * 用标记文件 records/.current_year 记录「当前这套表里的数据属于哪一年」。
 * 启动（或写入前）检测到标记年份早于当前年份时，把消费表与收入表导出为
 * Excel 存进 records/，然后清空表并更新标记。导出成功后才清表，任何一步
 * 失败都保留旧标记与数据，下���启动重试。
 */
public class Archiver {

    private static final String DIR_NAME = "records";
    private static final String MARKER_NAME = ".current_year";

    /** 唯一对外入口：幂等的跨年检查与归档。 */
    public static void ensureRollover() {
        try {
            int currentYear = Year.now().getValue();
            Path dir = baseDir();
            Path marker = dir.resolve(MARKER_NAME);

            int markerYear = readMarker(marker);
            if (markerYear < 0) {
                // 首次运行：只记录当前年份，不归档。
                Files.createDirectories(dir);
                writeMarker(marker, currentYear);
                return;
            }
            if (!shouldArchive(markerYear, currentYear)) return;

            // 跨年：执行归档
            Connection conn = One.getConn();
            if (conn == null) {
                System.out.println("跨年归档：数据库连接失败，本次跳过。");
                return;
            }
            try {
                archiveAll(conn, markerYear, dir);
                clearTables(conn);
                writeMarker(marker, currentYear);
                System.out.println("跨年归档完成：" + markerYear
                        + " 年数据已归档至 " + dir.toAbsolutePath());
            } catch (Exception e) {
                System.out.println("跨年归档失败，将于下次启动重试。");
                e.printStackTrace();
            } finally {
                try { conn.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.out.println("归档检查异常：");
            e.printStackTrace();
        }
    }

    /** 纯判定：标记年份早于当前年份即需要归档。 */
    static boolean shouldArchive(int markerYear, int currentYear) {
        return markerYear > 0 && markerYear < currentYear;
    }

    private static Path baseDir() {
        return Paths.get(System.getProperty("user.dir"), DIR_NAME);
    }

    /** 读取标记年份；文件不存在返回 -1（首次运行）；存在但损坏返回当前年份（保守跳过）。 */
    private static int readMarker(Path marker) {
        try {
            if (!Files.exists(marker)) return -1;
            String s = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) {
                System.out.println("归档标记为空，保守跳过本次归档：" + marker);
                return Year.now().getValue();
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            System.out.println("归档标记无法解析，保守跳过本次归档：" + marker);
            return Year.now().getValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void writeMarker(Path marker, int year) throws Exception {
        Files.createDirectories(marker.getParent());
        Files.write(marker, String.valueOf(year).getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 导出 ====================

    private static void archiveAll(Connection conn, int year, Path dir) throws Exception {
        for (int m = 1; m <= 12; m++) {
            String table = String.format("table_%02d", m);
            if (!tableExists(conn, table)) continue;
            List<Object[]> rows = selectConsumption(conn, table);
            if (rows.isEmpty()) continue;
            Path out = dir.resolve(String.format("%d-%02d.xlsx", year, m));
            writeWorkbook(out, "消费", new String[]{"序号", "日期", "商品", "价格", "类型"}, rows);
        }
        if (tableExists(conn, "income_records")) {
            List<Object[]> rows = selectIncome(conn, "income_records");
            if (!rows.isEmpty()) {
                Path out = dir.resolve(year + "-income.xlsx");
                writeWorkbook(out, "收入", new String[]{"序号", "月份", "日期", "金额", "说明"}, rows);
            }
        }
    }

    private static List<Object[]> selectConsumption(Connection conn, String table) throws SQLException {
        List<Object[]> list = new ArrayList<>();
        PreparedStatement ps = One.getPreparedStmt(conn,
                "SELECT date, goods, price, num_1 FROM " + table + " ORDER BY date");
        ResultSet rs = ps.executeQuery();
        int idx = 1;
        while (rs.next()) {
            String type = rs.getInt(4) == 1 ? "日常" : "特殊";
            list.add(new Object[]{ idx++, rs.getInt(1), rs.getString(2), rs.getDouble(3), type });
        }
        rs.close(); ps.close();
        return list;
    }

    private static List<Object[]> selectIncome(Connection conn, String table) throws SQLException {
        List<Object[]> list = new ArrayList<>();
        PreparedStatement ps = One.getPreparedStmt(conn,
                "SELECT month, date, amount, description FROM " + table + " ORDER BY month, date");
        ResultSet rs = ps.executeQuery();
        int idx = 1;
        while (rs.next()) {
            list.add(new Object[]{ idx++, rs.getInt(1), rs.getInt(2), rs.getDouble(3), rs.getString(4) });
        }
        rs.close(); ps.close();
        return list;
    }

    private static void writeWorkbook(Path out, String sheetName, String[] headers,
                                      List<Object[]> rows) throws Exception {
        Files.createDirectories(out.getParent());
        // 先写临时文件，再原子改名：避免导出中途失败留下半截 xlsx
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try {
            try (XSSFWorkbook wb = new XSSFWorkbook();
                 FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
                Sheet sheet = wb.createSheet(sheetName);
                CellStyle headerStyle = wb.createCellStyle();
                Font bold = wb.createFont();
                bold.setBold(true);
                headerStyle.setFont(bold);

                Row h = sheet.createRow(0);
                for (int i = 0; i < headers.length; i++) {
                    Cell c = h.createCell(i);
                    c.setCellValue(headers[i]);
                    c.setCellStyle(headerStyle);
                }
                for (int r = 0; r < rows.size(); r++) {
                    Row row = sheet.createRow(r + 1);
                    Object[] vals = rows.get(r);
                    for (int i = 0; i < vals.length; i++) {
                        Object v = vals[i];
                        Cell c = row.createCell(i);
                        if (v instanceof Integer) c.setCellValue((Integer) v);
                        else if (v instanceof Double) c.setCellValue((Double) v);
                        else c.setCellValue(v == null ? "" : String.valueOf(v));
                    }
                }
                for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
                wb.write(fos);
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ==================== 清表 ====================

    private static void clearTables(Connection conn) throws SQLException {
        for (int m = 1; m <= 12; m++) {
            String table = String.format("table_%02d", m);
            if (tableExists(conn, table)) execDelete(conn, table);
        }
        if (tableExists(conn, "income_records")) execDelete(conn, "income_records");
    }

    private static void execDelete(Connection conn, String table) throws SQLException {
        Statement st = conn.createStatement();
        try {
            st.executeUpdate("DELETE FROM " + table);
        } finally {
            st.close();
        }
    }

    private static boolean tableExists(Connection conn, String name) {
        try {
            ResultSet rs = conn.getMetaData()
                    .getTables(conn.getCatalog(), null, name, new String[]{"TABLE"});
            boolean exists = rs.next();
            rs.close();
            return exists;
        } catch (Exception e) {
            return false;
        }
    }
}