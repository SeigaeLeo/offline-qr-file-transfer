package com.qtx1.receiver;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Size;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.CameraFilter;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import zxingcpp.BarcodeReader;

public final class MainActivity extends ComponentActivity {
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String APP_URL = "https://" + APP_HOST + "/assets/index.html";
    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static final int SAVE_DOCUMENT_REQUEST = 1002;
    private static final long MAX_FILE_BYTES = 25L * 1024L * 1024L;
    private static final int CACHED_ROI_FAILURE_LIMIT = 2;

    private FrameLayout rootLayout;
    private WebView webView;
    private FrameLayout nativePreviewContainer;
    private PreviewView nativePreview;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private BarcodeReader barcodeReader;
    private final AtomicBoolean analyzerBusy = new AtomicBoolean(false);

    private PermissionRequest pendingWebPermission;
    private boolean nativePermissionPending;
    private boolean nativeScannerRunning;
    private boolean nativeTimingEnabled;
    private String pendingNativeCameraId = "";
    private String activeNativeCameraId = "";
    private String nativeRelocationMode = "adaptive";
    private int nativeRelocationInterval = 30;
    private long nativeFrameNumber;
    private long previousAnalysisNanos;
    private int cachedRoiFailures;
    private int failureRelocateCount;
    private Rect cachedCrop;
    private int cachedImageWidth;
    private int cachedImageHeight;
    private int cachedRotation;
    private double cachedSizeRatio = Double.NaN;
    private String lastDeliveredRaw = "";
    private long lastDeliveredNanos;

