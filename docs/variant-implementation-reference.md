> **状态：参考文档（P1 / P2）。** 当前按 `docs/p0-implementation.md` 分阶段执行。
> 本文保留设备画像、Range 探测、AXML/Zip 解析等详细规格，做 P1/P2 时直接照此实施，
> 不要在本轮 P0 里提前动手。

# 变体选择 + 懒验证：一次性实施计划

配套数据与结论见 `docs/variant-selection.md`。本文是**单次会话内做完全部范围**的施工单。
所有产品决策已确认（歧义要确认、默认 api102、偏好本地持久化、做 Range 探测、单 APK debug 不确认、
不回退旧版本、懒验证）。不需要再问。

## 0. 本次范围

| # | 交付物 | 说明 |
| --- | --- | --- |
| 1 | `domain/variant/*` | traits 解析、签名、打分、决策、状态机（纯 Kotlin） |
| 2 | `DeviceProfile` | SDK、ABI、厂商 OS（HyperOS/MIUI/OneUI/ColorOS）、LSPosed API |
| 3 | `RangeProbe` | CD + manifest + `module.prop` + `scope.list`，带缓存与降级 |
| 4 | `ApkGate` | 下载后本地门禁：包名/声明/minSdk/ABI/minApiVersion |
| 5 | 状态与存储 | `UNKNOWN/TRUSTED/SUSPECT/NOT_APPLICABLE` + 冷启动种子 + DataStore 持久化 |
| 6 | 集成 | 详情页、Installed、批量更新、后台检查、通知 |
| 7 | UI 与文案 | 推荐徽章/理由、配套包折叠、不可用说明、debug 徽章；8 个新字符串 ×13 语言 |
| 8 | 测试 | 4 个测试类 + 已入库 fixture |

**明确不做**（防止范围蔓延）：

- 目标 App 版本匹配（第 7 类）：只从 `scope.list` 记录数据，不参与打分。
- 下载量先验、忽略变体 UI（P3）。
- 旧版本回退、服务端预计算、下载器重写、设置页开关。
- 不新增第三方依赖（AXML 解析自己写；HTTP 用现有 Ktor/OkHttp）。

## 1. 环境与基线

- Kotlin 2.2.21 / compileSdk 36 / minSdk 26 / Ktor 3.2.3 + OkHttp engine / Compose BOM 2025.12.01。
- 改动前先跑 `./gradlew :app:testDebugUnitTest --offline`（当前为绿）。
- 工作区有大量**未提交**改动，**不要** `git checkout .` / `git stash`；不要整文件格式化。
- fixture 已入库：`app/src/test/resources/variant-corpus.json`（1183 模块 / 784 多 APK release）、
  `app/src/test/resources/variant-probes.json`（151 个真实 APK 元数据）。
  重新生成：`python3 tools/build_variant_fixture.py <modules 目录> app/src/test/resources/variant-corpus.json`。

## 2. 文件清单

新增：

```
app/src/main/java/org/lspstore/domain/variant/VariantTraits.kt
app/src/main/java/org/lspstore/domain/variant/VariantSignature.kt
app/src/main/java/org/lspstore/domain/variant/VariantDecision.kt
app/src/main/java/org/lspstore/domain/variant/VariantResolver.kt
app/src/main/java/org/lspstore/domain/variant/ModuleVariantState.kt
app/src/main/java/org/lspstore/domain/variant/DeviceProfile.kt
app/src/main/java/org/lspstore/data/device/DeviceProfileProvider.kt
app/src/main/java/org/lspstore/data/probe/ApkMeta.kt
app/src/main/java/org/lspstore/data/probe/RangeProbe.kt
app/src/main/java/org/lspstore/data/probe/AxmlParser.kt
app/src/main/java/org/lspstore/data/probe/ZipDirectory.kt
app/src/main/java/org/lspstore/data/install/ApkGate.kt
app/src/test/java/org/lspstore/VariantTraitsTest.kt
app/src/test/java/org/lspstore/VariantResolverTest.kt
app/src/test/java/org/lspstore/VariantStateMachineTest.kt
app/src/test/java/org/lspstore/ApkGateTest.kt
```

