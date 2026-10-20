# 多 APK 变体选择：数据评估与设计

本文是对 `modules.lsposed.org` 全量数据的实测分析，以及“一个 release 里有多个 APK 时该装哪个”的设计提案。
是对当前实现（`Release.defaultAsset()` → `List<ReleaseAsset>.bestForAbis()`）的替换方案讨论，尚未实现。

结论先行：

1. 当前算法只认 **ABI 关键字**，其余情况静默 `first()`。而仓库的 asset 列表 92% 按文件名字母序排列，
   所以“默认选中 OS2 而不是 OS3”不是判断错误，是**根本没判断**——`OS2_...` 字母序在 `OS3_...` 前面。
2. 多 APK 不只是 ABI 拆分，实际有 **9 类差异轴**；其中只有 3 类能靠文件名判，1 类必须靠 APK 元数据，
   2 类必须靠设备画像，剩下 3 类**技术上不可判定，只能问用户**。
3. 正确的目标不是“猜得更准”，而是“**不静默选错**”：硬过滤 + 打分 + 置信度 + 记忆 + 显式询问。
4. 拿到 APK 元数据的成本很低：对 8 MB 的 APK 用 HTTP Range 只取 central directory + manifest，
   实测 **156 KB** 就能读出包名、versionCode、minSdk、targetSdk、内置 ABI、xposedminversion。

---

## 1. 数据底座

抓取时间 2026-10-20，`/module-list/{1..40}.json` + `/module/{pkg}.json` 全量 1183 个模块。

| 指标 | 数值 |
| --- | --- |
| 模块数 | 1183 |
| release 总数 | 7591 |
| 多 APK release | 784（10.3%） |
| 含多 APK release 的模块 | 125（10.6%） |
| **最新** release 即多 APK 的模块 | 93 |
| 多 APK release 的 APK 数分布 | 2 个: 98 模块 / 3 个: 10 / 4 个: 7 / 5: 3 / ≥6: 6 |

两个结构性事实决定了后面所有讨论：

- **asset 列表 721/784（92%）按文件名字母序**。所以 `first()` 等价于“字母序第一个”，跟“谁更新 / 谁更常用”无关。
- 仓库 `ReleaseAssetDto` 只有 `name / size / downloadCount / downloadUrl`，**没有** ABI、minSdk、targetSdk、
  系统版本任何元数据。所有信息只能来自：文件名、APK 本体、设备。

当前实现落到 `first()` 的比例（全量 release 口径）：

| 结果 | release 数 |
| --- | --- |
| 去掉 debug 后只剩 1 个 → 自动正确 | 216 |
| 唯一 arm64 命中 → 自动正确 | 265 |
| 只有 universal 一个候选 → 自动正确 | 14 |
| 多个 arm64 候选 / 无 ABI token → **静默 first()** | **278（35%）** |

在这 278 个里，`first()` 恰好等于下载量最高的那个：**210（76%）**。也就是说约 1/4 的情况默认值是“明显不是大家装的那个”。
只看“最新 release”口径（93 个模块）：28 个 debug 解决、24 个 ABI 解决、2 个 universal、**39 个（42%）落到 first()**。

---

## 2. 差异轴分类（这是设计的核心）

把 784 个多 APK release 的“差异部分”抽出来归类（脚本见附录 A）。同一个 release 可能跨多类。

| # | 差异轴 | 典型例子 | release 数 | 判定依据 | 能否自动 |
| --- | --- | --- | --- | --- | --- |
| 1 | debug / release / dev | `app-debug.apk` + `app-release.apk` | 230 | 文件名 | ✅ 名字即可 |
| 2 | ABI | `app-arm64-v8a-…` / `…-armeabi-v7a-…` / `universal` | 265 + 14 | 文件名；精确可靠的做法是读 zip 里的 `lib/<abi>/` | ✅ |
| 3 | libxposed API 代际 | `…_legacy` / `…_api101` / `…_api102` / `A_…_API101` vs `Z_…_API93` | 29 | 文件名 + APK 的 `xposedminversion` / `XposedProvider` + 已装 LSPosed 能力 | ✅（需设备画像） |
| 4 | 厂商 OS / Android 大版本 | `OS2_` vs `OS3_`（HyperCeiler）、`A13_15_OneUI5.x` vs `A16_OneUI8.x`、`Android14-` vs `Android16-`、`…android.15+.apk` | 39 | 文件名 + 设备 HyperOS/MIUI/OneUI/ColorOS 版本 | ✅（需设备画像） |
| 5 | minSdk 拆分 | `app-arm64Minsdk26/29/35-release.apk` | 1（1 模块） | 文件名 + APK `minSdkVersion` | ✅ |
| 6 | 版本/功能流派（edition） | `full` / `lite` / `foss` / `pro` / `normal` / `root` / `native` / `non-native` / `offline` / `online` | 72（按宽松名字口径 91 release / 14 模块） | 文件名，含义不可推 | ❌ 问用户 |
| 7 | 目标 App 版本 | `fenbi_6.17.4.apk`、`hupu_8.0.74.04035.apk`、`…_com.google.android.apps.translate.apk`、`fanqie-6.0.3.32.apk` | 约 6–8 个模块 | 文件名里的包名/版本 vs 已装目标 App | 半自动（有目标 App 才能判） |
| 8 | 捆绑的其他 APK（同 release 里的其它应用/模块） | `wearos_app_69.apk`、`XPrivacyLuaPro-v0.83`、`AppErrorsTracking-demo`、`tsmclient`、`VISTrigger`、`…Provider-Apple/Spotify/…`（15 个）、`CrashStormTest` | 66 release / 11 模块（名字口径） | **包名比对**（APK manifest） | ✅（需探测） |
| 9 | 真·任意选择 | `app-toast` vs `app-no-toast`、`…_14-panel` vs `…_14-tile`、`…_simple` vs `…_sign`、`all` vs `adblock`、`DisableUpdate` vs 正常版、`Auth-one` vs `Auth-all` | 91 release / 14 模块（名字口径，与第 6 类重叠） | 无 | ❌ 问用户 |

