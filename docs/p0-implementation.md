# P0 实施清单：变体选择 + 懒验证

配套文档：`docs/variant-selection.md`（数据、结论、已定决策）。
P1（设备画像）/ P2（Range 探测）的详细规格在 `docs/variant-implementation-reference.md`，**本次不做**，但接口要留好。
所有产品决策已确认，可以直接动手，不需要再问。

## 0. 目标（P0 范围）

1. 删掉“静默 `first()`”：低置信 → 弹选择器并**要求确认**。
2. 引入模块状态 `UNKNOWN / TRUSTED / SUSPECT / NOT_APPLICABLE`（懒验证，见 7.1）。
3. 用已装 APK 的 `PackageInfo` 做**冷启动种子**，让已有用户零网络进入 `TRUSTED`。
4. 下载后、安装前的**本地门禁**（0 网络）：minSdk / Xposed 声明 / 内部 ABI / `minApiVersion`。
5. 单 APK 也走同一判定：不可安装 → `NOT_APPLICABLE`（不更新、不通知、不下载）。
6. debug 只降权、不拦截；单 APK 是 debug 也直接装、不弹窗。
7. 变体偏好与状态持久化（本地 DataStore；不提供备份导出/导入）。

## 1. 基线

- `./gradlew :app:testDebugUnitTest --offline` 已通过（改动前请保持绿）。
- 工作区有大量**未提交**改动（整个 app 是未提交状态）。**不要** `git checkout .` / `git stash`。
- 回归数据已就位：
  - `app/src/test/resources/variant-corpus.json`（1183 模块 / 784 个多 APK release / 340 KB）
  - `app/src/test/resources/variant-probes.json`（151 个真实 APK 探测结果：package/minSdk/targetSdk/debuggable/libs/module.prop）
  - 重新生成：`python3 tools/build_variant_fixture.py <dir-of-module-json> app/src/test/resources/variant-corpus.json`

## 2. 新增文件

```
app/src/main/java/org/lspstore/domain/variant/
    DeviceProfile.kt        // 设备画像（P0 只用 abis/sdkInt，其余留字段）
    VariantTraits.kt        // 文件名 → 结构化标签
    VariantSignature.kt     // 语义签名（跨版本记忆用）
    VariantDecision.kt      // Pick / Choose / NotApplicable
    VariantResolver.kt      // 纯 Kotlin，无 Android 依赖，便于单测
    ModuleVariantState.kt   // 状态机 + @Serializable
app/src/main/java/org/lspstore/data/install/
    ApkGate.kt              // 本地门禁（PackageManager + ZipFile，零网络）
app/src/test/java/org/lspstore/
    VariantResolverTest.kt
    VariantStateMachineTest.kt
    VariantTraitsTest.kt
```

## 3. API 草案