修改：

```
domain/model/Models.kt                     // bestForAbis/latestAssetFor 标 @Deprecated
data/repository/LibraryRepositoryImpl.kt   // variantStates + preferredVariants 迁移
data/repository/InstalledModulesRepositoryImpl.kt  // InstalledModule 增字段
data/install/ApkInstaller.kt               // gate 挂点（或调用方挂）
di/AppContainer.kt                         // deviceProfileProvider / rangeProbe / variantResolver 工厂
ui/screens/detail/ModuleDetailViewModel.kt / ModuleDetailScreen.kt
ui/screens/installed/InstalledViewModel.kt
domain/usecase/UpdateChecker.kt
data/work/UpdateCheckWorker.kt
res/values/strings.xml + 13 个 values-*/strings.xml
```

## 3. 领域层

```kotlin
// VariantTraits.kt
data class VariantTraits(
    val abis: Set<String> = emptySet(),
    val universal: Boolean = false,
    val debugName: Boolean = false,
    val libxposedApi: Int? = null,       // API101/102
    val legacyWord: Boolean = false,
    val android: Int? = null,            // A16 / android15+
    val vendor: VendorOsRef? = null,     // OS2/OS3/MIUI/OneUI8.x
    val minSdkToken: Int? = null,        // minsdk35
    val edition: Set<String> = emptySet(),
    val flavors: Set<String> = emptySet(),
    val targetPackage: String? = null,   // com.foo.bar
)
data class VendorOsRef(val family: VendorOs.Family, val major: Int?)
fun parseTraits(name: String): VariantTraits
```

解析规则（照抄 `docs/variant-selection.md` 第 2、3 节踩过的坑）：

- 归一化：小写 + `[_ ]` → `-`，再按 `-` 切 token，同时保留整串做子串匹配（`arm64-v8a` 会被切开）。
- ABI：`arm64|aarch64|armv8` / `armeabi|armv7|v7a|arm32` / `x86_64|x64` / `x86`；**`x86` 不得命中 `x86_64`**；
  universal：`universal|allabi|armall|noarch|uni`。
- API：`api-?(\d{2,3})` → libxposedApi；`legacy|modern` → 代际词（`modern` 视为 libxposed）。
- 系统：白名单 `os([23])\b|hyperos.*?([23])|miui|oneui\s?(\d+)|coloros|originos|a1([2-9])\b|android\s?-?(\d+)`。
  **禁止裸数字**（`_14-panel` 是 App 版本）。
- minSdk：仅 `minsdk(\d+)`。
- debug：词边界 `debug|dev`。
- edition：`full|lite|foss|pro|normal|root|native|non-native`；flavor：`toast|panel|tile|all|adblock|simple|sign|offline|online|log|nolog|icon`。
- 包名：正则 `[a-z][a-z0-9_]*(\.[a-z0-9_]+){2,}`，命中且不等于模块包名时记 `targetPackage`。

```kotlin
// VariantSignature.kt  —— 跨版本记忆，永不存文件名
@Serializable
data class VariantSignature(
    val abi: String? = null,
    val api: String? = null,          // "legacy" | "libxposed:<minApi>"
    val os: String? = null,           // "hyperos:3" | "oneui:8" | "android:16"
    val edition: List<String> = emptyList(),
    val flavor: List<String> = emptyList(),
) {
    fun matches(t: VariantTraits, meta: ApkMeta?): Boolean
    companion object {
        fun of(t: VariantTraits, meta: ApkMeta?): VariantSignature
        fun ofInstalled(info: InstalledApkFacts): VariantSignature   // 冷启动种子
    }
}
```

