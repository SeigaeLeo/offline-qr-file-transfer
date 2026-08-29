# Android V1.8.4 正式版接收工程

该工程把 `../mobile-receiver-v1.8.4` 的离线网页资源打入 APK，兼容QTX1 V1单码与V2单码/四码，并可选记录 Camera2 实际曝光、ISO 和帧周期。四码模式共享同一相机Y Plane，使用四个独立ZXing-C++实例并行执行粗搜索、边界提取和缓存ROI解码。

原生扫描路径：

```text
CameraX ImageAnalysis (KEEP_ONLY_LATEST)
→ YUV_420_888 Y Plane
→ 四路独立搜索/缓存 ROI
→ 四线程 ZXing-C++
→ 单个待处理 WebView 回调
→ JavaScript 批量协议处理和批量存储
→ ACK 原生层继续投递
```

网页回退路径仍保留 BarcodeDetector 与本地 jsQR，便于在开发者模式中做同机对照。

构建命令：

公开源码不包含签名密钥。请使用 Android Studio 打开本目录，配置自己的 release signing 后构建；或者使用项目外部的 Gradle 9.4.1 执行 `assembleRelease` 生成未签名包。

APK 使用项目开发证书签名，适合侧载测试。
