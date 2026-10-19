import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Main read-only desktop calendar panel. */
final class ActivityCalendarPanel extends JPanel {
    private static final DateTimeFormatter RANGE = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA);

    private final ActivityCalendarModel model;
    private LocalDate visibleDay = LocalDate.now();
    private ActivityCalendarModel.WeekData weekData;
    private final JLabel rangeLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel statusLabel = new JLabel(" ");
    private final JTextArea detailArea = new JTextArea();
    private ActivityWeekGrid weekGrid;
    private ActivityWeekHeader weekHeader;
    private JScrollPane gridScroll;

    ActivityCalendarPanel(ActivityCalendarModel model) {
        super(new BorderLayout(0, 0));
        this.model = model;
        this.weekData = model.loadWeek(visibleDay);
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildContent(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        showWeek(visibleDay, true);
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton previous = button("‹ 上一周");
        JButton today = button("今天");
        JButton latest = button("最近记录");
        JButton next = button("下一周 ›");
        JButton refresh = button("刷新");
        previous.addActionListener(e -> showWeek(visibleDay.minusWeeks(1), false));
        today.addActionListener(e -> showWeek(LocalDate.now(), false));
        latest.addActionListener(e -> model.latestSavedDate().ifPresentOrElse(
                date -> showWeek(date, false),
                () -> JOptionPane.showMessageDialog(this, "还没有已保存的活动总结。", "最近记录",
                        JOptionPane.INFORMATION_MESSAGE)));
        next.addActionListener(e -> showWeek(visibleDay.plusWeeks(1), false));
        refresh.addActionListener(e -> showWeek(visibleDay, false));
        left.add(previous); left.add(today); left.add(latest); left.add(next); left.add(refresh);
        rangeLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        bar.add(left, BorderLayout.WEST);
        bar.add(rangeLabel, BorderLayout.CENTER);
        JLabel readOnly = new JLabel("只读视图");
        readOnly.setForeground(new Color(36, 117, 76));
        readOnly.setFont(readOnly.getFont().deriveFont(Font.BOLD));
        bar.add(readOnly, BorderLayout.EAST);
        return bar;
    }

    private JPanel buildContent() {
        weekGrid = new ActivityWeekGrid(weekData);
        weekHeader = new ActivityWeekHeader(weekData);
        weekGrid.onSelection(period -> showDetail(period.detailText()));
        gridScroll = new JScrollPane(weekGrid);
        gridScroll.setColumnHeaderView(weekHeader);
        // Windows high-DPI rendering can leave trails when the viewport copies
        // pixels during scrolling. Repaint the exposed area instead.
        gridScroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        gridScroll.getViewport().setOpaque(true);
        gridScroll.getViewport().setBackground(Color.WHITE);
        gridScroll.getVerticalScrollBar().setUnitIncrement(30);
        gridScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 1, new Color(218, 222, 228)));

        detailArea.setEditable(false);
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);
        detailArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        detailArea.setMargin(new Insets(14, 14, 14, 14));
        detailArea.setText("点击一个活动格子查看时间、预计时长和可信度。\n\n"
                + "很短的活动会显示成细色条；这不会被自动拉长。\n\n"
                + "空白表示当时没有保存活动记录，不等于休息。\n"
                + "日历不会打开加密详情或模型原始回答。");
        JScrollPane details = new JScrollPane(detailArea);
        details.setBorder(BorderFactory.createTitledBorder("活动摘要"));
        details.setPreferredSize(new Dimension(285, 500));

        JPanel content = new JPanel(new BorderLayout(8, 0));
        content.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        content.add(gridScroll, BorderLayout.CENTER);
        content.add(details, BorderLayout.EAST);
        return content;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(6, 12, 8, 12));
        statusLabel.setForeground(new Color(84, 90, 100));
        footer.add(statusLabel, BorderLayout.CENTER);
        JLabel privacy = new JLabel("只读取 daily-summary.md；不读取图片、视频或加密详细记录");
        privacy.setForeground(new Color(36, 117, 76));
        footer.add(privacy, BorderLayout.EAST);
        return footer;
    }

    private void showWeek(LocalDate day, boolean initial) {
        visibleDay = day;
        weekData = model.loadWeek(day);
        LocalDate sunday = weekData.monday().plusDays(6);
        rangeLabel.setText(weekData.monday().format(RANGE) + " — " + sunday.format(RANGE));
        if (weekGrid != null) weekGrid.setWeekData(weekData);
        if (weekHeader != null) weekHeader.setWeekData(weekData);
        String status = weekData.totalPeriods() == 0
                ? "本周没有已保存的活动总结。"
                : "本周读取到 " + weekData.totalPeriods() + " 个活动时间段。";
        if (!weekData.warnings().isEmpty()) status += " 另有 " + weekData.warnings().size() + " 行无法识别。";
        statusLabel.setText(status);
        if (!initial) showDetail("已切换日期。点击活动格子查看摘要。\n\n空白表示当时没有保存活动记录。");
        revalidate();
        repaint();
        scrollNearFirstActivity();
    }

    private void showDetail(String text) {
        detailArea.setText(text == null ? "" : text);
        detailArea.setCaretPosition(0);
        detailArea.revalidate();
        detailArea.repaint();
    }

    private void scrollNearFirstActivity() {
        if (weekGrid == null || gridScroll == null) return;
        int target = Math.max(0, weekGrid.yForMinute(weekGrid.earliestStartMinute() - 60) - 10);
        SwingUtilities.invokeLater(() -> gridScroll.getViewport().setViewPosition(new java.awt.Point(0, target)));
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        return button;
    }
}
