# 发布流程

## 一次性准备

### 1. 签名密钥

发布 APK 自签名，模块索引直接分发它，没有渠道替你重签。**丢失私钥 = 所有已安装用户
无法再升级**，所以生成后立刻离线备份（密码管理器的附件 / U 盘 / 纸质密码）。

```bash
keytool -genkeypair -v \
  -keystore release.keystore.jks \
  -alias lspstore \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=LSP Store, O=tophtab, C=CN"
```

在仓库的 `Settings → Secrets and variables → Actions` 里加：

| Secret | 内容 |
|---|---|
| `SIGNING_KEY` | `base64 -w0 release.keystore.jks` 的输出 |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | `lspstore` |
| `KEY_PASSWORD` | 私钥口令 |
| `XPOSED_REPO_TOKEN` | 可选。对 `Xposed-Modules-Repo/io.github.tophtab.lspstore` 有 `contents: write` 的 PAT（fine-grained）。没有就跳过镜像步骤，手动发 |

### 2. 模块索引仓库

在 `Xposed-Modules-Repo/submission` 开一个 issue，标题：

```
[submission] io.github.tophtab.lspstore
```

bot 会建好 `Xposed-Modules-Repo/io.github.tophtab.lspstore` 并邀请你为 maintain。
包名满足 `io.github.<用户名>.` 前缀，不需要 TXT 域名验证（前提是你的 GitHub 组织
成员身份为 public；个人账号无需额外设置）。

### 3. 索引仓库里的文件

只放元数据，**不放源码**：

```
SUMMARY     一句话简介（索引首页显示；中英双语各一段）
SCOPE       ["io.github.tophtab.lspstore"]
SOURCE_URL  https://github.com/tophtab/lsp-store
README.md   本项目的 README（可精简）
assets/     图标等图片
```

`SUMMARY` 里必须写明"无需激活模块即可使用全部功能"，否则用户会去 Manager 里
对着一个空模块找设置。

## 自测渠道

push 到 master 时，`snapshot` 任务会用同一把 key 签一个 `1.0.0.dev.<sha>` 的包，
覆盖发布到 `dev` 预发布，固定直链可直接在手机上下载安装：

```
https://github.com/tophtab/lsp-store/releases/download/dev/LSP-Store-dev.apk
```

versionCode 与正式版相同（10000），所以 dev 包和正式包可以互相覆盖安装；
不要为了"比正式版新"去提高 dev 的 versionCode，否则正式版会被系统当成降级而装不上。

## 每次发版

1. 改 `app/build.gradle.kts` 的 `versionName` / `versionCode`
   （`versionCode = major * 10000 + minor * 100 + patch`）。
2. 写 `release-notes/<version>.md`——这份内容就是 GitHub Release 正文，也会被索引页
   当作该版本的更新说明展示。中文在前、英文在后，3–5 条面向用户的改动。
3. 在 `CHANGELOG.md` 里加一节。
4. 打 tag 并推送：

   ```bash
   git tag v1.0.0 && git push origin master --tags
   ```

5. CI 依次执行：lint → 单测 → debug/release 构建 → 解 keystore → 签名 → 校验
   tag 与 APK 的 `versionName` 一致 → 建 GitHub Release（APK + `SHA256SUMS`）→
   若配置了 `XPOSED_REPO_TOKEN`，再往组织仓库发一份 `versionCode-versionName` 的
   release。
6. 索引仓库的 bot 会重写 tag 为 `10000-1.0.0`，索引在 5–60 分钟内刷新。

**第一个版本建议手动发一遍**（网页建 release、上传 APK），确认权限、索引构建、
Manager 里的展示都正常，再依赖自动化。

## 应用自身的更新

没有单独的"自更新"功能，也不需要有：应用在索引里就是一个普通模块，所以它出现在自己的
"已安装"列表里，用同一套更新流程更新自己。安装覆盖自身时系统会杀掉进程，这是正常的。

两个前提：

- 能把自己认出来 —— 靠 `io.github.libxposed:service` 合并进 manifest 的
  `io.github.libxposed.service.XposedProvider`（商店扫描模块用的就是这个特征）。
- 索引里已经有这个包 —— 首次发布之前，应用会在"不在仓库中"分组里；索引构建完成后
  才会进入正常的更新/已是最新分组。

## 坑

- 同一 tag 不能重复发：重跑工作流会撞上已存在的 release，脚本会直接失败（这是故意的，
  因为 `softprops/action-gh-release` 遇到已存在的 tag 是**更新**而不是新建，会把上一版
  覆盖掉）。要重发就提高版本号。
- 索引仓库的**仓库描述/名称不能通过 API 修改**，只能在浏览器里改；release 本身可以
  通过 API 建，这就是 CI 能自动发的原因。
- 组织仓库里不要提交源码（官方 README 明确要求），源码只在 `tophtab/lsp-store`。
- `minSdk` 27 是 LSPosed 的下限，别为了兼容 8.0 降下去。
- 构建需要 `compileSdk 37`（AGP 9.4.1 / Gradle 9.8.2），本机或 CI 都要装
  `platforms;android-37.1`；`targetSdk` 仍是 36。
- libxposed 的 api / service 版本要和 `module.prop` 里的 min/target 保持一致
  （现在都是 102）；升其中一个就要升全部。
- `SHA256SUMS` 是给手动下载校验用的；应用内更新由模块索引提供，两者是同一份 APK。
