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

    // 返回指定月份的"已过天数"：当前月→到今天为止，历史月→整月天数，未来月→0
    public static int getElapsedDays(int month) {
        LocalDate now = LocalDate.now();
        int cur = now.getMonthValue();
        if (month == cur) return now.getDayOfMonth();
        if (month < 1 || month > 12) month = cur;
        if (month < cur) return now.withMonth(month).lengthOfMonth();
        return 0;
    }

    // 返回紧凑格式：20260430
    public static int getToday() {
        LocalDate d = LocalDate.now();
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }
}
