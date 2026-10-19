package local.nova9.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final long FRAME_INTERVAL_MS = 100L; // 10 fps

    private final AtomicBoolean encodingEnabled = new AtomicBoolean(false);
    private final AtomicReference<byte[]> latestFrame = new AtomicReference<>();

    private TextureView preview;
    private TextView status;
    private Button streamButton;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private boolean cameraOpening;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private LocalMjpegServer server;
    private long lastFrameTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        createInterface();
        startCameraThread();

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openWhenReady();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraThread == null) {
            startCameraThread();
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openWhenReady();
        }
    }

    @Override
    protected void onPause() {
        stopStreaming();
        closeCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopCameraThread();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openWhenReady();
        } else {
            setStatus("没有摄像头权限，应用无法工作。", true);
        }
    }

    private void createInterface() {
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        preview = new TextureView(this);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
        root.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setText("正在准备摄像头……");
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, padding, 0, padding);
        status.setTextIsSelectable(true);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        streamButton = new Button(this);
        streamButton.setText("开始局域网传输");
        streamButton.setEnabled(false);
        streamButton.setOnClickListener(view -> {
            if (server == null) {
                startStreaming();
            } else {
                stopStreaming();
            }
        });
        root.addView(streamButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void openWhenReady() {
        if (preview.isAvailable()) {
            openCamera();
        }
    }

    private void openCamera() {
        if (camera != null || cameraOpening || cameraHandler == null
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = findBackCamera(manager);
            if (cameraId == null) {
                setStatus("没有找到后置摄像头。", true);
                return;
            }
            cameraOpening = true;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice openedCamera) {
                    cameraOpening = false;
                    camera = openedCamera;
                    createCaptureSession(manager, cameraId);
                }

                @Override
                public void onDisconnected(CameraDevice disconnectedCamera) {
                    cameraOpening = false;
                    disconnectedCamera.close();
                    camera = null;
                    setStatus("摄像头连接已断开。", true);
                }

                @Override
                public void onError(CameraDevice failedCamera, int error) {
                    cameraOpening = false;
                    failedCamera.close();
                    camera = null;
                    setStatus("摄像头启动失败，错误代码：" + error, true);
                }
            }, cameraHandler);
        } catch (CameraAccessException error) {
            cameraOpening = false;
            setStatus("无法访问摄像头：" + error.getReason(), true);
        }
    }

    private String findBackCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    private void createCaptureSession(CameraManager manager, String cameraId) {
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                setStatus("摄像头没有提供视频格式信息。", true);
                return;
            }

            Size captureSize = chooseCaptureSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            imageReader = ImageReader.newInstance(
                    captureSize.getWidth(), captureSize.getHeight(), ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);

            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null || camera == null) {
                return;
            }
            texture.setDefaultBufferSize(captureSize.getWidth(), captureSize.getHeight());
            Surface previewSurface = new Surface(texture);

            camera.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (camera == null) {
                                return;
                            }
                            captureSession = session;
                            try {
                                CaptureRequest.Builder request =
                                        camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                request.addTarget(previewSurface);
                                request.addTarget(imageReader.getSurface());
                                request.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                Range<Integer> fpsRange = chooseFpsRange(characteristics);
                                if (fpsRange != null) {
                                    request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                                }
                                session.setRepeatingRequest(request.build(), null, cameraHandler);
                                runOnUiThread(() -> {
                                    streamButton.setEnabled(true);
                                    status.setText("摄像头已就绪。画面不会保存。\n"
                                            + captureSize.getWidth() + " × " + captureSize.getHeight()
                                            + "，最多 10 fps");
                                });
                            } catch (CameraAccessException error) {
                                setStatus("无法启动预览：" + error.getReason(), true);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            setStatus("摄像头画面配置失败。", true);
                        }
                    },
                    cameraHandler);
        } catch (CameraAccessException error) {
            setStatus("无法读取摄像头配置：" + error.getReason(), true);
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            if (!encodingEnabled.get() || now - lastFrameTime < FRAME_INTERVAL_MS) {
                return;
            }
            lastFrameTime = now;
            latestFrame.set(YuvJpegEncoder.encode(image, 65));
        } catch (RuntimeException error) {
            encodingEnabled.set(false);
            runOnUiThread(() -> {
                stopStreaming();
                status.setText("画面编码失败：" + error.getMessage());
            });
        } finally {
            image.close();
        }
    }

    private void startStreaming() {
        if (camera == null) {
            setStatus("摄像头尚未就绪。", true);
            return;
        }
        server = new LocalMjpegServer(latestFrame);
        try {
            String url = server.start(this);
            encodingEnabled.set(true);
            streamButton.setText("停止传输");
            status.setText("只在当前 Wi-Fi 内访问：\n" + url
                    + "\n\n在电脑浏览器中输入完整地址。不要公开这个地址。");
        } catch (IOException error) {
            server.stop();
            server = null;
            setStatus(error.getMessage(), false);
        }
    }

    private void stopStreaming() {
        encodingEnabled.set(false);
        if (server != null) {
            server.stop();
            server = null;
        }
        latestFrame.set(null);
        if (streamButton != null) {
            streamButton.setText("开始局域网传输");
            if (camera != null) {
                status.setText("传输已停止。摄像头预览仍在本机显示。\n退出应用会释放摄像头。");
            }
        }
    }

    private void closeCamera() {
        cameraOpening = false;
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (streamButton != null) {
            streamButton.setEnabled(false);
        }
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("nova9-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void setStatus(String message, boolean disableButton) {
        runOnUiThread(() -> {
            status.setText(message);
            if (disableButton) {
                streamButton.setEnabled(false);
            }
        });
    }

    private static Size chooseCaptureSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return new Size(640, 480);
        }
        Size target = new Size(1280, 720);
        for (Size size : sizes) {
            if (size.equals(target)) {
                return size;
            }
        }
        Size best = sizes[0];
        long targetPixels = (long) target.getWidth() * target.getHeight();
        long bestDifference = Math.abs((long) best.getWidth() * best.getHeight() - targetPixels);
        for (int index = 1; index < sizes.length; index++) {
            Size candidate = sizes[index];
            long difference = Math.abs(
                    (long) candidate.getWidth() * candidate.getHeight() - targetPixels);
            if (difference < bestDifference) {
                best = candidate;
                bestDifference = difference;
            }
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    private static Range<Integer> chooseFpsRange(CameraCharacteristics characteristics) {
        Range<Integer>[] ranges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            return null;
        }
        Range<Integer> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Range<Integer> range : ranges) {
            if (range.getLower() <= 10 && range.getUpper() >= 10) {
                int score = range.getUpper() - range.getLower();
                if (score < bestScore) {
                    best = range;
                    bestScore = score;
                }
            }
        }
        return best;
    }
}