```kotlin
// DeviceProfile.kt
data class DeviceProfile(
    val sdkInt: Int,
    val abis: List<String>,
    // P1 填充，P0 可全为 null
    val vendorOs: VendorOs? = null,
    val lsposedApi: Int? = null,
)

data class VendorOs(val family: Family, val major: Int?) {
    enum class Family { HYPEROS, MIUI, ONEUI, COLOROS, ORIGINOS, OTHER }
}

// VariantTraits.kt —— 纯函数，输入文件名，输出标签
data class VariantTraits(
    val abis: Set<String> = emptySet(),   // arm64-v8a / armeabi-v7a / x86 / x86_64
    val universal: Boolean = false,
    val debugName: Boolean = false,
    val libxposedApi: Int? = null,        // 100/101/102
    val legacyWord: Boolean = false,      // 名字里出现 legacy
    val android: Int? = null,             // A16 / android15+ / Android14
    val vendor: Pair<VendorOs.Family, Int?>? = null, // OS2/OS3/MIUI/OneUI8.x
    val minSdkToken: Int? = null,         // minsdk35
    val edition: Set<String> = emptySet(),// full/lite/foss/pro/normal/root/native
    val flavors: Set<String> = emptySet(),// toast/panel/tile/all/adblock/simple/sign
    val targetApp: String? = null,        // 文件名里出现的 com.xxx 包名
)

fun parseTraits(assetName: String): VariantTraits

// VariantSignature.kt —— 跨版本记忆（不要存 asset 文件名）
@Serializable
data class VariantSignature(
    val abi: String? = null,
    val api: String? = null,        // "legacy" | "libxposed"
    val os: String? = null,         // "hyperos3" | "oneui8" | "android16"
    val edition: List<String> = emptyList(),
    val flavor: List<String> = emptyList(),
) {
    fun matches(traits: VariantTraits): Boolean
    companion object { fun of(traits: VariantTraits, meta: ApkMeta?): VariantSignature }
}

// VariantDecision.kt
sealed interface VariantDecision {
    data class Pick(
        val asset: ReleaseAsset,
        val reasons: List<Reason>,          // UI 一句话理由
        val confidence: Confidence,          // HIGH / NEEDS_CONFIRM
    ) : VariantDecision

    data class Choose(val candidates: List<ScoredAsset>) : VariantDecision

    data class NotApplicable(val reason: Reason) : VariantDecision
}

data class ScoredAsset(val asset: ReleaseAsset, val traits: VariantTraits, val score: Int,
                       val recommended: Boolean, val blocked: Reason?)

// VariantResolver.kt
class VariantResolver(private val device: DeviceProfile) {
    /**
     * @param signature 非空表示 TRUSTED：只做签名映射，不重排
     * @param probes    可选的探测结果（P2）；P0 传空 Map
     */
    fun resolve(
        release: Release,
        signature: VariantSignature? = null,
        probes: Map<String, ApkMeta> = emptyMap(),
    ): VariantDecision
}
```

打分表沿用 `docs/variant-selection.md` L3。P0 只有名字层（`minSdk` 未知、设备 OS/API 为 null，
所以实际只有 ABI + universal + debug + edition/flavor 参与）。

## 4. 名字解析要点（踩过的坑）

- 分隔符统一 `[-_. ]`，全小写。
- ABI token：`arm64-v8a|arm64|aarch64|armv8` / `armeabi-v7a|armeabi|armv7|v7a|arm32` / `x86_64|x64` / `x86`；`universal|allabi|armall|noarch|uni`。
  **`x86` 不能命中 `x86_64`**。
- `minSdk` 只认 `minsdk\d+`；`API-101`/`api102` 是 libxposed；`legacy`/`modern` 是代际词。
- OS 只认白名单：`os2|os3|hyperos|miui|oneui\d?|coloros|originos|a1[2-9]|android\d+`。
  **禁止裸数字匹配**（`FiveGSwitcher-2.0.4_14-panel` 的 14 是 App 版本）。
- debug 只认词边界 `debug|dev`，不要 `contains("debug")` 误伤 `debuggerx`。
- `targetApp`：文件名里出现形如 `com.foo.bar` 的包名时记录（`hupu_8.0.74` 这种没有包名的不要硬猜）。

## 5. 状态机与存储

```kotlin
@Serializable
data class ModuleVariantState(
    val status: Status = Status.UNKNOWN,
    val signature: VariantSignature? = null,
    val failures: Int = 0,
    val reason: String? = null,        // NOT_APPLICABLE 的原因（用于 UI，存资源 key 而非文案）
    val updatedAt: Long = 0L,
) {
    enum class Status { UNKNOWN, TRUSTED, SUSPECT, NOT_APPLICABLE }
}
```

存储放 `LibraryRepositoryImpl`（DataStore `library`）：

