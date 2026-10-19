import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nova 9 local scene understanding.
 *
 * Private-LAN MJPEG -> in-memory recent frames -> localhost llama.cpp -> MiniCPM-V -> text.
 * No frame, scene description, token, or history is written to disk by this program.
 */
public final class Nova9LocalScene {
    private static final int LOCAL_PORT = Integer.getInteger("nova9.scene.port", 17861);
    private static final int MAX_SCENE_FRAMES = 3;
    private static final long FRAME_STALE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final String MODEL_SHA256 = "6b0c74962c44bc6bf4b655b9b02c13eda9d5a0491543ae976d1ac18e4b7892e2";
    private static final String MMPROJ_SHA256 = "ca931d861d0801d9003e50697cd764721a334107c0e0415a51168ee1938462de";
    private static final String DETAILED_PROMPT = """
            这些图片按时间先后排列，来自同一个第一人称摄像头。请只根据画面中的可见证据，用简洁中文描述主要场景和活动。
            必须严格按三行输出：
            观察：只写能直接看到的人、物体、位置和动作。
            推测：写最可能发生的活动；无法判断时明确写“无法可靠判断”。
            不确定性：指出饮品种类、是否交谈、人物身份等不能仅凭画面确认的内容。
            不要识别人脸或身份，不要推测年龄、健康、宗教、政治、财务等敏感属性，不要编造画面外的信息。
            """;
    private static final String DEMO_PROMPT = """
            这是第一人称摄像头的最新画面。请只用一句不超过35个汉字的自然中文，描述主要场景和最可能正在发生的活动。
            优先说明人物正在做什么；没有人物时，说明主要物体和环境。
            只依据可见证据，不识别人脸或身份，不推断敏感属性，不编造画面外信息。
            不要输出“观察”“推测”“不确定性”等标签，不要分段，不要重复。活动无法判断时，直接描述看得见的场景。
            """;
    private static final String ACTIVITY_PROMPT = """
            这是第一人称摄像头的最新画面。请只依据可见证据进行活动记录，不要识别人脸或身份，不要推断年龄、健康、宗教、政治、财务等敏感属性。
            不要转录电脑或手机屏幕、证件、银行卡、账号、密码、文件正文等可能敏感的文字；不要编造画面外信息。
            活动类别只能从以下十项选择一项：电脑工作、阅读学习、面对面交流、用餐饮水、行走移动、整理物品、休息、离开画面、其他、无法判断。
            必须严格输出以下七行，每行尽量简洁，不要添加其他标题或段落：
            活动类别：上述十项之一
            可信度：高、中、低之一
            场景：室内外和主要区域，不写精确地址
            直接观察：人物、动作、主要物体和位置关系，只写能直接看到的内容
            活动推测：最可能正在进行的活动
            判断依据：支持活动推测的可见证据
            不确定性：不能仅凭画面确认的内容
            """;

    private final Path home;
    private final JFrame window = new JFrame("Nova 9 本地场景理解");
    private final JTextField urlField = new JTextField();
    private final JButton startButton = new JButton("启动本地场景理解");
    private final JButton describeButton = new JButton("立即描述一次");
    private final JButton stopButton = new JButton("停止并清除");
    private final JCheckBox demoModeCheck = new JCheckBox("Demo 快速模式", true);
    private final JButton recordButton = new JButton("开始活动记录");
    private final JButton pauseRecordButton = new JButton("暂停记录");
    private final JButton saveRecordButton = new JButton("加密保存今天");
    private final JButton openRecordButton = new JButton("打开加密记录");
    private final JButton clearRecordButton = new JButton("清除内存记录");
    private final JLabel statusLabel = new JLabel("等待开始：请粘贴手机应用显示的完整地址");
    private final JLabel privacyLabel = new JLabel("MiniCPM-V 只在本机运行；画面和描述不写入文件");
    private final JTextArea descriptionArea = new JTextArea();
    private final JTextArea rawOutputArea = new JTextArea();
    private final JTextArea timelineArea = new JTextArea();
    private final JTabbedPane outputTabs = new JTabbedPane();
    private final ImagePanel imagePanel = new ImagePanel();

