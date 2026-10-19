import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Dimension;
import java.nio.file.Path;

/** Entry point for the independent, read-only local activity calendar. */
public final class Nova9LocalCalendar {
    private Nova9LocalCalendar() {}

    public static void main(String[] args) {
        System.setProperty("java.net.useSystemProxies", "false");
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}

            Path home = Path.of(System.getProperty("nova9.calendar.home", ".")).toAbsolutePath().normalize();
            Path recordsRoot = home.resolveSibling("nova9-activity-records").toAbsolutePath().normalize();
            ActivityCalendarModel model = new ActivityCalendarModel(recordsRoot);

            JFrame window = new JFrame("本地活动日历");
            window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            window.setContentPane(new ActivityCalendarPanel(model));
            window.setMinimumSize(new Dimension(1050, 680));
            window.setSize(1380, 860);
            window.setLocationRelativeTo(null);
            window.setVisible(true);
        });
    }
}