关键观察：

- **第 8 类是最危险的一类**：用户以为在“选变体”，其实列表里混着一个完全不同的 App。
  实测确认：`wearos_app_69.apk` 没有 `xposedmodule` 元数据、`XPrivacyLuaPro-v0.83-release.apk` 的
  versionCode=83/versionName=0.83（主包是 136/1.35）——是另一个包。名字里完全看不出来。
- **第 4 类的“匹配”不等于 targetSdk 匹配**。实测 HyperCeiler 同版本两个包：
  OS2 包 `minSdk=35 targetSdk=36 versionCode=4559`，OS3 包 `minSdk=35 targetSdk=37 versionCode=4571`。
  OS3 包的 targetSdk 比设备 SDK 还高，所以“targetSdk == 设备 SDK”这种判据是错的；
  设备侧只能靠 `ro.mi.os.version.name`（或 `Build.VERSION.INCREMENTAL` 里的 `OS3.x`）去对文件名里的 `OS3`。
- **第 4 类还有一个隐藏坑：versionCode ≠ release tag**。HyperCeiler 的 tag 是 `4571-2.10.166`，
  但 OS2 包的 versionCode 是 4559（历史上根本没有 4559 这个 tag）。装了 OS2 包，`isNewerThan`
  永远为真 → 每次检查更新都会提示，批量更新还会反复重装。选错变体不只是“功能不对”，是**长期更新噪音**。
  （注意：反过来用“versionCode==tag”当筛选依据也不成立，`com.suqi8.oshin` 的 `A_…_API101_v16.8.1382`
  比 tag `1370` 还高。它只能做“提示”，不能做“筛选”。）
- **第 3 类的语义依赖设备**：`_legacy` 与 `_api101/102` 谁该装，取决于装的是哪个 LSPosed。
  而且作者约定不统一：`DPIS_1.13.1.apk`（裸名=modern）+ `_legacy`；`qqkuchiguse-1-modern` + `-legacy`；
  `feishukit-1.8.12-api102` + `-legacy`。裸名既可能是 legacy 也可能是 modern，**不能靠命名推断，必须读 APK 元数据**。
- 模块级分布（同名差异 token 归类，可多标签）：debug 45、ABI 35、API 16、OS 5、edition 15。
- 只看 93 个“最新 release 即多 APK”的模块：按**当前算法**（只认 ABI）有 39 个（42%）落到 `first()`；
  若名字层再补上 OS / API / minsdk 轴，需要**人工选择**的仍有 27 个（第 6/7/8/9 类）。这 27 个就是产品必须正面处理的场景。
- `minsdk` 和“目标 App 版本”两类样本极少，但恰好是文件名信息量最大的两类，值得单独写规则。

---

## 3. 单 APK release：抽 120 个，翻车点在哪

上一版只看“多 APK 怎么选”。但 6807/7591（89.7%）的 release 只有一个 APK，这里没有“选哪个”，
却有“这个包到底能不能装 / 该不该装”的问题。随机抽 120 个模块的**最新 release（恰好只有一个 APK）**
用 Range 探测（每个约 150 KB），结果：

| 发现 | 样本 | 备注 |
| --- | --- | --- |
| `minSdk ≥ 30`（Android 11+） | 31/120（25.8%） | 老设备直接装不上 |
| `minSdk ≥ 33`（Android 13+） | 15/120（12.5%） | |
| `minSdk ≥ 34` | 9/120（7.5%） | |
| `minSdk ≥ 35`（Android 15+） | 6/120（5.0%） | 例：`dev.lackluster.mihelper`(35)、`io.github.lcebot.clipsync`(35) |
| `debuggable=true` | 12/120（10%） | **其中只有 1 个文件名带 debug** |
| 文件名带 debug 但 `debuggable=false` | 0/120 | 命名不撒谎，但也不完整 |
| APK 包名 ≠ 仓库包名 | 3/120（2.5%） | `dev.wtonec`→`dev.wtonec.final`、`YGHFv.ReaMicroExtend`→`com.reamicro.fix`、`nostoragerestrict` 大小写差异 |
| `versionCode` ≠ tag code | 0/120 | 单 APK 基本一致（HyperCeiler 那种是多 APK 特有） |
| `versionName` ≠ tag name | 1/120 | `Cleaner.Royall` tag `4-4.7_AraafRoyall` vs APK `4.8` → `isNewerThan` 永远为真 |
| 无 native lib（纯 Java） | 82/120（68%） | 文件名里的 ABI 往往只是作者习惯，不代表真的 ABI 限制 |
| 真实 ABI 限制（`lib/` 里没有 arm） | 0/120 | 但确实存在，见 3.2 |
| APK 无任何 Xposed 声明（manifest meta / provider / `META-INF/xposed/module.prop`） | 0/120 | 起初误判为 9%（11 个），实际是漏了第三种声明，见 3.3 |

