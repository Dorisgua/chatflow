import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/** A text-only activity interval parsed from a minimal daily summary. */
final class ActivityPeriod {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    final LocalDate date;
    final LocalTime start;
    final LocalTime lastObservation;
    final int estimatedEndMinute;
    final String category;
    final String confidence;

    ActivityPeriod(LocalDate date, LocalTime start, LocalTime lastObservation,
                   int estimatedEndMinute, String category, String confidence) {
        this.date = Objects.requireNonNull(date);
        this.start = Objects.requireNonNull(start);
        this.lastObservation = Objects.requireNonNull(lastObservation);
        this.estimatedEndMinute = estimatedEndMinute;
        this.category = clean(category, "无法判断");
        this.confidence = clean(confidence, "未知");
        if (lastObservation.isBefore(start)) throw new IllegalArgumentException("活动结束时间早于开始时间");
        if (estimatedEndMinute <= start.getHour() * 60 + start.getMinute() || estimatedEndMinute > 1440) {
            throw new IllegalArgumentException("预计结束时间必须晚于开始时间且不跨越次日");
        }
    }

    static ActivityPeriod parsed(LocalDate date, LocalTime start, LocalTime lastObservation,
                                 String category, String confidence) {
        int provisional = Math.min(1440, lastObservation.getHour() * 60 + lastObservation.getMinute() + 2);
        if (provisional <= start.getHour() * 60 + start.getMinute()) provisional = Math.min(1440, start.getHour() * 60 + start.getMinute() + 1);
        return new ActivityPeriod(date, start, lastObservation, provisional, category, confidence);
    }

    ActivityPeriod withEstimatedEndMinute(int end) {
        return new ActivityPeriod(date, start, lastObservation, end, category, confidence);
    }

    boolean isDataGap() { return "数据缺失".equals(category); }

    long estimatedMinutes() {
        return Math.max(1, estimatedEndMinute - startMinute());
    }

    int startMinute() { return start.getHour() * 60 + start.getMinute(); }

    int endMinute() { return estimatedEndMinute; }

    String timeLabel() { return start.format(TIME) + "—" + formatMinute(estimatedEndMinute); }

    String durationLabel() { return "约" + estimatedMinutes() + "分钟"; }

    String detailText() {
        return category + "\n\n"
                + timeLabel() + "｜" + durationLabel() + "\n"
                + "可信度：" + confidence + "\n\n"
                + "来源：已保存的最小化活动总结\n"
                + "说明：时长由定期观察估算，不代表连续录像或精确计时。";
    }

    private static String formatMinute(int minute) {
        if (minute == 1440) return "24:00";
        return LocalTime.of(minute / 60, minute % 60).format(TIME);
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().replaceAll("\\s+", " ");
    }
}
