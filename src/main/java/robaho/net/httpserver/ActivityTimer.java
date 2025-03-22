package robaho.net.httpserver;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.TimerTask;

final class ActivityTimer {
    private static volatile long now = System.currentTimeMillis();
    private static volatile String dateAndTime = formatDate();

    private static String formatDate() {
        var now = Instant.now();
        var datetime = now.atOffset(ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder(32);
        sb.append(datetime.getDayOfWeek().getDisplayName(TextStyle.SHORT,Locale.US));
        sb.append(", ");
        int day = datetime.getDayOfMonth();
        if(day<10) sb.append("0");
        sb.append(day);
        sb.append(" ");
        sb.append(datetime.getMonth().getDisplayName(TextStyle.SHORT,Locale.US));
        sb.append(" ");
        sb.append(datetime.getYear());
        sb.append(" ");
        int hour = datetime.getHour();
        if(hour<10) sb.append("0");
        sb.append(hour);
        sb.append(":");
        int minute = datetime.getMinute();
        if(minute<10) sb.append("0");
        sb.append(minute);
        sb.append(":");
        int second = datetime.getSecond();
        if(second<10) sb.append("0");
        sb.append(second);
        sb.append(" GMT");
        return sb.toString();
    }

    static long now() {
        return now;
    }
    /**
     * return the formatted current date and time suitable for use with the Date http header. this
     * is OK to cache since the resolution is only seconds, and we will update more often than that
     */
    static String dateAndTime() {
        return dateAndTime;
    }

    static void updateNow() {
        now = System.currentTimeMillis();
        dateAndTime = formatDate();
    }

    static TimerTask createTask() {
        return new TimerTask() {
            @Override
            public void run() {
                updateNow();
            }
        };
    }
}