```kotlin
// VariantResolver.kt —— 纯函数，输入 release + 设备 + 可选探测结果
class VariantResolver(private val device: DeviceProfile) {
    fun resolve(
        release: Release,
        signature: VariantSignature? = null,     // 非空 = TRUSTED：只做签名映射
        probes: Map<String, ApkMeta> = emptyMap(), // 空 Map = 名字层
    ): VariantDecision
}

sealed interface VariantDecision {
    data class Pick(val asset: ReleaseAsset, val reasons: List<Reason>, val confidence: Confidence)
    data class Choose(val candidates: List<ScoredAsset>, val hint: Reason?)
    data class NotApplicable(val reason: Reason)
}
```

硬过滤（顺序）：

1. 非 APK。
2. ABI：有具体 ABI token 且与 `device.abis` 无交集且非 universal → 剔除；`probes` 里有 `libs` 时以 `libs` 为准。
3. `minSdk`：`probes` 里的 `minSdk > device.sdkInt` → 剔除。
4. `package`：`probes` 里包名不符 **且** 无 Xposed 声明 → 剔除。
5. API：`probes` 里 `module.prop minApiVersion > device.lsposedApi`（两者非空时）→ 剔除。
6. debug **不剔除**，只降权（`-50`）。

全部被剔除 → `NotApplicable`；剩 1 个 → `Pick(HIGH)`。否则打分：

```
OS 匹配 +40 / Android 版本 +30 / minSdk 取≤设备的最大值 +20 / ABI 精确 +25 / universal +10
/ libxposed 匹配 +20（device.lsposedApi>=minApi 时）/ 签名匹配 +60 / debugName -50
/ 下载量 log 归一 +0..5 / 名字里 versionCode==tag -0（不做正向，避免 oshin 陷阱）
```

决策：`top>=60 && gap>=30` → `Pick(HIGH)`；否则 `Choose(candidates sorted, hint = 原因)`。
`signature != null` 时：签名唯一命中 → 直接 `Pick(HIGH)`（不再比较分数）；无命中 → 回名字层排序，
唯一最高分则 `Pick(NEEDS_CONFIRM)`（UI 标注），否则 `Choose`。

## 4. DeviceProfile（Android 实现）

```kotlin
data class DeviceProfile(
    val sdkInt: Int,
    val abis: List<String>,
    val vendorOs: VendorOs? = null,
    val lsposedApi: Int? = null,
)
interface DeviceProfileProvider { suspend fun get(): DeviceProfile }
```

- `vendorOs`：`Runtime.exec("getprop ro.mi.os.version.name")` → `OS3.0`；
  回退 `ro.miui.ui.version.name`（`V14`）、`ro.build.version.oneui`（`80000` → 8）、`ro.build.version.oplusrom`；
  再回退解析 `Build.VERSION.INCREMENTAL`（`OS3.0.2.0.WOCCNXM`）。
  全部 IO 线程、单次、超时 1s、结果缓存到进程内（`SettingsRepository` 落盘可选）。
- `lsposedApi`：读 `org.lsposed.manager` 的 `versionName/versionCode`；
  映射表保守：`<1.9.0 → 93(legacy only)`、`1.9.x → 100`、`1.10.x → 102`。
  取不到就 `null`（跳过 API 硬过滤，只做降权）。
- `AppContainer` 增加 `val deviceProfile: DeviceProfile`（启动时异步加载，带默认值 `sdkInt/abis`）。

## 5. RangeProbe

```kotlin
data class ApkMeta(
    val size: Long, val libs: List<String>,
    val packageName: String?, val versionCode: Long?, val versionName: String?,
    val minSdk: Int?, val targetSdk: Int?, val debuggable: Boolean?,
    val legacyXposed: Boolean, val libXposedProvider: Boolean, val moduleProp: Map<String, String>,
    val declared: Boolean,   // legacy || provider || moduleProp 非空
)
class RangeProbe(private val client: HttpClient, private val cacheFile: File) {
    suspend fun fetch(url: String, size: Long): ApkMeta?
}
```

