import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Proportional seven-day time grid. It paints text-only summary periods. */
final class ActivityWeekGrid extends JComponent {
    static final int TIME_AXIS = 68;
    static final int DAY_WIDTH = 150;
    static final int PIXELS_PER_HOUR = 120;
    private static final int TOP_GAP = 8;

    private ActivityCalendarModel.WeekData data;
    private int startHour = 6;
    private final List<HitBox> hitBoxes = new ArrayList<>();
    private Consumer<ActivityPeriod> selectionListener = ignored -> {};
    private ActivityPeriod selected;

    ActivityWeekGrid(ActivityCalendarModel.WeekData data) {
        this.data = data;
        setOpaque(true);
        setBackground(Color.WHITE);
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { selectAt(event.getX(), event.getY()); }
        });
        recalculateSize();
    }

    void setWeekData(ActivityCalendarModel.WeekData value) {
        data = value;
        selected = null;
        recalculateSize();
        repaint();
    }

    void onSelection(Consumer<ActivityPeriod> listener) {
        selectionListener = listener == null ? ignored -> {} : listener;
    }

    int yForMinute(int minute) {
        int clamped = Math.max(startHour * 60, Math.min(1440, minute));
        return TOP_GAP + (clamped - startHour * 60) * PIXELS_PER_HOUR / 60;
    }

    int earliestStartMinute() {
        int earliest = Integer.MAX_VALUE;
        for (List<ActivityPeriod> periods : data.days().values()) {
            for (ActivityPeriod period : periods) earliest = Math.min(earliest, period.startMinute());
        }
        return earliest == Integer.MAX_VALUE ? startHour * 60 : earliest;
    }

    @Override public String getToolTipText(MouseEvent event) {
        HitBox box = hitAt(event.getX(), event.getY());
        if (box == null) return null;
        ActivityPeriod period = box.period;
        return "<html><b>" + escape(period.category) + "</b><br>"
                + period.timeLabel() + "，" + period.durationLabel() + "<br>可信度："
                + escape(period.confidence) + "</html>";
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            hitBoxes.clear();
            paintGrid(g);
            paintPeriods(g);
            paintCurrentTime(g);
        } finally {
            g.dispose();
        }
    }

    private void paintGrid(Graphics2D g) {
        int gridRight = TIME_AXIS + 7 * DAY_WIDTH;
        LocalDate today = LocalDate.now();
        // Do not rely only on Swing's optimized viewport clearing: explicitly
        // erase the time axis so old labels cannot survive a scroll repaint.
        g.setColor(getBackground());
        g.fillRect(0, 0, TIME_AXIS, getHeight());
        for (int day = 0; day < 7; day++) {
            int x = TIME_AXIS + day * DAY_WIDTH;
            LocalDate date = data.monday().plusDays(day);
            g.setColor(date.equals(today) ? new Color(246, 249, 255) : Color.WHITE);
            g.fillRect(x, 0, DAY_WIDTH, getHeight());
            g.setColor(new Color(224, 228, 234));
            g.drawLine(x, 0, x, getHeight());
        }
        g.setColor(new Color(224, 228, 234));
        g.drawLine(gridRight, 0, gridRight, getHeight());

        Font timeFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        g.setFont(timeFont);
        FontMetrics metrics = g.getFontMetrics();
        for (int hour = startHour; hour <= 24; hour++) {
            int y = yForMinute(hour * 60);
            g.setColor(new Color(210, 215, 222));
            g.drawLine(TIME_AXIS, y, gridRight, y);
            if (hour < 24) {
                String label = String.format("%02d:00", hour);
                g.setColor(new Color(102, 109, 120));
                g.drawString(label, TIME_AXIS - metrics.stringWidth(label) - 10, y + metrics.getAscent() / 2);
                int half = yForMinute(hour * 60 + 30);
                Stroke old = g.getStroke();
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f,
                        new float[]{3f, 5f}, 0f));
                g.setColor(new Color(237, 239, 243));
                g.drawLine(TIME_AXIS, half, gridRight, half);
                g.setStroke(old);
            }
        }
    }

    private void paintPeriods(Graphics2D g) {
        for (int day = 0; day < 7; day++) {
            LocalDate date = data.monday().plusDays(day);
            List<ActivityPeriod> periods = data.days().getOrDefault(date, List.of());
            for (ActivityPeriod period : periods) {
                int x = TIME_AXIS + day * DAY_WIDTH + 5;
                int y = yForMinute(period.startMinute());
                int trueHeight = Math.max(4, yForMinute(period.endMinute()) - y);
                Rectangle visible = new Rectangle(x, y, DAY_WIDTH - 10, trueHeight);
                CategoryStyle style = CategoryStyle.forCategory(period.category);
                g.setColor(style.fill);
                g.fillRoundRect(visible.x, visible.y, visible.width, visible.height, 9, 9);
                g.setColor(style.accent);
                g.fillRoundRect(visible.x, visible.y, Math.min(5, visible.width), visible.height, 6, 6);
                if (period.isDataGap()) paintHatch(g, visible);
                if (period == selected) {
                    g.setColor(new Color(35, 78, 142));
                    g.setStroke(new BasicStroke(2f));
                    g.drawRoundRect(visible.x, visible.y, visible.width, Math.max(visible.height, 5), 9, 9);
                    g.setStroke(new BasicStroke(1f));
                }
                if (visible.height >= 22) paintPeriodText(g, period, visible, style);
                Rectangle hit = new Rectangle(visible);
                if (hit.height < 12) {
                    hit.y -= (12 - hit.height) / 2;
                    hit.height = 12;
                }
                hitBoxes.add(new HitBox(period, hit));
            }
        }
    }

    private static void paintPeriodText(Graphics2D g, ActivityPeriod period, Rectangle rect, CategoryStyle style) {
        Shape originalClip = g.getClip();
        try {
            g.clipRect(rect.x + 6, rect.y + 2, rect.width - 10, rect.height - 4);
            g.setColor(style.text);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g.drawString(style.symbol + " " + period.category, rect.x + 10, rect.y + 16);
            if (rect.height >= 40) {
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g.drawString(period.timeLabel() + " · " + period.confidence, rect.x + 10, rect.y + 32);
            }
        } finally {
            g.setClip(originalClip);
        }
    }

    private static void paintHatch(Graphics2D g, Rectangle rect) {
        g.setColor(new Color(115, 120, 128, 80));
        for (int offset = -rect.height; offset < rect.width; offset += 9) {
            g.drawLine(rect.x + offset, rect.y + rect.height, rect.x + offset + rect.height, rect.y);
        }
    }

    private void paintCurrentTime(Graphics2D g) {
        LocalDate today = LocalDate.now();
        if (today.isBefore(data.monday()) || today.isAfter(data.monday().plusDays(6))) return;
        LocalTime now = LocalTime.now();
        int minute = now.getHour() * 60 + now.getMinute();
        if (minute < startHour * 60) return;
        int day = (int) (today.toEpochDay() - data.monday().toEpochDay());
        int x1 = TIME_AXIS + day * DAY_WIDTH;
        int x2 = x1 + DAY_WIDTH;
        int y = yForMinute(minute);
        g.setColor(new Color(221, 69, 69));
        g.setStroke(new BasicStroke(2f));
        g.drawLine(x1, y, x2, y);
        g.fillOval(x1 - 3, y - 3, 7, 7);
        g.setStroke(new BasicStroke(1f));
    }

    private void selectAt(int x, int y) {
        HitBox box = hitAt(x, y);
        if (box == null) return;
        selected = box.period;
        selectionListener.accept(selected);
        repaint();
    }

    private HitBox hitAt(int x, int y) {
        HitBox closest = null;
        int bestDistance = Integer.MAX_VALUE;
        for (HitBox box : hitBoxes) {
            if (!box.bounds.contains(x, y)) continue;
            int center = box.bounds.y + box.bounds.height / 2;
            int distance = Math.abs(y - center);
            if (distance < bestDistance) { closest = box; bestDistance = distance; }
        }
        return closest;
    }

    private void recalculateSize() {
        int earliest = 6;
        for (List<ActivityPeriod> periods : data.days().values()) {
            for (ActivityPeriod period : periods) earliest = Math.min(earliest, period.start.getHour());
        }
        startHour = Math.max(0, earliest - (earliest < 6 ? 1 : 0));
        setPreferredSize(new Dimension(TIME_AXIS + 7 * DAY_WIDTH + 1,
                TOP_GAP + (24 - startHour) * PIXELS_PER_HOUR + 2));
        revalidate();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record HitBox(ActivityPeriod period, Rectangle bounds) {}

    private record CategoryStyle(Color fill, Color accent, Color text, String symbol) {
        static CategoryStyle forCategory(String category) {
            return switch (category) {
                case "电脑工作" -> style(255, 241, 207, 216, 147, 30, "▣");
                case "阅读学习" -> style(224, 237, 255, 76, 126, 213, "▤");
                case "面对面交流" -> style(239, 229, 255, 139, 91, 205, "◇");
                case "用餐饮水" -> style(255, 228, 219, 224, 103, 72, "●");
                case "行走移动" -> style(220, 245, 234, 49, 153, 112, "→");
                case "整理物品" -> style(226, 239, 246, 79, 132, 156, "≡");
                case "休息" -> style(225, 243, 247, 74, 151, 168, "～");
                case "离开画面" -> style(238, 239, 241, 138, 143, 151, "○");
                case "数据缺失" -> style(224, 226, 230, 91, 96, 105, "!");
                case "无法判断" -> style(243, 240, 233, 150, 135, 99, "?");
                default -> style(237, 237, 240, 125, 125, 136, "·");
            };
        }

        private static CategoryStyle style(int r, int g, int b, int ar, int ag, int ab, String symbol) {
            return new CategoryStyle(new Color(r, g, b), new Color(ar, ag, ab), new Color(40, 44, 52), symbol);
        }
    }
}