    private final FrameStore latestFrame = new FrameStore();
    private final Deque<byte[]> sceneFrames = new ArrayDeque<>();
    private final Object sceneLock = new Object();
    private final Object recordsLock = new Object();
    private final List<ActivityRecord> activityRecords = new ArrayList<>();
    private final AtomicBoolean descriptionBusy = new AtomicBoolean(false);
    private final AtomicLong runGeneration = new AtomicLong(0);
    private final AtomicLong freshnessGeneration = new AtomicLong(0);
    private final ExecutorService streamExecutor = Executors.newSingleThreadExecutor(named("phone-stream"));
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(named("model-control"));
    private final ExecutorService descriptionExecutor = Executors.newSingleThreadExecutor(named("scene-description"));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, named("memory-sampler"));
    private final javax.swing.Timer previewTimer;

    private volatile MjpegClient camera;
    private volatile LocalModelServer modelServer;
    private volatile Future<?> cameraTask;
    private volatile ScheduledFuture<?> sampleTask;
    private volatile ScheduledFuture<?> autoTask;
    private volatile ScheduledFuture<?> recordTask;
    private volatile ScheduledFuture<?> staleTask;
    private volatile boolean demoMode = true;
    private volatile boolean recording = false;
    private volatile boolean recordsDirty = false;
    private volatile boolean streamStale = false;
    private volatile long streamStartedNanos = 0;
    private volatile long watchedFrameSequence = -1;
    private volatile int recoveryFreshChecks = 0;

    private Nova9LocalScene(Path home) {
        this.home = home;
        buildWindow();
        previewTimer = new javax.swing.Timer(500, e -> updatePreview());
    }

    public static void main(String[] args) {
        Path home = Path.of(System.getProperty("nova9.scene.home", ".")).toAbsolutePath().normalize();
        if (args.length == 1 && "--self-test".equals(args[0])) {
            runSelfTest(home);
            return;
        }
        System.out.println("Java started; creating the local scene window...");
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> error.printStackTrace(System.err));
        SwingUtilities.invokeLater(() -> new Nova9LocalScene(home).show());
    }

    private void buildWindow() {
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.setMinimumSize(new Dimension(1050, 680));
        window.setSize(1280, 820);
        window.setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        JPanel top = new JPanel(new BorderLayout(8, 8));
        JLabel label = new JLabel("手机地址：");
        label.setLabelFor(urlField);
        urlField.setToolTipText("手机应用显示的 http://私有IP:端口/?token=完整地址");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        describeButton.setEnabled(false);
        stopButton.setEnabled(false);
        demoModeCheck.setToolTipText("开启：最新1帧、单句输出；关闭：最近3帧、详细输出");
        buttons.add(demoModeCheck);
        buttons.add(startButton);
        buttons.add(describeButton);
        buttons.add(stopButton);
        top.add(label, BorderLayout.WEST);
        top.add(urlField, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.EAST);

        configureTextArea(descriptionArea);
        descriptionArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
        descriptionArea.setText("Demo 快速模式已开启。\n\n程序只分析最新一帧，并用一句话描述场景；画面和结果不会保存到文件。");
        JScrollPane descriptionScroll = new JScrollPane(descriptionArea);
        configureTextArea(rawOutputArea);
        rawOutputArea.setText("模型未经整理的原始回答会显示在这里；只有活动记录才可由你主动加密保存。");
        configureTextArea(timelineArea);
        timelineArea.setText("今天尚无活动记录。\n\n点击“开始活动记录”后，每2分钟分析最新一帧；图片和视频不会保存。");
        outputTabs.addTab("当前显示", descriptionScroll);
        outputTabs.addTab("模型原始回答", new JScrollPane(rawOutputArea));
        outputTabs.addTab("今日时间线", new JScrollPane(timelineArea));
        outputTabs.setPreferredSize(new Dimension(440, 100));

        JPanel recordControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        recordButton.setEnabled(false);
        pauseRecordButton.setEnabled(false);
        saveRecordButton.setEnabled(false);
        openRecordButton.setEnabled(Files.isDirectory(home.resolveSibling("nova9-activity-records")));
        clearRecordButton.setEnabled(false);
        recordControls.add(new JLabel("本地文字记录："));
        recordControls.add(recordButton);
        recordControls.add(pauseRecordButton);
        recordControls.add(saveRecordButton);
        recordControls.add(openRecordButton);
        recordControls.add(clearRecordButton);

        JPanel statusPanel = new JPanel(new GridLayout(2, 1, 2, 2));
        privacyLabel.setForeground(new Color(20, 105, 55));
        statusPanel.add(statusLabel);
        statusPanel.add(privacyLabel);
        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.add(recordControls, BorderLayout.NORTH);
        bottom.add(statusPanel, BorderLayout.CENTER);

        root.add(top, BorderLayout.NORTH);
        root.add(imagePanel, BorderLayout.CENTER);
        root.add(outputTabs, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        window.setContentPane(root);

        startButton.addActionListener(e -> start());
        describeButton.addActionListener(e -> requestDescription(true));
        stopButton.addActionListener(e -> stop(true));
        recordButton.addActionListener(e -> startActivityRecording());
        pauseRecordButton.addActionListener(e -> pauseActivityRecording());
        saveRecordButton.addActionListener(e -> saveTodayRecords());
        openRecordButton.addActionListener(e -> openEncryptedRecord());
        clearRecordButton.addActionListener(e -> clearMemoryRecords());
        demoModeCheck.addActionListener(e -> {
            demoMode = demoModeCheck.isSelected();
            descriptionArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, demoMode ? 22 : 17));
            status(demoMode
                    ? "已切换为 Demo 快速模式：最新1帧、单句描述"
                    : "已切换为详细模式：最近3帧、谨慎分析");
        });
        urlField.addActionListener(e -> start());
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { attemptShutdown(); }
        });
    }

    private static void configureTextArea(JTextArea area) {
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        area.setMargin(new Insets(12, 12, 12, 12));
    }

    private void show() {
        window.setAlwaysOnTop(true);
        window.setVisible(true);
        window.toFront();
        window.requestFocus();
        System.out.println("Local scene window created successfully.");
        javax.swing.Timer release = new javax.swing.Timer(2000, e -> window.setAlwaysOnTop(false));
        release.setRepeats(false);
        release.start();
    }

    private void start() {
        if (camera != null) return;
        final URI streamUri;
        try {
            streamUri = validateAndBuildStreamUri(urlField.getText().trim());
            requireLocalFiles(home);
        } catch (Exception e) {
            showError(safeMessage(e));
            return;
        }

        setRunningUi(true, false);
        long generation = runGeneration.incrementAndGet();
        urlField.setText("地址已读取；安全令牌不会写入文件或日志");
        descriptionArea.setText("正在启动本地 MiniCPM-V。第一次加载可能需要几十秒，请稍候……");
        status("正在连接 Nova 9，并从本地硬盘加载模型……");

        camera = new MjpegClient(streamUri, latestFrame, this::status);
        cameraTask = streamExecutor.submit(camera);
        streamStale = false;
        streamStartedNanos = System.nanoTime();
        watchedFrameSequence = latestFrame.sequence();
        recoveryFreshChecks = 0;
        previewTimer.start();
        sampleTask = scheduler.scheduleWithFixedDelay(this::sampleForScene, 0, 2, TimeUnit.SECONDS);
        staleTask = scheduler.scheduleWithFixedDelay(this::watchStreamFreshness, 1, 1, TimeUnit.SECONDS);

        controlExecutor.submit(() -> {
            try {
                LocalModelServer server = new LocalModelServer(home);
                modelServer = server;
                server.startAndWait();
                if (runGeneration.get() != generation || camera == null) {
                    server.close();
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    setRunningUi(true, true);
                    status(demoMode
                            ? "本地 MiniCPM-V 已就绪；Demo 将自动描述最新画面"
                            : "本地 MiniCPM-V 已就绪；将自动描述最近3帧");
                    descriptionArea.setText("模型已就绪，正在收集最近几秒的画面……");
                });
                startAutoDescriptions();
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    if (runGeneration.get() != generation || camera == null) return;
                    showError("本地模型启动失败：" + safeMessage(e));
                    stop(true);
                });
            }
        });
    }

    private void sampleForScene() {
        if (streamStale) return;
        byte[] raw = latestFrame.copy();
        if (raw == null) return;
        try {
            byte[] prepared = prepareSceneFrame(raw);
            synchronized (sceneLock) {
                sceneFrames.addLast(prepared);
                while (sceneFrames.size() > MAX_SCENE_FRAMES) zero(sceneFrames.removeFirst());
            }
        } catch (IOException e) {
            status("这一帧无法转换，程序会继续接收下一帧");
        } finally {
            zero(raw);
        }
    }

    private void updatePreview() {
        if (streamStale) return;
        byte[] copy = latestFrame.copy();
        if (copy == null) return;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(copy));
            if (image != null) imagePanel.setImage(image);
        } catch (IOException ignored) {
        } finally {
            zero(copy);
        }
    }

    private void requestDescription(boolean manual) {
        if (streamStale) {
            if (manual) status("视频已中断，没有新鲜画面可供分析");
            return;
        }
        long generation = runGeneration.get();
        long freshness = freshnessGeneration.get();
        LocalModelServer server = modelServer;
        if (server == null || !server.isReady()) {
            if (manual) status("模型仍在加载，请稍候");
            return;
        }
        if (!descriptionBusy.compareAndSet(false, true)) {
            if (manual) status("上一轮场景描述尚未完成");
            return;
        }
        boolean quick = demoMode;
        List<byte[]> frames = copySceneFrames(quick ? 1 : MAX_SCENE_FRAMES);
        if (frames.isEmpty()) {
            descriptionBusy.set(false);
            if (manual) status("尚未收到摄像头画面");
            return;
        }
        status(quick
                ? "Demo 正在理解最新画面……"
                : "正在用本地 MiniCPM-V 理解最近 " + frames.size() + " 帧……");
        descriptionExecutor.submit(() -> {
            long started = System.nanoTime();
            try {
                SceneClient.SceneResponse response = SceneClient.describe(frames, server.apiKey(), quick);
                double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
                SwingUtilities.invokeLater(() -> {
                    if (runGeneration.get() != generation || freshnessGeneration.get() != freshness
                            || streamStale || camera == null) return;
                    descriptionArea.setText(response.display());
                    rawOutputArea.setText(response.raw());
                    status(String.format(Locale.ROOT, "本地场景描述完成，用时 %.1f 秒；结果未保存", seconds));
                });
            } catch (Exception e) {
                if (runGeneration.get() == generation && camera != null)
                    status("场景描述失败，稍后会重试：" + safeMessage(e));
            } finally {
                frames.forEach(Nova9LocalScene::zero);
                descriptionBusy.set(false);
            }
        });
    }

    private void startAutoDescriptions() {
        cancel(autoTask);
        if (recording || camera == null || modelServer == null || !modelServer.isReady()) return;
        autoTask = scheduler.scheduleWithFixedDelay(() -> requestDescription(false), 3, 10, TimeUnit.SECONDS);
    }

    private void startActivityRecording() {
        if (camera == null || modelServer == null || !modelServer.isReady()) {
            status("请先启动摄像头并等待本地模型就绪");
            return;
        }
        if (recording) return;
        recording = true;
        cancel(autoTask); autoTask = null;
        cancel(recordTask);
        demoModeCheck.setEnabled(false);
        recordButton.setEnabled(false);
        pauseRecordButton.setEnabled(true);
        privacyLabel.setText("记录中：图片/视频不保存；原始回答和详细文字仅在你点击保存后加密写入");
        outputTabs.setSelectedIndex(2);
        status("活动记录已开始；每2分钟分析最新一帧，尚未写入硬盘");
        recordTask = scheduler.scheduleWithFixedDelay(this::requestActivityRecord, 0, 2, TimeUnit.MINUTES);
    }

    private void pauseActivityRecording() {
        if (!recording) return;
        recording = false;
        cancel(recordTask); recordTask = null;
        demoModeCheck.setEnabled(true);
        recordButton.setEnabled(modelServer != null && modelServer.isReady());
        pauseRecordButton.setEnabled(false);
        privacyLabel.setText("记录已暂停；内存中的详细文字尚未保存，图片和视频从未保存");
        status("活动记录已暂停；可检查时间线、加密保存或继续记录");
        startAutoDescriptions();
    }

    private void requestActivityRecord() {
        long generation = runGeneration.get();
        long freshness = freshnessGeneration.get();
        LocalModelServer server = modelServer;
        if (!recording || streamStale || server == null || !server.isReady() || camera == null) return;
        if (!descriptionBusy.compareAndSet(false, true)) {
            status("记录时刻遇到上一轮推理，稍后将在下一周期继续");
            return;
        }
        List<byte[]> frames = copySceneFrames(1);
        if (frames.isEmpty()) {
            descriptionBusy.set(false);
            status("记录时刻尚未收到摄像头画面");
            return;
        }
        status("正在本地生成一条详细活动记录……");
        descriptionExecutor.submit(() -> {
            long started = System.nanoTime();
            try {
                String raw = SceneClient.describeActivity(frames, server.apiKey());
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (runGeneration.get() != generation || freshnessGeneration.get() != freshness
                        || streamStale || camera == null) return;
                ActivityRecord record = ActivityRecord.fromRaw(raw, OffsetDateTime.now(), elapsedMs, frames.size());
                synchronized (recordsLock) {
                    activityRecords.add(record);
                    recordsDirty = true;
                }
                SwingUtilities.invokeLater(() -> {
                    if (runGeneration.get() != generation || camera == null) return;
                    descriptionArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
                    descriptionArea.setText(record.displayOutput);
                    rawOutputArea.setText(record.rawModelOutput);
                    refreshTimeline();
                    saveRecordButton.setEnabled(true);
                    clearRecordButton.setEnabled(true);
                    status(String.format(Locale.ROOT,
                            "详细活动记录完成，用时 %.1f 秒；目前只在内存中", elapsedMs / 1000.0));
                });
            } catch (Exception e) {
                if (recording && runGeneration.get() == generation && camera != null)
                    status("活动记录失败，下一周期会重试：" + safeMessage(e));
            } finally {
                frames.forEach(Nova9LocalScene::zero);
                descriptionBusy.set(false);
            }
        });
    }

    private void refreshTimeline() {
        List<ActivityRecord> snapshot;
        synchronized (recordsLock) { snapshot = List.copyOf(activityRecords); }
        if (snapshot.isEmpty()) {
            timelineArea.setText("今天尚无活动记录。");
            return;
        }
        StringBuilder out = new StringBuilder("今日详细文字记录（未保存：" + recordsDirty + "）\n\n");
        for (ActivityRecord record : snapshot) out.append(record.timelineLine()).append('\n');
        out.append("\n共 ").append(snapshot.size()).append(" 次观察。图片和视频未保存。");
        timelineArea.setText(out.toString());
    }

    private void saveTodayRecords() {
        List<ActivityRecord> snapshot;
        synchronized (recordsLock) { snapshot = List.copyOf(activityRecords); }
        if (snapshot.isEmpty()) {
            status("今天还没有可保存的活动记录");
            return;
        }
        char[] password = promptForNewPassword();
        if (password == null) return;
        saveRecordButton.setEnabled(false);
        status("正在本地加密保存详细文字记录……");
        controlExecutor.submit(() -> {
            try {
                Path recordsRoot = home.resolveSibling("nova9-activity-records");
                EncryptedRecordStore.SaveResult result = EncryptedRecordStore.save(
                        recordsRoot, LocalDate.now(), snapshot, password);
                synchronized (recordsLock) { recordsDirty = activityRecords.size() != snapshot.size(); }
                SwingUtilities.invokeLater(() -> {
                    refreshTimeline();
                    saveRecordButton.setEnabled(recordsDirty || !activityRecords.isEmpty());
                    openRecordButton.setEnabled(true);
                    status("已加密保存详细记录：" + result.encryptedDetails().getParent());
                    JOptionPane.showMessageDialog(window,
                            "详细记录已加密保存到：\n" + result.encryptedDetails() +
                            "\n\n简要时间线（未加密）：\n" + result.plainSummary() +
                            "\n\n图片、视频、手机地址和令牌均未保存。",
                            "保存完成", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    saveRecordButton.setEnabled(true);
                    showError("保存失败：" + safeMessage(e));
                });
            } finally {
                Arrays.fill(password, '\0');
            }
        });
    }

    private void openEncryptedRecord() {
        Path recordsRoot = home.resolveSibling("nova9-activity-records").toAbsolutePath().normalize();
        if (!Files.isDirectory(recordsRoot)) {
            status("还没有已保存的活动记录目录");
            return;
        }
        JFileChooser chooser = new JFileChooser(recordsRoot.toFile());
        chooser.setDialogTitle("选择 activity-details.json.enc");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return;
        Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (!selected.startsWith(recordsRoot) || !"activity-details.json.enc".equals(selected.getFileName().toString())) {
            showError("只能打开活动记录目录中的 activity-details.json.enc");
            return;
        }
        JPasswordField passwordField = new JPasswordField(22);
        int answer = JOptionPane.showConfirmDialog(window, passwordField, "输入记录密码",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] password = passwordField.getPassword();
        if (answer != JOptionPane.OK_OPTION) {
            Arrays.fill(password, '\0');
            return;
        }
        status("正在本地解密活动记录……");
        controlExecutor.submit(() -> {
            try {
                String json = EncryptedRecordStore.decryptToJson(selected, password);
                String readable = readableSavedRecords(json);
                SwingUtilities.invokeLater(() -> showRecordViewer(selected, readable, json));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> showError("无法打开：密码错误或文件已损坏"));
            } finally {
                Arrays.fill(password, '\0');
            }
        });
    }

    private void showRecordViewer(Path selected, String readable, String json) {
        JTextArea readableArea = new JTextArea(readable, 28, 78);
        configureTextArea(readableArea);
        readableArea.setCaretPosition(0);
        JTextArea jsonArea = new JTextArea(json, 28, 78);
        configureTextArea(jsonArea);
        jsonArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        jsonArea.setCaretPosition(0);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("整理后的详细记录", new JScrollPane(readableArea));
        tabs.addTab("完整解密JSON（含原始回答）", new JScrollPane(jsonArea));
        JOptionPane.showMessageDialog(window, tabs,
                "本地加密记录｜" + selected.getParent().getFileName(), JOptionPane.INFORMATION_MESSAGE);
        readableArea.setText(""); jsonArea.setText("");
        status("加密记录已在本机打开；密码未保存");
    }

    private static String readableSavedRecords(String json) {
        List<String> times = jsonValues(json, "observed_at");
        List<String> displays = jsonValues(json, "display_text");
        StringBuilder out = new StringBuilder();
        int count = Math.max(times.size(), displays.size());
        for (int i = 0; i < count; i++) {
            String time = i < times.size()
                    ? times.get(i).replaceFirst("^.*T", "")
                        .replaceFirst("\\.\\d+(?=[+-])", "").replaceFirst("[+-].*$", "")
                    : "时间未知";
            String display = i < displays.size() ? displays.get(i) : "没有整理结果";
            if (i > 0) out.append("\n\n────────────────────────\n\n");
            out.append(time).append('\n').append(display);
        }
        return out.length() == 0 ? "文件中没有可显示的活动记录。" : out.toString();
    }

    private static List<String> jsonValues(String json, String key) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key)
                + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
        Matcher matcher = pattern.matcher(json);
        List<String> values = new ArrayList<>();
        while (matcher.find()) values.add(unescapeJson(matcher.group(1)));
        return values;
    }

    private char[] promptForNewPassword() {
        JPasswordField first = new JPasswordField(22);
        JPasswordField second = new JPasswordField(22);
        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("设置本次保存密码（至少8个字符；忘记后无法恢复）："));
        panel.add(first);
        panel.add(new JLabel("再次输入密码："));
        panel.add(second);
        int answer = JOptionPane.showConfirmDialog(window, panel, "加密保存详细记录",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] one = first.getPassword(), two = second.getPassword();
        if (answer != JOptionPane.OK_OPTION) {
            Arrays.fill(one, '\0'); Arrays.fill(two, '\0');
            return null;
        }
        if (one.length < 8 || !Arrays.equals(one, two)) {
            Arrays.fill(one, '\0'); Arrays.fill(two, '\0');
            showError("两次密码必须一致，并且至少8个字符");
            return null;
        }
        Arrays.fill(two, '\0');
        return one;
    }

    private void clearMemoryRecords() {
        int answer = JOptionPane.showConfirmDialog(window,
                "只清除本次运行内存中的活动文字。\n已经保存到硬盘的文件不会删除。\n\n确定继续吗？",
                "清除内存记录", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        synchronized (recordsLock) {
            activityRecords.clear();
            recordsDirty = false;
        }
        rawOutputArea.setText("原始回答已从当前窗口清除。");
        refreshTimeline();
        saveRecordButton.setEnabled(false);
        clearRecordButton.setEnabled(false);
        status("已清除本次运行内存中的活动文字；没有删除已保存文件");
    }

    private void watchStreamFreshness() {
        if (camera == null) return;
        long now = System.nanoTime();
        long sequence = latestFrame.sequence();
        long receivedAt = latestFrame.receivedAtNanos();
        if (sequence != watchedFrameSequence) {
            watchedFrameSequence = sequence;
            if (streamStale) {
                recoveryFreshChecks++;
                if (recoveryFreshChecks >= 2) recoverFreshStream();
            }
            return;
        }
        if (streamStale) return;
        long reference = receivedAt > 0 ? receivedAt : streamStartedNanos;
        if (hasFrameExpired(reference, now)) markStreamStale();
    }

    private static boolean hasFrameExpired(long receivedAtNanos, long nowNanos) {
        return receivedAtNanos > 0 && nowNanos - receivedAtNanos >= FRAME_STALE_NANOS;
    }

    private void markStreamStale() {
        if (streamStale || camera == null) return;
        streamStale = true;
        freshnessGeneration.incrementAndGet();
        recoveryFreshChecks = 0;
        latestFrame.clearImagePreservingClock();
        clearSceneFrames();
        if (recording) {
            ActivityRecord gap = ActivityRecord.dataGap(OffsetDateTime.now());
            synchronized (recordsLock) {
                activityRecords.add(gap);
                recordsDirty = true;
            }
        }
        SwingUtilities.invokeLater(() -> {
            imagePanel.setImage(null);
            descriptionArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
            descriptionArea.setText("视频已中断：超过10秒没有收到新画面。\n\n旧预览和内存帧已清除，推理与活动分析已自动暂停；等待连续新画面后自动恢复。");
            rawOutputArea.setText("视频中断期间没有调用模型，因此没有新的原始回答。");
            if (recording) refreshTimeline();
            privacyLabel.setText("视频中断保护已触发：旧画面已清除，推理暂停，图片和视频未保存");
            status("超过10秒没有新帧；已清除旧画面并自动暂停分析");
        });
    }

    private void recoverFreshStream() {
        if (!streamStale || camera == null) return;
        streamStale = false;
        recoveryFreshChecks = 0;
        SwingUtilities.invokeLater(() -> {
            privacyLabel.setText(recording
                    ? "记录中：图片/视频不保存；原始回答和详细文字仅在你点击保存后加密写入"
                    : "MiniCPM-V 只在本机运行；画面和描述不写入文件");
            status(recording ? "视频已恢复并连续收到新画面；活动分析将自动继续"
                    : "视频已恢复并连续收到新画面；本地场景分析已继续");
        });
        if (recording) scheduler.schedule(this::requestActivityRecord, 2, TimeUnit.SECONDS);
    }

    private List<byte[]> copySceneFrames(int maximum) {
        synchronized (sceneLock) {
            int count = Math.min(Math.max(1, maximum), sceneFrames.size());
            int skip = sceneFrames.size() - count;
            int index = 0;
            List<byte[]> copies = new ArrayList<>(count);
            for (byte[] frame : sceneFrames) {
                if (index++ >= skip) copies.add(Arrays.copyOf(frame, frame.length));
            }
            return copies;
        }
    }

    private void stop(boolean updateUi) {
        runGeneration.incrementAndGet();
        previewTimer.stop();
        cancel(sampleTask); sampleTask = null;
        cancel(autoTask); autoTask = null;
        cancel(recordTask); recordTask = null;
        cancel(staleTask); staleTask = null;
        recording = false;
        streamStale = false;
        streamStartedNanos = 0;
        recoveryFreshChecks = 0;
        MjpegClient currentCamera = camera; camera = null;
        if (currentCamera != null) currentCamera.close();
        Future<?> task = cameraTask; cameraTask = null;
        if (task != null) task.cancel(true);
        latestFrame.clear();
        clearSceneFrames();
        LocalModelServer server = modelServer; modelServer = null;
        if (server != null) server.close();
        if (updateUi) {
            imagePanel.setImage(null);
            descriptionArea.setText("已停止摄像头和模型。程序已释放画面引用。\n活动文字仍在当前运行内存中，可检查或加密保存。");
            urlField.setText("");
            setRunningUi(false, false);
            demoModeCheck.setEnabled(true);
            privacyLabel.setText("已停止；图片和视频没有保存，活动文字仅在你主动保存后写入文件");
            status("已停止摄像头和模型；内存中的活动文字未自动清除");
        }
    }

    private void clearSceneFrames() {
        synchronized (sceneLock) {
            while (!sceneFrames.isEmpty()) zero(sceneFrames.removeFirst());
        }
    }

    private void attemptShutdown() {
        if (recordsDirty) {
            int answer = JOptionPane.showConfirmDialog(window,
                    "还有未保存的活动文字。关闭后这些内存记录会丢失。\n\n仍然关闭吗？",
                    "存在未保存记录", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
        }
        shutdown();
    }

    private void shutdown() {
        stop(false);
        synchronized (recordsLock) { activityRecords.clear(); recordsDirty = false; }
        streamExecutor.shutdownNow();
        controlExecutor.shutdownNow();
        descriptionExecutor.shutdownNow();
        scheduler.shutdownNow();
        window.dispose();
        System.exit(0);
    }

    private void setRunningUi(boolean running, boolean modelReady) {
        startButton.setEnabled(!running);
        describeButton.setEnabled(running && modelReady);
        stopButton.setEnabled(running);
        urlField.setEditable(!running);
        recordButton.setEnabled(running && modelReady && !recording);
        pauseRecordButton.setEnabled(running && modelReady && recording);
        synchronized (recordsLock) {
            saveRecordButton.setEnabled(!activityRecords.isEmpty());
            clearRecordButton.setEnabled(!activityRecords.isEmpty());
        }
    }

    private void status(String text) { SwingUtilities.invokeLater(() -> statusLabel.setText(text)); }
    private void showError(String text) {
        status(text);
        JOptionPane.showMessageDialog(window, text, "无法继续", JOptionPane.ERROR_MESSAGE);
    }

    private static void requireLocalFiles(Path home) {
        for (Path path : List.of(
                home.resolve("runtime").resolve("llama-server.exe"),
                home.resolve("models").resolve("MiniCPM-V-4_6-Q4_K_M.gguf"),
                home.resolve("models").resolve("mmproj-model-f16.gguf"))) {
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("缺少本地文件：" + path.getFileName());
        }
    }

    private static URI validateAndBuildStreamUri(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("请粘贴手机应用显示的完整地址");
        final URI uri;
        try { uri = URI.create(value); }
        catch (Exception e) { throw new IllegalArgumentException("手机地址格式不正确"); }
        if (!"http".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("只允许 http 局域网地址");
        if (uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException("手机地址包含不允许的部分");
        String host = uri.getHost();
        if (host == null || !isPrivateIpv4(host)) throw new IllegalArgumentException("只允许私有 IPv4 地址，不允许域名或公网地址");
        int port = uri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("手机地址中缺少有效端口");
        String token = null;
        if (uri.getRawQuery() != null) {
            for (String part : uri.getRawQuery().split("&")) {
                int at = part.indexOf('=');
                if (at > 0 && "token".equals(part.substring(0, at))) token = part.substring(at + 1);
            }
        }
        if (token == null || !token.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("地址里的32位安全令牌不正确");
        return URI.create("http://" + host + ":" + port + "/stream?token=" + token.toLowerCase(Locale.ROOT));
    }

    private static boolean isPrivateIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] n = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || (parts[i].length() > 1 && parts[i].startsWith("0"))) return false;
                n[i] = Integer.parseInt(parts[i]);
                if (n[i] < 0 || n[i] > 255) return false;
            }
        } catch (NumberFormatException e) { return false; }
        return n[0] == 10 || (n[0] == 172 && n[1] >= 16 && n[1] <= 31) || (n[0] == 192 && n[1] == 168);
    }

    private static final class FrameStore {
        private byte[] data;
        private long sequence;
        private long receivedAtNanos;
        synchronized void replace(byte[] next) {
            zero(data); data = next; sequence++; receivedAtNanos = System.nanoTime();
        }
        synchronized byte[] copy() { return data == null ? null : Arrays.copyOf(data, data.length); }
        synchronized long sequence() { return sequence; }
        synchronized long receivedAtNanos() { return receivedAtNanos; }
        synchronized void clearImagePreservingClock() { zero(data); data = null; }
        synchronized void clear() { zero(data); data = null; receivedAtNanos = 0; sequence++; }
    }

    private static final class MjpegClient implements Runnable, AutoCloseable {
        private final URI streamUri;
        private final FrameStore store;
        private final java.util.function.Consumer<String> status;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile HttpURLConnection connection;

        MjpegClient(URI streamUri, FrameStore store, java.util.function.Consumer<String> status) {
            this.streamUri = streamUri; this.store = store; this.status = status;
        }

        @Override public void run() {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try { readStream(); }
                catch (Exception e) { if (running.get()) status.accept("手机视频中断，1秒后重试：" + safeMessage(e)); }
                finally {
                    HttpURLConnection c = connection; connection = null;
                    if (c != null) c.disconnect();
                }
                if (running.get()) try { Thread.sleep(1000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        private void readStream() throws IOException {
            HttpURLConnection c = (HttpURLConnection) streamUri.toURL().openConnection(Proxy.NO_PROXY);
            connection = c;
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(4000); c.setReadTimeout(7000); c.setUseCaches(false);
            c.setRequestProperty("Accept", "multipart/x-mixed-replace");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("手机返回 HTTP " + code);
            String type = c.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).contains("multipart/")) throw new IOException("手机返回的不是 MJPEG 视频流");
            status.accept("Nova 9 已连接；最近画面只保存在运行内存中");
            try (InputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024)) {
                while (running.get()) {
                    String line = readAsciiLine(in, 2048);
                    if (line == null) throw new EOFException("视频流结束");
                    if (!line.startsWith("--")) continue;
                    int length = -1;
                    while (true) {
                        line = readAsciiLine(in, 2048);
                        if (line == null) throw new EOFException("画面头部不完整");
                        if (line.isEmpty()) break;
                        int colon = line.indexOf(':');
                        if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim()))
                            length = Integer.parseInt(line.substring(colon + 1).trim());
                    }
                    if (length < 100 || length > 5_000_000) throw new IOException("画面大小超出安全范围");
                    byte[] frame = in.readNBytes(length);
                    if (frame.length != length) throw new EOFException("画面不完整");
                    store.replace(frame);
                }
            }
        }

        @Override public void close() {
            running.set(false);
            HttpURLConnection c = connection;
            if (c != null) c.disconnect();
        }

        private static String readAsciiLine(InputStream in, int max) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(128);
            int previous = -1;
            while (out.size() <= max) {
                int b = in.read();
                if (b < 0) return out.size() == 0 ? null : out.toString(StandardCharsets.US_ASCII);
                if (previous == '\r' && b == '\n') {
                    byte[] bytes = out.toByteArray();
                    return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
                }
                out.write(b); previous = b;
            }
            throw new IOException("视频流头部过长");
        }
    }

    private static final class LocalModelServer implements AutoCloseable {
        private final Path home;
        private final String apiKey = randomToken();
        private final AtomicBoolean ready = new AtomicBoolean(false);
        private final AtomicReference<String> lastLine = new AtomicReference<>("");
        private volatile Process process;

        LocalModelServer(Path home) { this.home = home; }

        void startAndWait() throws Exception {
            requireLocalFiles(home);
            ensureLocalPortFree();
            List<String> command = List.of(
                    home.resolve("runtime").resolve("llama-server.exe").toString(),
                    "-m", home.resolve("models").resolve("MiniCPM-V-4_6-Q4_K_M.gguf").toString(),
                    "--mmproj", home.resolve("models").resolve("mmproj-model-f16.gguf").toString(),
                    "--host", "127.0.0.1", "--port", Integer.toString(LOCAL_PORT),
                    "-c", "4096", "-n", "256", "-t", "4",
                    "--api-key", apiKey,
                    "--offline", "--log-disable", "--no-webui", "--no-slots");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(home.resolve("runtime").toFile());
            builder.redirectErrorStream(true);
            builder.environment().put("HF_HUB_OFFLINE", "1");
            builder.environment().put("TRANSFORMERS_OFFLINE", "1");
            process = builder.start();
            Thread reader = new Thread(() -> consume(process.getInputStream()), "llama-output-discarder");
            reader.setDaemon(true); reader.start();
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IOException("llama.cpp 已退出：" + sanitizeServerLine(lastLine.get()));
                if (SceneClient.health(apiKey)) { ready.set(true); return; }
                Thread.sleep(500);
            }
            throw new IOException("模型在3分钟内没有完成加载");
        }

        boolean isReady() { return ready.get() && process != null && process.isAlive(); }
        String apiKey() { return apiKey; }

        private void consume(InputStream input) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) lastLine.set(line);
            } catch (IOException ignored) { }
        }

        @Override public void close() {
            ready.set(false);
            Process p = process; process = null;
            if (p != null) {
                p.destroy();
                try { if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); p.destroyForcibly(); }
            }
        }

        private static void ensureLocalPortFree() throws IOException {
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(false);
                probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), LOCAL_PORT));
            } catch (BindException e) {
                throw new IOException("本机端口 " + LOCAL_PORT + " 已被旧窗口占用；请关闭旧版程序后重试");
            }
        }
    }

    private static final class SceneClient {
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new DirectProxySelector()).build();
        private static final URI HEALTH = URI.create("http://127.0.0.1:" + LOCAL_PORT + "/health");
        private static final URI CHAT = URI.create("http://127.0.0.1:" + LOCAL_PORT + "/v1/chat/completions");
        private static final Pattern CONTENT = Pattern.compile("\\\"content\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
        record SceneResponse(String raw, String display) { }

        static boolean health(String apiKey) {
            try {
                HttpRequest request = HttpRequest.newBuilder(HEALTH).timeout(Duration.ofSeconds(2))
                        .header("Authorization", "Bearer " + apiKey).GET().build();
                int code = CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                return code >= 200 && code < 300;
            } catch (Exception e) { return false; }
        }

        static SceneResponse describe(List<byte[]> frames, String apiKey, boolean quick) throws Exception {
            int maxTokens = quick ? 64 : 180;
            String prompt = quick ? DEMO_PROMPT : DETAILED_PROMPT;
            String raw = request(frames, apiKey, prompt, maxTokens);
            return new SceneResponse(raw, quick ? normalizeQuickDescription(raw) : raw);
        }

        static String describeActivity(List<byte[]> frames, String apiKey) throws Exception {
            return request(frames, apiKey, ACTIVITY_PROMPT, 220);
        }

        private static String request(List<byte[]> frames, String apiKey, String prompt, int maxTokens) throws Exception {
            StringBuilder body = new StringBuilder(1024 + frames.stream().mapToInt(f -> f.length * 2).sum());
            body.append("{\"model\":\"local\",\"stream\":false,\"temperature\":0.1,\"max_tokens\":").append(maxTokens).append(',')
                    .append("\"chat_template_kwargs\":{\"enable_thinking\":false},\"reasoning_effort\":\"none\",")
                    .append("\"messages\":[{\"role\":\"user\",\"content\":[")
                    .append("{\"type\":\"text\",\"text\":").append(jsonString(prompt)).append('}');
            for (byte[] frame : frames) {
                body.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,")
                        .append(Base64.getEncoder().encodeToString(frame)).append("\"}}");
            }
            body.append("]}]} ");
            HttpRequest request = HttpRequest.newBuilder(CHAT).timeout(Duration.ofMinutes(4))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IOException("本地模型返回 HTTP " + response.statusCode());
            Matcher matcher = CONTENT.matcher(response.body());
            String content = null;
            while (matcher.find()) content = unescapeJson(matcher.group(1));
            if (content == null || content.isBlank()) {
                if (Boolean.getBoolean("nova9.scene.selftest.debug"))
                    System.err.println("SYNTHETIC SELF-TEST RESPONSE: " + response.body());
                throw new IOException("本地模型没有返回文字");
            }
            return content.trim();
        }

        private static String normalizeQuickDescription(String content) {
            String one = content.replaceAll("\\s+", " ").trim()
                    .replaceFirst("^(?:观察|描述|场景)\\s*[：:]\\s*", "");
            for (int i = 0; i < one.length(); i++) {
                if ("。！？!?".indexOf(one.charAt(i)) >= 0) {
                    one = one.substring(0, i + 1);
                    break;
                }
            }
            if (one.length() > 60) one = one.substring(0, 59) + "…";
            return one;
        }
    }

    private static final class DirectProxySelector extends ProxySelector {
        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { }
    }

    private static final class ImagePanel extends JPanel {
        private volatile BufferedImage image;
        ImagePanel() { setBackground(new Color(25, 28, 32)); }
        void setImage(BufferedImage next) { image = next; repaint(); }
        @Override protected void paintComponent(Graphics raw) {
            super.paintComponent(raw);
            BufferedImage current = image;
            Graphics2D g = (Graphics2D) raw.create();
            if (current == null) {
                g.setColor(new Color(205, 210, 215));
                g.setFont(g.getFont().deriveFont(18f));
                String text = "最近3帧只保存在内存中";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, getHeight() / 2);
            } else {
                double scale = Math.min(getWidth() / (double) current.getWidth(), getHeight() / (double) current.getHeight());
                int w = (int) Math.round(current.getWidth() * scale), h = (int) Math.round(current.getHeight() * scale);
                g.drawImage(current, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            }
            g.dispose();
        }
    }

    private static void runSelfTest(Path home) {
        try {
            System.out.println("Checking official model hashes...");
            requireLocalFiles(home);
            verifySha(home.resolve("models").resolve("MiniCPM-V-4_6-Q4_K_M.gguf"), MODEL_SHA256);
            verifySha(home.resolve("models").resolve("mmproj-model-f16.gguf"), MMPROJ_SHA256);
            validateAndBuildStreamUri("http://192.168.1.8:8787/?token=0123456789abcdef0123456789abcdef");
            try {
                validateAndBuildStreamUri("http://8.8.8.8:8787/?token=0123456789abcdef0123456789abcdef");
                throw new IllegalStateException("公网地址没有被拒绝");
            } catch (IllegalArgumentException expected) { }

            long reference = 1_000_000_000L;
            if (hasFrameExpired(reference, reference + TimeUnit.MILLISECONDS.toNanos(9_999)))
                throw new IllegalStateException("未满10秒的画面被错误判定为过期");
            if (!hasFrameExpired(reference, reference + TimeUnit.SECONDS.toNanos(10)))
                throw new IllegalStateException("达到10秒的旧画面没有被判定为过期");
            FrameStore freshnessStore = new FrameStore();
            freshnessStore.replace(new byte[]{1, 2, 3});
            long received = freshnessStore.receivedAtNanos();
            if (received <= 0 || freshnessStore.copy() == null)
                throw new IllegalStateException("新帧时间戳没有保存");
            freshnessStore.clearImagePreservingClock();
            if (freshnessStore.copy() != null || freshnessStore.receivedAtNanos() != received)
                throw new IllegalStateException("旧画面清除没有保留重连检测时钟");

            System.out.println("Starting localhost MiniCPM-V for a synthetic-image test...");
            try (LocalModelServer server = new LocalModelServer(home)) {
                server.startAndWait();
                BufferedImage image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                g.setColor(new Color(245, 240, 225)); g.fillRect(0, 0, 640, 480);
                g.setColor(new Color(80, 120, 200)); g.fillRect(170, 210, 300, 25);
                g.setColor(Color.WHITE); g.fillOval(270, 150, 100, 90);
                g.setColor(Color.DARK_GRAY); g.drawOval(270, 150, 100, 90); g.dispose();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ImageIO.write(image, "jpg", bytes);
                byte[] jpeg = bytes.toByteArray();
                byte[] prepared = prepareSceneFrame(jpeg);
                zero(jpeg);
                byte[] second = Arrays.copyOf(prepared, prepared.length);
                byte[] third = Arrays.copyOf(prepared, prepared.length);
                long started = System.nanoTime();
                SceneClient.SceneResponse response = SceneClient.describe(
                        List.of(prepared, second, third), server.apiKey(), false);
                long elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
                zero(prepared); zero(second); zero(third);
                if (response.display().isBlank()) throw new IllegalStateException("场景模型没有输出");
                System.out.println("Local three-frame result (" + elapsed + " seconds): "
                        + response.display().replace('\n', ' '));
            }
            System.out.println("SELF-TEST OK: hashes, network guard, localhost server, and MiniCPM-V inference passed.");
        } catch (Exception e) {
            System.err.println("SELF-TEST FAILED: " + safeMessage(e));
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void verifySha(Path file, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
            zero(buffer);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expected)) throw new SecurityException("校验失败：" + file.getFileName());
    }

    private static byte[] prepareSceneFrame(byte[] jpeg) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (source == null) throw new IOException("不是有效 JPEG");
        int maxSide = 448;
        double scale = Math.min(1.0, maxSide / (double)Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int)Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int)Math.round(source.getHeight() * scale));
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16 * 1024, jpeg.length / 2));
        if (!ImageIO.write(resized, "jpg", out)) throw new IOException("JPEG编码器不可用");
        return out.toByteArray();
    }

    private static String jsonString(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }

    private static String unescapeJson(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) { out.append(c); continue; }
            char n = text.charAt(++i);
            switch (n) {
                case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                case 'b' -> out.append('\b'); case 'f' -> out.append('\f'); case '"' -> out.append('"');
                case '\\' -> out.append('\\'); case '/' -> out.append('/');
                case 'u' -> {
                    if (i + 4 < text.length()) { out.append((char)Integer.parseInt(text.substring(i + 1, i + 5), 16)); i += 4; }
                    else out.append('u');
                }
                default -> out.append(n);
            }
        }
        return out.toString();
    }

    private static ThreadFactory named(String name) {
        return r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; };
    }
    private static String randomToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        zero(bytes);
        return token;
    }
    private static void cancel(Future<?> future) { if (future != null) future.cancel(false); }
    private static void zero(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte)0); }
    private static String sanitizeServerLine(String line) {
        if (line == null || line.isBlank()) return "没有错误信息";
        return line.replaceAll("[A-Za-z]:\\\\[^ ]+", "[本地路径]").replaceAll("https?://\\S+", "[地址已隐藏]");
    }
    private static String safeMessage(Throwable error) {
        String text = error.getMessage();
        if (text == null || text.isBlank()) text = error.getClass().getSimpleName();
        return text.replaceAll("https?://\\S+", "[地址已隐藏]");
    }
}