### 3.1 “只有 debug 包”的模块（你记的那类）

- 单 APK 且文件名带 debug 的 release：**173 个**
- **最新 release 就是 debug 包**的模块：**34 个**（`com.souleven.nothingos`、`io.kazutoiris.mrga`、
  `com.sui.advancedpowermenu`、`dev.natsumi.wetype.markchanger`、`com.programminghoch10.securedisplay` …）
- 其中 **32 个模块的所有 release 都只有 debug 包** → 用户根本没得选
- 抽 6 个 debug-only 包探测：**6/6 `debuggable=true`**，versionName 与 tag 一致（不会触发更新循环）
- 更隐蔽的是反面：**12/120 随机单 APK 是 `debuggable=true`，但名字像正式版**
  （`WATransChat.v1.1.1.apk`、`mpro.apk`、`SilentRAR-100.apk`、`PokeCall-v1.3-signed.apk`、
  `YandexGOPatcher_v1.0.4.apk` …）。所以“是不是 debug 包”**不能靠文件名**，只能读 manifest
  的 `android:debuggable`，或者比对签名证书是否为 AOSP 调试证书。

策略：

策略（按你的决定）：

- 不隐藏、不阻止、**不弹窗**：单 APK 就是装它，debug 与否不影响流程。
- 多 APK 里出现 `*-debug.apk`、`*-release.apk`、`<模块名>.apk` 并存时，**优先非 debug**（作为降权项，
  不是提问项）。有了 Range 探测后，优先级用 manifest 的 `android:debuggable` 判，
  名字里的 `debug` 只做未探测时的 fallback。
- `debug` 徽章仅做信息展示（详情页），不影响一键安装。

### 3.2 单 APK 也会“最新 release 装不上”

- `minSdk` 门槛：Android 12 设备上约 1/5 的模块最新版装不上，Android 13 约 1/8，Android 14 约 1/13。
  当前实现完全不检查，用户点安装只会拿到 `INSTALL_FAILED_OLDER_SDK`。
- **release 级别的 ABI 变体**：有 3 个模块是“每个 release 一个 ABI”，而不是“一个 release 多个 ABI”：
  - `vn.kwaiching.live` 最新是 `V24.08.02_riscv64_release.apk`（实测 `lib/riscv64`，32 MB），arm64 手机装不上；
    更早的 `16-24.06.04` 是 universal
  - `vn.kwaiching.anantaryamarga` 最新是 `x86_64`（实测 `lib/x86_64`）
  - `cn.kwaiching.hook` 最新是 universal（正常）
  这两个模块现在会被当成“最新版”推给所有手机，下 30 MB 然后安装失败。
- 推论：**候选是“（release, asset）对”**，但**不做旧版本回退**（见下）。
- 为什么不做回退——数据说话：
  - 把样本里 9 个 `minSdk ≥ 34` 的模块向前探测 3–4 个 release：**旧版 minSdk 完全一样**
    （clipsync 35/35/35/35、hyperears 35×4、tricks 34×4、firefdskit 34×3、puddingpods 35×2、hyperste 34×2；
    只有 mihelper 从 35 降到 34）。作者是按一个系统基线构建的，回退几乎拿不到可装的旧包。
  - `vn.kwaiching.live`：最新是 riscv64，arm64 构建要回退到 `6-24.04.26`（比最新版老 16 个月）。
    把这样的包推荐给用户比“没有更新”更糟。
- 所以正确处理是：**最新 release 没有可用 asset → 该模块在当前设备上标记“不适用”，
  不进更新列表、不通知、不参与批量更新、不下载**；详情页说明原因（`minSdk 35 > 你的 Android 13` /
  `仅提供 riscv64/x86_64`），下面已有的版本选择器作为手动出口，让用户自己拿主意。
  这等价于你说的“忽略这个更新”，且不需要任何新逻辑。
- 影响面：Android 13 设备上约 12.5% 的模块会是“不适用”，Android 14 约 7.5%。
  release-per-ABI 的模块只有 3 个（0.25%），其中 2 个最新不可用。

### 3.3 模块声明有三种，检测不能只看 manifest

抽样里 33% 的模块用 libxposed 新式打包，声明在 APK 的 `META-INF/xposed/` 里：

```
META-INF/xposed/module.prop    minApiVersion=102 / targetApiVersion=102 / staticScope=true
META-INF/xposed/java_init.list hook 入口类
META-INF/xposed/scope.list     作用域包名（30 个里有 13 个是 system）
```

所以“这个 APK 是模块吗”要三选一：manifest `xposedmodule`/`xposedminversion`、
`io.github.libxposed.service.XposedProvider`、`META-INF/xposed/module.prop`。
`module.prop` 里的 `minApiVersion` 是**判断 API 轴的权威依据**（比文件名可靠），只有几百字节，
和 manifest 一起读成本几乎为零。`scope.list` 还能支撑“目标 App 版本”那条轴。

