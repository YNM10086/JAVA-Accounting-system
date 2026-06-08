package function;

import java.time.LocalDate;

public class Date_time {

    // 返回当前月份（1-12）
    public static int getMonth() {
        return LocalDate.now().getMonthValue();
    }

    // 返回当前日数
    public static int getDay() {
        return LocalDate.now().getDayOfMonth();
    }

    // 返回当月总天数
    public static int getMonthDays() {
        return LocalDate.now().lengthOfMonth();
    }

    // 返回当月剩余天数（含今天）
    public static int getRemainingDays() {
        return getMonthDays() - getDay() + 1;
    }

    // 返回紧凑格式：20260430
    public static int getToday() {
        LocalDate d = LocalDate.now();
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }
}
