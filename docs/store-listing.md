# 上架商店（F-Droid / IzzyOnDroid）

两条渠道的现状：**metadata 已备好，提交动作需要你的账号**（本机没有 GitLab / Codeberg 凭据）。

- `io.github.tophtab.lspstore.yml` —— F-Droid 的构建元数据，已用 `fdroid lint`（fdroidserver 2.4.5）验证通过。
- 商店页的描述、图标、截图来自本仓库的 `fastlane/metadata/android/`，不要另外维护一份。

## 0. 前置：需要一个包含 fastlane 元数据的 tag

F-Droid 和 IzzyOnDroid 都**只读最新 release/tag 里的** `fastlane/`（F-Droid 文档原话：
"F-Droid will only use metadata files from the latest release"）。

当前 `v1.0.0`（2026-10-24 01:23）打在 fastlane 截图更新（`dcbc904` / `8ba1e46`）之前，
所以 1.0.0 的商店页拿不到新截图。下次发版打 `v1.0.1` 时自然会带上；如果急着上线，
也可以重新打一个 tag（同一个 versionCode 不能重复发布，建议直接起 `1.0.1`）。

## 1. F-Droid 主仓库

**流程**（GitLab 账号）：

1. fork `https://gitlab.com/fdroid/fdroiddata`
2. 把 `docs/store-listing/io.github.tophtab.lspstore.yml` 复制成 `metadata/io.github.tophtab.lspstore.yml`
3. 本地校验（可选）：

   ```bash
   docker run --rm -v "$PWD":/repo -w /repo registry.gitlab.com/fdroid/fdroidserver:buildserver \
     fdroid lint io.github.tophtab.lspstore
   ```

4. 提交 MR 到 `fdroiddata`（分支名随意，比如 `lspstore`）。不想自己写 yml 也可以先在
   `https://gitlab.com/fdroid/rfp/-/issues` 开 RFP。

**审核看点**：Apache-2.0 + 源码公开 ✓；从源码构建，`gradle: - yes` 即根目录 `assembleRelease`
（无 `KEYSTORE_FILE` 时产未签名包，F-Droid 自己签）；不内嵌二进制（仓库里唯一的 jar 是
`gradle/wrapper/gradle-wrapper.jar`，这是允许的）。

**已知风险**：

| 风险 | 说明 |
|---|---|
| SDK / 工具链太新 | `compileSdk 37.1`、`build-tools 36.0.0`、AGP 9.4.1、Gradle 9.8.2、JDK 21。F-Droid buildserver 若还没装 android-37.1，会在这个 MR 上卡住，需要等他们把镜像更新 |
| 签名会变 | F-Droid 默认用**自己的 key** 重签 → 从 GitHub Releases / 模块索引装的用户无法覆盖升级，必须先卸载 |
| 版本滞后 | 审核 + 构建通常几天，之后每版 3–5 天 |

想保住作者签名，需要**可复现构建**：在 yml 里加

```yaml
Binaries: https://github.com/tophtab/lsp-store/releases/download/v1.0.1/lsp-store-1.0.1.apk
AllowedAPKSigningKeys: <签名证书的 SHA-256 指纹>
```

并且 F-Droid 的构建产物要和你的 APK 逐字节一致（用 `apksigcopier compare` 验证）。
Compose/AGP 项目默认往往不可复现，这一步要单独调试，别在第一次提交时就指望它。

## 2. IzzyOnDroid（建议先走）

用你自己的 GitHub Releases APK，**保留你的签名**，同步 `fastlane/` 元数据，一般 24 小时内上架。

在 `https://codeberg.org/IzzyOnDroid/repo/issues` 新建 issue，正文可直接用：

```
App: LSP Store
Package id: io.github.tophtab.lspstore
Source: https://github.com/tophtab/lsp-store
Releases: https://github.com/tophtab/lsp-store/releases
License: Apache-2.0
Fastlane metadata: yes (fastlane/metadata/android/)
Category: Xposed / System
Description: An updater and browser for LSPosed modules. Reads the official
modules.lsposed.org repository, groups installed modules into updates / up to date /
not in the repository, and can update one or all of them.
Notes: the app is itself an empty libxposed module scoped to itself (SCOPE declares
only its own package); it hooks nothing and needs no activation.
```

要求和 F-Droid 的差别：必须免费、无广告，会做病毒扫描；对部分非自由依赖较宽容；
APK 由上游自己构建并签名，所以不会出现"两个签名"的问题。
