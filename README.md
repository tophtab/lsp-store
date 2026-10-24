<div align="center">
  <br/>
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="140" alt="LSP Store 图标" />

# LSP Store

</div>

<p align="center">
面向 LSPosed 模块的 Android 应用商店与更新器，灵感来自
<a href="https://github.com/komi-store/komi-store">Komi Store</a>。
</p>

<p align="center">
  <a href="README.md">中文</a> | <a href="README.en.md">English</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-1.0.0-00A9A5?style=flat-square" alt="版本 1.0.0"/>
  <img src="https://img.shields.io/badge/Android-8.1%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.1+"/>
  <img src="https://img.shields.io/badge/languages-14-6C5CE7?style=flat-square" alt="14 种语言"/>
  <img src="https://img.shields.io/badge/license-Apache--2.0-blue?style=flat-square" alt="Apache 2.0"/>
</p>

---

### 下载

<p align="center">
  <a href="https://github.com/tophtab/lsp-store/releases/latest">
    <img src="https://img.shields.io/badge/Download-GitHub%20Releases-00A9A5?style=for-the-badge&logo=github" alt="从 GitHub Releases 下载" />
  </a>
  <a href="https://modules.lsposed.org/module/io.github.tophtab.lspstore/">
    <img src="https://img.shields.io/badge/LSPosed-模块索引-00A9A5?style=for-the-badge" alt="LSPosed 模块索引" />
  </a>
</p>

APK 由本项目自行签名，每个版本附带 `SHA256SUMS`，可这样校验：

```bash
apksigner verify --print-certs LSP-Store-1.0.0.apk
sha256sum -c SHA256SUMS
```

签名证书 SHA-256 指纹（所有版本都应是这个）：

```
b733f85831f27656fbbf0c424b0a76ceef74dd7ac3261423b0311dc6c8804fcb
```

也可以直接在 LSPosed Manager 的模块仓库里安装与更新。**本应用不需要激活任何模块，
也不 Hook 任何应用**：它只是把自己声明成一个空模块（作用域是自身），以便进入官方索引、
参与 Manager 的安装与更新流程。需要 Android 8.1+ 并已安装 LSPosed。

> LSP Store 是第三方项目，与 LSPosed 团队没有隶属关系。

---

### 功能

- 浏览并搜索 LSPosed 官方模块仓库，提供最近更新与最多星标分类。
- 已安装模块按可更新 / 已是最新 / 不在仓库中分组，支持单个或一键全部更新，
  也可直接就地安装模块。
- 模块详情页展示渲染后的 readme、Hook 作用域、更新日志、贡献者、发布历史，
  可在「稳定版 / 预发布版 / 全部」三档通道间切换，并单独下载每个 APK。
- 默认按正式版判定升级（规则与 LSPosed Manager 一致）；切到「预发布版」后该模块的
  更新检测也会跟随预发布版本，正式版更新时仍以较新者为准。
- 下载进度直接显示在安装按钮里，再次点击同一个按钮即取消；下载支持断点续传，
  前台服务保证切走后继续下载，离开详情页也不会打断安装。
- 兼容设备上已注册的任意 APK 安装器（系统安装器、InstallerX Revived、Dhizuku、
  AppManager 等），root 环境下可静默安装。
- 更新检查只在应用内进行，不做后台轮询；可选 root 静默安装。
- 镜像选择与延迟测试（按端点区分：索引、分页列表、模块详情各有自己的可用主机），
  APK 下载失败时回退到 GitHub。
- 收藏、最近浏览、忽略更新，并可将仓库模块关联到不在仓库中的已安装应用。
- 模块简介翻译（Google、LibreTranslate、DeepL、Microsoft）。
- 14 种语言，浅色 / 深色 / AMOLED 主题与 Material You 取色。
- 引导页、诊断信息、缓存管理与开源许可。

---

### 致谢

本项目从以下项目获得灵感与帮助，谨致谢意：

- [Komi Store](https://github.com/komi-store/komi-store) — 灵感来源与原型参考，界面设计、布局与交互方式均借鉴于此
- [LSPosed](https://github.com/LSPosed/LSPosed) — 模块框架，以及商店中所有模块所依赖的公共仓库
- [域见 ScopeView](https://github.com/Xposed-Modules-Repo/io.github.zhongjitianqianguai.scopeview) — 同类项目，他山之石，可以攻玉
- [LINUX DO](https://linux.do/)