这条同时修正上一版的一个错误：原本建议“包名 ≠ 仓库包名 → 硬剔除”，实测 2.5% 的合法模块包名
与仓库名不一致（改名/转手/大小写），硬剔除会误杀。正确规则：**包名不一致 + 没有任何 Xposed 声明
= 配套 App，剔除；只有包名不一致 = 警告但不拦**。

### 3.4 单 APK 的“判定”而非“选择”

| 结论 | 条件 | UI |
| --- | --- | --- |
| BLOCK | `minSdk > 设备 SDK`；或真实 `lib/` ABI 不兼容且无 universal；或（包名不符 + 无 Xposed 声明） | 详情页说明“不适用”，安装按钮禁用；不进更新/通知/批量 |
| WARN | 包名不符但有声明；`versionName` 与 tag 不一致 | 小字提示 + 徽章，不拦 |
| OK | 其余（含 debug-only 单 APK） | 正常一键安装 |

`versionName` 与 tag 不一致时，更新检查里要把“同 versionCode 不同 versionName”判为**不算更新**
（或标注“作者版本号不规范”），否则永久提示更新。

---

## 4. 具体反例（都是真的）

| 模块 | 现状默认 | 问题 |
| --- | --- | --- |
| `com.sevtinge.hyperceiler` | `OS2_…` (52.7k 下载) | 用户是 HyperOS 3；`OS3_…` 有 107.3k 下载。且 OS2 包 versionCode 4559 < tag 4571 → 装完永远提示更新 |
| `io.mesalabs.knoxpatch` | `…-sep-lite.apk` (468/515/…) | 正式包是 4k–6k 下载。`first()` 因为 `-sep-lite` 字母序在前 |
| `com.asameer.killappnowext` | `A13_15_OneUI5.x_API-Legacy89` | 设备是 Android 16 / OneUI 8，应选 `A16_OneUI8.x` |
| `io.github.fnyoat.tipismine` | `-legacy` | LSPosed 新版应选 `-modern`（读 manifest 才能区分：legacy 有 `xposedminversion=82`，modern 有 `XposedProvider`） |
| `xeed.xposed.cbppmod` | `pbmc_69.apk` | 列表里还有一个 WearOS 配套 App，`first()` 只是碰巧对了 |
| `eu.faircode.xlua` | `XPrivacyLua-v1.35` | 同 release 里 `XPrivacyLuaPro` 是独立 App（versionCode 83） |
| `com.parallelc.micts` | `MiCTS_2.6.apk` | 同包名的两个 flavor（`VISTrigger_2.6.apk`），无法判定 |
| `com.qingyu.mi5g` | `_14-panel.apk` | panel / tile 是功能选择，且 `14` 是 App 版本不是 Android 版本（说明裸数字 token 必须带上下文才能匹配） |

---

## 5. 设计目标与原则

1. **零静默错误**：宁可弹选择器，也不要默认一个“看起来像”的包。
2. **硬约束优先**：不合法/不兼容的候选先剔除，不要靠打分把它压下去（`minSdk > 设备 SDK`、
   真实 ABI 全不兼容、Xposed API 超出 LSPosed 能力、“包名不符 + 无声明”）。debug 不是硬约束，是降权项。
3. **可解释**：每个默认值都能说清“为什么是它”。UI 上给一句话理由。
4. **可覆盖、可记忆**：用户手动选过就以用户为准；跨版本记住“语义偏好”而不是 asset 文件名（文件名带版本号）。
5. **成本可控**：只在详情页 / 安装 / 更新检查时探测，不探测列表页；结果按 `(assetName, size)` 永久缓存，
   失败降级为“只用文件名”，绝不阻塞安装。
6. **一条代码路径**：详情页安装、Installed 页单项更新、批量更新、后台检查通知，全部走同一个 resolver。

---

## 6. 建议架构

### L0 硬过滤（`VariantCandidate.isEligible`）

顺序执行，剔除并记录原因（用于 UI 灰色显示“为何不可用”）：

1. 不是 `.apk` → 剔除（现在只按后缀过滤，可保留）。
2. 文件名含 `debug`/`dev`，且同 release 存在非 debug 包 → **降权**（不是剔除；探到 manifest 后用
   `debuggable` 判定）。单 APK 时不过滤。
3. **包名不符 + 没有任何 Xposed 声明**（manifest meta / provider / `META-INF/xposed/module.prop`）→ 剔除
   （这是解决第 8 类的唯一手段；只包名不符时不要拦，见 3.3）。
4. APK `minSdk > Build.VERSION.SDK_INT` → 剔除；`maxSdk < SDK_INT` → 剔除。
5. 有 ABI 限定且设备 ABI 无一兼容、也无 universal → 剔除（读 `lib/` 精确，不用猜）。
6. APK 的 `module.prop minApiVersion` > 已装 LSPosed 支持的 API → 剔除。

候选空间是 **(release, asset) 对**，但**只在当前 release 内选择**：当前 release 全被过滤时，
不往旧 release 回退（见 3.2 的数据），而是给该模块一个 `NOT_APPLICABLE` 结果：
不进更新列表、不通知、不下载，详情页写明原因。

P0 阶段没有探测能力时，L0 只做 1、2、5（名字版）——已经能修掉 1/3 的问题。

### L1 名字解析 → `VariantTraits`

统一 token 化（分隔符 `[-_. ]`，全小写），得到结构化标签，而不是散落的 `contains`：

