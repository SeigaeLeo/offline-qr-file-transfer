# Offline QR File Transfer

通过Windows电脑显示高速动态二维码，让Android手机在没有网络连接的环境中接收文字和文件。

当前正式版本：**V1.8.4**

- Windows发送器：V1.8.4
- Android接收器：V1.8.4
- 支持单二维码与田字形四二维码并行传输
- 正式安装包见 [GitHub Release V1.8.4](https://github.com/SeigaeLeo/offline-qr-file-transfer/releases/tag/v1.8.4)

## 主要特性

- 单帧100～2800字节，最高60页/秒；
- 田字形四二维码使用四个独立ROI和ZXing-C++实例并行解码；
- CameraX直接读取YUV亮度平面，避免浏览器Canvas灰度转换；
- WinForms `PerMonitorV2` 高DPI和整数二维码模块绘制；
- Payload白化，自动循环补发逐轮更换白化种子；
- 每帧CRC32、帧序号和整文件SHA-256；
- 缺失帧显示、指定补帧、文字/图片/视频预览、转发并删除；
- 开发者模式提供耗时分布、失败分类、图像质量、失败截图和Camera2曝光诊断。

## V1.8.4目录

```text
windows-sender-v1.8.4/  Windows WinForms发送端
android-receiver-v1.8.4/ Android CameraX/ZXing-C++接收端
mobile-receiver-v1.8.4/  APK内嵌HTML/CSS/JavaScript界面
tests-v1.8.4/            协议、二维码栅格和DOM测试
```

早期V1.7源码目录继续保留，方便比较演进过程。

## 构建Windows发送器

需要.NET 8 SDK和Windows 10/11 x64：

```powershell
.\build-v1.8.4.ps1
```

## 构建Android接收器

使用Android Studio打开 `android-receiver-v1.8.4`，需要JDK 17、Android SDK 36，并自行配置release签名。公开源码不包含签名密钥、本地Android开发环境或账号凭据。

详细功能参见 [README-V1.8.4.md](README-V1.8.4.md)。

## 使用说明

1. 在电脑端选择文件或输入文字，选择单码或四码后开始发送；
2. Android接收器打开摄像头并对准屏幕；
3. 接收器按帧号收集数据，自动忽略重复帧并通过后续循环补齐遗漏帧；
4. SHA-256校验成功后预览、保存或转发文件。

## 安全说明

仓库不包含Android签名密钥、账号凭据或本地工具链。发布页APK使用项目开发签名，后续同签名版本可以覆盖安装。