- 新 key `variant_state`，值 = `{ "<repoPkg>": ModuleVariantState }` 的 JSON。
- 提供 `val variantStates: Flow<Map<String, ModuleVariantState>>`、`suspend fun setVariantState(pkg, state)`、`suspend fun recordInstallResult(pkg, ok: Boolean, signature: VariantSignature?)`。
- 状态存在 `library.preferences_pb` 的 `variant_state` 里（`Map<String, ModuleVariantState>` 的 JSON）。**不要**把 `preferredVariants`（asset 文件名）继续当主存储，转成 `VariantSignature`；旧数据在读取时惰性迁移一次即可。备份导出/导入功能已移除，`LibraryBackup` 不再存在。

状态转移（集中在一个函数里，别散落到 ViewModel）：

```
onInstallSucceeded(pkg, sig) : TRUSTED(sig), failures=0
onInstallFailed(pkg)         : SUSPECT, failures+1；若 failures>=3 或 gate 报“无可用”→ NOT_APPLICABLE
onUserPicked(pkg, sig)       : TRUSTED(sig)   // 用户明确表态
onEvaluatedNoCandidate(pkg, reason) : NOT_APPLICABLE(reason)
resolveForUpdate(state)      : TRUSTED → 不探测、不重排；否则走 resolver
```

## 6. 集成点（逐文件）

| 文件 | 现状 | 改成 |
| --- | --- | --- |
| `ui/screens/detail/ModuleDetailViewModel.kt` `installLatest()` L202 | `apks.size == 1 -> 直接下，else 弹 picker` | `resolver.resolve()`；`Pick` 直接下 / `Choose` 弹 picker（`VariantPicker` 改持有 `List<ScoredAsset>`）/ `NotApplicable` 提示 + 禁装 |
| 同上 `performInstall()` L366 | 直接 `installer.install()` | 先 `ApkGate.check(file, expectedPkg, device)`；不通过 → 拒绝 + `onInstallFailed` |
| 同上安装成功分支 | 只 `finishInstall()` | 追加 `libraryRepository.recordInstallResult(pkg, true, signature)` |
| `ui/screens/detail/ModuleDetailScreen.kt` `defaultAsset()` L641 + `VariantPickerDialog` L379 + `AssetSheetContent` L915 | 全部走 `bestForAbis()` | 统一用 resolver；删掉本地 `defaultAsset()`；picker 显示推荐徽章/理由/灰区 |
| `ui/screens/installed/InstalledViewModel.kt` `performInstall()` L201 | `preferredVariants[assetName]` + `latestAssetFor()` | 用 `variantStates[pkg]` + resolver；`NotApplicable` 跳过并标记；成功后写状态 |
| 同上 L573 `setPreferredVariant` | 存 asset 文件名 | 存 `VariantSignature` + 置 `TRUSTED` |
| `domain/usecase/UpdateChecker.kt` | 只比较版本 | 新增参数 `variantStates`：过滤 `NOT_APPLICABLE`；`versionName` 仅因作者命名差异不同时不算更新 |
| `data/work/UpdateCheckWorker.kt` L58 | `latestAssetFor(channel, abis)` | 用 resolver 的 `Pick`；`Choose/NotApplicable` 不计入“有更新” |
| `data/install/ApkVerifier.kt` | 签名校验 | 保持不动；门禁独立成 `ApkGate`（职责分开） |
| `data/repository/InstalledModulesRepositoryImpl.kt` | `InstalledModule` 只有 version/label | 增加 `minSdk`、`targetSdk`、`primaryCpuAbi`、`debuggable`、`hasLibXposedProvider`（都有默认值，注意缓存 JSON 兼容） |
| `domain/model/Models.kt` | `bestForAbis` / `latestAssetFor` 公开 | 标 `@Deprecated`，P0 全部调用点迁走后删除；`AbiSelectionTest` 改成 resolver 测试 |

## 7. 本地门禁（`ApkGate`）

输入：已下载的 `File` + 期望仓库包名 + `DeviceProfile`。全部本地，零网络。