```kotlin
data class VariantTraits(
    val abis: Set<Abi>,              // arm64-v8a / armeabi-v7a / x86 / x86_64
    val universal: Boolean,
    val buildType: BuildType,        // DEBUG / RELEASE / UNKNOWN
    val xposedApi: ApiKind?,         // LEGACY(min) / LIBXPOSED(100|101|102) / null
    val osTokens: Set<OsToken>,      // MIUI / HYPEROS(2|3) / ONEUI(5|8) / COLOROS / ANDROID(14|16|15+)
    val minSdk: Int?,                // minsdk35
    val edition: Set<String>,        // full/lite/foss/pro/normal/root/native/...
    val flavors: Set<String>,        // toast/panel/tile/all/adblock/simple/sign/offline/online
    val targetApp: TargetAppRef?     // 文件名里出现 com.xxx + 版本
)
```

匹配必须**带上下文**：`_14-panel` 里的 14 是 App 版本，`A16_` / `android.15+` / `OS3_` 才是系统；
`minsdk35`、`API-101` 是显式前缀。建议白名单式解析（`os2|os3|hyperos|oneui|miui|coloros|a1[2-9]…`）而不是裸数字匹配。

### L2 设备画像 `DeviceProfile`

| 字段 | 来源 | 备注 |
| --- | --- | --- |
| `sdkInt` | `Build.VERSION.SDK_INT` | 已有 |
| `abis` | `Build.SUPPORTED_ABIS` | 已有 |
| `vendorOs` + `vendorMajor` | `getprop ro.mi.os.version.name`（HyperOS）/ `ro.miui.ui.version.name` / `ro.build.version.oneui` / `ro.build.version.oplusrom`，fallback 解析 `Build.VERSION.INCREMENTAL`（HyperOS 形如 `OS3.0.2.0.WOCCNXM`）| 新 |
| `lsposedApi` | 已装 `org.lsposed.manager` 的 versionCode/versionName → 支持的 libxposed API；再叠加“已装模块用的 API 代际” | 新 |
| `installedTargetApps` | `PackageManager` 查模块 `scope` 里的包版本 | 新，仅对第 7 类有用 |

注意：`SystemProperties` 属于隐藏 API，Android 9+ 反射受限；用 `Runtime.exec("getprop …")`（一次性、IO 线程、带超时）
再回退到 `Build.VERSION.INCREMENTAL` 解析更稳。

### L3 打分与决策

硬过滤后对候选打分（加分项，权重可调）：

| 条件 | 分 | 说明 |
| --- | --- | --- |
| 厂商 OS 主版本精确匹配（设备 OS3 ↔ 名字 `OS3`） | +40 | 解决 HyperCeiler / OneUI / killappnowext |
| Android 版本变体匹配（设备 SDK36 ↔ `A16`/`android16`） | +30 | |
| minSdk 变体取“≤ 设备 SDK 的最大值” | +20 | `minsdk26/29/35` 场景 |
| ABI 精确（arm64 设备 ↔ arm64 包） | +25 | |
| 仅 universal | +10 | 能用但更大 |
| libxposed API 与设备能力匹配 | +20 | modern 优先，legacy 兜底 |
| 与用户历史语义偏好一致（同 edition/flavor） | +30 | 见 L4 |
| `versionCode == release tag code` | +2 | 只做 tie-break，不做筛选 |
| 下载量（log 归一） | +0..5 | 弱先验，不能主导 |
| 目标 App 版本与已装版本一致 | +25 | 第 7 类 |

决策规则：

```
eligible = filter(candidates)
if eligible.isEmpty()             -> NOT_APPLICABLE（不是弹选择器！）：不更新、不通知、不下载，详情页说明原因
if eligible.size == 1             -> 自动选择（高置信）
gap = top.score - second.score
if top.score >= 60 && gap >= 30    -> 自动选择 + 显示“推荐理由”
else                              -> 打开选择器，预选 top，但标注“需要确认”
```

这样 HyperCeiler 在 HyperOS 3 上得 40 分差 → 自动选 OS3；`MiCTS/VISTrigger` 全部 0 分 → 必弹选择器；
`knoxpatch` 正式包与 sep-lite 都 0 分 → 弹选择器（下载量只用来排序预选，而不是决定装哪个）。

### L4 记忆（关键，避免每次问）

- 存 `(repoPackage → VariantSignature + 状态)`，`VariantSignature` 是语义标签集合（abis/api/os/edition/flavor），
  **不是**文件名（文件名带版本号，下个版本就失效）。状态用 7.1 的四个状态，跟偏好一起持久化。
- `TRUSTED` 模块的更新：signature 唯一命中就**直接下**，不探测、不重排、不提问；
  唯一命中不了（作者改命名/删变体）才回名字层重排，仍歧义则问。
- 新 release 解析后按 signature 相似度匹配：完全一致 → 满分；只剩部分维度 → 默认它但仍展示。
- 提供一个“不要再提示”的反向记忆：用户可以把某类变体标为忽略（例如永不装 WearOS 配套包）。
- **更新路径必须复用记忆**：批量更新时，若某模块“无记忆 + 多 APK + 低置信”，不要静默选 `first()`，
  而是把该行标记成“需选择变体”，批量更新跳过它并在结束时提示。
