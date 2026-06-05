package One;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Scanner;

public class Test_3 {
    public static void main(String[] args) {
        // 数据库连接信息
        String url = "jdbc:mysql://localhost:3306/test_project?useSSL=false&serverTimezone=UTC&characterEncoding=utf8";
        String user = "root";         // 你的MySQL用户名（你之前写的是root）
        String password = "274823137";

        // 让用户 输入想要录入的信息
        Scanner sc = new Scanner(System.in);
        System.out.println("请依次输入序列号、姓名、年龄、性别:");
        int Num = sc.nextInt();
        String StudentName = sc.next();
        int StudentAge = sc.nextInt();
        String StudentGender = sc.next();
        // 检测一次输入数据
        System.out.println(Num + " " + StudentName +  " " + StudentAge + " " + StudentGender);

        // 连接数据库 将数据录入
        try (
            // 连接
            Connection conn = DriverManager.getConnection(url, user, password);
            // 执行SQL语句              PreparedStatement 是作为传参的时候设置的一个用法
            PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO student_message (num, name, age, gender) VALUES (?, ?, ?, ?)"
            )
        ) {
            // 给占位符传参
            pstmt.setInt(1,Num);
            pstmt.setString(2,StudentName);
            pstmt.setInt(3,StudentAge);
            pstmt.setString(4,StudentGender);

            // 3.4 执行插入操作，executeUpdate()返回受影响的行数（插入成功就是1）
            int affectedRows = pstmt.executeUpdate();

            // 3.5 判断插入是否成功
            if (affectedRows > 0) {
                System.out.println("✅ 数据插入成功！学生num=" + Num + "的信息已存入数据库");
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


/*
    增删改查的操作，Java 里是分方法的，不是一个execute()包打天下：
    executeUpdate()：专门给 增 / 删 / 改（INSERT/DELETE/UPDATE） 用，返回受影响的行数
    executeQuery()：专门给 查询（SELECT） 用，返回结果集 ResultSet
    execute()：通用方法，什么都能执行，但新手一般不用，容易搞混返回值
 */
