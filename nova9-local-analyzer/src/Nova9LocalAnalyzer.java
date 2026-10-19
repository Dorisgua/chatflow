import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Nova 9 local camera analyzer.
 *
 * Data path: private-LAN MJPEG -> memory -> local ONNX Runtime -> Swing window.
 * This program does not contain any upload, cloud API, analytics, or image-saving code.
 */
public final class Nova9LocalAnalyzer {
    private static final int MODEL_SIZE = 640;
    private static final float CONFIDENCE = 0.35f;
    private static final String[] LABELS = {
            "人", "自行车", "汽车", "摩托车", "飞机", "公交车", "火车", "卡车", "船", "交通灯",
            "消防栓", "停车标志", "停车计时器", "长椅", "鸟", "猫", "狗", "马", "羊", "牛",
            "大象", "熊", "斑马", "长颈鹿", "背包", "雨伞", "手提包", "领带", "行李箱", "飞盘",
            "滑雪板", "单板滑雪", "球", "风筝", "棒球棒", "棒球手套", "滑板", "冲浪板", "网球拍", "瓶子",
            "酒杯", "杯子", "叉子", "刀", "勺子", "碗", "香蕉", "苹果", "三明治", "橙子",
            "西兰花", "胡萝卜", "热狗", "披萨", "甜甜圈", "蛋糕", "椅子", "沙发", "盆栽", "床",
            "餐桌", "马桶", "电视", "笔记本电脑", "鼠标", "遥控器", "键盘", "手机", "微波炉", "烤箱",
            "烤面包机", "水槽", "冰箱", "书", "时钟", "花瓶", "剪刀", "泰迪熊", "吹风机", "牙刷"
    };

    private final JFrame window = new JFrame("Nova 9 本地 YOLO 分析器");
    private final JTextField urlField = new JTextField();
    private final JButton startButton = new JButton("开始本地识别");
    private final JButton stopButton = new JButton("停止并清除画面");
    private final JLabel statusLabel = new JLabel("等待开始：请粘贴手机应用显示的完整地址");
    private final JLabel privacyLabel = new JLabel("只连接局域网私有地址；画面仅在内存中处理，不保存到硬盘");
    private final JTextArea resultArea = new JTextArea();
    private final ImagePanel imagePanel = new ImagePanel();

    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private final AtomicBoolean inferenceBusy = new AtomicBoolean(false);
    private final ExecutorService streamExecutor = Executors.newSingleThreadExecutor(named("camera-stream"));
    private final ScheduledExecutorService inferenceExecutor = Executors.newSingleThreadScheduledExecutor(named("local-yolo"));

    private volatile MjpegClient mjpegClient;
    private volatile Future<?> streamTask;
    private volatile ScheduledFuture<?> inferenceTask;
    private volatile YoloModel model;

    private final Path appHome;

    private Nova9LocalAnalyzer(Path appHome) {
        this.appHome = appHome;
        buildWindow();
    }

