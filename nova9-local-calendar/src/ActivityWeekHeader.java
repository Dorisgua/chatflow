import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Fixed day labels shown above the vertically scrolling activity grid. */
final class ActivityWeekHeader extends JComponent {
    private static final List<String> WEEKDAYS = List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);
    private ActivityCalendarModel.WeekData data;

    ActivityWeekHeader(ActivityCalendarModel.WeekData data) {
        this.data = data;
        setPreferredSize(new Dimension(ActivityWeekGrid.TIME_AXIS + 7 * ActivityWeekGrid.DAY_WIDTH + 1, 58));
        setOpaque(true);
        setBackground(Color.WHITE);
    }

    void setWeekData(ActivityCalendarModel.WeekData value) { data = value; repaint(); }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            LocalDate today = LocalDate.now();
            for (int day = 0; day < 7; day++) {
                int x = ActivityWeekGrid.TIME_AXIS + day * ActivityWeekGrid.DAY_WIDTH;
                LocalDate date = data.monday().plusDays(day);
                if (date.equals(today)) {
                    g.setColor(new Color(232, 240, 255));
                    g.fillRoundRect(x + 6, 5, ActivityWeekGrid.DAY_WIDTH - 12, 46, 14, 14);
                }
                g.setColor(new Color(225, 228, 233));
                g.drawLine(x, 0, x, getHeight());
                g.setColor(new Color(40, 44, 52));
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                drawCentered(g, WEEKDAYS.get(day), x, ActivityWeekGrid.DAY_WIDTH, 21);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                drawCentered(g, date.format(DATE), x, ActivityWeekGrid.DAY_WIDTH, 40);
                if (!data.days().getOrDefault(date, List.of()).isEmpty()) {
                    g.setColor(new Color(67, 113, 190));
                    g.fillOval(x + ActivityWeekGrid.DAY_WIDTH - 20, 10, 6, 6);
                }
            }
            int right = ActivityWeekGrid.TIME_AXIS + 7 * ActivityWeekGrid.DAY_WIDTH;
            g.setColor(new Color(225, 228, 233));
            g.drawLine(right, 0, right, getHeight());
            g.drawLine(0, getHeight() - 1, right, getHeight() - 1);
        } finally {
            g.dispose();
        }
    }

    private static void drawCentered(Graphics2D g, String text, int x, int width, int baseline) {
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, x + (width - metrics.stringWidth(text)) / 2, baseline);
    }
}
