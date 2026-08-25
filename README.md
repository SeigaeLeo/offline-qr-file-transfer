# Offline QR File Transfer

通过Windows电脑连续显示动态二维码，让Android手机在没有网络连接的情况下接收文字和文件。

当前公开源码对应：

- Windows发送器：V1.7.1
- Android接收器：V1.7
- 协议：QTX1-W V1

## 主要特性

- 单帧最多携带2800字节原始文件数据。
- Android使用CameraX读取YUV亮度平面，并通过ZXing-C++原生解码。
- 电脑发送端支持最高60FPS、后台二维码预生成和循环补发。
- 每帧CRC32校验，整文件SHA-256校验。
- Payload白化，改善Office等结构化二进制文件产生的规则图案。
- 支持缺失帧显示、指定补帧、文字/图片/视频预览和摄像头切换。

## 目录

```text
windows-sender-v1.7.1/  Windows WinForms发送端
android-receiver-v1.7/ Android CameraX接收端
mobile-receiver-v1.7/  APK内嵌的HTML/CSS/JavaScript界面
tests-v1.7.1/           协议与二维码生成测试
```

## 构建Windows发送器

需要.NET 8 SDK和Windows 10/11 x64：

```powershell
.\build-v1.7.1.ps1
```

## 构建Android接收器

使用Android Studio打开`android-receiver-v1.7`目录，安装Android SDK 36与JDK 17后构建。公开源码不包含开发者签名文件；正式发布APK时请使用自己的签名密钥。

## 使用说明

1. 在电脑端选择文件或输入文字并开始发送。
2. 在Android接收器中打开摄像头并对准屏幕二维码。
3. 接收器按二维码帧号收集数据，自动忽略重复帧并通过循环补齐遗漏帧。
4. SHA-256校验成功后预览或保存文件。

详细设计参见[README-V1.7.md](README-V1.7.md)、[README-V1.7.1.md](README-V1.7.1.md)和[QTX1W-protocol.md](QTX1W-protocol.md)。

## 安全说明

本项目不包含任何Android签名密钥、账号凭据或本地Android开发环境。请勿把自己的`.jks`或`.keystore`文件提交到Git仓库。
