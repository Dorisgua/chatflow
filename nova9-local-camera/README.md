# Nova 9 本地摄像头原型

面向 Huawei Nova 9 / HarmonyOS 4.2 的最小局域网视频验证应用。

## 当前功能

- 后置摄像头本机预览。
- 1280×720 优先、最多 10 fps 的 MJPEG 局域网画面。
- 手机显示带随机令牌的浏览器地址。
- 仅绑定 Wi-Fi 私有地址，不使用移动网络。
- 进入后台立即停止传输并释放摄像头。

## 透明性

应用源代码完全位于 `app/src/main`。运行时只使用 Android 系统接口，没有第三方 SDK。请先阅读 [PRIVACY.md](PRIVACY.md)。

## 构建方式

- JDK 17
- Android SDK Platform 35
- Android SDK Build Tools

项目不使用 Gradle，也不会在构建时解析第三方依赖。运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

脚本逐步执行 Java 编译、DEX 转换、APK 打包、对齐和本地测试签名。构建完成后，`dist` 目录包含：

- `Nova9LocalCamera-v0.1.0.apk`
- `BUILD-REPORT.txt`：权限、证书、APK 内容和 APK SHA-256
- `SOURCE-SHA256.txt`：参与构建的全部手机端源码哈希

可以独立运行 `scripts/verify-apk.ps1` 再次读取 APK 权限和签名。