- 详情页当前完全不写 `preferredVariants`（只有 Installed 页写），要统一到同一个仓库层。

### L5 UI

选择器每行显示：

```
OS3_HyperCeiler-2.10.166-20260331-release.apk     [推荐]
8.2 MB · ↓107k · arm64-v8a · minSdk 35 · API 102
匹配你的设备：HyperOS 3（OS3）
```

- 推荐徽章 + **一句话理由**；低置信时不预选，提示“此版本包含多个变体，无法判断，请选择”。
- 不兼容/配套包放在折叠区“同版本附带的其他应用（2）”，灰色不可直接安装，避免误点。
- 安装后 snackbar：“已记住你的选择，下次更新自动使用”。
- 已装模块的变体列表要显示“当前已安装：`X`”并默认对齐它（更新时最可靠的信号其实是 `PackageManager` 里
  已装 APK 的 `minSdkVersion/targetSdkVersion` + 元数据 + ABI）。

### 验证兜底（很便宜，建议和 P0 一起做）

见 7.2：下载完成后、交给安装器之前，用 `PackageManager.getPackageArchiveInfo(path, GET_META_DATA)` 校验
`minSdk`、Xposed 声明、内部 ABI、`minApiVersion`。不通过则拒绝安装 + 置 `SUSPECT`，**不是**提示一下就放行。
包名不一致只**提示**不拦（实测 2.5% 的合法模块包名与仓库名不同）；只有“包名不一致 + 无声明”才当配套 App 拦下。

---

## 7. 数据获取成本与可行性（已实测）

对 8.2 MB 的 HyperCeiler APK 用 HTTP Range 做元数据探测：

1. `Range: bytes=0-0` 拿 `Content-Range` → 总长
2. 取末尾 70 KB → EOCD + central directory（1376 个 entry）
3. 取 `AndroidManifest.xml` 条目（deflate）→ 解压 → 解析 AXML

实测总流量 **156 KB**（≈ 原文件的 1.9%），成功读出：

```json
{"package":"com.sevtinge.hyperceiler","versionCode":4571,"versionName":"2.10.166-20260331",
 "minSdkVersion":35,"targetSdkVersion":37,"abi":["arm64-v8a"]}
```

CD 本身就能给出精确 ABI（`lib/arm64-v8a/…`）；manifest 给出版本、minSdk、`debuggable`；
`META-INF/xposed/module.prop` 给出 API 版本（几百字节）；`scope.list` 给出作用域。

### 7.1 探测时机：按资产探，不按操作探

资产是不可变的，所以**探测结果是纯函数，一次即永久**。完整矩阵：

### 7.1 懒验证（lazy validation）：探测由失败驱动，不由更新驱动

一个模块只要“装成过一次”，后续更新就**不需要再探测**。只有出现失败信号时才重新进入验证。
状态机（每个模块一份，随偏好一起持久化）：

| 状态 | 含义 | 下次更新时 |
| --- | --- | --- |
| `UNKNOWN` | 没装过 / 没有可信历史 | 需要探测（首次安装、详情页、或第一次更新） |
| `TRUSTED(signature)` | 上一次安装/更新成功，记住语义签名 | **不探测、不重排、不提问**，直接下 |
| `SUSPECT` | 刚失败过（安装报错 / 本地校验拒绝 / 委托安装超时 / 装后版本不符） | 探测 + 重排；必要时问用户 |
| `NOT_APPLICABLE` | 探测后确认当前设备无可用候选（minSdk / ABI） | 不更新、不通知、不下载；新版本到来时仅用名字层复查 |

转移：`UNKNOWN --装成功--> TRUSTED`；`TRUSTED --失败--> SUSPECT`；`SUSPECT --装成功--> TRUSTED`；
`SUSPECT/TRUSTED --探测发现无可用--> NOT_APPLICABLE`；`NOT_APPLICABLE --新 release 名字层看起来可用--> UNKNOWN`。
用户手动改变体 = 直接写入新的 `TRUSTED(signature)`（用户已经明确表态），成功安装后自然生效。

**首次启用时不需要任何网络**：可以从已安装的 APK 直接读出签名（`PackageInfo` 的
`versionCode / minSdkVersion / targetSdkVersion / primaryCpuAbi / metaData / FLAG_DEBUGGABLE`），
所以已有用户的已装模块一开始就是 `TRUSTED`。

因此网络探测只在这四个时机发生：

| 时机 | 原因 |
| --- | --- |
| 首次安装（`UNKNOWN`） | 无从推断，必须探 |
| 名字层分不出（2+ 候选同分） | 反正要问用户，顺手把标签补全 |
| 失败后的 `SUSPECT` | 重新确定哪个能装 |
| 用户主动打开变体选择器 | 仅补标签，不阻塞、不改变可选项 |

| 场景 | 网络探测 |
| --- | --- |
| 浏览列表 / 仓库 / 更新横幅 | ❌ 0 |
| `TRUSTED` 模块的常规更新（含批量） | ❌ **0** |
| 后台更新检查 | ❌ 0（不算新版本，就只走名字层） |
| 名字层歧义 | ✅ 2–3 个候选 × 150 KB |
| 首次安装 | ✅ 剪枝后的候选（平均 1.06 个） |
| 失败恢复 | ✅ 剪枝后的候选 |

