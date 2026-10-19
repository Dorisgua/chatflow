# 第三方组件

本目录里只有两个不是本项目源码生成的大文件：

1. `models/yolo26n.onnx`
   - 发布者：Ultralytics
   - 来源：Ultralytics 官方 GitHub assets v8.4.0 release
   - 用途：80 类常见物体检测
   - 许可：AGPL-3.0；个人原型可研究使用，若以后商业发布，需要单独复核许可

2. `lib/onnxruntime-1.26.0.jar`
   - 发布者：Microsoft
   - 来源：Maven Central 的 `com.microsoft.onnxruntime:onnxruntime:1.26.0`
   - 用途：在本机 CPU 上执行 ONNX 模型
   - 许可：MIT

这两个文件都已经进行校验，数值记录在 `SHA256.txt`。程序运行不需要再次下载它们。
