package com.qtx1.receiver;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraFilter;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageInfo;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import zxingcpp.BarcodeReader;

public final class MainActivity extends ComponentActivity {
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String APP_URL = "https://" + APP_HOST + "/assets/index.html";
    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static final int SAVE_DOCUMENT_REQUEST = 1002;
    private static final long MAX_FILE_BYTES = 25L * 1024L * 1024L;
    private static final long SHARE_FILE_RETENTION_MS = 10L * 60L * 1000L;
    private static final int CACHED_ROI_FAILURE_LIMIT = 2;
    private static final int QUALITY_SAMPLE_INTERVAL = 15;
    private static final long FAILURE_SAMPLE_INTERVAL_NANOS = 350_000_000L;
    private static final int FAILURE_THUMB_MAX_SIDE = 320;
    private static final int QUAD_SLOT_FAILURE_LIMIT = 3;
    private static final float QUAD_SEARCH_OVERLAP_RATIO = 0.04f;
    private static final float QUAD_ROI_MARGIN_RATIO = 0.10f;

    private FrameLayout rootLayout;
    private WebView webView;
    private FrameLayout nativePreviewContainer;
    private PreviewView nativePreview;
    private ExecutorService cameraExecutor;
    private ExecutorService quadDecoderExecutor;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private volatile BarcodeReader barcodeReader;
    private volatile BarcodeReader[] quadBarcodeReaders;
    private final AtomicBoolean analyzerBusy = new AtomicBoolean(false);
    private final AtomicBoolean webResultPending = new AtomicBoolean(false);

    private PermissionRequest pendingWebPermission;
    private boolean nativePermissionPending;
    private boolean nativeScannerRunning;
    private volatile boolean nativeTimingEnabled;
    private volatile boolean nativeErrorDiagnosticsEnabled;
    private volatile boolean nativeQualityEnabled;
    private volatile boolean nativeFailureSamplingEnabled;
    private volatile boolean nativeExposureDiagnosticsEnabled;
    private volatile ExposureSample latestExposureSample;
    private volatile int nativeQrLayout = 1;
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
    private long lastFailureSampleNanos;
    private Rect[] quadCrops;
    private int[] quadSlotFailures = new int[4];
    private boolean[] quadSlotSearching = new boolean[]{true, true, true, true};