——完全符合你说的懒扫描：**更新成功一次之后就不再验证，直到某次更新失败才重新开始，成功后又停下。**
我把上一版“每个新版本验证 1 次”的说法撤了，那是多余的。

### 7.2 免费安全网：下载后本地校验（0 网络，每次都做）

懒验证唯一的危险是“错包装成功”——例如 release 被换成了配套 App（`XPrivacyLuaPro`、`wearos_app`），
这类包**能正常装上**，不会产生任何失败信号，于是会被误记为 `TRUSTED`。
所以每次下载完、交给安装器之前，用 `getPackageArchiveInfo`（已经下了文件，不再发请求）本地检查：

| 检查 | 代价 | 不通过时 |
| --- | --- | --- |
| 包名 / Xposed 声明（三者任一） | 0 | 拒绝安装，置 `SUSPECT`，当场探测重排一次 |
| `minSdkVersion ≤ SDK_INT` | 0 | 同上 → 重排后通常直接得到 `NOT_APPLICABLE` |
| `lib/` 里有无兼容 ABI | 0 | 同上 |
| `module.prop minApiVersion ≤ 设备 LSPosed API` | 0 | 同上 |
| `applicationInfo.flags` 的 DEBUGGABLE | 0 | 只记展示徽章，不拦 |

这层不是“验证”（不联网、不看新版本），而是“文件级门禁”，所以不会破坏懒验证的成本模型；
但它把“装完才发现不对”变成“安装前就拒绝 + 当场恢复”。

### 7.3 失败的定义与重试上限

- 失败：安装器返回错误、本地门禁拒绝、委托安装超时无包事件、安装后 `versionCode` ≠ 期望。
- 失败一次 → `SUSPECT`，下一次更新才探测（不是立刻疯狂重试）；用户手动点安装则当场探测。
- 连续 3 次失败，或探测结果显示无可用候选 → `NOT_APPLICABLE` + 详情页写原因，退出批量更新，不再打扰。
- 用户取消安装、或系统安装器被用户中途退出 → **不算失败**，不改状态。

### 7.4 缓存键与预算

缓存键用 **`originalDownloadUrl`（去掉 query）+ size**，不要用文件名或 `(name,size)`：

- 240 个模块（278 个文件名）在不同 release 里复用了**同名但内容不同**的 APK（`app-release.apk` 等）；
- 49 个模块（115 个键）在不同 release 里出现了**同名同大小**的资产 → `(name,size)` 会跨版本误命中。

其他约束：
- 单模块候选超过 5 个时，只探按名字层得分排序的前 5 个（`io.github.andrealtb.lockscreenlyrics` 有 16 个包）。
- 探测超时/失败 → 直接用名字层结果，不重试超过 1 次，**绝不阻塞安装**。
- 缓存跟随 release 轮换淘汰（只保留已装模块 + 当前 release 的条目）。

抽样 120 个单 APK 模块实际批量探测：96 个首次成功，24 个因 CDN 返回 `501 Unsupported client range`
失败，加整包下载 fallback（< 8 MB 才下）后全部成功——说明 **必须有降级路径**。

注意事项：
- GitHub 的 `objects.githubusercontent.com` 支持 Range；`assets.lsposed.org` 签名 URL 和镜像
  （blogcdn / cloudflare）需要 fallback：Range 失败就直接降级到“只用文件名”，不要阻塞安装。
- 解析 AXML 需要一个小型 parser（~100 行），或者退一步：只在**下载后**用
  `getPackageArchiveInfo` 校验（零解析器）；前者用于“预选”，后者用于“兜底”。
- 不要在主线程做；选择器打开时异步补全元数据，先用名字层渲染，探测完成后刷新徽章。

---

## 8. 反模式（明确不要做）

1. **不要**用下载量当主判据。它会被“用户默认点了第一个/universal”污染，也会随 release 累积。
2. **不要**用 `versionCode == tag` 过滤候选（oshin 反例）。
3. **不要**假设“裸名 = 正式版 / 现代 API”（DPIS 与 tipismine 约定相反）。
4. **不要**用裸数字匹配系统版本（`_14-panel` 是 App 版本）。
5. **不要**在多 APK 且低置信时静默 `first()`（当前 bug 的根因）。
6. **不要**在后台批量更新里自动决定 edition/flavor。
7. **不要**为了判定去下载整个 APK（几十 MB）；Range 探测或干脆问用户。

---

## 9. 落地路线

**P0（纯名字层 + 懒验证骨架，1 天级）**
- 把 `bestForAbis()` 换成 `VariantResolver`：`debug 降权 → ABI → universal → 打分 → 置信度`。
- 删除静默 `first()`：低置信返回 `null`，调用方弹选择器（按你的决定：要求确认）。
- 选择器显示推荐徽章 + 理由 + “不兼容/配套包”折叠区（配套包先靠名字黑名单：`demo|wear|pro|bridge|provider|client|trigger`，误判可接受，因为只是折叠）。
- 引入模块状态 `UNKNOWN / TRUSTED / SUSPECT / NOT_APPLICABLE`，**用已装 APK 的 `PackageInfo` 冷启动种子**；
  `TRUSTED` 时更新路径不探测、不重排。
- 详情页写入/读取 `preferredVariants`；批量更新跳过“低置信且无记忆”的模块。
- **单 APK 也走同一个 resolver 的判定分支**：debug 命名 / `versionName` ≠ tag → 徽章（**不弹窗**）；
  `minSdk` 不兼容 / 真实 ABI 不兼容 → 标记 `NOT_APPLICABLE`，禁用安装、不进更新。