- 用**独立** Ktor client（`expectSuccess = false`，复用超时配置），避免影响主 client。
- 三步：`Range: bytes=0-0` → 取 `Content-Range`；末尾 70 KB → 找 EOCD（只在最后 66 KB 里找，
  `0x06054b50`）→ CD；再按 local header 取 `AndroidManifest.xml` / `META-INF/xposed/*`。
- 解压：`Inflater(true)` 处理 deflate（method=8）。
- 防护：`0xFFFFFFFF` 视为 zip64 → 放弃；单条目 > 2 MB → 放弃；总流量 > 1.5 MB → 放弃。
- 失败：206 缺失（200/403/501）→ 若 `size <= 8 MB` 整包下载兜底，否则返回 `null`；
  **`null` 一律降级为名字层，不阻塞**。
- 缓存：`cacheDir/variant-cache.json`，键 = `originalDownloadUrl.substringBefore("?") + "|" + size`，
  值 = `ApkMeta`；上限 500 条，按 LRU 淘汰；读写走 IO 线程 + Mutex。
- AXML 解析器（约 150 行）：文件头 `0x0003` → 遍历 chunk：
  `0x0001` string pool（UTF-8 flag `0x100`，UTF-16 用 u16len）、`0x0180` resource map、
  `0x0102` start element。属性 20 字节：`ns,name,rawValue,u16 size,u8 res0,u8 type,u32 data`，
  只关心 `0x0101020c minSdk / 0x01010270 targetSdk / 0x0101021b versionCode / 0x0101021c versionName /
  0x0101000f debuggable`，以及 `<meta-data>` 的 `name`(0x01010003)/`value`(0x01010024)。

## 6. ApkGate（下载后、安装前，0 网络）

```kotlin
sealed interface GateResult {
    data object Ok
    data class Warn(val reason: Reason)                 // 放行
    data class Reject(val reason: Reason, val fatal: Boolean) // fatal → NOT_APPLICABLE
}
class ApkGate(private val context: Context, private val device: DeviceProfile) {
    fun check(file: File, expectedPackage: String): GateResult
}
```

- `getPackageArchiveInfo(file.path, GET_META_DATA or GET_ACTIVITIES)`：
  包名、versionCode、`applicationInfo.minSdkVersion`、`debuggable`、`metaData`。
- Zip 侧（`ZipFile`）：`lib/` 下的 ABI 集合；`META-INF/xposed/module.prop` 是否存在 + `minApiVersion`。
- 规则（严格执行，见 `docs/variant-selection.md` 3.4 / 7.2）：
  - `minSdk > sdkInt` → `Reject(fatal=true)`。
  - `libs` 非空且与设备无交集且无 universal → `Reject(fatal=true)`。
  - 包名一致 → 其余检查通过即 `Ok`（`debuggable` → `Warn`）。
  - 包名不一致 + 有声明 → `Warn`；包名不一致 + 无声明 → `Reject(fatal=true)`。
  - `minApiVersion > device.lsposedApi`（非空）→ `Reject(fatal=false)`（可换变体再试）。

## 7. 状态机、存储、冷启动

```kotlin
@Serializable
data class ModuleVariantState(
    val status: Status = Status.UNKNOWN,
    val signature: VariantSignature? = null,
    val failures: Int = 0,
    val reasonKey: String? = null,   // 资源名，不存文案
    val updatedAt: Long = 0L,
) { enum class Status { UNKNOWN, TRUSTED, SUSPECT, NOT_APPLICABLE } }
```

- `LibraryRepositoryImpl`：新 key `variant_state`（JSON map）；`variantStates: Flow<Map<String, ModuleVariantState>>`；
  `recordInstallResult(pkg, ok, signature, reasonKey)`：成功 → `TRUSTED` + `failures=0`；
  失败 → `SUSPECT` + `failures+1`，`>=3` 或 `fatal` → `NOT_APPLICABLE`。