    public static void main(String[] args) {
        Path home = Path.of(System.getProperty("nova9.app.home", ".")).toAbsolutePath().normalize();
        if (args.length == 1 && "--self-test".equals(args[0])) {
            runSelfTest(home);
            return;
        }
        System.out.println("Java started; creating the analyzer window...");
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace(System.err);
            try {
                JOptionPane.showMessageDialog(null, safeMessage(error), "Analyzer startup error", JOptionPane.ERROR_MESSAGE);
            } catch (Throwable ignored) { }
        });
        SwingUtilities.invokeLater(() -> {
            try {
                new Nova9LocalAnalyzer(home).show();
            } catch (Throwable error) {
                error.printStackTrace(System.err);
                JOptionPane.showMessageDialog(null, safeMessage(error), "Analyzer startup error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private static void runSelfTest(Path home) {
        try {
            URI accepted = validateAndBuildStreamUri("http://192.168.1.20:8787/?token=0123456789abcdef0123456789abcdef");
            if (!"/stream".equals(accepted.getPath())) throw new IllegalStateException("局域网地址转换失败");
            for (String rejected : List.of(
                    "https://192.168.1.20:8787/?token=0123456789abcdef0123456789abcdef",
                    "http://8.8.8.8:8787/?token=0123456789abcdef0123456789abcdef",
                    "http://example.com:8787/?token=0123456789abcdef0123456789abcdef",
                    "http://192.168.1.20:8787/?token=bad")) {
                try {
                    validateAndBuildStreamUri(rejected);
                    throw new IllegalStateException("危险地址没有被拒绝");
                } catch (IllegalArgumentException expected) { /* expected */ }
            }
            BufferedImage sample = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = sample.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, sample.getWidth(), sample.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.fillRect(160, 100, 320, 280);
            graphics.dispose();
            try (YoloModel testModel = new YoloModel(home.resolve("models").resolve("yolo26n.onnx"))) {
                List<Detection> ignored = testModel.detect(sample, CONFIDENCE);
            }
            System.out.println("SELF-TEST OK: private-network guard, native ONNX runtime, model load, and inference all passed.");
        } catch (Exception e) {
            System.err.println("SELF-TEST FAILED: " + safeMessage(e));
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void buildWindow() {
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.setMinimumSize(new Dimension(900, 620));
        window.setSize(1100, 760);
        window.setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new BorderLayout(8, 8));
        JLabel instruction = new JLabel("手机地址：");
        instruction.setLabelFor(urlField);
        urlField.setToolTipText("例如：http://192.168.x.x:8787/?token=32位字符");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        stopButton.setEnabled(false);
        buttons.add(startButton);
        buttons.add(stopButton);
        top.add(instruction, BorderLayout.WEST);
        top.add(urlField, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.EAST);

        resultArea.setEditable(false);
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        resultArea.setText("识别结果会显示在这里。\n模型只能识别 COCO 数据集中的 80 类常见物体。");
        JScrollPane results = new JScrollPane(resultArea);
        results.setPreferredSize(new Dimension(260, 100));

        JPanel bottom = new JPanel(new GridLayout(2, 1, 2, 2));
        privacyLabel.setForeground(new Color(20, 100, 50));
        bottom.add(statusLabel);
        bottom.add(privacyLabel);

        root.add(top, BorderLayout.NORTH);
        root.add(imagePanel, BorderLayout.CENTER);
        root.add(results, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        window.setContentPane(root);

        startButton.addActionListener(e -> start());
        stopButton.addActionListener(e -> stop(true));
        urlField.addActionListener(e -> start());
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });
    }

    private void show() {
        window.setAlwaysOnTop(true);
        window.setVisible(true);
        window.setExtendedState(JFrame.NORMAL);
        window.toFront();
        window.requestFocus();
        System.out.println("Analyzer window created successfully.");
        javax.swing.Timer releaseTopMost = new javax.swing.Timer(2000, e -> window.setAlwaysOnTop(false));
        releaseTopMost.setRepeats(false);
        releaseTopMost.start();
    }

    private void start() {
        if (mjpegClient != null) return;
        final URI streamUri;
        try {
            streamUri = validateAndBuildStreamUri(urlField.getText().trim());
        } catch (IllegalArgumentException ex) {
            showError(ex.getMessage());
            return;
        }

        Path modelPath = appHome.resolve("models").resolve("yolo26n.onnx");
        if (!Files.isRegularFile(modelPath)) {
            showError("找不到本地模型：" + modelPath);
            return;
        }

        setRunningUi(true);
        status("正在从硬盘加载本地 YOLO 模型（第一次可能需要几秒）……");
        CompletableFuture.runAsync(() -> {
            try {
                if (model == null) model = new YoloModel(modelPath);
                SwingUtilities.invokeLater(() -> beginCamera(streamUri));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setRunningUi(false);
                    showError("本地模型加载失败：" + safeMessage(ex));
                });
            }
        });
    }

    private void beginCamera(URI streamUri) {
        // The token is no longer needed in the visible text box after validation.
        urlField.setText("已安全读取地址；令牌未写入日志或文件");
        MjpegClient client = new MjpegClient(streamUri, latestJpeg, this::status);
        mjpegClient = client;
        streamTask = streamExecutor.submit(client);
        inferenceTask = inferenceExecutor.scheduleWithFixedDelay(this::analyzeLatest, 0, 1, TimeUnit.SECONDS);
        status("正在连接 Nova 9；之后每秒分析约 1 帧……");
    }

    private void analyzeLatest() {
        if (!inferenceBusy.compareAndSet(false, true)) return;
        try {
            byte[] jpeg = latestJpeg.getAndSet(null);
            if (jpeg == null) return;
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(jpeg));
            Arrays.fill(jpeg, (byte) 0);
            if (source == null) throw new IOException("摄像头返回的不是有效 JPEG 画面");

            long started = System.nanoTime();
            List<Detection> detections = model.detect(source, CONFIDENCE);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            RenderedFrame frame = new RenderedFrame(source, detections);
            SwingUtilities.invokeLater(() -> {
                imagePanel.setFrame(frame);
                resultArea.setText(formatResults(detections, elapsedMs));
                status("正在本地识别：最近一次用时 " + elapsedMs + " 毫秒；未保存画面");
            });
        } catch (Exception ex) {
            status("这一帧处理失败，程序会继续：" + safeMessage(ex));
        } finally {
            inferenceBusy.set(false);
        }
    }

    private void stop(boolean updateUi) {
        ScheduledFuture<?> scheduled = inferenceTask;
        inferenceTask = null;
        if (scheduled != null) scheduled.cancel(false);
        MjpegClient client = mjpegClient;
        mjpegClient = null;
        if (client != null) client.close();
        Future<?> task = streamTask;
        streamTask = null;
        if (task != null) task.cancel(true);
        byte[] bytes = latestJpeg.getAndSet(null);
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
        if (updateUi) {
            imagePanel.setFrame(null);
            resultArea.setText("已停止；内存中的最近画面已清除。\n要再次启动，请重新粘贴手机地址。");
            urlField.setText("");
            setRunningUi(false);
            status("已停止并清除画面");
        }
    }

    private void shutdown() {
        stop(false);
        streamExecutor.shutdownNow();
        inferenceExecutor.shutdownNow();
        YoloModel loaded = model;
        model = null;
        if (loaded != null) loaded.close();
        window.dispose();
        System.exit(0);
    }

    private void setRunningUi(boolean running) {
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        urlField.setEditable(!running);
    }

    private void status(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    private void showError(String message) {
        status("无法启动：" + message);
        JOptionPane.showMessageDialog(window, message, "无法启动", JOptionPane.ERROR_MESSAGE);
    }

    private static String formatResults(List<Detection> detections, long elapsedMs) {
        StringBuilder out = new StringBuilder();
        out.append("本地推理：").append(elapsedMs).append(" ms\n");
        out.append("检测到：").append(detections.size()).append(" 个物体\n\n");
        if (detections.isEmpty()) return out.append("没有达到 35% 置信度的结果").toString();
        detections.stream().sorted(Comparator.comparingDouble((Detection d) -> d.score).reversed())
                .forEach(d -> out.append(String.format(Locale.ROOT, "%s  %.0f%%%n", d.label(), d.score * 100)));
        return out.toString();
    }

    private static URI validateAndBuildStreamUri(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("请先粘贴手机应用显示的完整地址");
        final URI uri;
        try { uri = URI.create(value); }
        catch (Exception e) { throw new IllegalArgumentException("地址格式不正确"); }
        if (!"http".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("只允许 http 局域网地址");
        if (uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException("地址包含不允许的部分");
        String host = uri.getHost();
        if (host == null || !isPrivateIpv4(host)) throw new IllegalArgumentException("只允许 10.x、172.16–31.x 或 192.168.x 的私有 IPv4 地址");
        int port = uri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("地址中缺少有效端口");

        String token = null;
        String query = uri.getRawQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                int equal = part.indexOf('=');
                if (equal > 0 && "token".equals(part.substring(0, equal))) token = part.substring(equal + 1);
            }
        }
        if (token == null || !token.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("地址里的安全令牌不正确，应为 32 位十六进制字符");
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

    private static ThreadFactory named(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private static String safeMessage(Throwable t) {
        String text = t.getMessage();
        if (text == null || text.isBlank()) text = t.getClass().getSimpleName();
        // Never echo a URL/token from a network exception into the UI.
        return text.replaceAll("https?://\\S+", "[局域网地址已隐藏]");
    }

    private static final class MjpegClient implements Runnable, AutoCloseable {
        private final URI streamUri;
        private final AtomicReference<byte[]> latest;
        private final java.util.function.Consumer<String> status;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile HttpURLConnection connection;

        MjpegClient(URI streamUri, AtomicReference<byte[]> latest, java.util.function.Consumer<String> status) {
            this.streamUri = streamUri;
            this.latest = latest;
            this.status = status;
        }

        @Override public void run() {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try { readStream(); }
                catch (Exception e) {
                    if (running.get()) status.accept("摄像头连接中断，1 秒后重试：" + safeMessage(e));
                } finally {
                    HttpURLConnection c = connection;
                    connection = null;
                    if (c != null) c.disconnect();
                }
                if (running.get()) {
                    try { Thread.sleep(1000); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            }
        }

        private void readStream() throws IOException {
            HttpURLConnection c = (HttpURLConnection) streamUri.toURL().openConnection(Proxy.NO_PROXY);
            connection = c;
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(4000);
            c.setReadTimeout(7000);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "multipart/x-mixed-replace");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("手机返回 HTTP " + code);
            String contentType = c.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("multipart/"))
                throw new IOException("手机返回的不是 MJPEG 视频流");
            status.accept("Nova 9 已连接；电脑每秒取约 1 帧做本地识别");

            try (InputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024)) {
                while (running.get()) {
                    String line = readAsciiLine(in, 2048);
                    if (line == null) throw new EOFException("视频流结束");
                    if (!line.startsWith("--")) continue;
                    int length = -1;
                    while (true) {
                        line = readAsciiLine(in, 2048);
                        if (line == null) throw new EOFException("帧头不完整");
                        if (line.isEmpty()) break;
                        int colon = line.indexOf(':');
                        if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                            try { length = Integer.parseInt(line.substring(colon + 1).trim()); }
                            catch (NumberFormatException e) { throw new IOException("无效的画面长度"); }
                        }
                    }
                    if (length < 100 || length > 5_000_000) throw new IOException("画面大小超出安全范围");
                    byte[] frame = in.readNBytes(length);
                    if (frame.length != length) throw new EOFException("画面数据不完整");
                    byte[] old = latest.getAndSet(frame);
                    if (old != null) Arrays.fill(old, (byte) 0);
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
                if (b < 0) return out.size() == 0 ? null : out.toString(java.nio.charset.StandardCharsets.US_ASCII);
                if (previous == '\r' && b == '\n') {
                    byte[] bytes = out.toByteArray();
                    return new String(bytes, 0, Math.max(0, bytes.length - 1), java.nio.charset.StandardCharsets.US_ASCII);
                }
                out.write(b);
                previous = b;
            }
            throw new IOException("视频流头部过长");
        }
    }

    private static final class YoloModel implements AutoCloseable {
        private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
        private final OrtSession session;
        private final String inputName;

        YoloModel(Path modelPath) throws OrtException {
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setIntraOpNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
            session = environment.createSession(modelPath.toString(), options);
            inputName = session.getInputNames().iterator().next();
            NodeInfo info = session.getInputInfo().get(inputName);
            if (!(info.getInfo() instanceof TensorInfo tensor) || tensor.getShape().length != 4)
                throw new OrtException("模型输入格式不是图像张量");
        }

        List<Detection> detect(BufferedImage source, float threshold) throws OrtException {
            Letterbox input = makeInput(source);
            try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.data), new long[]{1, 3, MODEL_SIZE, MODEL_SIZE});
                 OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
                OnnxTensor output = (OnnxTensor) result.get(0);
                long[] shape = output.getInfo().getShape();
                FloatBuffer buffer = output.getFloatBuffer();
                float[] values = new float[buffer.remaining()];
                buffer.get(values);
                List<Detection> found = parse(values, shape, input, source.getWidth(), source.getHeight(), threshold);
                return nonMaximumSuppression(found, 0.55f);
            }
        }

        private static Letterbox makeInput(BufferedImage source) {
            float scale = Math.min(MODEL_SIZE / (float) source.getWidth(), MODEL_SIZE / (float) source.getHeight());
            int width = Math.max(1, Math.round(source.getWidth() * scale));
            int height = Math.max(1, Math.round(source.getHeight() * scale));
            int padX = (MODEL_SIZE - width) / 2;
            int padY = (MODEL_SIZE - height) / 2;
            BufferedImage resized = new BufferedImage(MODEL_SIZE, MODEL_SIZE, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setColor(new Color(114, 114, 114));
            g.fillRect(0, 0, MODEL_SIZE, MODEL_SIZE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, padX, padY, width, height, null);
            g.dispose();
            int[] rgb = resized.getRGB(0, 0, MODEL_SIZE, MODEL_SIZE, null, 0, MODEL_SIZE);
            int plane = MODEL_SIZE * MODEL_SIZE;
            float[] data = new float[3 * plane];
            for (int i = 0; i < plane; i++) {
                data[i] = ((rgb[i] >> 16) & 255) / 255f;
                data[plane + i] = ((rgb[i] >> 8) & 255) / 255f;
                data[2 * plane + i] = (rgb[i] & 255) / 255f;
            }
            return new Letterbox(data, scale, padX, padY);
        }

        private static List<Detection> parse(float[] v, long[] shape, Letterbox box, int originalW, int originalH, float threshold) throws OrtException {
            if (shape.length != 3 || shape[0] != 1) throw new OrtException("不支持的模型输出形状：" + Arrays.toString(shape));
            List<Detection> out = new ArrayList<>();
            if (shape[2] == 6) {
                int count = Math.toIntExact(shape[1]);
                for (int i = 0; i < count; i++) addEndToEnd(out, v, i * 6, box, originalW, originalH, threshold);
            } else if (shape[1] == 6) {
                int count = Math.toIntExact(shape[2]);
                for (int i = 0; i < count; i++) {
                    float[] row = new float[6];
                    for (int j = 0; j < 6; j++) row[j] = v[j * count + i];
                    addEndToEnd(out, row, 0, box, originalW, originalH, threshold);
                }
            } else if (shape[1] >= 5 && shape[1] <= 4 + LABELS.length) {
                int features = Math.toIntExact(shape[1]);
                int count = Math.toIntExact(shape[2]);
                for (int i = 0; i < count; i++) {
                    int bestClass = -1;
                    float best = threshold;
                    for (int c = 4; c < features; c++) {
                        float score = v[c * count + i];
                        if (score > best) { best = score; bestClass = c - 4; }
                    }
                    if (bestClass >= 0) {
                        float cx = v[i], cy = v[count + i], w = v[2 * count + i], h = v[3 * count + i];
                        addBox(out, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, best, bestClass, box, originalW, originalH);
                    }
                }
            } else throw new OrtException("不支持的模型输出形状：" + Arrays.toString(shape));
            return out;
        }

        private static void addEndToEnd(List<Detection> out, float[] v, int p, Letterbox b, int w, int h, float threshold) {
            float score = v[p + 4];
            int classId = Math.round(v[p + 5]);
            if (score >= threshold) addBox(out, v[p], v[p + 1], v[p + 2], v[p + 3], score, classId, b, w, h);
        }

        private static void addBox(List<Detection> out, float x1, float y1, float x2, float y2, float score, int classId,
                                   Letterbox b, int w, int h) {
            if (classId < 0 || classId >= LABELS.length) return;
            x1 = clamp((x1 - b.padX) / b.scale, 0, w - 1);
            y1 = clamp((y1 - b.padY) / b.scale, 0, h - 1);
            x2 = clamp((x2 - b.padX) / b.scale, 0, w - 1);
            y2 = clamp((y2 - b.padY) / b.scale, 0, h - 1);
            if (x2 > x1 + 1 && y2 > y1 + 1) out.add(new Detection(x1, y1, x2, y2, score, classId));
        }

        private static List<Detection> nonMaximumSuppression(List<Detection> input, float iouLimit) {
            input.sort(Comparator.comparingDouble((Detection d) -> d.score).reversed());
            List<Detection> kept = new ArrayList<>();
            for (Detection candidate : input) {
                boolean duplicate = false;
                for (Detection selected : kept) {
                    if (candidate.classId == selected.classId && iou(candidate, selected) > iouLimit) { duplicate = true; break; }
                }
                if (!duplicate) kept.add(candidate);
                if (kept.size() >= 100) break;
            }
            return kept;
        }

        private static float iou(Detection a, Detection b) {
            float iw = Math.max(0, Math.min(a.x2, b.x2) - Math.max(a.x1, b.x1));
            float ih = Math.max(0, Math.min(a.y2, b.y2) - Math.max(a.y1, b.y1));
            float intersection = iw * ih;
            float union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - intersection;
            return union <= 0 ? 0 : intersection / union;
        }

        private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
        @Override public void close() { try { session.close(); } catch (Exception ignored) {} }
    }

    private static final class ImagePanel extends JPanel {
        private volatile RenderedFrame frame;
        ImagePanel() { setBackground(new Color(25, 28, 32)); }
        void setFrame(RenderedFrame value) { frame = value; repaint(); }
        @Override protected void paintComponent(Graphics raw) {
            super.paintComponent(raw);
            RenderedFrame current = frame;
            Graphics2D g = (Graphics2D) raw.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (current == null) {
                g.setColor(new Color(200, 205, 210));
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 18f));
                String text = "画面不会保存；停止后从内存清除";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, getHeight() / 2);
                g.dispose();
                return;
            }
            BufferedImage image = current.image;
            double scale = Math.min(getWidth() / (double) image.getWidth(), getHeight() / (double) image.getHeight());
            int dw = (int) Math.round(image.getWidth() * scale), dh = (int) Math.round(image.getHeight() * scale);
            int ox = (getWidth() - dw) / 2, oy = (getHeight() - dh) / 2;
            g.drawImage(image, ox, oy, dw, dh, null);
            g.setStroke(new BasicStroke(3f));
            g.setFont(g.getFont().deriveFont(Font.BOLD, 14f));
            for (Detection d : current.detections) {
                int x = ox + (int) Math.round(d.x1 * scale), y = oy + (int) Math.round(d.y1 * scale);
                int w = (int) Math.round((d.x2 - d.x1) * scale), h = (int) Math.round((d.y2 - d.y1) * scale);
                Color color = Color.getHSBColor((d.classId * 0.071f) % 1f, 0.85f, 1f);
                g.setColor(color);
                g.drawRect(x, y, w, h);
                String label = String.format(Locale.ROOT, "%s %.0f%%", d.label(), d.score * 100);
                FontMetrics fm = g.getFontMetrics();
                int labelW = fm.stringWidth(label) + 10, labelH = fm.getHeight() + 4;
                int labelY = Math.max(oy, y - labelH);
                g.fillRect(x, labelY, labelW, labelH);
                g.setColor(Color.BLACK);
                g.drawString(label, x + 5, labelY + fm.getAscent() + 2);
            }
            g.dispose();
        }
    }

    private record Letterbox(float[] data, float scale, int padX, int padY) {}
    private record Detection(float x1, float y1, float x2, float y2, float score, int classId) {
        String label() { return LABELS[classId]; }
    }
    private record RenderedFrame(BufferedImage image, List<Detection> detections) {}
}
