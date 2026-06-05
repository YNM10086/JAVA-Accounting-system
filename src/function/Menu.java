package function;

import java.util.Scanner;

public class Menu {
    private static Scanner in = new Scanner(System.in);

    // ==================== 主菜单 ====================

    /** 显示主菜单：返回1/2/3，输入无效返回-1，EOF返回-2 */
    public static int mainMenu() {
        System.out.println("\n===== 每日消费记录系统 =====");
        System.out.println("1. 存入消费信息");
        System.out.println("2. 消费额查询");
        System.out.println("3. 退出程序");
        System.out.print("请输入数字选择: ");
        if (in.hasNextInt()) {
            int n = in.nextInt();
            if (in.hasNextLine()) in.nextLine();
            return n;
        }
        if (in.hasNextLine()) {
            in.nextLine();
            return -1;
        }
        return -2;
    }

    /** 主菜单循环：调度存入/查询/退出 */
    public static void run(Runnable onSave, Runnable onQuery) {
        while (true) {
            int choice = mainMenu();
            switch (choice) {
                case 1 -> onSave.run();
                case 2 -> onQuery.run();
                case 3 -> {
                    System.out.println("已退出系统，再见！");
                    close();
                    return;
                }
                case -2 -> {
                    close();
                    return;
                }
                default -> System.out.println("输入无效，请重新选择！");
            }
        }
    }

    // ==================== 子菜单（查询） ====================

    /** 显示查询子菜单：返回1/2/3/4/5，输入无效返回-1，EOF返回-2 */
    public static int queryMenu() {
        System.out.println("\n----- 查询消费信息 -----");
        System.out.println("1. 查询总消费与平均值");
        System.out.println("2. 消费折线图生成");
        System.out.println("3. 查询指定日消费明细");
        System.out.println("4. 查询特殊消费");
        System.out.println("5. 返回主菜单");
        System.out.print("请输入数字选择: ");
        if (in.hasNextInt()) {
            int n = in.nextInt();
            if (in.hasNextLine()) in.nextLine();
            return n;
        }
        if (in.hasNextLine()) {
            in.nextLine();
            return -1;
        }
        return -2;
    }

    /** 子菜单循环：调度各查询功能 */
    public static void runQuery(Runnable onTotal, Runnable onChart, Runnable onDayDetail, Runnable onSpecial) {
        while (true) {
            int choice = queryMenu();
            switch (choice) {
                case 1 -> onTotal.run();
                case 2 -> onChart.run();
                case 3 -> onDayDetail.run();
                case 4 -> onSpecial.run();
                case 5 -> { return; }
                case -2 -> { return; }
                default -> System.out.println("输入无效，请重新选择！");
            }
        }
    }

    // ==================== 消费类型子菜单 ====================

    /** 显示消费类型子菜单：返回1/2，输入无效返回-1，EOF返回-2 */
    public static int consumeTypeMenu() {
        System.out.println("请选择消费类型：");
        System.out.println("1. 日常消费");
        System.out.println("2. 固定消费");
        System.out.print("请输入数字选择: ");
        if (in.hasNextInt()) {
            int n = in.nextInt();
            if (in.hasNextLine()) in.nextLine();
            return n;
        }
        if (in.hasNextLine()) {
            in.nextLine();
            return -1;
        }
        return -2;
    }

    // ==================== 工具 ====================

    /** 关闭扫描器 */
    public static void close() {
        in.close();
    }
}