    private File pendingSaveFile;
    private String pendingSaveName;
    private String pendingSaveMime;
    private OutputStream pendingSaveStream;
    private long pendingExpectedSize;
    private long pendingWrittenSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(7, 17, 31));
        getWindow().setNavigationBarColor(Color.rgb(7, 17, 31));

        cameraExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qtx-zxing-analyzer");
            thread.setPriority(Thread.NORM_PRIORITY + 1);
            return thread;
        });
        configureBarcodeReader();
        createLayout();
        configureWebView();
        webView.loadUrl(APP_URL);
    }

    private void createLayout() {
        rootLayout = new FrameLayout(this);
        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(webView);

        nativePreviewContainer = new FrameLayout(this);
        nativePreviewContainer.setVisibility(View.GONE);
        nativePreviewContainer.setBackgroundColor(Color.BLACK);
        nativePreview = new PreviewView(this);
        nativePreview.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        nativePreview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        nativePreviewContainer.addView(nativePreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        nativePreviewContainer.addView(new ScannerOverlayView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(nativePreviewContainer, new FrameLayout.LayoutParams(1, 1));
        setContentView(rootLayout);
    }

    private void configureBarcodeReader() {
        BarcodeReader.Options options = new BarcodeReader.Options();
        options.setFormats(Collections.singleton(BarcodeReader.Format.QR_CODE));
        options.setTryHarder(false);
        options.setTryRotate(false);
        options.setTryInvert(false);
        options.setTryDownscale(false);
        options.setTryDenoise(false);
        options.setBinarizer(BarcodeReader.Binarizer.LOCAL_AVERAGE);
        options.setMaxNumberOfSymbols(1);
        options.setReturnErrors(false);
        barcodeReader = new BarcodeReader(options);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(true);

        WebView.setWebContentsDebuggingEnabled(false);
        webView.addJavascriptInterface(new NativeBridge(), "AndroidBridge");

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return isAppUri(request.getUrl()) ? assetLoader.shouldInterceptRequest(request.getUrl()) : blockedResponse();
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                Uri uri = Uri.parse(url);
                return isAppUri(uri) ? assetLoader.shouldInterceptRequest(uri) : blockedResponse();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isAppUri(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isAppUri(Uri.parse(url));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermissionRequest(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermission == request) pendingWebPermission = null;
            }
        });
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        if (!isAppUri(request.getOrigin()) || !requestsVideoCapture(request)) {
            request.deny();
            return;
        }
        pendingWebPermission = request;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            grantPendingCameraPermission();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private static boolean requestsVideoCapture(PermissionRequest request) {
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) return true;
        }
        return false;
    }

    private void grantPendingCameraPermission() {
        PermissionRequest request = pendingWebPermission;
        pendingWebPermission = null;
        if (request != null) request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            grantPendingCameraPermission();
            if (nativePermissionPending) {
                nativePermissionPending = false;
                startNativeScannerInternal(pendingNativeCameraId);
            }
        } else {
            if (pendingWebPermission != null) {
                pendingWebPermission.deny();
                pendingWebPermission = null;
            }
            nativePermissionPending = false;
            sendScannerError("需要相机权限才能使用原生二维码扫描");
        }
    }

    private void requestNativeScanner(String cameraId) {
        pendingNativeCameraId = cameraId == null ? "" : cameraId;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startNativeScannerInternal(pendingNativeCameraId);
        } else {
            nativePermissionPending = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private void startNativeScannerInternal(String cameraId) {
        resetNativeTracking();
        nativeScannerRunning = true;
        nativePreviewContainer.setVisibility(View.VISIBLE);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                cameraProvider.unbindAll();

                Preview preview = new Preview.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setTargetRotation(nativePreview.getDisplay().getRotation())
                        .build();
                preview.setSurfaceProvider(nativePreview.getSurfaceProvider());

                imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setTargetRotation(nativePreview.getDisplay().getRotation())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                CameraSelector selector = selectorForCamera(cameraId);
                cameraProvider.bindToLifecycle(this, selector, preview, imageAnalysis);
                activeNativeCameraId = cameraId == null ? "" : cameraId;
                sendJavascript("window.onNativeScannerStarted && window.onNativeScannerStarted(" + JSONObject.quote(activeNativeCameraId) + ");");
            } catch (Exception error) {
                nativeScannerRunning = false;
                nativePreviewContainer.setVisibility(View.GONE);
                sendScannerError("原生相机启动失败：" + safeMessage(error));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private CameraSelector selectorForCamera(String cameraId) {
        if (cameraId == null || cameraId.isBlank()) return CameraSelector.DEFAULT_BACK_CAMERA;
        CameraFilter filter = cameraInfos -> {
            List<CameraInfo> matches = new ArrayList<>();
            for (CameraInfo info : cameraInfos) {
                if (cameraId.equals(Camera2CameraInfo.from(info).getCameraId())) matches.add(info);
            }
            return matches;
        };
        return new CameraSelector.Builder().addCameraFilter(filter).build();
    }

    private void stopNativeScannerInternal() {
        nativeScannerRunning = false;
        analyzerBusy.set(false);
        if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
        if (cameraProvider != null) cameraProvider.unbindAll();
        imageAnalysis = null;
        nativePreviewContainer.setVisibility(View.GONE);
        resetNativeTracking();
    }

    private void analyzeFrame(ImageProxy image) {
        if (!nativeScannerRunning || !analyzerBusy.compareAndSet(false, true)) {
            image.close();
            return;
        }

        long totalStart = SystemClock.elapsedRealtimeNanos();
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        long intervalNanos = previousAnalysisNanos == 0 ? 0 : totalStart - previousAnalysisNanos;
        previousAnalysisNanos = totalStart;
        nativeFrameNumber++;
        boolean fullLocate = shouldFullyRelocate(image);
        long roiStart = SystemClock.elapsedRealtimeNanos();
        Rect activeCrop = new Rect(0, 0, image.getWidth(), image.getHeight());
        if (!fullLocate && cachedCrop != null) activeCrop.set(cachedCrop);
        image.setCropRect(activeCrop);
        double roiMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - roiStart);

        String raw = null;
        boolean detected = false;
        double resultSizeRatio = cachedSizeRatio;
        long decodeStart = SystemClock.elapsedRealtimeNanos();
        double decodeMs = 0;
        double resultMs = 0;
        try {
            List<BarcodeReader.Result> results = barcodeReader.read(image);
            decodeMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - decodeStart);
            long resultStart = SystemClock.elapsedRealtimeNanos();
            if (!results.isEmpty() && results.get(0).getError() == null) {
                BarcodeReader.Result result = results.get(0);
                raw = result.getText();
                if (raw == null && result.getBytes() != null) {
                    raw = new String(result.getBytes(), StandardCharsets.UTF_8);
                }
                detected = raw != null && !raw.isEmpty();
                if (detected) {
                    cachedRoiFailures = 0;
                    if (fullLocate || cachedCrop == null) {
                        cacheCropFromResult(result, activeCrop, image);
                        resultSizeRatio = cachedSizeRatio;
                    }
                }
            }
            if (!detected) handleDecodeFailure(fullLocate);
            resultMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - resultStart);
        } catch (Throwable error) {
            decodeMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - decodeStart);
            handleDecodeFailure(fullLocate);
        } finally {
            double totalMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - totalStart);
            image.close();
            analyzerBusy.set(false);
            boolean repeatedResult = detected && raw.equals(lastDeliveredRaw) && totalStart - lastDeliveredNanos < 180_000_000L;
            if (nativeTimingEnabled || (detected && !repeatedResult)) {
                sendNativeSample(raw, resultSizeRatio, intervalNanos, roiMs, decodeMs, resultMs, totalMs, fullLocate, imageWidth, imageHeight);
                if (detected) {
                    lastDeliveredRaw = raw;
                    lastDeliveredNanos = totalStart;
                }
            }
        }
    }

    private boolean shouldFullyRelocate(ImageProxy image) {
        boolean dimensionsChanged = cachedImageWidth != image.getWidth()
                || cachedImageHeight != image.getHeight()
                || cachedRotation != normalizeRotation(image.getImageInfo().getRotationDegrees());
        if (dimensionsChanged) cachedCrop = null;
        if (cachedCrop == null) return true;
        return "fixed".equals(nativeRelocationMode)
                && nativeRelocationInterval > 1
                && nativeFrameNumber % nativeRelocationInterval == 0;
    }

    private void handleDecodeFailure(boolean fullLocate) {
        if (fullLocate || cachedCrop == null) {
            cachedRoiFailures = 0;
            return;
        }
        cachedRoiFailures++;
        if (cachedRoiFailures >= CACHED_ROI_FAILURE_LIMIT) {
            cachedCrop = null;
            cachedRoiFailures = 0;
            failureRelocateCount++;
        }
    }

    private void cacheCropFromResult(BarcodeReader.Result result, Rect sourceCrop, ImageProxy image) {
        BarcodeReader.Position position = result.getPosition();
        if (position == null) return;
        int rotation = normalizeRotation(image.getImageInfo().getRotationDegrees());
        Point[] rotatedPoints = new Point[]{position.getTopLeft(), position.getTopRight(), position.getBottomRight(), position.getBottomLeft()};
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Point point : rotatedPoints) {
            Point original = inverseRotate(point, sourceCrop.width(), sourceCrop.height(), rotation);
            int x = sourceCrop.left + original.x;
            int y = sourceCrop.top + original.y;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        int qrSide = Math.max(maxX - minX, maxY - minY);
        int margin = Math.max(24, Math.round(qrSide * 0.22f));
        Rect next = new Rect(
                Math.max(0, minX - margin),
                Math.max(0, minY - margin),
                Math.min(image.getWidth(), maxX + margin),
                Math.min(image.getHeight(), maxY + margin));
        if (next.width() < 160 || next.height() < 160) return;
        cachedCrop = next;
        cachedImageWidth = image.getWidth();
        cachedImageHeight = image.getHeight();
        cachedRotation = rotation;
        int rotatedWidth = rotation % 180 == 0 ? sourceCrop.width() : sourceCrop.height();
        int rotatedHeight = rotation % 180 == 0 ? sourceCrop.height() : sourceCrop.width();
        cachedSizeRatio = qrSide / (double) Math.max(1, Math.min(rotatedWidth, rotatedHeight));
    }

    private static Point inverseRotate(Point point, int width, int height, int rotation) {
        switch (rotation) {
            case 90:
                return new Point(point.y, height - 1 - point.x);
            case 180:
                return new Point(width - 1 - point.x, height - 1 - point.y);
            case 270:
                return new Point(width - 1 - point.y, point.x);
            default:
                return new Point(point.x, point.y);
        }
    }

    private static int normalizeRotation(int rotation) {
        int normalized = rotation % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }

    private void sendNativeSample(String raw, double sizeRatio, long intervalNanos, double roiMs, double decodeMs,
                                  double resultMs, double totalMs, boolean fullLocate, int width, int height) {
        try {
            JSONObject metrics = new JSONObject();
            metrics.put("cameraInterval", nanosToMs(intervalNanos));
            metrics.put("roiCrop", roiMs);
            metrics.put("decoder", decodeMs);
            metrics.put("result", resultMs);
            metrics.put("total", totalMs);
            metrics.put("fullLocate", fullLocate);
            metrics.put("failureRelocateCount", failureRelocateCount);
            metrics.put("imageWidth", width);
            metrics.put("imageHeight", height);
            String rawValue = raw == null ? "null" : JSONObject.quote(raw);
            String ratioValue = Double.isFinite(sizeRatio) ? Double.toString(sizeRatio) : "null";
            sendJavascript("window.onNativeScanSample && window.onNativeScanSample(" + rawValue + "," + ratioValue + "," + metrics + ");");
        } catch (JSONException ignored) {
        }
    }

    private void resetNativeTracking() {
        nativeFrameNumber = 0;
        previousAnalysisNanos = 0;
        cachedRoiFailures = 0;
        failureRelocateCount = 0;
        cachedCrop = null;
        cachedImageWidth = 0;
        cachedImageHeight = 0;
        cachedRotation = 0;
        cachedSizeRatio = Double.NaN;
        lastDeliveredRaw = "";
        lastDeliveredNanos = 0;
    }

    private void updatePreviewBounds(double left, double top, double width, double height) {
        int rootWidth = Math.max(1, rootLayout.getWidth());
        int rootHeight = Math.max(1, rootLayout.getHeight());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.max(1, (int) Math.round(rootWidth * clamp01(width))),
                Math.max(1, (int) Math.round(rootHeight * clamp01(height))));
        params.leftMargin = (int) Math.round(rootWidth * left);
        params.topMargin = (int) Math.round(rootHeight * top);
        nativePreviewContainer.setLayoutParams(params);
    }

    private String nativeCameraListJson() {
        JSONArray output = new JSONArray();
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            List<CameraDescription> cameras = new ArrayList<>();
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics details = manager.getCameraCharacteristics(id);
                Integer facing = details.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                float[] focalLengths = details.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                float focal = focalLengths != null && focalLengths.length > 0 ? focalLengths[0] : 5f;
                cameras.add(new CameraDescription(id, focal));
            }
            cameras.sort(Comparator.comparingDouble(camera -> Math.abs(camera.focalLength - 5.2f)));
            for (int index = 0; index < cameras.size(); index++) {
                CameraDescription camera = cameras.get(index);
                String type = index == 0 ? "后置主摄候选" : camera.focalLength < 3.5f ? "后置广角候选" : "后置摄像头";
                JSONObject item = new JSONObject();
                item.put("id", camera.id);
                item.put("label", type + " · ID " + camera.id + " · " + String.format(java.util.Locale.ROOT, "%.1fmm", camera.focalLength));
                output.put(item);
            }
        } catch (Exception ignored) {
        }
        return output.toString();
    }

    private void sendScannerError(String message) {
        sendJavascript("window.onNativeScannerError && window.onNativeScannerError(" + JSONObject.quote(message) + ");");
    }

    private void sendJavascript(String script) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    private static double nanosToMs(long nanos) {
        return nanos <= 0 ? 0 : nanos / 1_000_000.0;
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static boolean isAppUri(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && APP_HOST.equalsIgnoreCase(uri.getHost());
    }

    private static WebResourceResponse blockedResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopNativeScannerInternal();
        closePendingStream();
        if (cameraExecutor != null) cameraExecutor.shutdownNow();
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SAVE_DOCUMENT_REQUEST) return;
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && pendingSaveFile != null) {
            Uri destination = data.getData();
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(pendingSaveFile));
                 OutputStream rawOutput = getContentResolver().openOutputStream(destination);
                 BufferedOutputStream output = rawOutput == null ? null : new BufferedOutputStream(rawOutput)) {
                if (output == null) throw new IOException("无法打开保存位置");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                Toast.makeText(this, "文件保存成功", Toast.LENGTH_LONG).show();
            } catch (IOException error) {
                Toast.makeText(this, "保存失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        cleanupPendingSave();
    }

    private void launchSavePicker() {
        if (pendingSaveFile == null || pendingWrittenSize != pendingExpectedSize) {
            Toast.makeText(this, "接收临时文件不完整，无法保存", Toast.LENGTH_LONG).show();
            cleanupPendingSave();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(pendingSaveMime == null || pendingSaveMime.isBlank() ? "application/octet-stream" : pendingSaveMime)
                .putExtra(Intent.EXTRA_TITLE, pendingSaveName);
        try {
            startActivityForResult(intent, SAVE_DOCUMENT_REQUEST);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "手机没有可用的文件保存器", Toast.LENGTH_LONG).show();
            cleanupPendingSave();
        }
    }

    private synchronized void closePendingStream() {
        if (pendingSaveStream == null) return;
        try { pendingSaveStream.close(); } catch (IOException ignored) { }
        pendingSaveStream = null;
    }

    private synchronized void cleanupPendingSave() {
        closePendingStream();
        if (pendingSaveFile != null && pendingSaveFile.exists()) pendingSaveFile.delete();
        pendingSaveFile = null;
        pendingSaveName = null;
        pendingSaveMime = null;
        pendingExpectedSize = 0;
        pendingWrittenSize = 0;
    }

    public final class NativeBridge {
        @JavascriptInterface
        public boolean nativeScannerAvailable() {
            return true;
        }

        @JavascriptInterface
        public String getNativeCameraList() {
            return nativeCameraListJson();
        }

        @JavascriptInterface
        public void setPreviewBounds(double left, double top, double width, double height) {
            runOnUiThread(() -> updatePreviewBounds(left, top, width, height));
        }

        @JavascriptInterface
        public void setNativePreviewVisible(boolean visible) {
            runOnUiThread(() -> nativePreviewContainer.setVisibility(visible && nativeScannerRunning ? View.VISIBLE : View.GONE));
        }

        @JavascriptInterface
        public void configureNativeScanner(String relocationMode, int relocationInterval, boolean timingEnabled) {
            nativeRelocationMode = "fixed".equals(relocationMode) ? "fixed" : "adaptive";
            nativeRelocationInterval = Math.max(2, Math.min(300, relocationInterval));
            nativeTimingEnabled = timingEnabled;
            resetNativeTracking();
        }

        @JavascriptInterface
        public void startNativeScanner(String cameraId) {
            runOnUiThread(() -> requestNativeScanner(cameraId));
        }

        @JavascriptInterface
        public void stopNativeScanner() {
            runOnUiThread(MainActivity.this::stopNativeScannerInternal);
        }

        @JavascriptInterface
        public synchronized boolean beginSave(String fileName, String mimeType, long expectedSize) {
            if (expectedSize < 0 || expectedSize > MAX_FILE_BYTES) return false;
            cleanupPendingSave();
            try {
                pendingSaveFile = new File(getCacheDir(), "qtx1-pending-save.bin");
                pendingSaveStream = new BufferedOutputStream(new FileOutputStream(pendingSaveFile));
                pendingSaveName = sanitizeFileName(fileName);
                pendingSaveMime = mimeType;
                pendingExpectedSize = expectedSize;
                pendingWrittenSize = 0;
                return true;
            } catch (IOException error) {
                cleanupPendingSave();
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean appendSave(String base64Chunk) {
            if (pendingSaveStream == null || base64Chunk == null) return false;
            try {
                byte[] bytes = Base64.decode(base64Chunk, Base64.NO_WRAP);
                if (pendingWrittenSize + bytes.length > pendingExpectedSize) {
                    cleanupPendingSave();
                    return false;
                }
                pendingSaveStream.write(bytes);
                pendingWrittenSize += bytes.length;
                return true;
            } catch (IllegalArgumentException | IOException error) {
                cleanupPendingSave();
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean finishSave() {
            closePendingStream();
            if (pendingSaveFile == null || pendingWrittenSize != pendingExpectedSize) {
                cleanupPendingSave();
                return false;
            }
            runOnUiThread(MainActivity.this::launchSavePicker);
            return true;
        }

        @JavascriptInterface
        public synchronized void cancelSave() {
            cleanupPendingSave();
        }
    }

    private static String sanitizeFileName(String name) {
        String clean = name == null ? "received.bin" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (clean.isEmpty()) clean = "received.bin";
        return clean.length() <= 180 ? clean : clean.substring(0, 180);
    }

    private static final class CameraDescription {
        final String id;
        final float focalLength;

        CameraDescription(String id, float focalLength) {
            this.id = id;
            this.focalLength = focalLength;
        }
    }

    private static final class ScannerOverlayView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ScannerOverlayView(android.content.Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float side = Math.min(getWidth(), getHeight()) * 0.82f;
            float left = (getWidth() - side) / 2f;
            float top = (getHeight() - side) / 2f;
            float corner = side * 0.12f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f * density);
            paint.setStrokeCap(Paint.Cap.SQUARE);
            paint.setColor(Color.rgb(85, 222, 207));
            canvas.drawLine(left, top, left + corner, top, paint);
            canvas.drawLine(left, top, left, top + corner, paint);
            canvas.drawLine(left + side, top, left + side - corner, top, paint);
            canvas.drawLine(left + side, top, left + side, top + corner, paint);
            canvas.drawLine(left, top + side, left + corner, top + side, paint);
            canvas.drawLine(left, top + side, left, top + side - corner, paint);
            canvas.drawLine(left + side, top + side, left + side - corner, top + side, paint);
            canvas.drawLine(left + side, top + side, left + side, top + side - corner, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(14f * density);
            paint.setShadowLayer(4f * density, 0, 1f * density, Color.BLACK);
            canvas.drawText("将完整二维码和白边放入框内", getWidth() / 2f, Math.min(getHeight() - 14f * density, top + side + 28f * density), paint);
            paint.clearShadowLayer();
        }
    }
}