- 多 APK 且出现 debug 包时：降权非 debug，不额外提问。
- **下载后本地门禁**（见 7.2）：校验 minSdk / Xposed 声明 / ABI / minApiVersion，不通过则拒绝安装 + 置 `SUSPECT`。

**P1（设备画像，2–3 天）**
- `DeviceProfile`（vendor OS / LSPosed API），名字层新增 OS / API 轴匹配。
- 修 HyperCeiler、killappnowext、OneUI、legacy/modern 这几类（合计约 70 个 release）。
- 更新检查的 `isNewerThan` 放宽：同 versionCode 且 versionName 仅因作者命名不规范而不同时，不当作更新。

**P2（Range 探测，3–5 天，已确认要做）**
- Range 探测（CD + manifest + `module.prop` + `scope.list`）+ AXML 解析 + 缓存 + 整包 fallback。
- **只用于 7.1 规定的四个时机**（`UNKNOWN` 首次安装 / 名字层歧义 / `SUSPECT` 失败恢复 / 用户主动打开选择器），
  `TRUSTED` 模块的常规更新永不探测。
- 用真实包名 + Xposed 声明识别第 8 类；`minSdk` 硬过滤；`lib/` 做精确 ABI；`minApiVersion` 做 API 轴。
- 选择器展示精确元数据；用 `debuggable` 做 debug 降权；无可用时标 `NOT_APPLICABLE`（不回退旧版）。

**P3（语义记忆与先验）**
- 语义 signature 记忆、忽略列表、目标 App 版本匹配、下载量弱先验。

---

## 10. 如何验证

1. 把本次抓到的 **784 个多 APK release** 和 **120 个单 APK 抽样结果**（已含真实 minSdk/ABI/debuggable/包名）
   存成 fixture（几十 KB，不含 APK 本体）。
2. 断言不变量：
   - **`TRUSTED` 模块的更新路径不得发任何网络探测**（可用 fake resolver 断计数为 0）
   - **本地门禁拒绝后必须置 `SUSPECT`**，并在下一次探测时重排
   - 任何自动决策都不得是 debug（当同 release 存在非 debug 时）
   - **单 APK 是 debug 时不弹窗、不阻止**（可直接装）
   - 不得选 `minSdk > 设备` 的候选，也不得选真实 ABI 不兼容的候选
   - **候选全不可用时返回 NOT_APPLICABLE，不得回退到旧 release**
   - 不得把“包名不符 + 无 Xposed 声明”的候选当作模块
   - 不得因包名不符单独拦下 `dev.wtonec` / `ReaMicroExtend` 这类合法改名模块
   - 对已知 OS 变体（HyperCeiler / OneUI / killappnowext）在给定设备画像下必须选对
   - libxposed 优先：同版本同时存在 legacy 与 api102 时默认 api102
   - 低置信集合必须稳定（快照测试，防止回归成“又静默选了第一个”）
3. 输出指标：自动决策率、需要询问率、以及“与下载量第一名一致率”（只做参考，不作目标）。

---

## 11. 产品决策

已确认：

1. 多 APK 歧义：**要求用户确认**（不静默预选后一键装）。
2. modern / legacy 并存：**默认 api102**（设备 LSPosed 支持的前提下）。
3. 变体偏好**持久化**（备份导出/导入已取消）。
4. **做 Range 探测**（每个候选约 150 KB，只读目录尾部 + manifest + module.prop）。
5. debug 策略：**单 APK 不管是不是 debug，直接装、不确认**；多 APK 同时有 debug 与正式包时
   **优先非 debug**（降权，不提问）。
6. **不做旧版本回退**：最新 release 不可安装 → 当“忽略更新”，不进列表/通知/下载，详情页说明原因。
7. **懒验证（已采纳）**：装成功一次后不再探测，直到某次更新失败才重新验证；成功后又停下。
   具体状态机与四个探测时机见 7.1，下载后的免费门禁见 7.2。

唯一还没定的小事：详情页顶部要不要直接展示设备画像
（“HyperOS 3 / SDK 36 / arm64-v8a / LSPosed API 102”），方便用户自己判断变体。

→ 已定：**不显示**。设备画像只在内部用于打分，不占界面空间。


---

## 附录 A：分析脚本

数据抓取与分析脚本在 `/tmp`（未入库）：`/tmp/ml`、`/tmp/mods`（1183 个 module JSON），
`/tmp/bin/axml.py`（最小 AXML 解析）、`/tmp/bin/rangeprobe.py` / `rp.py` / `rp2.py`（Range 探测，后者带整包 fallback）、
`/tmp/bin/cdprobe.py`（只读 central directory + `META-INF/xposed/*`）。
样本结果：`/tmp/sample_ok.json`（120 个单 APK 的 manifest 元数据）、`/tmp/cd_rand.json`（libxposed 声明）、
`/tmp/kw.json`（kwaiching 的 riscv64 / x86_64 / universal 实测）。
如需固化为回归 fixture，建议把 784 个多 APK release 的 `{pkg, tagName, assets[{name,size,downloadCount}]}`
和 120 个抽样元数据精简后放进 `app/src/test/resources/`。
