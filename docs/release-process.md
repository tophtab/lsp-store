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

另外建议加一个**仓库变量**（不是 secret，`Variables` 标签页）`SIGNING_CERT_SHA256`，值是 release 证书的
SHA-256 指纹——CI 会在发布前比对，签名用错 keystore 时当场失败，而不是等用户手机上装出第二个 app：

```bash
keytool -list -v -keystore release.keystore.jks -alias lspstore | grep SHA256:
# 取冒号后的 64 位小写 hex，去掉冒号
```

没设这个变量也能发版，只是少一道校验（日志里会有 `::notice::`）。

### 2. 模块索引仓库

在 `Xposed-Modules-Repo/submission` 开一个 issue，标题：

```
[submission] io.github.tophtab.lspstore
```

bot 会建好 `Xposed-Modules-Repo/io.github.tophtab.lspstore` 并邀请你为 maintain。
包名满足 `io.github.<用户名>.` 前缀，不需要 TXT 域名验证（前提是你的 GitHub 组织
成员身份为 public；个人账号无需额外设置）。

### 3. 索引仓库里的文件

只放元数据，**不放源码**。这些文件在本仓库各有唯一来源，由
`tools/sync_module_repo.sh`（CI 里是 `module-repo` 工作流）原样推过去，
不要在索引仓库里直接改，否则下次同步就会被覆盖：

| 索引仓库 | 本仓库来源 | 说明 |
|---|---|---|
| `SUMMARY` | `module-repo/SUMMARY` | 一句话简介（索引首页显示；中英双语各一段） |
| `SCOPE` | `module-repo/SCOPE` | `["io.github.tophtab.lspstore"]` |
| `SOURCE_URL` | `module-repo/SOURCE_URL` | 源码地址 |
| `README.md` | `README.md` | 本项目的 README，原样复制 |
| `README.en.md` | `README.en.md` | 同上（否则 README 里的语言切换是死链） |
| `assets/icon.png` | `assets/icon.png` | 512×512 应用图标 |
| `assets/screenshots/*.png` | `assets/screenshots/*.png` | README 里的三张截图 |

两条容易踩的坑：

- README 里引用的图片必须写成 `assets/...`，并且由 `tools/sync_module_repo.sh`
  一并同步。索引仓库没有 `fastlane/` 目录，也不共享源仓库的其他文件；漏同步就是
  一张裂图（图标和截图都踩过）。
- `SUMMARY` 里必须写明"无需激活模块即可使用全部功能"，否则用户会去 Manager 里
  对着一个空模块找设置。

图标和商店包装图都从 APK 自己用的图标（`app/src/main/res/drawable-xxxhdpi/`）
导出，别再另外画一张：

```bash
convert app/src/main/res/drawable-xxxhdpi/ic_launcher_icon.png \
  -filter Lanczos -resize 512x512 -strip assets/icon.png
```

`assets/icon.png` 就是 fastlane 的 `images/icon.png`，两份必须一致。

索引仓库的仓库描述**不要动**：索引网站和 LSPosed Manager 把它当作模块在列表里
显示的名称，所以它必须是应用名 `LSP Store`。它也确实只有组织管理员能改
（API 对 maintain 返回 403），正好避免手滑把它改成介绍文字。

## 自测渠道

没有常设的 dev 预发布。master 每次 push 都会构建并签名 release APK，上传成 Actions
构建产物（artifact，保留 30 天），需要真机自测时取下来安装；手动运行 workflow
（Actions 页面 Run workflow，或 `gh workflow run android.yml --ref <branch>`）也能
为任意分支构建一份，同样只留 artifact，不会创建或改动任何 Release：

```bash
gh run download <run-id> -n app-release --dir /tmp/lsp-store-apk
adb install -r /tmp/lsp-store-apk/LSP-Store-<version>.apk
```

master push 产出的包**就是**该提交将来发版用的那个 APK（打 tag 时直接复用，不重新
编译）：versionName 不做任何后缀，提交号由 CI 写进 `BuildConfig.BUILD_SHA`，在关于页
/诊断页显示为 `1.0.2 · 3f2a91c`。

## 每次发版

1. 改 `app/build.gradle.kts` 的 `versionName` / `versionCode`
   （`versionCode = major * 10000 + minor * 100 + patch`）。
2. 写 `release-notes/<version>.md`——这份内容就是 GitHub Release 正文，也会被索引页
   当作该版本的更新说明展示。中文在前、英文在后，3–5 条面向用户的改动。
3. 在 `CHANGELOG.md` 里加一节，使用 `增加：` / `减少：` / `修复：` 三个分类；
   没有改动的分类写 `- 无。`。
4. 先推 master，等 `build` 任务绿了（它会把这次的 APK 上传成构建产物），再打 tag：

   ```bash
   git push origin master
   # 等 CI 的 build 任务跑完（约 5 分钟）
   git tag v1.0.0
   git push origin v1.0.0
   ```

   发版任务会**复用** master 那次跑出来、已经验过的那个 APK，不再编译：整条发布链
   （下载 → 校验版本/签名 → 建 Release → 镜像）大约 1 分钟。
   如果 tag 和 master 是一起推的（`git push origin master --tags`），同一提交的构建还
   没跑完，找不到产物，就直接从源码构建一次（约 5 分钟）——代码一样，只是没省下这次
   编译。tag 打在很早、已经被清理（30 天）的提交上也是同样的回退路径。

