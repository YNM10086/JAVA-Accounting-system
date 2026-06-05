package One;

import java.sql.Connection;
import java.sql.DriverManager;

public class Test_1 {
    public static void main(String[] args) {
        try {
            // 加载MySQL驱动  必做前置
            Class.forName("com.mysql.cj.jdbc.Driver");
            System.out.println("✅ 驱动加载成功！可以连接数据库了");
        } catch (ClassNotFoundException error) {
            System.out.println("❌ 驱动未加载成功，请检查是否Add as Library");
            error.printStackTrace();
        }

        // 创建连接
        String URL = "jdbc:mysql://localhost:3306/test_project";
        String USER = "root";   //  用户名
        String PASSWORD = "274823137";   //   数据库密码

        // 连接对象
        Connection conn = null;

        try {
            conn = DriverManager.getConnection(URL, USER, PASSWORD);
        }
        catch (Exception error) {
            error.printStackTrace();
        }

        // 打印连接对象
        System.out.println(conn);




    }
}