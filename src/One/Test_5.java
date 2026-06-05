package One;

import java.sql.*;// 这一句话完成所有SQL相关语句的调用

public class Test_5 {
    // 全局固定数据库信息，只在这里改一次
    private static final String URL = "jdbc:mysql://localhost:3306/test_project?useSSL=false&serverTimezone=UTC";
    private static final String USER = "root";
    private static final String PWD = "274823137";

    // 主方法入口
    public static void main(String[] args) throws SQLException {
        // 带参数的用法
        // 1. 直接拿连接，不用再写账号密码
        Connection conn = getConn();
        // 2. 写带?的SQL
        String sql = "INSERT INTO student_message(num,name,age,gender) VALUES (?,?,?,?)";
        // 3. 拿预编译对象
        PreparedStatement pstmt = getPreparedStmt(conn, sql);

        try {
            // 直接赋值
            pstmt.setInt(1, 127);
            pstmt.setString(2, "王小明");
            pstmt.setInt(3, 20);
            pstmt.setString(4, "男");

            // 执行
            pstmt.executeUpdate();
            System.out.println("插入成功");
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 不带参数的用法
        Statement stmt = getStatement(conn);

        try {
            String sql_1 = "SELECT * FROM student_message";
            ResultSet rs = stmt.executeQuery(sql_1);
            while (rs.next()) {
                System.out.println(rs.getInt("num") + " " + rs.getString("name"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 获取数据库连接 方法
    public static Connection getConn() {
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(URL, USER, PWD);
        } catch (Exception e) {
            // 统一全局报错
            System.out.println("数据库连接失败！");
            e.printStackTrace();
        }
        return conn;
    }

    // 获取 Statement 对象 适合简单查询、固定SQL
    public static Statement getStatement(Connection conn) {
        Statement stmt = null;
        try {
            stmt = conn.createStatement();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return stmt;
    }

    // 获取 PreparedStatement 对象 适合增删改、带参数
    public static PreparedStatement getPreparedStmt(Connection conn, String sql) {
        PreparedStatement pstmt = null;
        try {
            pstmt = conn.prepareStatement(sql);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return pstmt;
    }
}
