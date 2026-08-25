(() => {
  "use strict";

  document.documentElement.dataset.qtxVersion = "1.7";

  const MAGIC = "QTX1";
  const HEADER_LENGTH = 35;
  const MAX_FILE_BYTES = 25 * 1024 * 1024;
  const MISSING_FRAME_THRESHOLD = 0.95;
  const BASE45_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
  const CAMERA_STORAGE_KEY = "qtx1w-preferred-camera-v1";
  const DECODER_STORAGE_KEY = "qtx1w-decoder-preference-v1";
  const RELOCATION_MODE_STORAGE_KEY = "qtx1w-relocation-mode-v1";
  const RELOCATION_INTERVAL_STORAGE_KEY = "qtx1w-relocation-interval-v1";
  const CRC_TABLE = buildCrcTable();
  const TIMING_KEYS = ["detector", "resultWait", "cameraCapture", "cameraInterval", "frameCopy", "roiCrop", "grayscale", "qrDetect", "qrDecode", "rsCorrection", "protocol", "uiUpdate", "nativeCombined", "nativeResult", "total"];
  const TIMING_RENDER_INTERVAL = 400;
  const CACHED_FAILURE_RELOCATE_THRESHOLD = 2;

  const ui = {
    video: document.getElementById("video"),
    canvas: document.getElementById("decodeCanvas"),
    placeholder: document.getElementById("cameraPlaceholder"),
    distanceGuide: document.getElementById("distanceGuide"),
    distanceTitle: document.getElementById("distanceTitle"),
    distanceDetail: document.getElementById("distanceDetail"),
    start: document.getElementById("startButton"),
    stop: document.getElementById("stopButton"),
    cameraControls: document.getElementById("cameraControls"),
    cameraSelect: document.getElementById("cameraSelect"),
    switchCamera: document.getElementById("switchCameraButton"),
    developerMode: document.getElementById("developerModeToggle"),
    developerModeLabel: document.getElementById("developerModeLabel"),
    developerPanel: document.getElementById("developerPanel"),
    decoderMode: document.getElementById("decoderModeSelect"),
    decoderAvailability: document.getElementById("decoderAvailability"),
    relocationMode: document.getElementById("relocationModeSelect"),
    relocationInterval: document.getElementById("relocationIntervalInput"),
    relocationIntervalLabel: document.getElementById("relocationIntervalLabel"),
    applyRelocation: document.getElementById("applyRelocationButton"),
    relocationDescription: document.getElementById("relocationDescription"),
    reset: document.getElementById("resetButton"),
    engine: document.getElementById("engineLabel"),
    status: document.getElementById("statusText"),
    transferElapsed: document.getElementById("transferElapsed"),
    dot: document.getElementById("statusDot"),
    filePanel: document.getElementById("filePanel"),
    fileName: document.getElementById("fileName"),
    fileMeta: document.getElementById("fileMeta"),
    progressTrack: document.getElementById("progressTrack"),
    progressFill: document.getElementById("progressFill"),
    progressPercent: document.getElementById("progressPercent"),
    progressDetail: document.getElementById("progressDetail"),
    missingCard: document.getElementById("missingCard"),
    missingCount: document.getElementById("missingCount"),
    missingFrames: document.getElementById("missingFrames"),
    validFrames: document.getElementById("validFrames"),
    duplicates: document.getElementById("duplicateFrames"),
    crcErrors: document.getElementById("crcErrors"),
    speed: document.getElementById("speed"),
    message: document.getElementById("message"),
    completeCard: document.getElementById("completeCard"),
    completeSummary: document.getElementById("completeSummary"),
    newTransfer: document.getElementById("newTransferButton"),
    download: document.getElementById("downloadButton"),
    preview: document.getElementById("previewButton"),
    share: document.getElementById("shareButton"),
    previewPanel: document.getElementById("previewPanel"),
    imagePreview: document.getElementById("imagePreview"),
    videoPreview: document.getElementById("videoPreview"),
    textPreview: document.getElementById("textPreview"),
    previewMessage: document.getElementById("previewMessage"),
    timingToggle: document.getElementById("timingToggle"),
    performanceCard: document.getElementById("performanceCard"),
    timingToggleLabel: document.getElementById("timingToggleLabel"),
    timingOffNote: document.getElementById("timingOffNote"),
    timingDetails: document.getElementById("timingDetails"),
    timingSamples: document.getElementById("timingSamples"),
    timingEngine: document.getElementById("timingEngine"),
    timingDetector: document.getElementById("timingDetector"),
    timingResultWait: document.getElementById("timingResultWait"),
    timingCameraCapture: document.getElementById("timingCameraCapture"),
    timingCameraInterval: document.getElementById("timingCameraInterval"),
    timingFrameCopy: document.getElementById("timingFrameCopy"),
    timingRoiCrop: document.getElementById("timingRoiCrop"),
    timingGrayscale: document.getElementById("timingGrayscale"),
    timingQrDetect: document.getElementById("timingQrDetect"),
    timingQrDecode: document.getElementById("timingQrDecode"),
    timingRsCorrection: document.getElementById("timingRsCorrection"),
    timingProtocol: document.getElementById("timingProtocol"),
    timingUiUpdate: document.getElementById("timingUiUpdate"),
    timingNativeRow: document.getElementById("timingNativeRow"),
    timingNativeCombined: document.getElementById("timingNativeCombined"),
    timingNativeResultRow: document.getElementById("timingNativeResultRow"),
    timingNativeResult: document.getElementById("timingNativeResult"),
    timingOverhead: document.getElementById("timingOverhead"),
    timingTotal: document.getElementById("timingTotal"),
    timingNote: document.getElementById("timingNote"),
    cachedRoiSamples: document.getElementById("cachedRoiSamples"),
    cachedRoiAverage: document.getElementById("cachedRoiAverage"),
    relocateSamples: document.getElementById("relocateSamples"),
    relocateAverage: document.getElementById("relocateAverage"),
    relocateDetectAverage: document.getElementById("relocateDetectAverage"),
    failureRelocateCount: document.getElementById("failureRelocateCount"),
    compareNativeSamples: document.getElementById("compareNativeSamples"),
    compareNativeRate: document.getElementById("compareNativeRate"),
    compareNativePipeline: document.getElementById("compareNativePipeline"),
    compareNativeTotal: document.getElementById("compareNativeTotal"),
    compareJsQrSamples: document.getElementById("compareJsQrSamples"),
    compareJsQrRate: document.getElementById("compareJsQrRate"),
    compareJsQrPipeline: document.getElementById("compareJsQrPipeline"),
    compareJsQrTotal: document.getElementById("compareJsQrTotal")
  };

  const context = ui.canvas.getContext("2d", { willReadFrequently: true });
  let stream = null;
  let videoDevices = [];
  let activeDeviceId = "";
  let switchingCamera = false;
  let scanning = false;
  let decoding = false;
  let lastDecodeAt = 0;
  let lastRaw = "";
  let lastRawAt = 0;
  let lastDetectedAt = 0;
  let detector = null;
  let nativeDetectorFailed = false;
  let nativeDetectorAvailable = false;
  let nativePipelineActive = false;
  let nativeResultBusy = false;
  let developerModeEnabled = false;
  let decoderPreference = loadDecoderPreference();
  let relocationMode = loadRelocationMode();
  let relocationInterval = loadRelocationInterval();
  let activeDecoderEngine = "";
  let database = null;
  let active = null;
  let received = new Set();
  let memoryChunks = new Map();
  let validFrameCount = 0;
  let duplicateCount = 0;
  let crcErrorCount = 0;
  let receivedBytes = 0;
  let startedAt = 0;
  let transferStartedAt = 0;
  let transferCompletedAt = 0;
  let transferTimerHandle = 0;
  let completedBlob = null;
  let completedFile = null;
  let previewUrl = null;
  let foreignSessionNotice = "";
  let memoryOnly = false;
  let missingIndexes = null;
  let missingRenderTimer = 0;
  let activeTimingSample = null;
  let timingStats = createTimingStats();
  let engineComparison = createEngineComparison();
  let roiDiagnosticStats = createRoiDiagnosticStats();
  let lastTimingRenderAt = 0;
  let performanceTimingEnabled = false;
  let cachedQrLocation = null;
  let cachedQrRoi = null;
  let cachedQrSizeRatio = null;
  let jsQrFrameCounter = 0;
  let cachedQrFailureCount = 0;
  let failureRelocatePending = false;
  let failureRelocateCount = 0;
  const databaseReady = initialize();

  ui.start.addEventListener("click", () => startCamera());
  ui.stop.addEventListener("click", stopCamera);
  ui.cameraSelect.addEventListener("change", () => switchToCamera(ui.cameraSelect.value, true));
  ui.switchCamera.addEventListener("click", cycleCamera);
  ui.developerMode.addEventListener("change", toggleDeveloperMode);
  ui.decoderMode.addEventListener("change", changeDecoderPreference);
  ui.relocationMode.addEventListener("change", changeRelocationSettings);
  ui.relocationInterval.addEventListener("change", changeRelocationSettings);
  ui.applyRelocation.addEventListener("click", changeRelocationSettings);
  ui.reset.addEventListener("click", resetTransfer);
  ui.newTransfer.addEventListener("click", startNewTransfer);
  ui.download.addEventListener("click", downloadCompletedFile);
  ui.preview.addEventListener("click", togglePreview);
  ui.share.addEventListener("click", shareCompletedFile);
  ui.timingToggle.addEventListener("change", togglePerformanceTiming);
  ui.imagePreview.addEventListener("error", showPreviewError);
  ui.videoPreview.addEventListener("error", showPreviewError);
  window.addEventListener("pagehide", stopCamera);
  window.addEventListener("resize", updateNativePreviewBounds);
  window.addEventListener("scroll", updateNativePreviewBounds, { passive: true });
  ui.decoderMode.value = decoderPreference;
  ui.relocationMode.value = relocationMode;
  ui.relocationInterval.value = String(relocationInterval);
  renderDeveloperMode();
  renderRelocationControls();
  renderTransferTimer();
  showInitialEnvironment();
  window.onNativeScannerStarted = handleNativeScannerStarted;
  window.onNativeScannerError = handleNativeScannerError;
  window.onNativeScanSample = handleNativeScanSample;

  async function initialize() {
    try {
      const openedDatabase = await openDatabase();
      if (memoryOnly) {
        openedDatabase.close();
      } else {
        database = openedDatabase;
      }
    } catch (error) {
      console.warn("IndexedDB unavailable, using memory storage", error);
      database = null;
      if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
        setMessage("浏览器临时数据库不可用，将改用内存接收；请不要刷新页面。", "warn");
      }
    }

    if (!window.AndroidBridge && "serviceWorker" in navigator && location.protocol.startsWith("http")) {
      navigator.serviceWorker.register("sw.js").catch(() => {});
    }
  }

  async function startCamera(requestedDeviceId = "") {
    if (!scanning) {
      resetQrTracking();
      if (performanceTimingEnabled) resetPerformanceStats();
    }
    ui.start.disabled = true;
    ui.start.textContent = "正在请求摄像头…";
    setStatus("正在请求摄像头", "active");

    if (shouldUseNativeScanner()) {
      await startNativeCamera(requestedDeviceId);
      return;
    }
    if (effectiveDecoderPreference() === "zxingcpp") {
      setError("当前不是支持原生 CameraX 的 Android APK，无法使用 ZXing-C++。请选择自动、BarcodeDetector 或本地 jsQR。");
      ui.start.disabled = false;
      ui.start.textContent = "重新尝试";
      return;
    }

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      setError(cameraApiUnavailableMessage());
      ui.start.disabled = false;
      ui.start.textContent = "重新检查摄像头";
      return;
    }

    try {
      await Promise.race([databaseReady, delay(350)]);
      if (!database) memoryOnly = true;

      const savedDeviceId = requestedDeviceId || loadPreferredCamera();
      try {
        stream = await openCameraStream(savedDeviceId);
      } catch (error) {
        if (!savedDeviceId) throw error;
        clearPreferredCamera();
        stream = await openCameraStream("");
      }

      ui.video.srcObject = stream;
      await ui.video.play();
      activeDeviceId = stream.getVideoTracks()[0]?.getSettings?.().deviceId || savedDeviceId;
      await refreshCameraDevices();
      const preferred = choosePreferredCamera(videoDevices, savedDeviceId);
      if (!requestedDeviceId && preferred && preferred.deviceId !== activeDeviceId) {
        await replaceCameraStream(preferred.deviceId, true);
      } else if (!requestedDeviceId && preferred && preferred.deviceId === activeDeviceId) {
        savePreferredCamera(activeDeviceId);
      }
      ui.placeholder.hidden = true;
      updateDistanceGuide(null);
      ui.start.disabled = true;
      ui.start.textContent = "摄像头已开启";
      ui.stop.disabled = false;
      scanning = true;
      startedAt ||= performance.now();

      await probeBarcodeDetector();
      applyDecoderPreference(false);
      if (activeDecoderEngine) {
        setStatus(active ? "继续接收" : "正在寻找发送端", "active");
        setMessage("保持完整二维码和四周白边都在取景框内。", "info");
      } else {
        setStatus("所选解码引擎不可用", "error");
        setMessage("当前 WebView 无法使用 BarcodeDetector。请切换到本地 jsQR 或自动选择。", "warn");
      }
      requestAnimationFrame(scanLoop);
    } catch (error) {
      setError(cameraErrorMessage(error));
      ui.start.disabled = false;
      ui.start.textContent = "重新尝试";
    }
  }

  function nativeScannerSupported() {
    try {
      return Boolean(window.AndroidBridge
        && typeof window.AndroidBridge.nativeScannerAvailable === "function"
        && window.AndroidBridge.nativeScannerAvailable());
    } catch {
      return false;
    }
  }

  function shouldUseNativeScanner() {
    const preference = effectiveDecoderPreference();
    return nativeScannerSupported() && (preference === "auto" || preference === "zxingcpp");
  }

  async function startNativeCamera(requestedDeviceId = "") {
    try {
      await Promise.race([databaseReady, delay(350)]);
      if (!database) memoryOnly = true;
      videoDevices = loadNativeCameraDevices();
      const savedDeviceId = requestedDeviceId || loadPreferredCamera();
      const preferred = choosePreferredCamera(videoDevices, savedDeviceId) || videoDevices[0] || null;
      activeDeviceId = preferred?.deviceId || savedDeviceId || "";
      renderNativeCameraDevices();
      updateNativePreviewBounds();
      window.AndroidBridge.configureNativeScanner(effectiveRelocationMode(), relocationInterval, performanceTimingEnabled);
      nativePipelineActive = true;
      scanning = true;
      startedAt ||= performance.now();
      activeDecoderEngine = "zxingcpp";
      ui.placeholder.hidden = true;
      ui.start.disabled = true;
      ui.start.textContent = "原生相机正在启动";
      ui.stop.disabled = false;
      ui.engine.textContent = "解码引擎：CameraX Y Plane → ZXing-C++ · 单任务 · KEEP_ONLY_LATEST";
      setStatus(active ? "继续接收" : "正在启动原生相机", "active");
      setMessage("V1.7 正在直接读取相机 Y Plane，不经过 Canvas、RGBA 或 JavaScript 灰度转换。", "info");
      window.AndroidBridge.startNativeScanner(activeDeviceId);
    } catch (error) {
      nativePipelineActive = false;
      scanning = false;
      setError(`原生扫描器启动失败：${error?.message || error}`);
      ui.start.disabled = false;
      ui.start.textContent = "重新尝试";
    }
  }

  function loadNativeCameraDevices() {
    try {
      const list = JSON.parse(window.AndroidBridge.getNativeCameraList() || "[]");
      return list.map(camera => ({ kind: "videoinput", deviceId: String(camera.id), label: String(camera.label || `摄像头 ${camera.id}`) }));
    } catch (error) {
      console.warn("Unable to enumerate native cameras", error);
      return [];
    }
  }

  function renderNativeCameraDevices() {
    ui.cameraSelect.replaceChildren();
    videoDevices.forEach((device, index) => {
      const option = document.createElement("option");
      option.value = device.deviceId;
      option.textContent = cameraDisplayName(device, index);
      ui.cameraSelect.appendChild(option);
    });
    if (activeDeviceId) ui.cameraSelect.value = activeDeviceId;
    ui.cameraControls.hidden = videoDevices.length === 0;
    ui.cameraSelect.disabled = videoDevices.length < 2;
    ui.switchCamera.disabled = videoDevices.length < 2;
  }

  function updateNativePreviewBounds() {
    if (!nativeScannerSupported()) return;
    const viewport = document.getElementById("viewport");
    const bounds = viewport.getBoundingClientRect();
    const width = Math.max(1, window.innerWidth);
    const height = Math.max(1, window.innerHeight);
    window.AndroidBridge.setPreviewBounds(bounds.left / width, bounds.top / height, bounds.width / width, bounds.height / height);
    if (typeof window.AndroidBridge.setNativePreviewVisible === "function") {
      window.AndroidBridge.setNativePreviewVisible(nativePipelineActive && bounds.bottom > 0 && bounds.top < height);
    }
  }

  function handleNativeScannerStarted(cameraId) {
    if (!nativePipelineActive) return;
    activeDeviceId = cameraId || activeDeviceId;
    if (activeDeviceId) savePreferredCamera(activeDeviceId);
    renderNativeCameraDevices();
    ui.start.textContent = "原生相机已开启";
    setStatus(active ? "继续接收" : "正在寻找发送端", "active");
    setMessage("原生 ZXing-C++ 已启动。保持完整二维码和四周白边位于取景框内。", "success");
  }

  function handleNativeScannerError(message) {
    nativePipelineActive = false;
    scanning = false;
    ui.placeholder.hidden = false;
    ui.start.disabled = false;
    ui.start.textContent = "重新尝试";
    ui.stop.disabled = true;
    setError(message || "原生扫描器发生未知错误");
  }

  async function handleNativeScanSample(raw, sizeRatio, metrics) {
    if (!nativePipelineActive || !scanning || nativeResultBusy) return;
    const now = performance.now();
    const timing = performanceTimingEnabled ? createTimingSample("zxingcpp") : null;
    if (timing) {
      timing.cameraInterval = finiteDuration(metrics?.cameraInterval);
      timing.roiCrop = finiteDuration(metrics?.roiCrop);
      timing.detector = finiteDuration(metrics?.decoder);
      timing.nativeCombined = finiteDuration(metrics?.decoder);
      timing.nativeResult = finiteDuration(metrics?.result);
      timing.total = finiteDuration(metrics?.total);
      timing.detected = Boolean(raw);
      timing.relocated = Boolean(metrics?.fullLocate);
      timing.qrDetect = finiteDuration(metrics?.decoder);
      failureRelocateCount = Number.isFinite(metrics?.failureRelocateCount) ? metrics.failureRelocateCount : failureRelocateCount;
    }
    if (raw) {
      lastDetectedAt = now;
      updateDistanceGuide(sizeRatio);
    } else if (now - lastDetectedAt > 1200) {
      updateDistanceGuide(null);
    }
    nativeResultBusy = true;
    try {
      if (raw && (raw !== lastRaw || now - lastRawAt > 180)) {
        lastRaw = raw;
        lastRawAt = now;
        const protocolStarted = timing ? performance.now() : 0;
        activeTimingSample = timing;
        await processFrame(raw);
        if (timing) {
          const protocolElapsed = performance.now() - protocolStarted;
          timing.protocol += protocolElapsed;
          timing.total += protocolElapsed;
        }
      }
    } finally {
      activeTimingSample = null;
      if (timing) recordTimingSample(timing);
      nativeResultBusy = false;
    }
  }

  function stopCamera() {
    scanning = false;
    resetQrTracking();
    if (nativePipelineActive) {
      try { window.AndroidBridge.stopNativeScanner(); } catch {}
      nativePipelineActive = false;
      nativeResultBusy = false;
    }
    if (stream) {
      for (const track of stream.getTracks()) track.stop();
    }
    stream = null;
    activeDeviceId = "";
    ui.video.srcObject = null;
    ui.placeholder.hidden = false;
    updateDistanceGuide(null);
    ui.start.disabled = false;
    ui.start.textContent = "开始扫描";
    ui.stop.disabled = true;
    ui.cameraSelect.disabled = videoDevices.length < 2;
    ui.switchCamera.disabled = videoDevices.length < 2;
    ui.engine.textContent = "摄像头已停止";
    if (!completedBlob) setStatus(active ? "接收已暂停" : "尚未开始", "idle");
  }

  function cameraConstraints(deviceId) {
    return {
      audio: false,
      video: deviceId ? {
        deviceId: { exact: deviceId },
        width: { ideal: 1920 },
        height: { ideal: 1080 },
        frameRate: { ideal: 30 }
      } : {
        facingMode: { ideal: "environment" },
        width: { ideal: 1920 },
        height: { ideal: 1080 },
        frameRate: { ideal: 30 }
      }
    };
  }

  async function openCameraStream(deviceId) {
    return navigator.mediaDevices.getUserMedia(cameraConstraints(deviceId));
  }

  async function refreshCameraDevices() {
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      videoDevices = devices.filter(device => device.kind === "videoinput").sort((first, second) => cameraScore(second) - cameraScore(first));
      const currentId = stream?.getVideoTracks?.()[0]?.getSettings?.().deviceId || activeDeviceId;
      activeDeviceId = currentId || activeDeviceId;
      ui.cameraSelect.replaceChildren();
      videoDevices.forEach((device, index) => {
        const option = document.createElement("option");
        option.value = device.deviceId;
        option.textContent = cameraDisplayName(device, index);
        ui.cameraSelect.appendChild(option);
      });
      if (activeDeviceId && videoDevices.some(device => device.deviceId === activeDeviceId)) ui.cameraSelect.value = activeDeviceId;
      ui.cameraControls.hidden = videoDevices.length === 0;
      ui.cameraSelect.disabled = videoDevices.length < 2;
      ui.switchCamera.disabled = videoDevices.length < 2;
    } catch (error) {
      console.warn("Unable to enumerate cameras", error);
      videoDevices = [];
      ui.cameraControls.hidden = true;
    }
  }

  function choosePreferredCamera(devices, savedDeviceId) {
    if (!devices.length) return null;
    if (savedDeviceId) {
      const saved = devices.find(device => device.deviceId === savedDeviceId);
      if (saved) return saved;
    }
    const nonFront = devices.filter(device => !/(front|user|前置|自拍)/i.test(device.label || ""));
    const candidates = nonFront.length ? nonFront : devices;
    return [...candidates].sort((first, second) => cameraScore(second) - cameraScore(first))[0];
  }

  function cameraScore(device) {
    const label = (device.label || "").toLowerCase();
    let score = 0;
    if (/(back|rear|environment|后置)/i.test(label)) score += 80;
    if (/(main|primary|主摄|标准|\b1x\b)/i.test(label)) score += 140;
    if (/(camera2\s*0|camera\s*0)/i.test(label)) score += 35;
    if (/(ultra.?wide|wide.?angle|超广角|超广|\b0[.,]5x\b)/i.test(label)) score -= 220;
    if (/(tele|zoom|periscope|长焦|潜望)/i.test(label)) score -= 130;
    if (/(macro|depth|微距|景深)/i.test(label)) score -= 100;
    if (/(front|user|前置|自拍)/i.test(label)) score -= 500;
    return score;
  }

  document.documentElement.dataset.qtxCameraReady = "true";

  function cameraDisplayName(device, index) {
    const label = (device.label || "").trim();
    const suffix = device.deviceId === activeDeviceId ? "（当前）" : "";
    return `${label || `摄像头 ${index + 1}`}${suffix}`;
  }

  async function switchToCamera(deviceId, rememberChoice) {
    if (!deviceId || switchingCamera || deviceId === activeDeviceId) return;
    switchingCamera = true;
    ui.cameraSelect.disabled = true;
    ui.switchCamera.disabled = true;
    setStatus("正在切换摄像头", "active");
    try {
      if (nativePipelineActive) {
        activeDeviceId = deviceId;
        if (rememberChoice) savePreferredCamera(deviceId);
        lastRaw = "";
        resetQrTracking();
        window.AndroidBridge.configureNativeScanner(effectiveRelocationMode(), relocationInterval, performanceTimingEnabled);
        window.AndroidBridge.startNativeScanner(deviceId);
        renderNativeCameraDevices();
      } else {
        await replaceCameraStream(deviceId, rememberChoice);
      }
      setStatus(active ? "继续接收" : "正在寻找发送端", "active");
      setMessage(`已切换到：${selectedCameraName()}`, "success");
    } catch (error) {
      setError(`切换摄像头失败：${error?.message || error}`);
    } finally {
      switchingCamera = false;
      ui.cameraSelect.disabled = videoDevices.length < 2;
      ui.switchCamera.disabled = videoDevices.length < 2;
    }
  }

  async function replaceCameraStream(deviceId, rememberChoice) {
    const previousId = activeDeviceId;
    const wasScanning = scanning;
    let switchError = null;
    scanning = false;
    if (stream) for (const track of stream.getTracks()) track.stop();
    stream = null;
    try {
      stream = await openCameraStream(deviceId);
    } catch (error) {
      switchError = error;
      if (previousId && previousId !== deviceId) stream = await openCameraStream(previousId);
      else throw error;
    }
    ui.video.srcObject = stream;
    await ui.video.play();
    activeDeviceId = stream.getVideoTracks()[0]?.getSettings?.().deviceId || deviceId;
    if (rememberChoice && activeDeviceId === deviceId) savePreferredCamera(deviceId);
    await refreshCameraDevices();
    lastRaw = "";
    resetQrTracking();
    if (wasScanning) {
      scanning = true;
      requestAnimationFrame(scanLoop);
    }
    if (switchError) throw switchError;
  }

  async function cycleCamera() {
    if (videoDevices.length < 2) return;
    const currentIndex = Math.max(0, videoDevices.findIndex(device => device.deviceId === activeDeviceId));
    const next = videoDevices[(currentIndex + 1) % videoDevices.length];
    await switchToCamera(next.deviceId, true);
  }

  function selectedCameraName() {
    const index = videoDevices.findIndex(device => device.deviceId === activeDeviceId);
    return index >= 0 ? cameraDisplayName(videoDevices[index], index).replace("（当前）", "") : "已选择的摄像头";
  }

  function loadPreferredCamera() {
    try { return localStorage.getItem(CAMERA_STORAGE_KEY) || ""; } catch { return ""; }
  }

  function savePreferredCamera(deviceId) {
    try { localStorage.setItem(CAMERA_STORAGE_KEY, deviceId); } catch {}
  }

  function clearPreferredCamera() {
    try { localStorage.removeItem(CAMERA_STORAGE_KEY); } catch {}
  }

  function loadDecoderPreference() {
    try {
      const saved = localStorage.getItem(DECODER_STORAGE_KEY);
      return ["auto", "zxingcpp", "native", "jsqr"].includes(saved) ? saved : "auto";
    } catch {
      return "auto";
    }
  }

  function saveDecoderPreference(value) {
    try { localStorage.setItem(DECODER_STORAGE_KEY, value); } catch {}
  }

  function loadRelocationMode() {
    try { return localStorage.getItem(RELOCATION_MODE_STORAGE_KEY) === "fixed" ? "fixed" : "adaptive"; }
    catch { return "adaptive"; }
  }

  function loadRelocationInterval() {
    try { return clampRelocationInterval(Number.parseInt(localStorage.getItem(RELOCATION_INTERVAL_STORAGE_KEY) || "30", 10)); }
    catch { return 30; }
  }

  function clampRelocationInterval(value) {
    return Number.isFinite(value) ? Math.max(2, Math.min(300, Math.round(value))) : 30;
  }

  function effectiveDecoderPreference() {
    return developerModeEnabled ? decoderPreference : "auto";
  }

  function effectiveRelocationMode() {
    return developerModeEnabled ? relocationMode : "adaptive";
  }

  function toggleDeveloperMode() {
    developerModeEnabled = ui.developerMode.checked;
    if (!developerModeEnabled && performanceTimingEnabled) {
      ui.timingToggle.checked = false;
      togglePerformanceTiming();
    }
    lastRaw = "";
    resetQrTracking();
    resetPerformanceStats();
    renderDeveloperMode();
    renderRelocationControls();
    if (scanning) {
      stopCamera();
      startCamera();
    } else {
      applyDecoderPreference(true);
    }
  }

  function renderDeveloperMode() {
    ui.developerPanel.hidden = !developerModeEnabled;
    ui.performanceCard.hidden = !developerModeEnabled;
    ui.developerModeLabel.textContent = developerModeEnabled ? "开启" : "关闭";
  }

  function changeRelocationSettings() {
    relocationMode = ui.relocationMode.value === "fixed" ? "fixed" : "adaptive";
    relocationInterval = clampRelocationInterval(Number.parseInt(ui.relocationInterval.value, 10));
    ui.relocationInterval.value = String(relocationInterval);
    try {
      localStorage.setItem(RELOCATION_MODE_STORAGE_KEY, relocationMode);
      localStorage.setItem(RELOCATION_INTERVAL_STORAGE_KEY, String(relocationInterval));
    } catch {}
    resetQrTracking();
    resetPerformanceStats();
    renderRelocationControls();
    if (nativePipelineActive) {
      try { window.AndroidBridge.configureNativeScanner(effectiveRelocationMode(), relocationInterval, performanceTimingEnabled); } catch {}
    }
    setMessage(relocationMode === "adaptive"
      ? "jsQR 已切换为自适应重定位：缓存连续失败2次后执行完整定位。"
      : `jsQR 将每 ${relocationInterval} 帧完整定位一次，缓存连续失败时也会提前定位。`, "info");
  }

  function renderRelocationControls() {
    const fixed = relocationMode === "fixed";
    ui.relocationIntervalLabel.hidden = !fixed;
    ui.applyRelocation.hidden = !fixed;
    ui.relocationDescription.textContent = fixed
      ? `每 ${relocationInterval} 帧执行一次完整定位；缓存连续失败2次时不等待间隔，下一帧立即重新定位。`
      : "不做定期完整定位；仅在缓存位置连续解码失败2次后，下一帧执行完整定位。";
  }

  async function changeDecoderPreference() {
    decoderPreference = ui.decoderMode.value;
    saveDecoderPreference(decoderPreference);
    lastRaw = "";
    resetQrTracking();
    resetPerformanceStats("", true);
    if (scanning) {
      stopCamera();
      await startCamera();
      return;
    }
    if (decoderPreference === "native" && !nativeDetectorAvailable) {
      await probeBarcodeDetector();
    }
    applyDecoderPreference(true);
  }

  async function probeBarcodeDetector() {
    detector = null;
    nativeDetectorAvailable = false;
    nativeDetectorFailed = false;
    if (!("BarcodeDetector" in window)) {
      updateDecoderAvailability("当前 WebView 不提供 BarcodeDetector API", false);
      return;
    }
    try {
      const formats = await BarcodeDetector.getSupportedFormats();
      if (!formats.includes("qr_code")) {
        updateDecoderAvailability("BarcodeDetector 存在，但不支持 qr_code", false);
        return;
      }
      detector = new BarcodeDetector({ formats: ["qr_code"] });
      nativeDetectorAvailable = true;
      updateDecoderAvailability("BarcodeDetector 可用，可以与 jsQR 对照测试", true);
    } catch (error) {
      updateDecoderAvailability(`BarcodeDetector 初始化失败：${error?.message || error}`, false);
    }
  }

  function updateDecoderAvailability(text, available) {
    ui.decoderAvailability.textContent = text;
    ui.decoderAvailability.className = available ? "available" : "unavailable";
  }

  function applyDecoderPreference(announce) {
    const preference = effectiveDecoderPreference();
    if ((preference === "auto" || preference === "zxingcpp") && nativeScannerSupported()) {
      activeDecoderEngine = "zxingcpp";
    } else if (preference === "native") {
      activeDecoderEngine = nativeDetectorAvailable && detector ? "native" : "";
    } else if (preference === "jsqr") {
      activeDecoderEngine = "jsqr";
    } else {
      activeDecoderEngine = nativeDetectorAvailable && detector && !nativeDetectorFailed ? "native" : "jsqr";
    }

    if (activeDecoderEngine === "zxingcpp") {
      ui.engine.textContent = `解码引擎：原生 CameraX Y Plane → ZXing-C++ · 单任务${preference === "auto" ? "（自动）" : "（强制）"}`;
      if (announce) setMessage("已选择原生 ZXing-C++；相机帧忙碌时由 KEEP_ONLY_LATEST 丢弃旧帧。", "info");
    } else if (activeDecoderEngine === "native") {
      ui.engine.textContent = `解码引擎：浏览器原生 BarcodeDetector · 单任务${preference === "auto" ? "（自动）" : "（强制）"}`;
      if (announce) setMessage("已切换到单任务 BarcodeDetector；解码忙碌时直接跳过相机帧。", "info");
    } else if (activeDecoderEngine === "jsqr") {
      ui.engine.textContent = `解码引擎：本地 jsQR · 单任务${preference === "auto" ? "（自动回退）" : "（强制）"}`;
      if (announce) setMessage("已切换到单任务本地 jsQR；可以查看分阶段耗时。", "info");
    } else {
      ui.engine.textContent = "解码引擎：强制 BarcodeDetector，但当前设备不可用";
      setMessage("当前 WebView 无法使用 BarcodeDetector。请选择“本地 jsQR”或“自动选择”。", "warn");
    }
  }

  async function scanLoop(now) {
    if (!scanning) return;
    requestAnimationFrame(scanLoop);
    if (!activeDecoderEngine || decoding || ui.video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA || now - lastDecodeAt < 55) return;

    decoding = true;
    lastDecodeAt = now;
    const engine = activeDecoderEngine;
    const timing = performanceTimingEnabled ? createTimingSample(engine) : null;
    const totalStarted = timing ? performance.now() : 0;
    activeTimingSample = timing;
    try {
      const detectorStarted = timing ? performance.now() : 0;
      const detection = engine === "native" ? await decodeNative(timing) : decodeWithJsQr(timing);
      const resultReadyAt = timing ? performance.now() : 0;
      if (timing) timing.detector += resultReadyAt - detectorStarted;
      const resultHandlingStarted = timing ? performance.now() : 0;
      if (timing) timing.resultWait += resultHandlingStarted - resultReadyAt;
      const raw = detection?.rawValue;
      if (timing) timing.detected = Boolean(raw);
      if (detection) {
        lastDetectedAt = now;
        updateDistanceGuide(detection.sizeRatio);
      } else if (now - lastDetectedAt > 1200) {
        updateDistanceGuide(null);
      }
      if (raw && (raw !== lastRaw || now - lastRawAt > 180)) {
        lastRaw = raw;
        lastRawAt = now;
        const protocolStarted = timing ? performance.now() : 0;
        const uiBeforeProtocol = timing ? timing.uiUpdate : 0;
        await processFrame(raw);
        if (timing) timing.protocol += Math.max(0, performance.now() - protocolStarted - (timing.uiUpdate - uiBeforeProtocol));
      }
    } catch (error) {
      if (engine === "native") {
        console.warn("Native detector failed", error);
        detector = null;
        nativeDetectorFailed = true;
        nativeDetectorAvailable = false;
        updateDecoderAvailability(`BarcodeDetector 运行失败：${error?.message || error}`, false);
        applyDecoderPreference(false);
        if (effectiveDecoderPreference() === "auto") {
          setMessage("BarcodeDetector 运行失败，自动切换到本地 jsQR。", "warn");
        } else {
          setStatus("BarcodeDetector 运行失败", "error");
          setMessage("当前选择的是强制 BarcodeDetector，因此没有自动回退。请手动选择本地 jsQR。", "error");
        }
      }
    } finally {
      if (timing) timing.total = performance.now() - totalStarted;
      activeTimingSample = null;
      if (timing) recordTimingSample(timing);
      decoding = false;
    }
  }

  async function decodeNative(timing) {
    const started = timing ? performance.now() : 0;
    const results = await detector.detect(ui.video);
    if (timing) timing.nativeCombined += performance.now() - started;
    const resultStarted = timing ? performance.now() : 0;
    if (!results.length) {
      if (timing) timing.nativeResult += performance.now() - resultStarted;
      return null;
    }
    const result = results[0];
    const bounds = result.boundingBox;
    const referenceSide = Math.max(1, Math.min(ui.video.videoWidth, ui.video.videoHeight));
    const detectedSide = bounds ? Math.max(bounds.width, bounds.height) : 0;
    const output = { rawValue: result.rawValue, sizeRatio: detectedSide > 0 ? detectedSide / referenceSide : null };
    if (timing) timing.nativeResult += performance.now() - resultStarted;
    return output;
  }

  function decodeWithJsQr(timing) {
    if (typeof window.jsQR !== "function") {
      throw new Error("离线解码库 jsQR 未加载。");
    }

    const sourceWidth = ui.video.videoWidth;
    const sourceHeight = ui.video.videoHeight;
    if (!sourceWidth || !sourceHeight) return null;

    const scale = Math.min(1, 1100 / sourceWidth);
    const fullWidth = Math.max(1, Math.round(sourceWidth * scale));
    const fullHeight = Math.max(1, Math.round(sourceHeight * scale));
    jsQrFrameCounter++;
    const cacheMissing = !cachedQrLocation || !cachedQrRoi;
    const dimensionsChanged = Boolean(cachedQrRoi) && (cachedQrRoi.fullWidth !== fullWidth || cachedQrRoi.fullHeight !== fullHeight);
    const periodicRelocate = effectiveRelocationMode() === "fixed" && jsQrFrameCounter % relocationInterval === 0;
    const relocate = cacheMissing || dimensionsChanged || periodicRelocate;
    const relocationReason = failureRelocatePending && relocate
      ? "failure"
      : dimensionsChanged ? "dimensions" : periodicRelocate ? "periodic" : cacheMissing ? "initial" : "cached";
    if (timing) {
      timing.relocated = relocate;
      timing.relocationReason = relocationReason;
    }
    if (relocate) failureRelocatePending = false;
    const width = relocate ? fullWidth : cachedQrRoi.width;
    const height = relocate ? fullHeight : cachedQrRoi.height;
    if (ui.canvas.width !== width || ui.canvas.height !== height) {
      ui.canvas.width = width;
      ui.canvas.height = height;
    }

    let started = timing ? performance.now() : 0;
    if (relocate) {
      context.drawImage(ui.video, 0, 0, width, height);
    } else {
      context.drawImage(
        ui.video,
        cachedQrRoi.x / scale,
        cachedQrRoi.y / scale,
        cachedQrRoi.width / scale,
        cachedQrRoi.height / scale,
        0,
        0,
        width,
        height
      );
    }
    if (timing) timing.cameraCapture += performance.now() - started;
    started = timing ? performance.now() : 0;
    const image = context.getImageData(0, 0, width, height);
    if (timing) timing.frameCopy += performance.now() - started;

    const result = window.jsQR(image.data, width, height, {
      inversionAttempts: "dontInvert",
      qtxCachedLocation: relocate ? null : cachedQrLocation,
      qtxRelocate: relocate,
      qtxTiming: Boolean(timing)
    });
    const internal = timing ? window.jsQR.lastTiming : null;
    if (timing && internal) {
      timing.roiCrop += finiteDuration(internal.roiCrop);
      timing.grayscale += finiteDuration(internal.grayscale);
      timing.qrDetect += finiteDuration(internal.qrDetect);
      timing.qrDecode += finiteDuration(internal.qrDecode);
      timing.rsCorrection += finiteDuration(internal.rsCorrection);
    }
    if (!result) {
      if (!relocate && cachedQrLocation && cachedQrRoi) {
        cachedQrFailureCount++;
      } else {
        cachedQrFailureCount = 0;
      }
      if (cachedQrFailureCount >= CACHED_FAILURE_RELOCATE_THRESHOLD) {
        cachedQrLocation = null;
        cachedQrRoi = null;
        cachedQrSizeRatio = null;
        cachedQrFailureCount = 0;
        failureRelocatePending = true;
        failureRelocateCount++;
      }
      return null;
    }
    cachedQrFailureCount = 0;
    const points = result.location;
    const sides = [
      pointDistance(points.topLeftCorner, points.topRightCorner),
      pointDistance(points.topRightCorner, points.bottomRightCorner),
      pointDistance(points.bottomRightCorner, points.bottomLeftCorner),
      pointDistance(points.bottomLeftCorner, points.topLeftCorner)
    ];
    const detectedSide = sides.reduce((sum, side) => sum + side, 0) / sides.length;
    cachedQrSizeRatio = detectedSide / Math.max(1, Math.min(fullWidth, fullHeight));
    if (window.jsQR.lastLocation) {
      if (relocate) {
        cachedQrRoi = buildQrRoi(points, fullWidth, fullHeight);
        cachedQrLocation = translateQrLocation(window.jsQR.lastLocation, -cachedQrRoi.x, -cachedQrRoi.y);
      } else {
        cachedQrLocation = window.jsQR.lastLocation;
      }
    }
    return { rawValue: result.data, sizeRatio: cachedQrSizeRatio };
  }

  function buildQrRoi(points, fullWidth, fullHeight) {
    const corners = [points.topLeftCorner, points.topRightCorner, points.bottomRightCorner, points.bottomLeftCorner];
    const minX = Math.min(...corners.map(point => point.x));
    const maxX = Math.max(...corners.map(point => point.x));
    const minY = Math.min(...corners.map(point => point.y));
    const maxY = Math.max(...corners.map(point => point.y));
    const side = Math.max(maxX - minX, maxY - minY);
    const margin = Math.max(12, side * 0.14);
    const x = Math.max(0, Math.floor(minX - margin));
    const y = Math.max(0, Math.floor(minY - margin));
    const right = Math.min(fullWidth, Math.ceil(maxX + margin));
    const bottom = Math.min(fullHeight, Math.ceil(maxY + margin));
    return { x, y, width: Math.max(1, right - x), height: Math.max(1, bottom - y), fullWidth, fullHeight };
  }

  function translateQrLocation(location, offsetX, offsetY) {
    const move = point => point ? { x: point.x + offsetX, y: point.y + offsetY } : point;
    return {
      topLeft: move(location.topLeft),
      topRight: move(location.topRight),
      bottomLeft: move(location.bottomLeft),
      alignmentPattern: move(location.alignmentPattern),
      dimension: location.dimension
    };
  }

  function resetQrTracking() {
    cachedQrLocation = null;
    cachedQrRoi = null;
    cachedQrSizeRatio = null;
    jsQrFrameCounter = 0;
    cachedQrFailureCount = 0;
    failureRelocatePending = false;
    if (typeof window.jsQR === "function") window.jsQR.lastLocation = null;
  }

  function pointDistance(first, second) {
    return Math.hypot(first.x - second.x, first.y - second.y);
  }

  function updateDistanceGuide(sizeRatio) {
    const uiStarted = activeTimingSample ? performance.now() : 0;
    ui.distanceGuide.className = "distance-guide";
    if (!Number.isFinite(sizeRatio)) {
      ui.distanceTitle.textContent = "把完整二维码放入方框";
      ui.distanceDetail.textContent = "四周白边不要超出框外";
    } else if (sizeRatio < 0.32) {
      ui.distanceGuide.classList.add("far");
      ui.distanceTitle.textContent = "二维码偏小，请靠近屏幕";
      ui.distanceDetail.textContent = "让二维码占据方框的大部分区域";
    } else if (sizeRatio > 0.78) {
      ui.distanceGuide.classList.add("near");
      ui.distanceTitle.textContent = "距离太近，请后退一些";
      ui.distanceDetail.textContent = "确保二维码四边和白边都看得见";
    } else {
      ui.distanceGuide.classList.add("good");
      ui.distanceTitle.textContent = "距离合适，请保持稳定";
      ui.distanceDetail.textContent = "已识别到二维码，正在接收数据";
    }
    addUiTiming(uiStarted);
  }

  async function processFrame(raw) {
    if (typeof raw !== "string" || raw.length < HEADER_LENGTH || raw.slice(0, 4) !== MAGIC) return;

    let frame;
    try {
      frame = parseFrame(raw);
    } catch {
      crcErrorCount++;
      updateMetrics();
      return;
    }

    validFrameCount++;
    updateMetrics();

    if (frame.type === "S") {
      await handleStartFrame(frame);
    } else if (frame.type === "D" || frame.type === "R" || frame.type === "W") {
      await handleDataFrame(frame);
    } else if (frame.type === "E") {
      handleEndFrame(frame);
    }
  }

  function parseFrame(raw) {
    const type = raw[4];
    const sessionId = raw.slice(5, 15);
    const indexText = raw.slice(15, 21);
    const totalText = raw.slice(21, 27);
    const crcText = raw.slice(27, 35);

    if (!/[SDERW]/.test(type) || !/^[0-9A-F]{10}$/.test(sessionId) ||
        !/^[0-9A-Z]{6}$/.test(indexText) || !/^[0-9A-Z]{6}$/.test(totalText) ||
        !/^[0-9A-F]{8}$/.test(crcText)) {
      throw new Error("invalid header");
    }

    const payload = decodeBase45(raw.slice(HEADER_LENGTH));
    const expectedCrc = Number.parseInt(crcText, 16) >>> 0;
    if (crc32(payload) !== expectedCrc) throw new Error("CRC mismatch");

    return {
      type,
      sessionId,
      index: Number.parseInt(indexText, 36),
      total: Number.parseInt(totalText, 36),
      payload
    };
  }

  async function handleStartFrame(frame) {
    let metadata;
    try {
      metadata = JSON.parse(new TextDecoder().decode(frame.payload));
      validateMetadata(metadata, frame.total);
    } catch (error) {
      crcErrorCount++;
      updateMetrics();
      setMessage(`元数据无效：${error.message}`, "error");
      return;
    }

    if (active && active.id !== frame.sessionId) {
      if (foreignSessionNotice !== frame.sessionId) {
        foreignSessionNotice = frame.sessionId;
        setMessage("检测到另一项传输。请先完成当前任务，或点击“清除当前任务”后重新扫描。", "warn");
      }
      return;
    }

    if (!active) {
      startTransferTimer();
      active = { id: frame.sessionId, meta: metadata };
      received = new Set();
      resetMissingTracking();
      receivedBytes = 0;
      completedBlob = null;
      completedFile = null;
      ui.completeCard.hidden = true;
      const stored = await getStoredSummary(frame.sessionId);
      for (const item of stored) {
        if (item.i >= 0 && item.i < metadata.t) {
          received.add(item.i);
          receivedBytes += item.length;
        }
      }
      showFileMetadata();
      setStatus(stored.length ? "已恢复进度，继续接收" : "已连接发送端", "active");
      setMessage(stored.length ? `从本机恢复了 ${stored.length} 个分片。` : "元数据已收到，正在接收文件分片。", "info");
      updateProgress();
      if (received.size === metadata.t) await finalizeTransfer();
    }
  }

  async function handleDataFrame(frame) {
    if (!active) {
      setStatus("等待元数据", "active");
      setMessage("已经看到数据帧，等待电脑下一次重发元数据。", "info");
      return;
    }

    if (frame.sessionId !== active.id || frame.total !== active.meta.t || frame.index < 0 || frame.index >= active.meta.t) return;
    if ((frame.type === "R" || frame.type === "W") && frame.payload.length < 1) {
      crcErrorCount++;
      updateMetrics();
      return;
    }
    let chunkPayload;
    if (frame.type === "W") {
      const seed = frame.payload[0];
      chunkPayload = frame.payload.slice(1);
      applyPayloadWhitening(chunkPayload, frame.sessionId, frame.index, seed);
    } else {
      chunkPayload = frame.type === "R" ? frame.payload.slice(1) : frame.payload;
    }
    if (chunkPayload.length > active.meta.c || (frame.index < active.meta.t - 1 && chunkPayload.length !== active.meta.c)) {
      crcErrorCount++;
      updateMetrics();
      return;
    }

    if (received.has(frame.index)) {
      duplicateCount++;
      updateMetrics();
      return;
    }

    await storeChunk(active.id, frame.index, chunkPayload);
    received.add(frame.index);
    if (missingIndexes) missingIndexes.delete(frame.index);
    receivedBytes += chunkPayload.length;
    setStatus("正在接收", "active");
    updateProgress();

    if (received.size === active.meta.t) await finalizeTransfer();
  }

  function handleEndFrame(frame) {
    if (!active || frame.sessionId !== active.id) return;
    const missing = active.meta.t - received.size;
    if (missing > 0) {
      const nearComplete = received.size / active.meta.t >= 0.95;
      setMessage(nearComplete
        ? `本轮结束，还缺 ${missing} 帧；可按上方缺失编号在电脑端指定补帧。`
        : `本轮结束，还缺 ${missing} 帧；保持扫描，电脑下一轮会自动补齐。`, "warn");
    }
  }

  function validateMetadata(meta, total) {
    if (!meta || meta.v !== 1) throw new Error("不支持的协议版本");
    if (typeof meta.n !== "string" || meta.n.length < 1 || meta.n.length > 240) throw new Error("文件名不合法");
    if (typeof meta.m !== "string" || meta.m.length > 160) throw new Error("MIME 类型不合法");
    if (!Number.isSafeInteger(meta.s) || meta.s < 0 || meta.s > MAX_FILE_BYTES) throw new Error("文件大小超出限制");
    if (!Number.isSafeInteger(meta.c) || meta.c < 100 || meta.c > 2800) throw new Error("分片大小不合法");
    if (!Number.isSafeInteger(meta.t) || meta.t < 1 || meta.t > 300000 || meta.t !== total) throw new Error("分片总数不合法");
    if (!/^[0-9A-F]{64}$/.test(meta.h)) throw new Error("SHA-256 不合法");
    if (meta.z !== "NONE" || meta.e !== "NONE") throw new Error("当前版本不支持压缩或加密");
    if (meta.w !== undefined && meta.w !== "XORSHIFT32-V1") throw new Error("Payload 白化参数不受支持");
    const expectedChunks = Math.max(1, Math.ceil(meta.s / meta.c));
    if (expectedChunks !== meta.t) throw new Error("文件大小与分片数不一致");
  }

  async function finalizeTransfer() {
    setStatus("正在组装并校验", "active");
    setMessage("全部分片已经收到，正在计算 SHA-256……", "info");

    try {
      const chunks = await loadChunks(active.id);
      if (chunks.length !== active.meta.t) throw new Error("临时存储中的分片数量不完整");
      chunks.sort((a, b) => a.i - b.i);

      const output = new Uint8Array(active.meta.s);
      let offset = 0;
      for (let i = 0; i < chunks.length; i++) {
        if (chunks[i].i !== i) throw new Error(`缺少第 ${i + 1} 个分片`);
        const bytes = new Uint8Array(chunks[i].data);
        if (offset + bytes.length > output.length) throw new Error("拼接后的数据超出声明长度");
        output.set(bytes, offset);
        offset += bytes.length;
      }
      if (offset !== active.meta.s) throw new Error("拼接后的文件长度不一致");

      if (!crypto.subtle) throw new Error("当前打开方式不支持安全哈希 API");
      const digest = await crypto.subtle.digest("SHA-256", output);
      const hash = toHex(new Uint8Array(digest));
      if (hash !== active.meta.h) throw new Error("SHA-256 不一致，文件不能保存");

      completedBlob = new Blob([output], { type: active.meta.m || "application/octet-stream" });
      completedFile = new File([completedBlob], sanitizeFileName(active.meta.n), { type: completedBlob.type });
      completeTransferTimer();
      setStatus("接收成功", "success");
      setMessage("整文件 SHA-256 校验通过。现在可以停止电脑端发送。", "success");
      ui.completeCard.hidden = false;
      ui.completeSummary.textContent = `${completedFile.name} · ${formatBytes(completedBlob.size)} · 用时 ${ui.transferElapsed.textContent}`;
      ui.preview.hidden = !isPreviewable(active.meta.m, active.meta.n);
      ui.preview.textContent = previewButtonLabel();
      ui.share.hidden = !(navigator.share && navigator.canShare && navigator.canShare({ files: [completedFile] }));
      updateProgress();
      stopCamera();
      setStatus("接收成功", "success");
      if (isVisualPreview(active.meta.m, active.meta.n)) {
        await togglePreview();
      }
    } catch (error) {
      setError(`组装失败：${error.message}`);
    }
  }

  async function downloadCompletedFile() {
    if (!completedBlob || !completedFile) return;
    if (window.AndroidBridge && typeof window.AndroidBridge.beginSave === "function") {
      await saveThroughAndroid();
      return;
    }
    const url = URL.createObjectURL(completedBlob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = completedFile.name;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  }

  async function saveThroughAndroid() {
    ui.download.disabled = true;
    ui.download.textContent = "正在准备保存…";
    try {
      const bytes = new Uint8Array(await completedBlob.arrayBuffer());
      const mimeType = completedBlob.type || "application/octet-stream";
      if (!window.AndroidBridge.beginSave(completedFile.name, mimeType, bytes.length)) {
        throw new Error("无法创建临时保存文件");
      }

      const blockSize = 48 * 1024;
      for (let offset = 0; offset < bytes.length; offset += blockSize) {
        const block = bytes.subarray(offset, Math.min(bytes.length, offset + blockSize));
        if (!window.AndroidBridge.appendSave(bytesToBase64(block))) {
          throw new Error("写入临时保存文件失败");
        }
        if (offset > 0 && offset % (blockSize * 16) === 0) {
          await delay(0);
        }
      }

      if (!window.AndroidBridge.finishSave()) {
        throw new Error("临时保存文件不完整");
      }
      setMessage("请选择手机中的保存位置。", "success");
    } catch (error) {
      if (window.AndroidBridge.cancelSave) window.AndroidBridge.cancelSave();
      setMessage(`保存失败：${error.message}`, "error");
    } finally {
      ui.download.disabled = false;
      ui.download.textContent = "保存文件";
    }
  }

  function bytesToBase64(bytes) {
    let binary = "";
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    return btoa(binary);
  }

  async function togglePreview() {
    if (!completedBlob || !active) return;
    if (!ui.previewPanel.hidden) {
      closePreview();
      return;
    }

    ui.previewPanel.hidden = false;
    ui.preview.textContent = "关闭预览";
    ui.previewMessage.hidden = true;
    ui.textPreview.hidden = true;

    if (isTextPreview(active.meta.m, active.meta.n)) {
      ui.videoPreview.hidden = true;
      ui.imagePreview.hidden = true;
      const previewLimit = 1024 * 1024;
      try {
        const textBytes = await completedBlob.slice(0, previewLimit).arrayBuffer();
        ui.textPreview.textContent = new TextDecoder("utf-8").decode(textBytes);
        ui.textPreview.hidden = false;
        if (completedBlob.size > previewLimit) {
          ui.previewMessage.hidden = false;
          ui.previewMessage.textContent = "文字较长，目前只预览前 1 MiB；保存文件可查看完整内容。";
        }
      } catch (error) {
        ui.previewMessage.hidden = false;
        ui.previewMessage.textContent = `文字预览失败：${error.message}。仍可使用“保存文件”。`;
      }
      return;
    }

    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = URL.createObjectURL(completedBlob);
    if (isImagePreview(active.meta.m, active.meta.n)) {
      ui.videoPreview.hidden = true;
      ui.imagePreview.hidden = false;
      ui.imagePreview.src = previewUrl;
    } else {
      ui.imagePreview.hidden = true;
      ui.videoPreview.hidden = false;
      ui.videoPreview.src = previewUrl;
      ui.videoPreview.load();
    }
  }

  function closePreview() {
    ui.videoPreview.pause();
    ui.videoPreview.removeAttribute("src");
    ui.imagePreview.removeAttribute("src");
    ui.videoPreview.load();
    ui.videoPreview.hidden = true;
    ui.imagePreview.hidden = true;
    ui.textPreview.hidden = true;
    ui.textPreview.textContent = "";
    ui.previewMessage.hidden = true;
    ui.previewMessage.textContent = "";
    ui.previewPanel.hidden = true;
    ui.preview.textContent = previewButtonLabel();
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = null;
  }

  function isPreviewable(mimeType, fileName) {
    return isTextPreview(mimeType, fileName)
      || isVisualPreview(mimeType, fileName);
  }

  function isVisualPreview(mimeType, fileName) {
    return isImagePreview(mimeType, fileName) || isVideoPreview(mimeType, fileName);
  }

  function isImagePreview(mimeType, fileName) {
    const normalizedMime = (mimeType || "").split(";", 1)[0].trim().toLowerCase();
    return normalizedMime.startsWith("image/") || /\.(jpe?g|png|gif|webp|bmp|avif|svg)$/i.test(fileName || "");
  }

  function isVideoPreview(mimeType, fileName) {
    const normalizedMime = (mimeType || "").split(";", 1)[0].trim().toLowerCase();
    return normalizedMime.startsWith("video/") || /\.(mp4|m4v|mov|webm|ogv|mkv)$/i.test(fileName || "");
  }

  function isTextPreview(mimeType, fileName) {
    const normalizedMime = (mimeType || "").split(";", 1)[0].trim().toLowerCase();
    return normalizedMime.startsWith("text/")
      || /^(application\/(json|ld\+json|xml|javascript|x-javascript|yaml|x-yaml))$/.test(normalizedMime)
      || /\.(txt|md|markdown|json|jsonl|csv|tsv|log|xml|html?|css|js|mjs|cjs|yaml|yml|ini|conf|cfg|sql|sh|bat|cmd|ps1|py|java|cs|c|h|cpp|hpp)$/i.test(fileName || "");
  }

  function previewButtonLabel() {
    return active && isTextPreview(active.meta.m, active.meta.n) ? "预览文字" : "即时预览";
  }

  function showPreviewError() {
    ui.previewMessage.hidden = false;
    ui.previewMessage.textContent = "文件已经完整接收，但当前手机浏览器不支持这种图片或视频编码。仍可使用“保存文件”。";
  }

  async function shareCompletedFile() {
    if (!completedFile || !navigator.share) return;
    try {
      await navigator.share({ files: [completedFile], title: completedFile.name });
    } catch (error) {
      if (error.name !== "AbortError") setMessage(`分享失败：${error.message}`, "error");
    }
  }

  async function resetTransfer() {
    const sessionId = active?.id;
    if (sessionId) await deleteSession(sessionId);
    closePreview();
    active = null;
    received = new Set();
    resetMissingTracking();
    memoryChunks.clear();
    completedBlob = null;
    completedFile = null;
    validFrameCount = 0;
    duplicateCount = 0;
    crcErrorCount = 0;
    receivedBytes = 0;
    startedAt = scanning ? performance.now() : 0;
    resetTransferTimer();
    resetPerformanceStats();
    foreignSessionNotice = "";
    ui.filePanel.hidden = true;
    ui.completeCard.hidden = true;
    ui.preview.hidden = true;
    setStatus(scanning ? "正在寻找发送端" : "尚未开始", scanning ? "active" : "idle");
    setMessage("当前任务已经清除，可以扫描新的传输。", "info");
    updateProgress();
    updateMetrics();
  }

  async function startNewTransfer() {
    ui.newTransfer.disabled = true;
    try {
      await resetTransfer();
      await startCamera();
    } finally {
      ui.newTransfer.disabled = false;
    }
  }

  function showFileMetadata() {
    ui.filePanel.hidden = false;
    ui.fileName.textContent = active.meta.n;
    ui.fileMeta.textContent = `${formatBytes(active.meta.s)} · ${active.meta.t} 个数据帧 · 会话 ${active.id}`;
  }

  function updateProgress() {
    const total = active ? active.meta.t : 0;
    const count = active ? received.size : 0;
    const percent = total ? Math.min(100, Math.round(count * 1000 / total) / 10) : 0;
    const uiStarted = activeTimingSample ? performance.now() : 0;
    ui.progressFill.style.width = `${percent}%`;
    ui.progressPercent.textContent = `${percent}%`;
    ui.progressDetail.textContent = `${count} / ${total} 帧`;
    ui.progressTrack.setAttribute("aria-valuenow", String(percent));
    addUiTiming(uiStarted);
    updateMissingPanel(total, count);
    updateMetrics();
  }

  function updateMissingPanel(total, count) {
    if (!active || total < 1 || count >= total || count / total < MISSING_FRAME_THRESHOLD) {
      ui.missingCard.hidden = true;
      if (!active || count >= total) resetMissingTracking();
      return;
    }

    if (!missingIndexes) {
      missingIndexes = new Set();
      for (let index = 0; index < total; index++) {
        if (!received.has(index)) missingIndexes.add(index);
      }
    }

    ui.missingCard.hidden = false;
    ui.missingCount.textContent = String(missingIndexes.size);
    scheduleMissingRender();
  }

  function scheduleMissingRender() {
    if (missingRenderTimer) return;
    missingRenderTimer = setTimeout(() => {
      missingRenderTimer = 0;
      if (!missingIndexes || missingIndexes.size === 0) return;
      ui.missingCount.textContent = String(missingIndexes.size);
      ui.missingFrames.textContent = Array.from(missingIndexes, index => String(index + 1)).join("、");
    }, 180);
  }

  function resetMissingTracking() {
    if (missingRenderTimer) clearTimeout(missingRenderTimer);
    missingRenderTimer = 0;
    missingIndexes = null;
    ui.missingCard.hidden = true;
    ui.missingCount.textContent = "0";
    ui.missingFrames.textContent = "";
  }

  function updateMetrics() {
    const uiStarted = activeTimingSample ? performance.now() : 0;
    ui.validFrames.textContent = String(validFrameCount);
    ui.duplicates.textContent = String(duplicateCount);
    ui.crcErrors.textContent = String(crcErrorCount);
    const seconds = startedAt ? Math.max(1, (performance.now() - startedAt) / 1000) : 1;
    ui.speed.textContent = `${formatBytes(receivedBytes / seconds)}/s`;
    addUiTiming(uiStarted);
  }

  function startTransferTimer() {
    if (transferStartedAt) return;
    transferStartedAt = performance.now();
    transferCompletedAt = 0;
    renderTransferTimer();
    if (transferTimerHandle) clearInterval(transferTimerHandle);
    transferTimerHandle = setInterval(renderTransferTimer, 100);
  }

  function completeTransferTimer() {
    if (!transferStartedAt) return;
    transferCompletedAt = performance.now();
    if (transferTimerHandle) clearInterval(transferTimerHandle);
    transferTimerHandle = 0;
    renderTransferTimer();
  }

  function resetTransferTimer() {
    if (transferTimerHandle) clearInterval(transferTimerHandle);
    transferTimerHandle = 0;
    transferStartedAt = 0;
    transferCompletedAt = 0;
    renderTransferTimer();
  }

  function renderTransferTimer() {
    if (!ui.transferElapsed) return;
    const elapsed = transferStartedAt ? Math.max(0, (transferCompletedAt || performance.now()) - transferStartedAt) : 0;
    const totalTenths = Math.floor(elapsed / 100);
    const hours = Math.floor(totalTenths / 36000);
    const minutes = Math.floor(totalTenths / 600) % 60;
    const seconds = Math.floor(totalTenths / 10) % 60;
    const tenths = totalTenths % 10;
    ui.transferElapsed.textContent = hours
      ? `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}.${tenths}`
      : `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}.${tenths}`;
  }

  function createTimingSample(engine) {
    const sample = { engine, detected: false };
    for (const key of TIMING_KEYS) sample[key] = 0;
    return sample;
  }

  function createTimingStats(engine = "") {
    const sums = Object.create(null);
    for (const key of TIMING_KEYS) sums[key] = 0;
    return { engine, count: 0, sums };
  }

  function createEngineComparison() {
    return {
      native: { count: 0, detected: 0, pipeline: 0, total: 0 },
      jsqr: { count: 0, detected: 0, pipeline: 0, total: 0 }
    };
  }

  function createRoiDiagnosticStats() {
    return {
      cached: { count: 0, total: 0 },
      relocate: { count: 0, total: 0, qrDetect: 0 }
    };
  }

  function resetPerformanceStats(engine = "", preserveComparison = false) {
    timingStats = createTimingStats(engine);
    if (!preserveComparison) {
      engineComparison = createEngineComparison();
      roiDiagnosticStats = createRoiDiagnosticStats();
      failureRelocateCount = 0;
    }
    lastTimingRenderAt = 0;
    if (performanceTimingEnabled) renderPerformanceStats(true);
  }

  function togglePerformanceTiming() {
    performanceTimingEnabled = ui.timingToggle.checked;
    if (nativePipelineActive) {
      try { window.AndroidBridge.configureNativeScanner(effectiveRelocationMode(), relocationInterval, performanceTimingEnabled); } catch {}
    }
    activeTimingSample = null;
    ui.timingDetails.hidden = !performanceTimingEnabled;
    ui.timingOffNote.hidden = performanceTimingEnabled;
    ui.timingToggleLabel.textContent = performanceTimingEnabled ? "开启" : "关闭";
    if (performanceTimingEnabled) {
      resetPerformanceStats();
    } else {
      timingStats = createTimingStats();
      engineComparison = createEngineComparison();
      roiDiagnosticStats = createRoiDiagnosticStats();
      failureRelocateCount = 0;
      lastTimingRenderAt = 0;
    }
  }

  function recordTimingSample(sample) {
    if (timingStats.engine && timingStats.engine !== sample.engine) {
      timingStats = createTimingStats(sample.engine);
    } else if (!timingStats.engine) {
      timingStats.engine = sample.engine;
    }

    timingStats.count++;
    for (const key of TIMING_KEYS) timingStats.sums[key] += finiteDuration(sample[key]);
    const comparison = engineComparison[sample.engine === "zxingcpp" ? "native" : sample.engine];
    if (comparison) {
      comparison.count++;
      comparison.detected += sample.detected ? 1 : 0;
      comparison.pipeline += sample.engine === "native" || sample.engine === "zxingcpp"
        ? finiteDuration(sample.nativeCombined) + finiteDuration(sample.nativeResult)
        : finiteDuration(sample.cameraCapture) + finiteDuration(sample.frameCopy) + finiteDuration(sample.roiCrop) +
          finiteDuration(sample.grayscale) + finiteDuration(sample.qrDetect) + finiteDuration(sample.qrDecode) + finiteDuration(sample.rsCorrection);
      comparison.total += finiteDuration(sample.total);
    }
    if ((sample.engine === "jsqr" || sample.engine === "zxingcpp") && typeof sample.relocated === "boolean") {
      const roiStats = sample.relocated ? roiDiagnosticStats.relocate : roiDiagnosticStats.cached;
      roiStats.count++;
      roiStats.total += finiteDuration(sample.total);
      if (sample.relocated) roiStats.qrDetect += finiteDuration(sample.qrDetect);
    }
    const now = performance.now();
    if (timingStats.count <= 2 || now - lastTimingRenderAt >= TIMING_RENDER_INTERVAL) {
      renderPerformanceStats();
      lastTimingRenderAt = now;
    }
  }

  function renderPerformanceStats(force = false) {
    const count = timingStats.count;
    const native = timingStats.engine === "native" || timingStats.engine === "zxingcpp";
    const zxingCpp = timingStats.engine === "zxingcpp";
    ui.timingSamples.textContent = `${count} 帧样本`;
    ui.timingEngine.textContent = count
      ? `当前引擎：${zxingCpp ? "CameraX Y Plane → ZXing-C++" : native ? "浏览器原生 BarcodeDetector" : "本地 jsQR"}`
      : "开启摄像头后开始统计";

    const internalElements = [
      [ui.timingCameraCapture, "cameraCapture"],
      [ui.timingFrameCopy, "frameCopy"],
      [ui.timingRoiCrop, "roiCrop"],
      [ui.timingGrayscale, "grayscale"],
      [ui.timingQrDetect, "qrDetect"],
      [ui.timingQrDecode, "qrDecode"],
      [ui.timingRsCorrection, "rsCorrection"]
    ];
    for (const [element, key] of internalElements) {
      element.textContent = count ? (native && !zxingCpp ? "系统合并" : formatMilliseconds(timingStats.sums[key] / count)) : "—";
    }

    ui.timingCameraInterval.textContent = count && zxingCpp ? formatMilliseconds(timingStats.sums.cameraInterval / count) : "—";

    ui.timingDetector.textContent = count ? formatMilliseconds(timingStats.sums.detector / count) : "—";
    ui.timingResultWait.textContent = count ? formatMilliseconds(timingStats.sums.resultWait / count) : "—";
    ui.timingProtocol.textContent = count ? formatMilliseconds(timingStats.sums.protocol / count) : "—";
    ui.timingUiUpdate.textContent = count ? formatMilliseconds(timingStats.sums.uiUpdate / count) : "—";
    ui.timingTotal.textContent = count ? formatMilliseconds(timingStats.sums.total / count) : "—";
    ui.timingNativeRow.hidden = !native || !count;
    ui.timingNativeResultRow.hidden = !native || !count;
    ui.timingNativeCombined.textContent = count && native ? formatMilliseconds(timingStats.sums.nativeCombined / count) : "—";
    ui.timingNativeResult.textContent = count && native ? formatMilliseconds(timingStats.sums.nativeResult / count) : "—";
    const accountedKeys = zxingCpp
      ? ["roiCrop", "nativeCombined", "nativeResult", "resultWait", "protocol", "uiUpdate"]
      : native
      ? ["nativeCombined", "nativeResult", "resultWait", "protocol", "uiUpdate"]
      : ["cameraCapture", "frameCopy", "roiCrop", "grayscale", "qrDetect", "qrDecode", "rsCorrection", "resultWait", "protocol", "uiUpdate"];
    const accounted = accountedKeys.reduce((sum, key) => sum + timingStats.sums[key], 0);
    ui.timingOverhead.textContent = count ? formatMilliseconds(Math.max(0, (timingStats.sums.total - accounted) / count)) : "—";
    ui.timingNote.textContent = zxingCpp
      ? "ZXing-C++ 直接读取 CameraX Y Plane：Frame copy 与 Grayscale 应接近 0。Camera frame interval 是相邻分析帧的间隔，不计入端到端 TOTAL；Native pipeline combined 是 C++ 定位、二值化、解码和纠错的合并耗时。"
      : native
      ? "BarcodeDetector 保持单任务：忙碌时直接跳过相机帧。Detector 是 detect() 调用耗时；结果排队等待应接近 0；End-to-end TOTAL 还包含协议处理和界面更新。"
      : "本地 jsQR 同样保持单任务。Detector 包含画面读取和 jsQR 处理；结果排队等待应接近 0。ROI crop 表示透视提取，Grayscale 包含二值化。";

    renderRoiDiagnostics();
    renderEngineComparison();
    if (force && !count) {
      ui.timingNativeRow.hidden = true;
      ui.timingNativeResultRow.hidden = true;
    }
  }

  function renderRoiDiagnostics() {
    const cached = roiDiagnosticStats.cached;
    const relocate = roiDiagnosticStats.relocate;
    ui.cachedRoiSamples.textContent = String(cached.count);
    ui.cachedRoiAverage.textContent = cached.count ? formatMilliseconds(cached.total / cached.count) : "—";
    ui.relocateSamples.textContent = String(relocate.count);
    ui.relocateAverage.textContent = relocate.count ? formatMilliseconds(relocate.total / relocate.count) : "—";
    ui.relocateDetectAverage.textContent = relocate.count ? formatMilliseconds(relocate.qrDetect / relocate.count) : "—";
    ui.failureRelocateCount.textContent = String(failureRelocateCount);
  }

  function renderEngineComparison() {
    renderComparisonRow(engineComparison.native, ui.compareNativeSamples, ui.compareNativeRate, ui.compareNativePipeline, ui.compareNativeTotal);
    renderComparisonRow(engineComparison.jsqr, ui.compareJsQrSamples, ui.compareJsQrRate, ui.compareJsQrPipeline, ui.compareJsQrTotal);
  }

  function renderComparisonRow(stats, samplesElement, rateElement, pipelineElement, totalElement) {
    samplesElement.textContent = String(stats.count);
    rateElement.textContent = stats.count ? `${(stats.detected * 100 / stats.count).toFixed(1)}%` : "—";
    pipelineElement.textContent = stats.count ? formatMilliseconds(stats.pipeline / stats.count) : "—";
    totalElement.textContent = stats.count ? formatMilliseconds(stats.total / stats.count) : "—";
  }

  function addUiTiming(started) {
    if (activeTimingSample) activeTimingSample.uiUpdate += performance.now() - started;
  }

  function finiteDuration(value) {
    return Number.isFinite(value) && value >= 0 ? value : 0;
  }

  function formatMilliseconds(value) {
    return `${finiteDuration(value).toFixed(1)} ms`;
  }

  function setStatus(text, state) {
    const uiStarted = activeTimingSample ? performance.now() : 0;
    ui.status.textContent = text;
    ui.dot.className = "status-dot";
    if (state === "active" || state === "success" || state === "error") ui.dot.classList.add(state);
    addUiTiming(uiStarted);
  }

  function setMessage(text, kind) {
    const uiStarted = activeTimingSample ? performance.now() : 0;
    ui.message.textContent = text;
    const colors = { info: "#4ba3ff", warn: "#f5b94c", error: "#ff6f7d", success: "#55d9a5" };
    ui.message.style.borderLeftColor = colors[kind] || colors.info;
    addUiTiming(uiStarted);
  }

  function setError(text) {
    setStatus("发生错误", "error");
    setMessage(text, "error");
  }

  function cameraErrorMessage(error) {
    if (error && error.name === "NotAllowedError") return "摄像头权限被拒绝。请在浏览器设置中允许本页面使用摄像头。";
    if (error && error.name === "NotFoundError") return "没有找到可用摄像头。";
    if (error && error.name === "NotReadableError") return "摄像头正被其他应用占用，请关闭其他相机应用后重试。";
    return `无法启动摄像头：${error && error.message ? error.message : "未知错误"}`;
  }

  function cameraApiUnavailableMessage() {
    const scheme = location.protocol || "未知";
    if (scheme === "file:" || scheme === "content:") {
      return `当前浏览器禁止本地文件（${scheme}//）调用实时摄像头。即使系统已经授予相机和相册权限也不会开放此 API。请通过 HTTPS/PWA 打开，或改用封装后的 Android 安装版。`;
    }
    if (!window.isSecureContext) {
      return `当前页面不是安全上下文（${scheme}//），浏览器不会开放摄像头。需要 HTTPS、localhost 或安装后的 PWA。`;
    }
    return "当前浏览器没有提供实时摄像头 API。请改用最新版 Chrome、Edge 或 Safari，并从 HTTPS/PWA 打开。";
  }

  function showInitialEnvironment() {
    const cameraApi = Boolean(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
    const scheme = location.protocol.replace(":", "").toUpperCase() || "UNKNOWN";
    ui.engine.textContent = `打开方式：${scheme} · 安全上下文：${window.isSecureContext ? "是" : "否"} · 摄像头 API：${cameraApi ? "可用" : "不可用"}`;
    if (!cameraApi) {
      setStatus("当前打开方式不能调用摄像头", "error");
      setMessage(cameraApiUnavailableMessage(), "error");
      ui.start.textContent = "检查摄像头兼容性";
    }
  }

  function delay(milliseconds) {
    return new Promise(resolve => setTimeout(resolve, milliseconds));
  }

  function decodeBase45(text) {
    if (text.length % 3 === 1) throw new Error("invalid Base45 length");
    const output = new Uint8Array(Math.floor(text.length * 2 / 3) + 1);
    let inputIndex = 0;
    let outputIndex = 0;

    while (inputIndex < text.length) {
      const c = base45Value(text[inputIndex]);
      const d = base45Value(text[inputIndex + 1]);
      if (inputIndex + 2 < text.length) {
        const e = base45Value(text[inputIndex + 2]);
        const value = c + d * 45 + e * 2025;
        if (value > 65535) throw new Error("invalid Base45 triplet");
        output[outputIndex++] = Math.floor(value / 256);
        output[outputIndex++] = value % 256;
        inputIndex += 3;
      } else {
        const value = c + d * 45;
        if (value > 255) throw new Error("invalid Base45 pair");
        output[outputIndex++] = value;
        inputIndex += 2;
      }
    }

    return output.slice(0, outputIndex);
  }

  function base45Value(character) {
    const value = BASE45_ALPHABET.indexOf(character);
    if (value < 0) throw new Error("invalid Base45 character");
    return value;
  }

  function crc32(bytes) {
    let crc = 0xFFFFFFFF;
    for (let i = 0; i < bytes.length; i++) {
      crc = CRC_TABLE[(crc ^ bytes[i]) & 0xFF] ^ (crc >>> 8);
    }
    return (crc ^ 0xFFFFFFFF) >>> 0;
  }

  function applyPayloadWhitening(payload, sessionId, index, seed) {
    let state = 2166136261;
    for (let offset = 0; offset < sessionId.length; offset++) {
      state ^= sessionId.charCodeAt(offset);
      state = Math.imul(state, 16777619) >>> 0;
    }
    state ^= Math.imul(index + 1, 0x9E3779B9);
    state ^= Math.imul(seed, 0x85EBCA6B);
    state >>>= 0;
    if (state === 0) state = 0xA5366B4D;
    for (let offset = 0; offset < payload.length; offset++) {
      state ^= state << 13;
      state ^= state >>> 17;
      state ^= state << 5;
      state >>>= 0;
      payload[offset] ^= state & 0xFF;
    }
  }

  function buildCrcTable() {
    const table = new Uint32Array(256);
    for (let i = 0; i < 256; i++) {
      let crc = i;
      for (let bit = 0; bit < 8; bit++) crc = (crc & 1) ? (0xEDB88320 ^ (crc >>> 1)) : (crc >>> 1);
      table[i] = crc >>> 0;
    }
    return table;
  }

  function toHex(bytes) {
    return Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("").toUpperCase();
  }

  function formatBytes(value) {
    if (!Number.isFinite(value) || value <= 0) return "0 B";
    const units = ["B", "KB", "MB", "GB"];
    const index = Math.min(units.length - 1, Math.floor(Math.log(value) / Math.log(1024)));
    const amount = value / Math.pow(1024, index);
    return `${amount >= 100 || index === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[index]}`;
  }

  function sanitizeFileName(name) {
    const clean = String(name).replace(/[\\/:*?"<>|\u0000-\u001F]/g, "_").trim();
    return (clean || "received.bin").slice(0, 180);
  }

  function openDatabase() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open("qtx1-receiver", 1);
      request.onupgradeneeded = () => {
        const db = request.result;
        const store = db.createObjectStore("chunks", { keyPath: "k" });
        store.createIndex("sid", "sid", { unique: false });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  async function storeChunk(sessionId, index, bytes) {
    const copy = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
    if (memoryOnly || !database) {
      memoryChunks.set(index, copy);
      return;
    }

    await transactionPromise("readwrite", store => store.put({ k: `${sessionId}:${index}`, sid: sessionId, i: index, data: copy, length: bytes.byteLength }));
  }

  async function getStoredSummary(sessionId) {
    if (memoryOnly || !database) return Array.from(memoryChunks, ([i, data]) => ({ i, length: data.byteLength }));
    const rows = await getSessionRows(sessionId);
    return rows.map(row => ({ i: row.i, length: row.length }));
  }

  async function loadChunks(sessionId) {
    if (memoryOnly || !database) return Array.from(memoryChunks, ([i, data]) => ({ i, data }));
    return getSessionRows(sessionId);
  }

  function getSessionRows(sessionId) {
    return new Promise((resolve, reject) => {
      const transaction = database.transaction("chunks", "readonly");
      const request = transaction.objectStore("chunks").index("sid").getAll(IDBKeyRange.only(sessionId));
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  function transactionPromise(mode, operation) {
    return new Promise((resolve, reject) => {
      const transaction = database.transaction("chunks", mode);
      operation(transaction.objectStore("chunks"));
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error || new Error("database transaction aborted"));
    });
  }

  async function deleteSession(sessionId) {
    if (memoryOnly || !database) return;
    await new Promise((resolve, reject) => {
      const transaction = database.transaction("chunks", "readwrite");
      const request = transaction.objectStore("chunks").index("sid").openKeyCursor(IDBKeyRange.only(sessionId));
      request.onsuccess = () => {
        const cursor = request.result;
        if (!cursor) return;
        transaction.objectStore("chunks").delete(cursor.primaryKey);
        cursor.continue();
      };
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
  }
})();