    private File pendingSaveFile;
    private String pendingSaveName;
    private String pendingSaveMime;
    private OutputStream pendingSaveStream;
    private long pendingExpectedSize;
    private long pendingWrittenSize;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        AtomicInteger quadThreadNumber = new AtomicInteger();
        quadDecoderExecutor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "qtx-zxing-quad-" + quadThreadNumber.incrementAndGet());
            thread.setPriority(Thread.NORM_PRIORITY + 1);
            return thread;
        });
        configureBarcodeReader(false);
        cleanupExpiredShareFiles();
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

    private void configureBarcodeReader(boolean returnErrors) {
        barcodeReader = createBarcodeReader(returnErrors, 1);
        BarcodeReader[] readers = new BarcodeReader[4];
        for (int slot = 0; slot < readers.length; slot++) readers[slot] = createBarcodeReader(returnErrors, 1);
        quadBarcodeReaders = readers;
    }

    private static BarcodeReader createBarcodeReader(boolean returnErrors, int maxSymbols) {
        BarcodeReader.Options options = new BarcodeReader.Options();
        options.setFormats(Collections.singleton(BarcodeReader.Format.QR_CODE));
        options.setTryHarder(false);
        options.setTryRotate(false);
        options.setTryInvert(false);
        options.setTryDownscale(false);
        options.setTryDenoise(false);
        options.setBinarizer(BarcodeReader.Binarizer.LOCAL_AVERAGE);
        options.setMaxNumberOfSymbols(maxSymbols);
        options.setReturnErrors(returnErrors);
        return new BarcodeReader(options);
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

    @androidx.annotation.OptIn(markerClass = ExperimentalCamera2Interop.class)
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
                        .setTargetResolution(new Size(1920, 1080))
                        .setTargetRotation(nativePreview.getDisplay().getRotation())
                        .build();
                preview.setSurfaceProvider(nativePreview.getSurfaceProvider());

                ImageAnalysis.Builder analysisBuilder = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1920, 1080))
                        .setTargetRotation(nativePreview.getDisplay().getRotation())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888);
                new Camera2Interop.Extender<>(analysisBuilder)
                        .setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                           @NonNull CaptureRequest request,
                                                           @NonNull TotalCaptureResult result) {
                                recordExposureResult(result);
                            }
                        });
                imageAnalysis = analysisBuilder.build();
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

    private void recordExposureResult(CaptureResult result) {
        if (!nativeExposureDiagnosticsEnabled) return;
        Long exposureNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        Long frameDurationNanos = result.get(CaptureResult.SENSOR_FRAME_DURATION);
        Long sensorTimestampNanos = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (exposureNanos == null || exposureNanos <= 0) return;
        latestExposureSample = new ExposureSample(
                exposureNanos,
                iso == null ? -1 : iso,
                frameDurationNanos == null ? 0 : frameDurationNanos,
                sensorTimestampNanos == null ? 0 : sensorTimestampNanos);
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
        webResultPending.set(false);
        if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
        if (cameraProvider != null) cameraProvider.unbindAll();
        imageAnalysis = null;
        nativePreviewContainer.setVisibility(View.GONE);
        resetNativeTracking();
    }

    private void analyzeFrame(ImageProxy image) {
        if (!nativeScannerRunning || webResultPending.get() || !analyzerBusy.compareAndSet(false, true)) {
            image.close();
            return;
        }

        if (nativeQrLayout == 4) {
            try {
                analyzeQuadFrame(image);
            } catch (Throwable error) {
                try { image.close(); } catch (Throwable ignored) { }
                analyzerBusy.set(false);
                sendScannerError("四码并行解码异常：" + safeMessage(error));
            }
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
        String decodeStatus = "no_symbol";
        QualityMetrics qualityMetrics = null;
        String failureImage = null;
        double resultSizeRatio = cachedSizeRatio;
        long decodeStart = SystemClock.elapsedRealtimeNanos();
        double decodeMs = 0;
        double resultMs = 0;
        try {
            BarcodeReader activeReader = barcodeReader;
            List<BarcodeReader.Result> results = activeReader.read(image);
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
                    decodeStatus = "success";
                    cachedRoiFailures = 0;
                    if (fullLocate || cachedCrop == null) {
                        cacheCropFromResult(result, activeCrop, image);
                        resultSizeRatio = cachedSizeRatio;
                    }
                }
            } else if (!results.isEmpty() && results.get(0).getError() != null) {
                decodeStatus = classifyDecodeError(results.get(0).getError());
            }
            if (!detected) handleDecodeFailure(fullLocate);
            resultMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - resultStart);
        } catch (Throwable error) {
            decodeMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - decodeStart);
            decodeStatus = "exception";
            handleDecodeFailure(fullLocate);
        } finally {
            if (nativeQualityEnabled && nativeFrameNumber % QUALITY_SAMPLE_INTERVAL == 0) {
                qualityMetrics = measureImageQuality(image, activeCrop);
            }
            if (nativeFailureSamplingEnabled && !detected
                    && totalStart - lastFailureSampleNanos >= FAILURE_SAMPLE_INTERVAL_NANOS) {
                failureImage = createFailureThumbnail(image, activeCrop);
                if (failureImage != null) lastFailureSampleNanos = totalStart;
            }
            double totalMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - totalStart);
            image.close();
            analyzerBusy.set(false);
            boolean repeatedResult = detected && raw.equals(lastDeliveredRaw) && totalStart - lastDeliveredNanos < 180_000_000L;
            boolean shouldDeliver = nativeTimingEnabled || nativeErrorDiagnosticsEnabled || nativeQualityEnabled
                    || nativeFailureSamplingEnabled || nativeExposureDiagnosticsEnabled
                    || (detected && !repeatedResult);
            if (shouldDeliver && webResultPending.compareAndSet(false, true)) {
                sendNativeSample(raw, resultSizeRatio, intervalNanos, roiMs, decodeMs, resultMs, totalMs,
                        fullLocate, imageWidth, imageHeight, decodeStatus, qualityMetrics, failureImage);
                if (detected) {
                    lastDeliveredRaw = raw;
                    lastDeliveredNanos = totalStart;
                }
            }
        }
    }

    private void analyzeQuadFrame(ImageProxy image) {
        long totalStart = SystemClock.elapsedRealtimeNanos();
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        long intervalNanos = previousAnalysisNanos == 0 ? 0 : totalStart - previousAnalysisNanos;
        previousAnalysisNanos = totalStart;
        nativeFrameNumber++;
        boolean dimensionsChanged = cachedImageWidth != imageWidth || cachedImageHeight != imageHeight
                || cachedRotation != normalizeRotation(image.getImageInfo().getRotationDegrees());
        boolean scheduledRelocate = "fixed".equals(nativeRelocationMode)
                && nativeFrameNumber % nativeRelocationInterval == 0;
        long locateStart = SystemClock.elapsedRealtimeNanos();
        Rect[] searchAreas = buildQuadrantSearchAreas(imageWidth, imageHeight);
        if (quadCrops == null || dimensionsChanged || scheduledRelocate) {
            quadCrops = copyRects(searchAreas);
            quadSlotFailures = new int[4];
            quadSlotSearching = new boolean[]{true, true, true, true};
            cachedImageWidth = imageWidth;
            cachedImageHeight = imageHeight;
            cachedRotation = normalizeRotation(image.getImageInfo().getRotationDegrees());
        }
        boolean relocate = false;
        for (boolean searching : quadSlotSearching) relocate |= searching;
        double locateMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - locateStart);

        List<QuadDecodeResult> decoded = new ArrayList<>(4);
        List<Future<QuadDecodeResult>> futures = new ArrayList<>(4);
        BarcodeReader[] readers = quadBarcodeReaders;
        Rect[] crops = quadCrops == null ? copyRects(searchAreas) : copyRects(quadCrops);
        for (int slot = 0; slot < 4; slot++) {
            final int slotIndex = slot;
            final Rect crop = new Rect(crops[slot]);
            final BarcodeReader reader = readers[slot];
            final boolean refineRoi = quadSlotSearching[slot];
            futures.add(quadDecoderExecutor.submit(() -> decodeQuadSlot(slotIndex, reader, image, crop,
                    searchAreas[slotIndex], refineRoi)));
        }
        for (int slot = 0; slot < futures.size(); slot++) {
            try {
                decoded.add(futures.get(slot).get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                decoded.add(QuadDecodeResult.failed(slot, "exception", 0));
            } catch (Throwable error) {
                decoded.add(QuadDecodeResult.failed(slot, "exception", 0));
            }
        }

        int successCount = 0;
        int firstFailedSlot = -1;
        double maximumDecodeMs = 0;
        for (QuadDecodeResult result : decoded) {
            if (result.raw != null && !result.raw.isEmpty()) {
                successCount++;
                quadSlotFailures[result.slot] = 0;
                if (result.refinedCrop != null) {
                    quadCrops[result.slot] = result.refinedCrop;
                    quadSlotSearching[result.slot] = false;
                }
            } else {
                if (firstFailedSlot < 0) firstFailedSlot = result.slot;
                quadSlotFailures[result.slot]++;
                if (!quadSlotSearching[result.slot]
                        && quadSlotFailures[result.slot] >= QUAD_SLOT_FAILURE_LIMIT) {
                    quadCrops[result.slot] = new Rect(searchAreas[result.slot]);
                    quadSlotSearching[result.slot] = true;
                    quadSlotFailures[result.slot] = 0;
                    failureRelocateCount++;
                }
            }
            maximumDecodeMs = Math.max(maximumDecodeMs, result.decodeMs);
        }

        QualityMetrics qualityMetrics = null;
        String failureImage = null;
        if (nativeQualityEnabled && nativeFrameNumber % QUALITY_SAMPLE_INTERVAL == 0) {
            qualityMetrics = measureImageQuality(image, new Rect(0, 0, imageWidth, imageHeight));
        }
        if (nativeFailureSamplingEnabled && firstFailedSlot >= 0
                && totalStart - lastFailureSampleNanos >= FAILURE_SAMPLE_INTERVAL_NANOS) {
            failureImage = createFailureThumbnail(image, crops[firstFailedSlot]);
            if (failureImage != null) lastFailureSampleNanos = totalStart;
        }

        double totalMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - totalStart);
        image.close();
        analyzerBusy.set(false);
        boolean detected = successCount > 0;
        String signature = buildQuadSignature(decoded);
        boolean repeated = detected && signature.equals(lastDeliveredRaw)
                && totalStart - lastDeliveredNanos < 180_000_000L;
        boolean shouldDeliver = nativeTimingEnabled || nativeErrorDiagnosticsEnabled || nativeQualityEnabled
                || nativeFailureSamplingEnabled || nativeExposureDiagnosticsEnabled
                || (detected && !repeated);
        if (shouldDeliver && webResultPending.compareAndSet(false, true)) {
            sendNativeQuadSample(decoded, intervalNanos, locateMs, maximumDecodeMs, totalMs, relocate,
                    imageWidth, imageHeight, qualityMetrics, failureImage);
            if (detected) {
                lastDeliveredRaw = signature;
                lastDeliveredNanos = totalStart;
            }
        }
    }

    private QuadDecodeResult decodeQuadSlot(int slot, BarcodeReader reader, ImageProxy source, Rect crop,
                                            Rect searchArea, boolean refineRoi) {
        long started = SystemClock.elapsedRealtimeNanos();
        try {
            List<BarcodeReader.Result> results = reader.read(new CroppedImageProxy(source, crop));
            double elapsed = nanosToMs(SystemClock.elapsedRealtimeNanos() - started);
            if (results.isEmpty()) return QuadDecodeResult.failed(slot, "no_symbol", elapsed);
            BarcodeReader.Result first = results.get(0);
            if (first.getError() != null) {
                return QuadDecodeResult.failed(slot, classifyDecodeError(first.getError()), elapsed);
            }
            String raw = first.getText();
            if (raw == null && first.getBytes() != null) raw = new String(first.getBytes(), StandardCharsets.UTF_8);
            Rect refinedCrop = refineRoi
                    ? constrainCrop(calculateCropFromResult(first, crop, source, QUAD_ROI_MARGIN_RATIO), searchArea)
                    : null;
            return raw == null || raw.isEmpty()
                    ? QuadDecodeResult.failed(slot, "no_symbol", elapsed)
                    : new QuadDecodeResult(slot, raw, "success", elapsed, refinedCrop);
        } catch (Throwable error) {
            return QuadDecodeResult.failed(slot, "exception", nanosToMs(SystemClock.elapsedRealtimeNanos() - started));
        }
    }

    private static Rect[] buildQuadrantSearchAreas(int width, int height) {
        int middleX = width / 2;
        int middleY = height / 2;
        int overlapX = Math.max(8, Math.round(width * QUAD_SEARCH_OVERLAP_RATIO));
        int overlapY = Math.max(8, Math.round(height * QUAD_SEARCH_OVERLAP_RATIO));
        return new Rect[]{
                new Rect(0, 0, Math.min(width, middleX + overlapX), Math.min(height, middleY + overlapY)),
                new Rect(Math.max(0, middleX - overlapX), 0, width, Math.min(height, middleY + overlapY)),
                new Rect(0, Math.max(0, middleY - overlapY), Math.min(width, middleX + overlapX), height),
                new Rect(Math.max(0, middleX - overlapX), Math.max(0, middleY - overlapY), width, height)
        };
    }

    private static Rect[] copyRects(Rect[] source) {
        Rect[] copy = new Rect[source.length];
        for (int index = 0; index < source.length; index++) copy[index] = new Rect(source[index]);
        return copy;
    }

    private static Rect constrainCrop(Rect candidate, Rect bounds) {
        if (candidate == null) return null;
        Rect constrained = new Rect(candidate);
        if (!constrained.intersect(bounds)) return null;
        return constrained.width() >= 120 && constrained.height() >= 120 ? constrained : null;
    }

    private static Rect calculateCropFromResult(BarcodeReader.Result result, Rect sourceCrop, ImageProxy image,
                                                float marginRatio) {
        BarcodeReader.Position position = result.getPosition();
        if (position == null) return null;
        int rotation = normalizeRotation(image.getImageInfo().getRotationDegrees());
        Point[] rotatedPoints = new Point[]{position.getTopLeft(), position.getTopRight(),
                position.getBottomRight(), position.getBottomLeft()};
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
        int margin = Math.max(8, Math.round(qrSide * marginRatio));
        Rect next = new Rect(
                Math.max(0, minX - margin),
                Math.max(0, minY - margin),
                Math.min(image.getWidth(), maxX + margin),
                Math.min(image.getHeight(), maxY + margin));
        return next.width() >= 120 && next.height() >= 120 ? next : null;
    }

    private static String buildQuadSignature(List<QuadDecodeResult> decoded) {
        StringBuilder builder = new StringBuilder();
        for (QuadDecodeResult result : decoded) {
            if (result.raw != null) builder.append(result.raw);
            builder.append('|');
        }
        return builder.toString();
    }

    private void sendNativeQuadSample(List<QuadDecodeResult> decoded, long intervalNanos, double locateMs,
                                      double maximumDecodeMs, double totalMs, boolean relocated, int width, int height,
                                      QualityMetrics qualityMetrics, String failureImage) {
        try {
            JSONArray rawValues = new JSONArray();
            JSONArray statuses = new JSONArray();
            for (QuadDecodeResult result : decoded) {
                rawValues.put(result.raw == null ? JSONObject.NULL : result.raw);
                statuses.put(result.status);
            }
            JSONObject metrics = new JSONObject();
            metrics.put("cameraInterval", nanosToMs(intervalNanos));
            metrics.put("roiCrop", locateMs);
            metrics.put("decoder", maximumDecodeMs);
            metrics.put("total", totalMs);
            metrics.put("fullLocate", relocated);
            metrics.put("failureRelocateCount", failureRelocateCount);
            metrics.put("imageWidth", width);
            metrics.put("imageHeight", height);
            metrics.put("frameNumber", nativeFrameNumber);
            metrics.put("layout", 4);
            metrics.put("sizeRatio", 0.42);
            metrics.put("decodeStatuses", statuses);
            if (qualityMetrics != null) {
                metrics.put("qualitySample", true);
                metrics.put("brightness", qualityMetrics.brightness);
                metrics.put("contrast", qualityMetrics.contrast);
                metrics.put("sharpness", qualityMetrics.sharpness);
                metrics.put("blackClip", qualityMetrics.blackClip);
                metrics.put("whiteClip", qualityMetrics.whiteClip);
            }
            appendExposureMetrics(metrics);
            if (failureImage != null) metrics.put("failureImage", failureImage);
            sendJavascript("window.onNativeQuadScanSample && window.onNativeQuadScanSample(" + rawValues + "," + metrics + ");");
        } catch (JSONException ignored) {
            webResultPending.set(false);
        }
    }

    private static final class QuadDecodeResult {
        final int slot;
        final String raw;
        final String status;
        final double decodeMs;
        final Rect refinedCrop;

        QuadDecodeResult(int slot, String raw, String status, double decodeMs, Rect refinedCrop) {
            this.slot = slot;
            this.raw = raw;
            this.status = status;
            this.decodeMs = decodeMs;
            this.refinedCrop = refinedCrop == null ? null : new Rect(refinedCrop);
        }

        static QuadDecodeResult failed(int slot, String status, double decodeMs) {
            return new QuadDecodeResult(slot, null, status, decodeMs, null);
        }
    }

    private static final class ExposureSample {
        final long exposureNanos;
        final int iso;
        final long frameDurationNanos;
        final long sensorTimestampNanos;

        ExposureSample(long exposureNanos, int iso, long frameDurationNanos, long sensorTimestampNanos) {
            this.exposureNanos = exposureNanos;
            this.iso = iso;
            this.frameDurationNanos = frameDurationNanos;
            this.sensorTimestampNanos = sensorTimestampNanos;
        }
    }

    private static String classifyDecodeError(BarcodeReader.Error error) {
        if (error == null || error.getType() == null) return "no_symbol";
        String type = error.getType().name();
        if ("FORMAT".equals(type)) return "format";
        if ("CHECKSUM".equals(type)) return "checksum";
        if ("UNSUPPORTED".equals(type)) return "unsupported";
        return "no_symbol";
    }

    private static QualityMetrics measureImageQuality(ImageProxy image, Rect crop) {
        if (image.getPlanes().length == 0 || crop.width() < 3 || crop.height() < 3) return null;
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int base = buffer.position();
        int limit = buffer.limit();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int step = Math.max(2, Math.min(crop.width(), crop.height()) / 96);
        double sum = 0;
        double sumSquares = 0;
        double laplacianSquares = 0;
        long count = 0;
        long laplacianCount = 0;
        long black = 0;
        long white = 0;
        for (int y = crop.top; y < crop.bottom; y += step) {
            for (int x = crop.left; x < crop.right; x += step) {
                int value = readLuma(buffer, base, limit, rowStride, pixelStride, x, y);
                if (value < 0) continue;
                sum += value;
                sumSquares += value * (double) value;
                count++;
                if (value < 20) black++;
                if (value > 235) white++;
                if (x - step >= crop.left && x + step < crop.right
                        && y - step >= crop.top && y + step < crop.bottom) {
                    int left = readLuma(buffer, base, limit, rowStride, pixelStride, x - step, y);
                    int right = readLuma(buffer, base, limit, rowStride, pixelStride, x + step, y);
                    int up = readLuma(buffer, base, limit, rowStride, pixelStride, x, y - step);
                    int down = readLuma(buffer, base, limit, rowStride, pixelStride, x, y + step);
                    if (left >= 0 && right >= 0 && up >= 0 && down >= 0) {
                        double laplacian = 4.0 * value - left - right - up - down;
                        laplacianSquares += laplacian * laplacian;
                        laplacianCount++;
                    }
                }
            }
        }
        if (count == 0) return null;
        double brightness = sum / count;
        double variance = Math.max(0, sumSquares / count - brightness * brightness);
        return new QualityMetrics(
                brightness,
                Math.sqrt(variance),
                laplacianCount == 0 ? 0 : Math.sqrt(laplacianSquares / laplacianCount),
                black * 100.0 / count,
                white * 100.0 / count);
    }

    private static int readLuma(ByteBuffer buffer, int base, int limit, int rowStride, int pixelStride, int x, int y) {
        int index = base + y * rowStride + x * pixelStride;
        return index >= base && index < limit ? buffer.get(index) & 0xff : -1;
    }

    private static String createFailureThumbnail(ImageProxy image, Rect crop) {
        if (image.getPlanes().length == 0 || crop.width() <= 0 || crop.height() <= 0) return null;
        int outputWidth;
        int outputHeight;
        if (crop.width() >= crop.height()) {
            outputWidth = Math.min(FAILURE_THUMB_MAX_SIDE, crop.width());
            outputHeight = Math.max(1, Math.round(outputWidth * crop.height() / (float) crop.width()));
        } else {
            outputHeight = Math.min(FAILURE_THUMB_MAX_SIDE, crop.height());
            outputWidth = Math.max(1, Math.round(outputHeight * crop.width() / (float) crop.height()));
        }
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int base = buffer.position();
        int limit = buffer.limit();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int[] pixels = new int[outputWidth * outputHeight];
        for (int y = 0; y < outputHeight; y++) {
            int sourceY = crop.top + Math.min(crop.height() - 1, y * crop.height() / outputHeight);
            for (int x = 0; x < outputWidth; x++) {
                int sourceX = crop.left + Math.min(crop.width() - 1, x * crop.width() / outputWidth);
                int value = readLuma(buffer, base, limit, rowStride, pixelStride, sourceX, sourceY);
                if (value < 0) value = 0;
                pixels[y * outputWidth + x] = Color.rgb(value, value, value);
            }
        }
        Bitmap source = Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Bitmap output = source;
        int rotation = normalizeRotation(image.getImageInfo().getRotationDegrees());
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            output = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        }
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        boolean compressed = output.compress(Bitmap.CompressFormat.JPEG, 60, stream);
        if (output != source) output.recycle();
        source.recycle();
        return compressed ? Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP) : null;
    }

    private static final class QualityMetrics {
        final double brightness;
        final double contrast;
        final double sharpness;
        final double blackClip;
        final double whiteClip;

        QualityMetrics(double brightness, double contrast, double sharpness, double blackClip, double whiteClip) {
            this.brightness = brightness;
            this.contrast = contrast;
            this.sharpness = sharpness;
            this.blackClip = blackClip;
            this.whiteClip = whiteClip;
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
                                  double resultMs, double totalMs, boolean fullLocate, int width, int height,
                                  String decodeStatus, QualityMetrics qualityMetrics, String failureImage) {
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
            metrics.put("frameNumber", nativeFrameNumber);
            metrics.put("decodeStatus", decodeStatus);
            if (qualityMetrics != null) {
                metrics.put("qualitySample", true);
                metrics.put("brightness", qualityMetrics.brightness);
                metrics.put("contrast", qualityMetrics.contrast);
                metrics.put("sharpness", qualityMetrics.sharpness);
                metrics.put("blackClip", qualityMetrics.blackClip);
                metrics.put("whiteClip", qualityMetrics.whiteClip);
            }
            appendExposureMetrics(metrics);
            if (failureImage != null) metrics.put("failureImage", failureImage);
            String rawValue = raw == null ? "null" : JSONObject.quote(raw);
            String ratioValue = Double.isFinite(sizeRatio) ? Double.toString(sizeRatio) : "null";
            sendJavascript("window.onNativeScanSample && window.onNativeScanSample(" + rawValue + "," + ratioValue + "," + metrics + ");");
        } catch (JSONException ignored) {
            webResultPending.set(false);
        }
    }

    private void appendExposureMetrics(JSONObject metrics) throws JSONException {
        if (!nativeExposureDiagnosticsEnabled) return;
        ExposureSample sample = latestExposureSample;
        if (sample == null) return;
        metrics.put("exposureSample", true);
        metrics.put("exposureTimeMs", nanosToMs(sample.exposureNanos));
        metrics.put("iso", sample.iso);
        metrics.put("sensorFrameDurationMs", nanosToMs(sample.frameDurationNanos));
        metrics.put("sensorFps", sample.frameDurationNanos > 0
                ? 1_000_000_000d / sample.frameDurationNanos : 0d);
        metrics.put("exposureRatio", sample.frameDurationNanos > 0
                ? 100d * sample.exposureNanos / sample.frameDurationNanos : 0d);
        metrics.put("sensorTimestampNanos", sample.sensorTimestampNanos);
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
        lastFailureSampleNanos = 0;
        latestExposureSample = null;
        quadCrops = null;
        quadSlotFailures = new int[4];
        quadSlotSearching = new boolean[]{true, true, true, true};
        webResultPending.set(false);
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
        if (quadDecoderExecutor != null) quadDecoderExecutor.shutdownNow();
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
        if (pendingSaveFile != null) cleanupShareFile(pendingSaveFile);
        pendingSaveFile = null;
        pendingSaveName = null;
        pendingSaveMime = null;
        pendingExpectedSize = 0;
        pendingWrittenSize = 0;
    }

    private void launchShareSheet(File shareFile, String displayName, String mimeType) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", shareFile);
            String resolvedMime = mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
            Intent sendIntent = new Intent(Intent.ACTION_SEND)
                    .setType(resolvedMime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_TITLE, displayName);
            sendIntent.setClipData(ClipData.newUri(getContentResolver(), displayName, uri));
            sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(sendIntent, "转发 " + displayName));
            mainHandler.postDelayed(() -> cleanupShareFile(shareFile), SHARE_FILE_RETENTION_MS);
        } catch (ActivityNotFoundException | IllegalArgumentException error) {
            cleanupShareFile(shareFile);
            Toast.makeText(this, "无法打开系统分享面板：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void cleanupShareFile(File shareFile) {
        if (shareFile.exists()) shareFile.delete();
        File parent = shareFile.getParentFile();
        if (parent != null && parent.getName().matches("\\d+")) parent.delete();
    }

    private void cleanupExpiredShareFiles() {
        File directory = new File(getCacheDir(), "qtx1-shares");
        File[] files = directory.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - SHARE_FILE_RETENTION_MS;
        for (File file : files) {
            if (file.lastModified() >= cutoff) continue;
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) for (File child : children) if (child.isFile()) child.delete();
            }
            file.delete();
        }
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
        public void configureNativeScanner(String relocationMode, int relocationInterval, boolean timingEnabled,
                                           boolean errorDiagnosticsEnabled, boolean qualityEnabled,
                                           boolean failureSamplingEnabled, boolean exposureDiagnosticsEnabled) {
            nativeRelocationMode = "fixed".equals(relocationMode) ? "fixed" : "adaptive";
            nativeRelocationInterval = Math.max(2, Math.min(300, relocationInterval));
            nativeTimingEnabled = timingEnabled;
            boolean returnErrorsChanged = nativeErrorDiagnosticsEnabled != errorDiagnosticsEnabled;
            nativeErrorDiagnosticsEnabled = errorDiagnosticsEnabled;
            nativeQualityEnabled = qualityEnabled;
            nativeFailureSamplingEnabled = failureSamplingEnabled;
            nativeExposureDiagnosticsEnabled = exposureDiagnosticsEnabled;
            if (returnErrorsChanged) configureBarcodeReader(errorDiagnosticsEnabled);
            resetNativeTracking();
        }

        @JavascriptInterface
        public void configureNativeLayout(int qrCount) {
            int nextLayout = qrCount == 4 ? 4 : 1;
            if (nativeQrLayout == nextLayout) return;
            nativeQrLayout = nextLayout;
            resetNativeTracking();
            runOnUiThread(() -> {
                for (int index = 0; index < nativePreviewContainer.getChildCount(); index++) {
                    nativePreviewContainer.getChildAt(index).invalidate();
                }
            });
        }

        @JavascriptInterface
        public void acknowledgeNativeResult() {
            webResultPending.set(false);
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
        public synchronized boolean beginShare(String fileName, String mimeType, long expectedSize) {
            if (expectedSize < 0 || expectedSize > MAX_FILE_BYTES) return false;
            cleanupPendingSave();
            try {
                File rootDirectory = new File(getCacheDir(), "qtx1-shares");
                File directory = new File(rootDirectory, Long.toString(System.currentTimeMillis()));
                if (!directory.mkdirs()) return false;
                pendingSaveName = sanitizeFileName(fileName);
                pendingSaveFile = new File(directory, pendingSaveName);
                pendingSaveStream = new BufferedOutputStream(new FileOutputStream(pendingSaveFile));
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
        public synchronized boolean finishShare() {
            closePendingStream();
            if (pendingSaveFile == null || pendingWrittenSize != pendingExpectedSize) {
                cleanupPendingSave();
                return false;
            }
            File shareFile = pendingSaveFile;
            String shareName = pendingSaveName;
            String shareMime = pendingSaveMime;
            pendingSaveFile = null;
            pendingSaveName = null;
            pendingSaveMime = null;
            pendingExpectedSize = 0;
            pendingWrittenSize = 0;
            runOnUiThread(() -> launchShareSheet(shareFile, shareName, shareMime));
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

    private static final class CroppedImageProxy implements ImageProxy {
        private final ImageProxy source;
        private final ImageProxy.PlaneProxy[] planes;
        private Rect cropRect;

        CroppedImageProxy(ImageProxy source, Rect cropRect) {
            this.source = source;
            this.cropRect = new Rect(cropRect);
            ImageProxy.PlaneProxy[] sourcePlanes = source.getPlanes();
            this.planes = new ImageProxy.PlaneProxy[sourcePlanes.length];
            for (int index = 0; index < sourcePlanes.length; index++) {
                this.planes[index] = new DuplicatePlaneProxy(sourcePlanes[index]);
            }
        }

        @Override public void close() { }
        @Override public Rect getCropRect() { return new Rect(cropRect); }
        @Override public void setCropRect(Rect rect) {
            Rect bounded = new Rect(0, 0, getWidth(), getHeight());
            if (rect != null) bounded.intersect(rect);
            cropRect = bounded;
        }
        @Override public int getFormat() { return source.getFormat(); }
        @Override public int getHeight() { return source.getHeight(); }
        @Override public int getWidth() { return source.getWidth(); }
        @Override public ImageProxy.PlaneProxy[] getPlanes() { return planes.clone(); }
        @Override public ImageInfo getImageInfo() { return source.getImageInfo(); }
        @Override public Image getImage() { return source.getImage(); }
    }

    private static final class DuplicatePlaneProxy implements ImageProxy.PlaneProxy {
        private final ImageProxy.PlaneProxy source;

        DuplicatePlaneProxy(ImageProxy.PlaneProxy source) {
            this.source = source;
        }

        @Override public int getRowStride() { return source.getRowStride(); }
        @Override public int getPixelStride() { return source.getPixelStride(); }
        @Override public ByteBuffer getBuffer() { return source.getBuffer().duplicate(); }
    }

    private final class ScannerOverlayView extends View {
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
            if (nativeQrLayout == 4) {
                paint.setStrokeWidth(2f * density);
                paint.setColor(Color.argb(180, 85, 222, 207));
                canvas.drawLine(left + side / 2f, top, left + side / 2f, top + side, paint);
                canvas.drawLine(left, top + side / 2f, left + side, top + side / 2f, paint);
            }
            canvas.drawText(nativeQrLayout == 4 ? "将田字形四个二维码整体放入框内" : "将完整二维码和白边放入框内",
                    getWidth() / 2f, Math.min(getHeight() - 14f * density, top + side + 28f * density), paint);
            paint.clearShadowLayer();
        }
    }
}
