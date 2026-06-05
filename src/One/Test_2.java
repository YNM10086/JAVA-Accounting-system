package One;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

public class Test_2 {
    public static void main(String[] args) {
        // ===================== 1. 定义数据库连接信息 =====================
        // 数据库URL：格式固定，test_project是你要连接的数据库名，后面加了时区、编码、SSL参数，避免新手踩坑
        String url = "jdbc:mysql://localhost:3306/test_project?useSSL=false&serverTimezone=UTC&characterEncoding=utf8";
        String user = "root";         // 你的MySQL用户名（你之前写的是root）
        String password = "274823137";// 你的MySQL密码（你之前写的密码）

        // ===================== 2. 定义要插入的数据 =====================
        int studentNum = 127;          // 题目要求的num=127
        String studentName = "李华";    // 你可以改成自己想加的名字，比如"张三"
        int studentAge = 19;            // 年龄，你可以改成18/20等
        String studentGender = "男";    // 性别，也可以写"女"

        // ===================== 3. JDBC核心操作（try-with-resources自动关资源） =====================
        // 语法说明：try()里的对象会在代码执行完后自动关闭，不用手动写finally
        try (
                // 3.1 获取数据库连接：用DriverManager根据url、用户名、密码建立连接
                Connection conn = DriverManager.getConnection(url, user, password);

                // 3.2 编写SQL插入语句，用?作为占位符（防止SQL注入，比直接拼接字符串安全）
                // 表名是student_message，字段是num、name、age、gender，对应4个?
                PreparedStatement pstmt = conn.prepareStatement(
                        "INSERT INTO student_message (num, name, age, gender) VALUES (?, ?, ?, ?)"
                )
        ) {
            // 3.3 给SQL语句的占位符?设置值，注意：占位符的序号从1开始！
            pstmt.setInt(1, studentNum);    // 第1个?对应num，类型是int，值为127
            pstmt.setString(2, studentName);// 第2个?对应name，类型是String，值为李华
            pstmt.setInt(3, studentAge);    // 第3个?对应age，类型是int，值为19
            pstmt.setString(4, studentGender);// 第4个?对应gender，类型是String，值为男

            // 3.4 执行插入操作，executeUpdate()返回受影响的行数（插入成功就是1）
            int affectedRows = pstmt.executeUpdate();

            // 3.5 判断插入是否成功
            if (affectedRows > 0) {
                System.out.println("✅ 数据插入成功！学生num=" + studentNum + "的信息已存入数据库");
            } else {
                System.out.println("❌ 数据插入失败，没有行被影响");
            }

        } catch (Exception e) {
            // 捕获所有异常（比如连接失败、SQL错误、主键重复等），打印错误信息
            System.out.println("❌ 程序出错了！错误原因：" + e.getMessage());
            e.printStackTrace(); // 打印完整错误栈，方便你排查问题
        }
    }
}