- `preferredVariants`（asset 文件名）保留读兼容，写路径改为签名；读到时惰性转一次签名。
- 备份导出/导入已移除；状态直接存在 `library.preferences_pb` 的 `variant_state` 键下：`Map<String, ModuleVariantState>`；
  导入接受 `1..2`，v1 忽略该字段；导出写 v2。
- 冷启动种子：`AppContainer.seedVariantStates()`（只跑一次，`SharedPreferences` 标记）：
  遍历已装模块 → 用 `InstalledApkFacts`（`primaryCpuAbi/minSdk/targetSdk/hasLibXposed`）构造签名 →
  状态缺失时写 `TRUSTED`。`InstalledModule` 增加 `minSdk/targetSdk/primaryCpuAbi/debuggable/hasLibXposed`
  （**全部带默认值**，磁盘缓存 JSON 才兼容）。

## 8. 集成点

| 文件 | 改动 |
| --- | --- |
| `ModuleDetailViewModel.installLatest()` L202 | `resolve()`；`Pick` 直接下；`Choose` 弹 picker（`VariantPicker` 改持 `List<ScoredAsset>`）；`NotApplicable` 提示+禁装 |
| `ModuleDetailViewModel.performInstall()` L366 | 先 `ApkGate`；`Reject` → 记失败/置状态并提示；`Ok` → 原流程；成功后 `recordInstallResult(ok=true)` |
| `ModuleDetailScreen` L641 / L379 / L915 | 删除本地 `defaultAsset()`；picker 用 `ScoredAsset`，显示徽章/理由/灰区；未确认时按钮写“选择并安装” |
| `InstalledViewModel.performInstall()` L201 | 用 `variantStates[pkg]`：`TRUSTED` → 签名映射，不探测；否则 `resolve()`；`NotApplicable` → 跳过并在批量结束时汇总 |
| `InstalledViewModel` L573 | `setPreferredVariant` 改为存签名 + `TRUSTED` |
| `UpdateChecker.findUpdates()` | 新参数 `variantStates`：过滤 `NOT_APPLICABLE`；同 versionCode 且 versionName 仅命名差异 → 不算更新 |
| `UpdateCheckWorker` L58 | 用 resolver `Pick`；`Choose/NotApplicable` 不计入“有更新”、不通知 |
| `AppContainer` | 组装 `deviceProfile` / `rangeProbe` / `variantResolver` / `apkGate` |

探测调用点（只有 4 处，见 `variant-selection.md` 7.1）：首次安装（`UNKNOWN`）、名字层 `Choose`、
`SUSPECT` 恢复、用户主动打开选择器。`TRUSTED` 路径**不得**出现探测调用。

## 9. UI 与文案

- picker 行：`[推荐]` + 大小/下载量/ABI/(minSdk/API 若已探测)；推荐行下一行小字理由。
- 低置信顶部提示；配套 App 折叠区 `同版本附带的其他应用（N）` 灰显。
- `NOT_APPLICABLE`：安装按钮禁用 + 原因一行；仍可手动切版本（现有版本选择器）。
- debug：详情页小徽章。
- 新字符串（英文写在 `values/strings.xml`，Android Studio Translations Editor 机翻；lint 报漏翻）：

```
variant_recommended / variant_reason_matches_device / variant_needs_confirm
variant_bundled_apps / variant_incompatible_min_sdk / variant_incompatible_abi
variant_remembered / variant_debug_badge
```

## 10. 测试

- `VariantTraitsTest`：ABI(`x86` vs `x86_64`)、`minsdk35`、`OS2/OS3`、`A16`、`_14-panel`(不得判成 Android 14)、
  `legacy/modern`、`full/lite`、真实文件名抽样 30 个（来自 `variant-probes.json`）。
