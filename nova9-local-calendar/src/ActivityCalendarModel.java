import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Read-only access to saved minimal summaries for one calendar week. */
final class ActivityCalendarModel {
    record WeekData(LocalDate monday, Map<LocalDate, List<ActivityPeriod>> days,
                    List<String> warnings, int totalPeriods) {}

    private final Path recordsRoot;
    private final SavedSummaryReader reader = new SavedSummaryReader();

    ActivityCalendarModel(Path recordsRoot) {
        this.recordsRoot = recordsRoot.toAbsolutePath().normalize();
    }

    Path recordsRoot() { return recordsRoot; }

    WeekData loadWeek(LocalDate anyDay) {
        LocalDate monday = anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Map<LocalDate, List<ActivityPeriod>> days = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int total = 0;
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = monday.plusDays(offset);
            SavedSummaryReader.ReadResult result = reader.readDay(recordsRoot, date);
            days.put(date, result.periods());
            warnings.addAll(result.warnings());
            total += result.periods().size();
        }
        return new WeekData(monday, Map.copyOf(days), List.copyOf(warnings), total);
    }

    Optional<LocalDate> latestSavedDate() {
        if (!Files.isDirectory(recordsRoot)) return Optional.empty();
        try (Stream<Path> paths = Files.find(recordsRoot, 3,
                (path, attrs) -> attrs.isRegularFile() && "daily-summary.md".equals(path.getFileName().toString()))) {
            return paths.map(Path::getParent)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .map(ActivityCalendarModel::parseDate)
                    .flatMap(Optional::stream)
                    .max(LocalDate::compareTo);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<LocalDate> parseDate(String value) {
        try { return Optional.of(LocalDate.parse(value)); }
        catch (RuntimeException ignored) { return Optional.empty(); }
    }
}