```kotlin
sealed interface GateResult {
    data object Ok : GateResult
    data class Warn(val reason: Reason) : GateResult
    data class Reject(val reason: Reason, val fatal: Boolean) : GateResult  // fatal=true → NOT_APPLICABLE
}

fun check(file: File, expectedPackage: String, device: DeviceProfile): GateResult
```

实现要点：

- `PackageManager.getPackageArchiveInfo(path, GET_META_DATA or GET_ACTIVITIES)` → `packageName/versionCode/applicationInfo.minSdkVersion/primaryCpuAbi/metaData/flags`。
- `debuggable = applicationInfo.flags and FLAG_DEBUGGABLE != 0`（只展示，不拦）。
- 声明判定优先用两个可靠信号：`meta-data` 有 `xposedmodule`/`xposedminversion`；
  `ZipFile(file).getEntry("META-INF/xposed/module.prop") != null`。
  `getPackageArchiveInfo(..., GET_PROVIDERS)` 拿 provider 不可靠（部分 API 级别不填），只当附加信号：
  **取不到 provider 不算“无声明”**，探到才加分。
- `module.prop` 解析 `minApiVersion=`（几行文本），与 `device.lsposedApi` 比较（P0 若 `lsposedApi == null` 跳过此检查）。
- 包名不一致：**有声明 → 放行 + 警告**；**无声明 → 拒绝**（实测 2.5% 合法模块包名不同，别一刀切）。
  包名一致时不需要声明也能放行。
- `minSdk > device.sdkInt` → 拒绝且 `fatal = true`（→ `NOT_APPLICABLE`）。
- 内部 ABI：读 zip 的 `lib/<abi>/`；非空且与设备无交集且无 universal → 拒绝且 `fatal = true`。

## 8. 冷启动种子

首次跑一次（`AppContainer` 或 `LspStoreApp`）：

1. `installedRepository.getInstalledModules()`（已有缓存）→ 对每个模块用 `PackageInfo` 构造 `VariantSignature`
   （`primaryCpuAbi` → abi；`hasLibXposedProvider` → api；`minSdk`/`targetSdk` 记录备用）。
2. 若该模块 `variantStates` 里没有记录 → 写 `TRUSTED(sig)`。
3. 幂等：只在状态缺失时写；用 SharedPreferences 标记 `variant_seed_done` 避免每次启动扫描。

## 9. UI 与文案

- 选择器：每行 `[推荐]` 徽章 + 大小/下载量/ABI 标签；推荐行下加一句理由；低置信时顶部提示“此版本包含多个变体，请确认”。
- 配套 App 折叠区：`同版本附带的其他应用（N）`，灰显不可直接装。
- `NOT_APPLICABLE`：安装按钮禁用 + 一行原因（`minSdk 35 > 你的 Android 13` / `仅提供 riscv64`），详情页仍可手动切换版本。
- debug：详情页小徽章，不弹窗。
- 新增字符串（先加 `values/strings.xml`，再用 Android Studio 的 Translations Editor 机翻；漏翻/多余翻译由 lint 的 `MissingTranslation` / `ExtraTranslation` 直接报错）：

```
variant_recommended            Recommended
variant_reason_matches_device  Matches your device (%1$s)
variant_needs_confirm          This release ships several variants — choose one
variant_bundled_apps           Other apps in this release (%1$d)
variant_incompatible_min_sdk   Requires Android %1$d+ (you have %2$d)
variant_incompatible_abi       Only %1$s build — not compatible with this device
variant_remembered             Remembered your choice for future updates
variant_debug_badge            debug build
```

## 10. 测试

1. `VariantResolverTest`（用 `variant-corpus.json`，`DeviceProfile(36, [arm64-v8a, armeabi-v7a])`）：
   - 不存在“同 release 有非 debug 却选中 debug”的案例；
   - 不存在“无条件落到 assets.first()”的分支（`Choose` 集合用快照断言）；
   - HyperCeiler / killappnowext / mi5g / micts 的决策符合预期（P0 无设备画像时 HyperCeiler 应返回 `Choose`，而不是 OS2）。
