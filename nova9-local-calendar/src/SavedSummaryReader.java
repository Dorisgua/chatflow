import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only daily-summary.md. It never opens encrypted details or media files. */
final class SavedSummaryReader {
    private static final Pattern ENTRY = Pattern.compile(
            "^-\\s*(\\d{2}):(\\d{2})(?:\\s*[–—-]\\s*(\\d{2}):(\\d{2}))?\\s*｜\\s*([^｜]+?)\\s*｜\\s*([^｜]+?)\\s*$");

    record ReadResult(List<ActivityPeriod> periods, List<String> warnings) {}

    ReadResult readDay(Path recordsRoot, LocalDate date) {
        Path normalizedRoot = recordsRoot.toAbsolutePath().normalize();
        Path summary = normalizedRoot.resolve(String.format("%04d-%02d", date.getYear(), date.getMonthValue()))
                .resolve(date.toString()).resolve("daily-summary.md").normalize();
        if (!summary.startsWith(normalizedRoot)) {
            return new ReadResult(List.of(), List.of("已拒绝记录目录之外的路径"));
        }
        if (!Files.isRegularFile(summary)) return new ReadResult(List.of(), List.of());

        List<ActivityPeriod> periods = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(summary, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new ReadResult(List.of(), List.of(date + " 的活动总结无法读取"));
        }

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (!line.startsWith("-")) continue;
            Matcher matcher = ENTRY.matcher(line);
            if (!matcher.matches()) {
                warnings.add(date + " 第" + (index + 1) + "行格式无法识别，已跳过");
                continue;
            }
            try {
                LocalTime start = time(matcher.group(1), matcher.group(2));
                LocalTime last = matcher.group(3) == null ? start : time(matcher.group(3), matcher.group(4));
                if (last.isBefore(start)) throw new DateTimeException("跨日时间段不属于单日摘要");
                periods.add(ActivityPeriod.parsed(date, start, last, matcher.group(5), matcher.group(6)));
            } catch (DateTimeException | IllegalArgumentException e) {
                warnings.add(date + " 第" + (index + 1) + "行时间无效，已跳过");
            }
        }
        periods.sort(Comparator.comparing(period -> period.start));
        return new ReadResult(estimateEnds(periods), List.copyOf(warnings));
    }

    private static List<ActivityPeriod> estimateEnds(List<ActivityPeriod> input) {
        List<ActivityPeriod> output = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            ActivityPeriod current = input.get(i);
            int end = current.estimatedEndMinute;
            if (i + 1 < input.size()) {
                LocalTime next = input.get(i + 1).start;
                int nextMinute = next.getHour() * 60 + next.getMinute();
                if (current.isDataGap() && next.isAfter(current.start)) {
                    end = nextMinute;
                } else if (next.isAfter(current.start) && nextMinute < end) {
                    end = nextMinute;
                }
            }
            output.add(current.withEstimatedEndMinute(end));
        }
        return List.copyOf(output);
    }

    private static LocalTime time(String hour, String minute) {
        return LocalTime.of(Integer.parseInt(hour), Integer.parseInt(minute));
    }
}
