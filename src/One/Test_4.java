package One;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class Test_4 {
    public static void main(String[] args) {
        // 数据库连接信息
        String url = "jdbc:mysql://localhost:3306/test_project?useSSL=false&serverTimezone=UTC&characterEncoding=utf8";
        String user = "root";         // 你的MySQL用户名（你之前写的是root）
        String password = "274823137";

        try (
                Connection conn = DriverManager.getConnection(url, user, password);
                Statement stmt = conn.createStatement();// createStatement 的用法更适合对于查询数据 写起来更方便

                ){
            // 4. 直接写SQL字符串，executeUpdate()直接跑
            String insertSql = "INSERT INTO student_message (num, name, age, gender) VALUES (127, '李华', 19, '男')";
            int affected = stmt.executeUpdate(insertSql);
            System.out.println("执行成功，影响了" + affected + "行");

            // 想删数据也直接写
            stmt.executeUpdate("DELETE FROM student_message WHERE num=127");

            // 想查数据也直接写，和Python的cursor.execute+fetchall逻辑一致
            // var rs = stmt.executeQuery("SELECT * FROM student_message");
            // while (rs.next()) {
            //     System.out.println(rs.getInt("num") + " | " + rs.getString("name"));
            // }

        } catch (Exception e) {
            e.printStackTrace();
        }



    }
}