2. `VariantTraitsTest`：ABI/`minsdk`/`A14`/`OS2`/`_14-panel`/`x86` vs `x86_64` 的解析用例（含 `variant-probes.json` 里的真实文件名）。
3. `VariantStateMachineTest`：成功→TRUSTED；失败 1 次→SUSPECT；连续 3 次→NOT_APPLICABLE；`TRUSTED` 路径不调用探测回调（传计数的假 probe）。
4. `ApkGate` 逻辑抽成纯函数（输入 `ArchiveInfo` 数据类）以便 JVM 单测；Android 部分只做薄封装。

## 11. 验收

- [ ] `grep -rn "bestForAbis" app/src/main` 只剩 `@Deprecated` 定义或 0 处。
- [ ] 手动：HyperCeiler（多 APK，P0 无画像）→ 弹选择器且不预选 OS2；单 APK 模块 → 一键装、无额外弹窗。
- [ ] 手动：debug-only 模块（如 `com.souleven.nothingos`）→ 直接装，无确认弹窗。
- [ ] 手动：把某模块最新 APK 换成配套 App（可用测试包）→ 门禁拒绝并给出原因。
- [ ] `./gradlew :app:testDebugUnitTest --offline` 绿；新增测试类全绿。
- [x] ~~备份导出/导入~~ 功能已移除（`LibraryBackup` 与相关 UI/字符串一并删除）。

## 12. 注意

- 不要重排/重格式化整文件（仓库现状是大量未提交改动，diff 越小越好）。
- `InstalledModule` 是 `@Serializable` 且被缓存到磁盘 —— 新增字段必须带默认值。
- 译文放在 `values-<lang>/strings.xml`，靠 lint 兜底：漏翻或多出键都会让 `lintDebug` 失败。品牌名等不翻译的字符串标 `translatable="false"`。
- 新字符串不要硬编码中文；理由句用 `%1$s` 占位。
- P1/P2 的接口先留好（`probes` 参数、`DeviceProfile` 的 `vendorOs/lsposedApi`），本次不实现。

## 13. 委托安装的完成信号（实机验证记录）

系统安装器路径没有回调，真机（API 30 模拟器）验证出的结论：

1. `PACKAGE_ADDED` 是隐式广播，App 在后台时清单里的 `PackageEventReceiver` 收不到 —— 而外部安装器把 App 顶到后台正是常态，所以不能只靠广播清 `pending_installs`。
2. 安装成功后 `PackageInstaller` 还会停在 “App installed” 页，App 仍是后台状态，连 `onResume` 都不会触发；因此批量更新**不能**只等 resume 对账。
3. 采用的做法：
   - `AppContainer.awaitPendingInstallSettled(pkg, timeoutMs, pollMs)`：每 `pollMs` 用 `PackageManager.getPackageInfo(pkg, 0)` 查版本（不要 `getInstalledModules(force = true)`，整表重扫要数秒，会错过落地窗口），命中 `pending.versionCode` 后走 `completePendingInstall` 释放全局安装槽；
   - 批量 worker 在 `DelegatedToSystem` 之后等这个结果再出队下一个，超时（`SERIALIZED_INSTALL_WAIT_MS = 180s`）则把该项标 `SKIPPED`、置 `installInterrupted`、其余项标 `SKIPPED`，摘要用 `installed_batch_installer_busy`；
   - `MainActivity.onResume` → `reconcilePendingInstalls()` 仍保留，负责“用户直接杀掉安装器”的残留：重试若干次后把超过 60s 的未决记录清掉，更新项重新出现。
4. 验收表现：连续 “Update all” 两个模块时，第一个 `Install` 点完、无需关掉 “App installed” 页，进度条就推进到下一个；两个都更新完成后 `pending_installs` 为 `{"installs":[]}`，模块版本与列表状态一致。