- `VariantResolverTest`：加载 `variant-corpus.json`，`DeviceProfile(36, [arm64-v8a, armeabi-v7a])`，
  断言：① 同 release 有非 debug 时绝不 `Pick(debug)`；② `Choose` 集合与快照一致（防回归成静默 first）；
  ③ 关键模块断言（HyperCeiler → `Choose`（无画像时）/`Pick(OS3)`（有画像时）；mi5g/micts/knoxpatch → `Choose`；
  systemednotificationblocker → 选 `minsdk35`（设备 36））；④ 真实 ABI 限制时（probes 注入）→ `NotApplicable`。
- `VariantStateMachineTest`：成功→TRUSTED；失败→SUSPECT；3 次→NOT_APPLICABLE；`TRUSTED` 更新路径
  探测调用计数 = 0；`SUSPECT` 后恢复成功 → TRUSTED 且计数归零。
- `ApkGateTest`：把规则抽成 `GatePolicy.evaluate(facts: ApkFacts, device)` 纯函数，覆盖
  minSdk/ABI/包名+声明/`minApiVersion` 四组用例；Android 侧只做薄封装。

## 11. 执行顺序（一次会话，保持可编译）

1. `VariantTraits` + `VariantTraitsTest` → 绿。
2. `VariantSignature` + `VariantDecision` + `VariantResolver` + `VariantResolverTest`（只跑 corpus，不接 UI）→ 绿。
3. `ModuleVariantState` + `LibraryRepositoryImpl`（状态持久化/迁移）+ `VariantStateMachineTest` → 绿。
4. `InstalledModule` 扩字段 + 冷启动种子（`AppContainer`）→ 编译。
5. `ApkGate`（纯函数 + 薄封装）+ `ApkGateTest` → 绿。
6. `DeviceProfileProvider` + `RangeProbe`（含 AXML/Zip）→ 编译 + 手动验证 1 个真实 APK。
7. 换 5 处调用点（详情页、Installed、Checker、Worker、通知）→ 编译，跑全量单测。
8. 文案 + 翻译 + 删除 `bestForAbis` 引用（保留 `@Deprecated` 定义）→ 全绿。

## 12. 验收

- [ ] `grep -rn "bestForAbis" app/src/main` 只剩 `@Deprecated` 定义。
- [ ] HyperCeiler：无画像 → 弹选择器、不预选 OS2；有画像（HyperOS 3）→ 自动选 OS3 并显示理由。
- [ ] 单 APK 模块（含 debug-only，如 `com.souleven.nothingos`）→ 一键装、无弹窗。
- [ ] 多 APK 含 debug → 优先非 debug，不弹窗。
- [ ] 把某模块的 APK 换成配套 App 场景（测试包）→ `ApkGate` 拒绝并给原因。
- [ ] `TRUSTED` 模块批量更新：探测调用次数 = 0（日志断言/单测）。
- [ ] `minSdk 35` 模块在 SDK 33 设备上：详情页禁用安装 + 原因；不出现在更新列表。
- [x] ~~备份导出/导入~~ 功能已移除。
- [ ] `./gradlew :app:testDebugUnitTest --offline` 绿。

## 13. 风险

| 风险 | 处理 |
| --- | --- |
| AXML/zip 解析兼容性（少量奇葩 APK） | 解析失败一律 `null` → 降级名字层；单测覆盖 chunk 边界 |
| 探测被 CDN 拒绝（实测 24/120 出现 501） | 8 MB 内整包兜底；否则降级；缓存失败结果 10 分钟，避免反复打 |
| 探测拖慢详情页 | 先渲染名字层，探测结果异步回来再刷新徽章；并发上限 3 |
| 缓存膨胀 | 500 条 LRU + `cacheDir`，随 release 轮换淘汰 |
| `InstalledModule` 缓存 JSON 不兼容 | 新字段全部给默认值 |
| 文案 13 语言 | 只加英文 + Translations Editor 机翻；`MissingTranslation`/`ExtraTranslation` 让 lint 失败 |
