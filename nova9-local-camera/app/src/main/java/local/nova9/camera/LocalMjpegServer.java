package local.nova9.camera;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

final class LocalMjpegServer {
    private static final int PORT = 8787;
    private static final String BOUNDARY = "nova9frame";

    private final AtomicReference<byte[]> latestFrame;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final String token = createToken();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Inet4Address bindAddress;

    LocalMjpegServer(AtomicReference<byte[]> latestFrame) {
        this.latestFrame = latestFrame;
    }

    String start(Context context) throws IOException {
        bindAddress = findWifiAddress(context);
        if (bindAddress == null) {
            throw new IOException("请先连接家庭 Wi-Fi");
        }

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddress, PORT));
        running = true;

        acceptThread = new Thread(this::acceptLoop, "nova9-local-server");
        acceptThread.start();
        return "http://" + bindAddress.getHostAddress() + ":" + PORT + "/?token=" + token;
    }

    void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        clients.shutdownNow();
        latestFrame.set(null);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                clients.execute(() -> handle(socket));
            } catch (IOException error) {
                if (running) {
                    stop();
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(5000);
            InetAddress remote = connection.getInetAddress();
            if (!(remote.isSiteLocalAddress() || remote.isLoopbackAddress())) {
                sendText(connection.getOutputStream(), "403 Forbidden", "仅允许局域网设备访问");
                return;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.length() > 2048) {
                sendText(connection.getOutputStream(), "400 Bad Request", "无效请求");
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"GET".equals(parts[0])) {
                sendText(connection.getOutputStream(), "405 Method Not Allowed", "只接受 GET 请求");
                return;
            }

            String target = parts[1];
            if (!token.equals(readToken(target))) {
                sendText(connection.getOutputStream(), "403 Forbidden", "连接密码错误");
                return;
            }

            if (target.startsWith("/stream")) {
                stream(connection.getOutputStream());
            } else {
                sendViewer(connection.getOutputStream());
            }
        } catch (IOException ignored) {
            // A browser closing the page is a normal end to a stream.
        }
    }

    private void sendViewer(OutputStream output) throws IOException {
        String html = "<!doctype html><html lang=\"zh-CN\"><head>"
                + "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                + "<meta name=\"referrer\" content=\"no-referrer\"><title>Nova 9 本地摄像头</title>"
                + "<style>body{margin:0;background:#111;color:#eee;font-family:sans-serif;text-align:center}"
                + "h1{font-size:20px}img{max-width:100vw;max-height:82vh}p{color:#aaa}</style></head>"
                + "<body><h1>Nova 9 本地摄像头</h1><img alt=\"实时画面\" src=\"/stream?token="
                + token + "\"><p>画面只来自当前局域网连接；关闭手机应用即停止。</p></body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, "200 OK", "text/html; charset=utf-8", body.length);
        output.write(body);
        output.flush();
    }

    private void stream(OutputStream output) throws IOException {
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                + "Pragma: no-cache\r\n"
                + "X-Content-Type-Options: nosniff\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));

        byte[] previous = null;
        while (running && !Thread.currentThread().isInterrupted()) {
            byte[] frame = latestFrame.get();
            if (frame != null && frame != previous) {
                String part = "--" + BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + frame.length + "\r\n\r\n";
                output.write(part.getBytes(StandardCharsets.US_ASCII));
                output.write(frame);
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                previous = frame;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void sendText(OutputStream output, String status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, status, "text/plain; charset=utf-8", body.length);
        output.write(body);
        output.flush();
    }

    private static void writeHeaders(OutputStream output, String status, String type, int length)
            throws IOException {
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
    }

    private static String readToken(String target) {
        int queryStart = target.indexOf('?');
        if (queryStart < 0 || queryStart == target.length() - 1) {
            return "";
        }
        String[] pairs = target.substring(queryStart + 1).split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2 && "token".equals(keyValue[0])) {
                // Tokens are lowercase hexadecimal and never require URL decoding.
                return keyValue[1];
            }
        }
        return "";
    }

    private static String createToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static Inet4Address findWifiAddress(Context context) {
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return null;
        }

        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue;
            }
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) {
                continue;
            }
            for (LinkAddress linkAddress : properties.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                    return (Inet4Address) address;
                }
            }
        }
        return null;
    }
}
