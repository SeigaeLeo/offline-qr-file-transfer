# Android V1.7 原生扫描工程

该工程把 `../mobile-receiver-v1.7` 的离线网页资源打入 APK，界面和传输协议继续由 HTML/JavaScript 管理；相机预览与默认二维码解码改由原生 Android 管理。

原生扫描路径：

```text
CameraX Preview + ImageAnalysis (KEEP_ONLY_LATEST)
→ YUV_420_888 Y Plane
→ 固定/自适应 ROI
→ ZXing-C++
→ JavaScript 协议处理
```

网页回退路径仍保留 BarcodeDetector 与本地 jsQR，便于在开发者模式中做同机对照。

构建命令：

```powershell
.\build-android-v1.7.ps1
```

APK 使用项目开发证书签名，适合侧载测试。