5. 发版任务（`build`，push 触发）依次执行：找该提交的构建产物（有就复用，不再编译）→
   没有就解 keystore → 单测 + `assembleRelease`（`assembleRelease` 里本来就跑
   `lintVitalRelease`，它按项目的 error 策略把关，见下一条）→ 校验签名与 APK 信息 →
   建 GitHub Release（APK + `SHA256SUMS`）→ 若配置了 `XPOSED_REPO_TOKEN`，再往组织仓库
   发一份 `versionCode-versionName` 的 release（先建 draft、传完 APK 再发布，否则索引
   bot 看不到，见「坑」）。tag 与 APK 的 `versionName` 必须一致，否则任务在发布前失败。
   PR 走独立的 `checks` 任务：全量 `lintDebug`（含所有 warning）、单测、debug 包、
   release 编译检查，碰不到签名密钥；只改文档、`docs/`、`assets/`、`module-repo/`、
   `release-notes/` 的提交不触发构建（tag push 不受路径过滤影响，发版照常）。

   **push 上不单独跑全量 lint**，只靠 `assembleRelease` 里的 `lintVitalRelease`：
   `app/build.gradle.kts` 把 `MissingTranslation` / `ExtraTranslation` 从 error 提升成了
   fatal，所以这条 40s 的 vital 检查能捕获全量 lint 会拦下的全部 error，而全量 lint 要
   ~150s。全量 lint（包括 warning 级别）留给 PR。给这两个检查加上 `fatal` 是这套流水线
   的前提，不要随手删掉。
6. 索引仓库的 bot 会重写 tag 为 `10000-1.0.0`，索引在 5–60 分钟内刷新（见下节，
   不用等它）。
7. 如果这次发版改了 README、图标或 `module-repo/` 里的元数据，确认 `module-repo`
   工作流跑过了（或本地跑一次 `tools/sync_module_repo.sh`），否则索引页还是旧的。

**第一个版本建议手动发一遍**（网页建 release、上传 APK），确认权限、索引构建、
Manager 里的展示都正常，再依赖自动化。

## 怎么判断这次发版成了

只有两件事算数：

1. `tophtab/lsp-store` 上该 tag 的 GitHub Release 已发布，`LSP-Store-<version>.apk`
   和 `SHA256SUMS` 就位；
2. 索引仓库里 `<versionCode>-<versionName>` 那个 release 是 `draft=false`。

两件都由 CI 自己判定：发版 job 会校验 tag 与 APK 的 `versionName`、签名，上传后断言
asset 字节数，发布后等 30 秒再查一次 `draft`——被 bot 退回就让 job 失败。

**不需要去盯 `modules.lsposed.org` 的 `latestRelease`。** 那份 JSON 是定时全量重建的
（5–60 分钟）并且 CDN 再缓存一层，它晚一点刷出来不代表发版有问题；反过来，它没刷出来
也很难说明是哪一步坏了。看上面两件事就够了，多盯没有额外信息。

## 应用自身的更新

没有单独的"自更新"功能，也不需要有：应用在索引里就是一个普通模块，所以它出现在自己的
"已安装"列表里，用同一套更新流程更新自己。安装覆盖自身时系统会杀掉进程，这是正常的。

两个前提：

- 能把自己认出来 —— 靠 `io.github.libxposed:service` 合并进 manifest 的
  `io.github.libxposed.service.XposedProvider`（商店扫描模块用的就是这个特征）。
- 索引里已经有这个包 —— 首次发布之前，应用会在"不在仓库中"分组里；索引构建完成后
  才会进入正常的更新/已是最新分组。

## 坑

- 改那段镜像上传脚本时，`--data-binary` 后面的 `@` 不能丢：丢了传给 GitHub 的是文件名
  （ 23 字节）而不是 APK，asset 列表照样显示“上传成功”，只有对字节数才看得出；索引 bot
  随后会把 release 退回 draft。CI 现在有字节数断言 + 发布后 draft 复查兜底，这两道别一起删。
- 索引仓库里**先发 release、再传 APK 是无效的**：索引 bot 只在 release 内容被发布/编辑时
  校验，官方 README 原话是 “editing only the release asset won't trigger the bot”。
  这样发出去的 release 会一直躺在那里当 draft，索引里根本看不到（1.0.1、1.0.2 都踩过）。
  正确顺序是：建 draft → 传 APK/SHA256SUMS → 改内容（写 body）并发布，这一次内容变更
  才会让 bot 在 APK 已在位的情况下索引。要让索引重新看一遍，就 PATCH 一次该 release
  的内容（例如补 body），单独重传 asset 没有用。
- 同一 tag 不能重复发：重跑工作流会撞上已存在的 release，脚本会直接失败（这是故意的，
  因为 `softprops/action-gh-release` 遇到已存在的 tag 是**更新**而不是新建，会把上一版
  覆盖掉）。要重发就提高版本号。
- 索引仓库的**仓库描述 = 索引/Manager 里显示的模块名称**，必须保持应用名
  `LSP Store`，不能改成介绍文字；它也改不了（需要组织管理员）。release 本身可以
  通过 API 建，这就是 CI 能自动发的原因。
- 组织仓库里不要提交源码（官方 README 明确要求），源码只在 `tophtab/lsp-store`。
- `minSdk` 27 是 LSPosed 的下限，别为了兼容 8.0 降下去。
- 构建需要 `compileSdk 37`（AGP 9.4.1 / Gradle 9.8.2），本机或 CI 都要装
  `platforms;android-37.1`；`targetSdk` 仍是 36。
- libxposed 的 api / service 版本要和 `module.prop` 里的 min/target 保持一致
  （现在都是 102）；升其中一个就要升全部。
- `SHA256SUMS` 是给手动下载校验用的；应用内更新由模块索引提供，两者是同一份 APK。
