import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** Parser, duration estimation, latest-date and visual smoke tests. */
public final class CalendarSelfTest {
    private CalendarSelfTest() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path temporary = Files.createTempDirectory("nova9-calendar-test-").toAbsolutePath().normalize();
        try {
            LocalDate date = LocalDate.of(2026, 8, 17);
            Path day = temporary.resolve("2026-08").resolve(date.toString());
            Files.createDirectories(day);
            Files.writeString(day.resolve("daily-summary.md"), """
                    # 2026-08-17 活动总结

                    > 这是未加密的简要时间线。

                    - 10:35–10:37｜电脑工作｜中
                    - 10:39｜阅读学习｜高
                    - 10:41｜数据缺失｜不适用
                    - 11:00–11:28｜电脑工作｜高
                    - 无效时间｜不应读取｜低
                    """, StandardCharsets.UTF_8);
            Files.writeString(day.resolve("activity-details.json.enc"),
                    "THIS ENCRYPTED SENTINEL MUST NEVER BE PARSED", StandardCharsets.UTF_8);

            ActivityCalendarModel model = new ActivityCalendarModel(temporary);
            ActivityCalendarModel.WeekData week = model.loadWeek(date);
            List<ActivityPeriod> periods = week.days().get(date);
            require(periods.size() == 4, "应读取4条有效摘要");
            require(week.warnings().size() == 1, "应报告1条无效摘要");
            require(periods.get(0).estimatedMinutes() == 4, "首段工作应估算为4分钟");
            require(periods.get(1).estimatedMinutes() == 2, "单次阅读应估算为2分钟");
            require(periods.get(2).estimatedMinutes() == 19, "数据缺失应延续到下一条新记录");
            require(periods.get(3).estimatedMinutes() == 30, "末段工作应在最后观察后补2分钟");
            require(model.latestSavedDate().orElseThrow().equals(date), "最近记录日期错误");

            Path preview = Path.of("build", "calendar-preview.png").toAbsolutePath().normalize();
            Files.createDirectories(preview.getParent());
            renderPreview(week, preview);
            require(Files.size(preview) > 10_000, "日历预览渲染异常");
            checkOptionalRealSummaries();
            System.out.println("CALENDAR SELF-TEST OK");
            System.out.println("- parsed minimal summaries only");
            System.out.println("- duration and data-gap estimation passed");
            System.out.println("- latest saved date lookup passed");
            System.out.println("- visual preview: " + preview);
        } finally {
            deleteOwnTemporaryDirectory(temporary);
        }
    }

    private static void renderPreview(ActivityCalendarModel.WeekData week, Path output) throws IOException {
        ActivityWeekGrid grid = new ActivityWeekGrid(week);
        ActivityWeekHeader header = new ActivityWeekHeader(week);
        Dimension gridSize = grid.getPreferredSize();
        int width = gridSize.width;
        int height = 520;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            grid.setSize(gridSize);
            int cropStart = grid.yForMinute(9 * 60 + 30);
            g.translate(0, 58 - cropStart);
            grid.paint(g);
            g.translate(0, cropStart - 58);
            header.setSize(width, 58);
            header.paint(g);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", output.toFile());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void checkOptionalRealSummaries() {
        String configured = System.getProperty("nova9.calendar.check.records", "").trim();
        if (configured.isEmpty()) return;
        ActivityCalendarModel real = new ActivityCalendarModel(Path.of(configured));
        LocalDate latest = real.latestSavedDate().orElseThrow(() -> new AssertionError("真实摘要目录中没有记录"));
        ActivityCalendarModel.WeekData week = real.loadWeek(latest);
        require(week.totalPeriods() > 0, "最近一周没有可读取的真实摘要");
        System.out.println("- real minimal summary check: " + latest + ", " + week.totalPeriods() + " periods");
    }

    private static void deleteOwnTemporaryDirectory(Path root) {
        if (root == null || !root.getFileName().toString().startsWith("nova9-calendar-test-")) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
