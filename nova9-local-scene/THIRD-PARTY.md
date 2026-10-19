# 第三方组件与面壁智能

## MiniCPM-V 4.6

- 开发机构：清华大学自然语言处理实验室（THUNLP）与面壁智能（ModelBest）。
- 开源组织：OpenBMB。
- 架构基础：SigLIP2-400M 视觉组件、Qwen3.5-0.8B 语言底座，以及 MiniCPM-V 团队的视觉压缩、多模态训练和端侧适配。
- 许可：Apache-2.0。
- 本目录版本：Q4_K_M 主模型 + F16 视觉投影组件。

面壁智能是一家专注高效率、端侧模型与多模态技术的中国人工智能公司，也是 OpenBMB 开源社区的重要参与方。MiniCPM 系列强调在较少参数和较低设备资源下实现可用的语言、视觉和多模态能力。这里使用的是公开下载的模型权重，不是面壁智能的在线 API。

## llama.cpp

- 开发组织：ggml-org 开源项目。
- 用途：在 Windows CPU 上运行 GGUF 模型，并仅通过本机回环地址提供推理接口。
- 许可：MIT。
- 本目录版本：GitHub Release `b10448` 的 Windows x64 CPU 构建。

## 供应链边界

模型文件来自 OpenBMB 官方 ModelScope 仓库，并与 OpenBMB 官方 Hugging Face 页面公布的 SHA-256 匹配。llama.cpp 来自 ggml-org 官方 GitHub Release，并使用该 Release API 公布的 SHA-256 校验。
