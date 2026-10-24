# LSPosed 模块版本更新规律与已安装模块更新机制评估

数据快照：**2026-10-20 10:00 UTC**（单次快照，仓库仍在变化）
评估对象：本仓库 `:app` 的已安装模块检查/通知/下载/安装链路
外部对照：LSPosed `RepoLoader`（`app/src/main/java/org/lsposed/manager/repo/RepoLoader.java`，master）

---

## 0. 结论摘要

1. **更新节奏是重尾分布，不是均匀发布。** 全量 1190 个模块里，只有 4.0% 在最近 24h 发过版、14.2% 在 7 天内、30.3% 在 30 天内；最新版本年龄中位数 **116 天**，36.9% 超过一年。少数活跃模块贡献了大部分更新。当前 6h 索引 TTL + 12h 后台检查与这个节奏是匹配的，**不需要提高轮询频率**；真正的问题在“通知噪声”和“检查成本”。
2. **版本号不是可靠身份。** 18.1% 的模块最新 tag 的 `versionCode` 是 `1`，抽样中 18.6% 的模块所有 release 都用 `1`；7.1% 的模块历史上存在同码不同名，4.2% 的模块“当前最新码”出现在多个 tag 上。按 `versionCode` 做“跳过此版本”会在这类模块上**永久静默**（`LibraryRepositoryImpl.skipUpdate` 只存 Long）。
3. **Beta/Nightly 语义有明显漏洞。** 33 个 beta 模块中 4 个只有预发布版；在有 stable 的 29 个里，**24 个的 beta 码比 stable 旧**。全局切到 Beta 后，这些模块反而看不到新的 stable（极端的 `com.chrxw.purenga`：stable `60-3.5.0`，beta `37-2.7.0`）。Nightly 已经名存实亡：**0/1190** 个模块有 snapshot tag，列表接口连字段都不返回，307 个详情样本的 `snapshotReleases` 全为空。
4. **仓库端点/镜像现状与代码假设不一致。** `modules.lsposed.org/modules.json` 对本机返回 **403**；备份 `backup.modules.lsposed.org/modules.json` 可用但**滞后 3–7 天**且少 33 个模块；两个旧镜像（blogcdn / cloudflare）已不可用。当前实现却把 `modules.json` 当首选、失败后才走 40 页分页，且镜像回退可能拿到**过期详情**（实测同一模块备份详情落后 7 天、28 个 release），有装回旧版的正确性风险。
5. **最大的性能优化点是“增量索引”。** `module-list` 全量是 40 个请求（1.01 MB raw / 0.31 MB gzip），但它按 `latestReleaseTime` **全局降序**且每页 30 条；快照时第 1 页覆盖约 16.4 小时。用“水位线 + 逐页拉取到水位线”可把 12h 检查从 40 个请求降到 **1–2 个**，再每天做一次全量兜底。
6. **通知链路有两个失真：** 同一批待更新每次检查都会重发（用户拖一周 = 14 次提醒）；当所有更新都需要人工选 APK 变体时，worker 在 `ready.isEmpty()` 提前返回，**完全不通知**（`UpdateCheckWorker.kt:98`），而多 APK 最新版占抽样模块的 3.3%（如 HyperCeiler 的 OS2/OS3）。
7. **`updatedAt` 不等于“发版时间”。** 53.9% 的模块 `updatedAt` 比 `latestReleaseTime` 新 30 天以上（中位差 42.8 天），因为仓库推送/README 修改也会更新它。当前 Repository/Home 的“Recently updated”按 `updatedAt` 排序，会把 2021 年就没发版的模块排到前面。

---

## 0.1 评审决策（2026-10-20）

| 项 | 决策 | 说明 |
| --- | --- | --- |
| P0-1 Beta 取 stable/beta 较新者 | **不做** | Beta 是给主动追预发布用户的独立通道，不是 stable 用户的兜底；保持与上游一致。 |
| P0-2 跳过/忽略入口 | **已实现** | 两个动作放进已安装行右侧 More："跳过此版本" + "永久忽略更新"（可恢复）。skip 必须按 tag 存，否则 code=1 的模块会变成永久忽略。 |
| P0-3 严格校验详情 release | **已实现** | 下载前比对详情 release 与索引 latest；不一致直接失败，不装旧版。 |
| P0-4 歧义/详情失败通知 | **不做** | Installed 页已能看到更新，接受“需要选变体时不发通知”。 |
| P0-5 通知去重 | **不做** | 接受重复提醒。 |
| P0-6 移除 Nightly | **已实现** | Settings 隐藏，旧偏好映射到 Beta，enum 保留兼容。 |
| P1-1 增量索引 | **做** | 水位线 + 每 24h 全量兜底。 |
| P1-2 端点/镜像策略 | **做** | 实时分页为权威源；备份只做首屏；删除死镜像。 |
| P1-3 详情 ETag/force 冷却 | **不做**（用户决定；scan TTL 单独实现） | 收益小。 |
| P1-5 PackageManager 扫描 TTL | **已实现** | 30s 缓存，改善切屏性能。 |
| P1-4 APK 缓存清理 | **不做** | 按用户决定保留现状；APK 继续留在 `cacheDir/apk`。 |
| P1-6 自动更新约束/并发 | **待定** | 独立于本次更新检测改造。 |
| P2-1 每模块预发布开关 | **不做** | 同 P0-1。 |
| P2-2 已安装高于仓库 | **可不做** | 只影响作者回退 versionCode 的 2.5% 模块。 |
| P2-3 按发版时间排序 | **已实现** | “最近更新”改用 `Module.lastPublishedAt`：取 stable/beta/snapshot 中最新的 `latest*Time`；无 release 的模块不显示时间、排在已发版模块之后，不再回退 `updatedAt`（GitHub 的 `updated_at` 会被 star/README 刷新）。 |
| P2-4 诊断指标 / P2-5 pending 感知 | **可不做** | 可观测性/一致性优化。 |
| P2-6 死代码清理 | **待定** | `ModuleVersion.isNewerThan` 仅测试使用；`hasChannel(NIGHTLY)` 仍保留兼容。 |

---

## 1. 研究方法与数据

### 1.1 数据来源

| 数据 | 获取方式 | 规模 |
| --- | --- | --- |
| 实时分页索引 | `GET /module-list/{1..40}.json` | 1190 个模块，40 页，30/页 |
| 备份全量索引 | `GET https://backup.modules.lsposed.org/modules.json` | 1157 个模块，2,087,423 B |
| 模块详情 | `GET /module/{pkg}.json` | 307 个详情（240 随机 + 全部 33 个 beta + Top 60 star） |
| 备份详情 | `GET https://backup.modules.lsposed.org/module/{pkg}.json` | 10 个交叉核对 |
| 上游语义 | LSPosed `RepoLoader.java` | `upgradable` / `updateLatestVersion` |

### 1.2 抽样与偏差

- 240 个随机模块用于频率类指标（同码、版本号回退、多 APK），并给出 95% Wilson 区间。
- “全部 beta + Top star”只用于通道和极端案例分析，**不用于比例推断**。
- 307 个详情共 2573 个 release；详情采样偏向活跃/热门模块，因此“发布间隔”类指标以 240 随机子集为准。
- 未下载 APK 二进制做 `versionName` 交叉校验；tag 的 `versionCode-versionName` 视为作者声明值。
- 单次快照不能证明长期趋势，但足以定位机制缺陷。

### 1.3 复现要点

```bash
# 全量索引
for p in $(seq 1 40); do curl -sS "https://modules.lsposed.org/module-list/$p.json" -o "pages/$p.json"; done
# 详情
curl -sS "https://modules.lsposed.org/module/<pkg>.json"
# 备份索引 / 详情
curl -sS "https://backup.modules.lsposed.org/modules.json"
```

---

## 2. 版本更新规律

### 2.1 活跃度与发布节奏（全量 1190）

| 最新版本发布时间 | 模块数 | 占比 |
| --- | ---: | ---: |
| ≤ 1 天 | 47 | 4.0% |
| ≤ 7 天 | 169 | 14.2% |
| ≤ 30 天 | 360 | 30.3% |
| ≤ 90 天 | 552 | 46.4% |
| ≤ 365 天 | 751 | 63.1% |
| > 1 年 | 439 | 36.9% |

- 最新版本年龄中位数：**115.9 天**。
- 240 个随机模块中：33.3% 只有 1 个 release，21.7% 有 ≥10 个 release，中位 3 个。
- 同一模块相邻 release 的间隔（1200 个间隔）：中位 **4.61 天**，P25 **0.97 天**，P75 **19.9 天**。即“要么一天发几个，要么几周不动”。
- 按 14.2%/周 的模块触达率估算，装 20 个模块的用户每周约有 **3 次**更新事件（实际会更多，因为活跃模块会连发）。

**对机制的启示：** 12h 检查足以把发现延迟控制在半天量级；把周期降到 1–2h 收益很小。优化重点应是“每次检查更便宜”和“同一条更新只提醒一次”。

### 2.2 版本号与版本名格式（全量 1190）

| `versionCode` 区间（1186 个有 stable tag 的模块） | 模块数 | 占比 |
| --- | ---: | ---: |
| 1–99 | 808 | 68.1% |
| 100–999 | 172 | 14.5% |
| 1,000–9,999 | 58 | 4.9% |
| 10,000–99,999 | 51 | 4.3% |
| 100,000–9,999,999 | 31 | 2.6% |
| ≥ 10,000,000 | 66 | 5.6% |

- 最小 1，最大 **2,026,101,917**（日期式编码），其中 17 个模块的码是 `YYYYMMDDHH` 一类的 ≥1e9 数值。
- `versionCode == 1`：**215 / 1190 = 18.1%**；抽样中 57/307（18.6%）的模块**所有** release 都是 1。
- `versionName` 形态（1186）：`x.y.z` 662（55.8%）、`x.y` 384（32.4%）、其他 99（8.3%，日期/哈希/flavor 后缀）、`v` 前缀 24（2.0%）、纯整数 17（1.4%）。
- 版本名里带空格：0 个；带下划线/日期/`-api102`/`-release` 等后缀很常见。

**对机制的启示：**
- `versionCode` 只能在同一模块内比较，**不能跨模块排序**（当前 `UpdateChecker` 按 `latest.versionCode` 排序，见 3.1）。
- “跳过某版本”不能只存 code（见 P0-2）。
- 解析器 `ModuleVersion.parse` 只取第一个 `-` 前的 Long，这点是稳健的；0 个解析失败样本。

### 2.3 同码、回退与重传（240 随机抽样）

| 现象 | 模块数 | 比例（95% CI） | 例子 |
| --- | ---: | ---: | --- |
| 历史 release 中存在同 `versionCode` 不同 `versionName` | 17 / 240 | **7.1%**（4.5–11.0） | `com.Bilibili_Innocent_Lab.xposedmodule`: `21-1.2.0` 与 `21-1.2.1-alpha.1` |
| **当前最新** code 出现在多个 tag 上 | 10 / 240 | **4.2%**（2.3–7.5） | `com.coolest.toolbox`: 所有版本都是 `1-x` |
| 按时间排序后 `versionCode` 出现回退 | 6 / 240 | **2.5%**（1.2–5.3） | `com.chrxw.purenga` `90505` → `4`；`com.android1500.androidfaker` `23101803` → `10902` |
| 最新 release 有多个 APK | 8 / 240 | **3.3%**（1.7–6.4） | HyperCeiler OS2/OS3、WaEnhancer debug/release |
| stable 最新 tag 带预发布字样 | 1 / 240 | **0.4%**（0.1–2.3） | `io.github.uraniam9.lostxposed` `4-0.2.2-beta` |

补充：
- 在“备份索引（约 10-16）→ 实时列表（10-20）”的约 4 天窗口里，**0 个**模块出现“同 code、不同 name”的切换；说明当前的时间戳护栏（`UpdateChecker.isUpdate`）很少被触发，但它防的是长期命名漂移（如 `4-4.7_AraafRoyall` vs APK `4.8`），仍然必要。
- 同 tag 重传（tag 不变、`latestReleaseTime` 变新）在窗口内有 1 个模块（`com.sumver.tbulu`）。
- 307 个详情、2573 个 release 中：**没有 0 APK 的 release**；291 个 release 带多个 APK；draft 为 0。
- APK 体积：中位 **3.5 MB**，P90 **11.6 MB**，最大 **146 MB**。

### 2.4 发布通道（实时列表 1190）

| 项目 | 数量 |
| --- | ---: |
| 有 `latestBetaRelease` | 33（2.8%） |
| 只有预发布版（没有 `latestRelease`） | 4（0.3%） |
| beta code > stable code | 5 |
| beta code < stable code | **24** |
| beta code = stable code | 0 |
| 有 `latestSnapshotRelease` | **0** |
| 详情里 `snapshotReleases` 非空 | **0 / 307** |

- 307 个详情抽样中只有 **4.1%** 的 release 带 `isPrerelease=true`（106/2573）；33/307 的模块曾发过预发布版，4 个模块**从来只发预发布版**（`io.github.tangdino.sjmladhook`、`io.github.good_joe2049.millennium`、`io.github.li_yifei.pixelwatchbatterydrainfix`、`moe.chenxy.hyperpods`）。
- `latestBetaRelease` 的语义是“最后一个 pre-release”，不是“比 stable 新的版本”。stable 发布后 beta 指针不会前移，所以 **24/29（82.8%）** 的 beta 模块在 Beta 通道下会丢 stable 更新。
- `module-list` 的 DTO **根本不包含** `latestSnapshotRelease` 字段；即使未来恢复 snapshot，分页回退路径也会静默把它当 beta。

### 2.5 变体（多 APK）

- 最新 release 多 APK：3.3%（随机）/ 4.6%（偏置样本）。
- 命名形态：
  - ABI：`arm64-v8a`、`armeabi-v7a`、`universal`、`x86_64`（SmsCode、QAuxv、bbzq）。
  - 构建类型：`debug` / `release`（WaEnhancer、Impad、Lyric Getter）。
  - 平台/flavor：`OS2` / `OS3`（HyperCeiler）、`api102` / `legacy`（飞书回退）、`sign` / 伴生 App（CoolapkReviver）。
- 结论：ABI 启发式能解决一部分，但 **flavor/debug 对无法可靠自动选择**；当前“后台遇到 Choose 就跳过”是对的，问题是跳过后**没有通知**（P0-4）。

### 2.6 仓库端点、镜像与新鲜度

| 端点 | 状态 | 大小 | 新鲜度 | 条件请求 |
| --- | --- | --- | --- | --- |
| `modules.lsposed.org/modules.json` | **403**（TencentEdgeOne） | 0 | — | — |
| `backup.modules.lsposed.org/modules.json` | 200 | 2.09 MB / 446 KB gzip | 约 10-16 生成，最新 release 10-16；比实时列表少 **33** 个模块 | ETag 304 有效 |
| `modules.lsposed.org/module-list/{n}.json` | 200 | 40 页 1.01 MB / 310 KB gzip | 实时（到分钟） | ETag 有，但 `If-None-Match` 仍返回 200 |
| `modules.lsposed.org/module/{pkg}.json` | 200 | 中位 15.2 KB / 3.7 KB gzip | 实时 | ETag 304 有效 |
| `backup.modules.lsposed.org/module/{pkg}.json` | 200 | 随模块 | **可能严重滞后**：`com.juwanh.autoqqpet` 备份 `14-1.1.1`，实时 `42-2.0.5`（差 7 天、28 个 release） | 304 有效 |
| `modules-blogcdn.lsposed.org` | TLS 证书过期（上游同时报告 418） | — | — | — |
| `modules-cloudflare.lsposed.org` | DNS 不可用 | — | — | — |

- 备份索引不是“实时列表的镜像”，而是周期性生成的快照：`latestReleaseTime` 最大值 10-16，实时是 10-20；33 个新模块完全缺失（即使它们的最新 release 时间很旧，也会因为新加入而缺失）。
- 81/1157 个共同模块在约 4 天内换了最新 tag（7.0%），与 14.2%/周 的触达率一致。
- 首页/仓库 “Recently updated” 使用 `updatedAt`：641/1190（53.9%）比 `latestReleaseTime` 新 30 天以上，884（74.3%）新 1 天以上，中位差 42.8 天。`updatedAt` 会被 README/元数据推送刷新，不适合代表“发版”。

---

## 3. 当前更新机制逐段评估

### 3.1 检测链路

已做对的部分：

- 索引缓存优先 + 6h TTL + 失败保留旧索引（`ModuleRepositoryImpl`）。
- 支持 stable/beta/nightly 的 tag 选择，且 `latestFor` 的 fallback 与 LSPosed 一致。
- 同码同名不重复提示、同码异名按“发布时间晚于安装时间”只提示一次（`UpdateChecker.isUpdate`）——这是相对 LSPosed 的实质改进。
- 忽略模块、按版本跳过、包名重定向（links）、变体不适用过滤、pending 安装过滤（UI 侧）。
- 后台 worker 对 `NotApplicable` 做名称层复查，新版本发布后可以复活。

数据暴露的问题：

| 编号 | 问题 | 证据 | 代码 |
| --- | --- | --- | --- |
| D1 | Beta 通道在 beta 比 stable 旧时不回退 stable，导致丢更新 | 24/29 个 beta 模块；`latestFor(BETA)` 只在 beta 为 null 时回退 | `Models.kt:83-87` |
| D2 | Nightly 已无数据，但仍作为可选项；分页索引也没有 snapshot 字段 | 0/1190 snapshot tag；0/307 `snapshotReleases` | `Models.kt`、`SettingsScreen.kt:159-167` |
| D3 | 仅预发布模块在 Stable 通道永远无更新 | 4 个模块 `latestRelease=null` | `UpdateChecker.kt:40` |
| D4 | “跳过此版本”只存 `versionCode`；code=1/同码模块会永久跳过 | 18.1% 模块 code=1；4.2% 最新码重复 | `LibraryRepositoryImpl.kt:126-132`、`UpdateChecker.kt:41-42` |
| D5 | 版本码回退（作者改编号）后永远检测不到更新 | 2.5% 模块；如 purenga `90505→4` | `UpdateChecker.kt:68-69` |
| D6 | 跨模块按 `versionCode` 排序无意义 | code 从 1 到 20 亿，编码体系混杂 | `UpdateChecker.kt:49` |
| D7 | 详情拉取失败被静默丢弃；全为 Choose 时提前返回不通知 | 多 APK 最新版 3.3%；`ready.isEmpty()` 早退 | `UpdateCheckWorker.kt:74-76, 98-105` |
| D8 | pending 安装未纳入 worker 判定，可能对同一更新重复通知 | UI 过滤了 pending，worker 没有 | `UpdateCheckWorker.kt:31-56` |
| D9 | 频道全局化，无法只对 33 个 beta 模块开预发布 | 2.8% 模块有 beta | `SettingsRepositoryImpl.kt` |
| D10 | `updatedAt` 被当作发版时间用于“最近更新” | 53.9% 模块差 >30 天 | `HomeViewModel.kt:150,167` |
| D11 | `ModuleVersion.isNewerThan` 无生产调用，且与 `UpdateChecker` 的时间戳护栏语义不同 | 仅测试使用 | `Models.kt:35-40` |

### 3.2 通知链路

已做对的部分：

- 单条/多条/歧义三种文案，深链到 Installed。
- 通知权限检查、专用渠道、独立小图标。
- 自动更新结果单独通知。

数据暴露的问题：

| 编号 | 问题 | 证据/影响 |
| --- | --- | --- |
| N1 | 每次 worker 都对同一批待更新 `notify(2001)`，无“新更新”去重、无 `setOnlyAlertOnce` | 12h 一次；用户拖 7 天 = 14 次提醒；约 3 次更新/周（20 模块） |
| N2 | 所有更新都需人工选变体时完全不通知 | `ready.isEmpty()` 早退；HyperCeiler OS2/OS3 这类热门模块会中招 |
| N3 | 详情失败时该模块从通知里消失，但 Installed 仍显示更新 | `getDetail(...).getOrNull() ?: return@forEach` |
| N4 | 没有持久化“上次检查时间/结果”，Settings/Diagnostics 无法解释“为什么没提醒” | 无 lastCheck 字段 |
| N5 | 没有按模块的自动更新/稍后提醒策略 | 全局 autoUpdate+root |

### 3.3 下载/安装链路

已做对的部分：

- 资产缓存键包含下载身份，避免同名覆盖；Range 断点续传；镜像回退到 GitHub asset；前台下载服务。
- 变体解析器有评分/探针/记忆签名，后台不静默选歧义项；ABI 启发式；串行安装；pending 状态；root 静默安装。
- `ApkGate` 只拒绝“完全跑不起来”的包（minSdk/ABI/API/非 APK）。

数据暴露的问题：

| 编号 | 问题 | 证据 | 代码 |
| --- | --- | --- | --- |
| E1 | 下载前不校验详情 release 是否等于索引里的 `latest`；镜像详情过期会装旧版 | 备份详情落后 7 天/28 个 release | `InstallModuleUseCase.kt:68-69`、`Models.kt:113-117` |
| E2 | `latestReleaseFor` 找不到目标 tag 时回退到“第一个有 APK 的 release”，可能装到别的版本 | 307 样本中目标 tag 100% 存在，说明可以严格化 | `Models.kt:113-117` |
| E3 | 镜像列表包含已死主机，且详情也走同一轮转；用户选死镜像时每次都先超时 | blogcdn 证书过期、cloudflare 无 DNS | `LsposedRepoApi.kt:29-44`、`MirrorState.kt` |
| E4 | `getIndex(force=true)` 无最小间隔，连点“立即检查”会重复全量爬取 | 40 请求/次 | `ModuleRepositoryImpl.kt:87-99` |
| E5 | `getInstalledModules(force)` 完全忽略 `force`，每次调用都全量扫描 PackageManager | 多个 screen/diagnostics/detail 都调用 | `InstalledModulesRepositoryImpl.kt:58` |
| E6 | APK 缓存从不清理；更新 20 个模块会留下 70–230 MB | 中位 3.5 MB、P90 11.6 MB | `DownloadManager.kt:209-217` |
| E7 | 自动更新只要求联网，不要求非计费网络/电量；串行下载 | 10 个更新 ≈35 MB（中位），P90 可到 116 MB | `UpdateScheduler.kt:20-33` |
| E8 | worker 逐条拉详情、逐条下载；没有复用 batch 的 3 路并发 | 详情中位 15 KB，但延迟叠加 | `UpdateCheckWorker.kt:72-95` |
| E9 | `recheckNotApplicable` 每次都对所有 NotApplicable 模块拉详情，哪怕索引 tag 没变 | 无 tag/time 门控 | `UpdateCheckWorker.kt:217-247` |

---

## 4. 优化建议（按优先级）

### P0：正确性 / 漏更新 / 误安装

#### P0-1 Beta 通道取 stable 与 beta 的“较新者”

**证据：** 24/29 个 beta 模块的 beta 码小于 stable 码；Beta 用户会永远停在旧 beta（purenga 37 vs 60、waenhancer 17102 vs 20300、phdp 10129 vs 210000）。

**方案：**
- 新增一个返回“版本 + 发布时间”的成对类型，避免 `latestFor` 与 `latestTimeFor` 分叉：

```kotlin
data class ChannelTarget(val version: ModuleVersion, val publishedAt: String?)

fun targetFor(channel: UpdateChannel): ChannelTarget? = when (channel) {
    STABLE -> stableTarget
    BETA -> newestByCode(stableTarget, betaTarget)      // 同码时取发布时间较晚者
    NIGHTLY -> newestByCode(stableTarget, betaTarget, snapshotTarget)
}
```

- `newestByCode` 先比 `versionCode`，相等再比发布时间；不要用版本名字符串比较。
- 保留 LSPosed 的“同码异名算更新”规则，但时间戳必须来自同一个 target。
- 迁移/保留 `latestFor` 作为兼容包装，内部调用 `targetFor`。

**收益：** 修复 24/29 个 beta 模块在 Beta 通道下的漏更新；Nightly 退化为 beta 也仍有意义。
**风险：** 与 LSPosed Manager 行为不同（上游 Beta 通道也会丢 stable）。这是有意改进，需在 Settings 文案里写清楚“Beta = 预发布 ∪ 稳定版，取较新者”。
**测试：** 用 purenga/waenhancer 的真实 tag 做 fixture；断言 Beta target 为 stable。

#### P0-2 “跳过”改为按 tag/版本名存储

**证据：** 18.1% 模块 code=1；4.2% 当前最新码出现在多个 tag。现在 `skipUpdate(pkg, 1)` 会让该模块**以后所有版本**都被跳过。

**方案：**
- 存储格式从 `pkg:code` 升级为 `pkg:code:versionName`（或直接存 raw tag），保留对旧 `pkg:code` 的读取：
  - 旧记录：仍按 code 匹配（兼容）。
  - 新记录：`latest.raw == skippedTag` 才跳过。
- 当模块发布了不同 tag 时，旧 skip 自然失效；Settings 的“Ignored updates”列表展示被跳过的 tag，而不是 code。
- `SkippedItem` 的 `skippedVersionCode` 改为 `skippedVersion`（code+name）。

**收益：** 修复 code=1/同码模块的永久静默；语义从“跳过这个数字”变成“跳过这个版本”。
**风险：** DataStore 格式迁移；旧记录仍可能过宽，可加“迁移时若 code=1 则清空 skip”作为一次性清洗。
**测试：** `skip(code=1, name=1.0)` 后发布 `1-1.1` 仍然提示；发布 `1-1.0` 不提示。

#### P0-3 下载前校验“详情 release == 索引 latest”

**证据：** 备份详情实测落后 7 天（`com.juwanh.autoqqpet` 14-1.1.1 vs 42-2.0.5）；镜像回退是正常路径；`latestReleaseFor` 找不到 tag 时会退回第一个 APK。

**方案：**
- `PrepareModuleInstallUseCase.prepare` 和 `UpdateCheckWorker` 在拿到 `release` 后校验：
  - `release.version?.versionCode == info.latest.versionCode`
  - `release.version?.versionName == info.latest.versionName`（允许空格/下划线归一化）
- 不匹配时返回 `PreparedInstall.Failed("仓库详情过期")`，不要下载；UI 可提供“重试/打开详情”。
- `latestReleaseFor` 增加 `requireExactTarget: Boolean`；更新路径使用严格模式，浏览/手动安装保留宽松 fallback。
- 对镜像详情加来源标记；来自备份/镜像的详情只用于浏览，不用于自动更新（或至少必须通过上述校验）。

**收益：** 防止把旧版/错误变体装回去，避免“更新→仍是旧版→下次再提示”的循环。
**风险：** 极少数“索引已更新、详情还没生成”的竞态会暂时失败；重试一次即可。
**测试：** 构造 index `42-2.0.5`、detail `14-1.1.1`，断言 prepare 失败且不调用 download。

#### P0-4 歧义/详情失败也要通知，并修正 “updates=0”

**证据：** `ready.isEmpty()` 在 `notifyUpdates` 之前早退；多 APK 最新版占 3.3%；HyperCeiler 的 OS2/OS3 无法自动选。

**方案：**
- `ready.isEmpty() && (skippedVariants > 0 || detailFailures > 0)` 时调用 `notifyUpdates(emptyList(), ambiguous = ambiguousVariants)`，或发一条“有 N 个更新需要你选择 APK”。
- `resultData` 的 `updates` 应包含 ambiguous 数量（或新增 `ambiguous` 字段），让“立即检查”不显示 0。
- 详情失败计数并写入返回值/诊断；不要静默 `return@forEach`。
- `recheckNotApplicable` 的详情失败也计数。

**收益：** 消除“Installed 里有更新但从不提醒”的黑洞；让 Settings 的检查结果可信。
**测试：** worker 输入一个多 APK 且 resolver 返回 Choose 的模块，断言通知被调用且 updates 计数正确。

#### P0-5 通知去重 + `setOnlyAlertOnce`

**证据：** 同一批待更新每 12h 重发；用户拖 7 天 = 14 次。

**方案：**
- 持久化 `lastNotified: Map<pkg, tag>` 与 `lastNotifiedAt`。
- 仅当“包→tag 集合”发生变化时发新通知；集合不变时静默更新内容（或完全跳过）。
- `NotificationCompat.Builder.setOnlyAlertOnce(true)`。
- 可选：7 天后对仍未处理的更新再提醒一次（避免用户划掉后彻底忘记）。
- 通知文案按 `latestTimeFor` 排序，而不是 `versionCode`。

**收益：** 把“每周 3 次更新”从 14 条提醒降到 1–3 条；不牺牲时效。
**测试：** 纯函数 `shouldNotify(previous, current, now)` 的单测。

#### P0-6 移除/隐藏 Nightly 通道

**证据：** 0/1190 snapshot tag；列表接口无字段；307 详情全空。

**方案：**
- Settings 只展示 Stable / Beta；`UpdateChannel.NIGHTLY` 保留用于读取旧偏好，读到后映射为 BETA。
- 删除 `hasChannel(NIGHTLY)` 之类无效分支，或让 nightly target 明确返回 beta/stable。
- 文档同步：README 的“Nightly”段落改为“仓库当前不提供 snapshot，Nightly 已合并到 Beta”。

**收益：** 去掉一个永远无效的选项，避免用户以为自己在追 nightly。
**风险：** 极小；旧偏好迁移即可。

### P1：成本 / 新鲜度 / 基础设施

#### P1-1 增量索引刷新（收益最大）

**证据：** 全量 40 个请求（1.01 MB / 310 KB gzip）；快照时 `module-list` 按 `latestReleaseTime` **全局降序**，每页 30；第 1 页覆盖约 16.4 小时；47 个模块/24h。实现时仍需加“全局有序”的运行时断言/契约测试，不能假设永远成立。

**方案：**
- 在缓存元数据里记录 `newestLatestReleaseTime`（水位线）。
- `refreshIncremental()`：
  1. 拉第 1 页，按 package 合并进缓存；
  2. 若该页最后一条 `latestReleaseTime` 仍 > 水位线，继续拉下一页；
  3. 最多拉 N 页（如 5 页 ≈ 3 天）；超过则退回全量；
  4. 更新水位线为本次合并后的最大值。
- 每 24h 或 pull-to-refresh 做一次全量，处理：新模块（最新 release 可能很旧，落在深页）、删除/隐藏、元数据变更。
- worker 默认走增量；`force=true` 仍可全量。

**收益：** 12h 检查从 40 请求/310 KB 降到 1–2 请求/~11–22 KB gzip；延迟和耗电明显下降，且仍能看到所有新 release。
**风险：** 新模块/删除需要全量兜底；排序键必须是 `latestReleaseTime`（已确认全局有序）；实现时加单元测试。
**测试：** 用两页 fixture + 水位线，断言只请求到水位线；新模块只靠全量发现。

#### P1-2 端点策略与镜像清理

**证据：** origin `modules.json` 403；backup 可用但滞后 3–7 天且少 33 个模块；blogcdn 证书过期/418；cloudflare 无 DNS；`fullModuleIndex` 的 `maxAttempts=1` 不会尝试备份。

**方案：**
- 把 **分页实时索引** 作为默认/权威源；`modules.json` 仅作为“单请求优化”，失败不影响正确性。
- 若要用全量单文件：`backup.modules.lsposed.org/modules.json` 只用于冷启动首屏/离线兜底，**不用于更新判定**；或始终与分页结果做一次交叉校验。
- 详情只信任 origin；备份详情仅用于浏览（配合 P0-3 严格校验）。
- 镜像列表改为“端点感知”：索引/详情分别配置可用主机；删除 blogcdn/cloudflare；对失败主机做会话级降权/健康检查，避免用户选到死镜像后每次先超时。
- 可选：对下载 URL 做 host allowlist（`assets.lsposed.org` / `github.com`），防止镜像供给任意 APK。

**收益：** 去掉每次进程启动的 403 尝试；避免备份滞后导致的漏更新/旧版；镜像选择真正可用。
**风险：** 端点会继续变化，需要把主机列表做成可诊断/可更新的配置。

#### P1-3 详情缓存与请求收敛

**证据：** 详情中位 15.2 KB / 3.7 KB gzip；ETag 304 有效；worker 每次对 pending 更新重复拉详情；`recheckNotApplicable` 无条件拉详情。

**方案：**
- 在 `ModuleRepositoryImpl` 增加 detail 的内存/磁盘 LRU（如 10 分钟 TTL + ETag），304 时复用。
- worker 内用 3 路并发拉详情（与 batch 下载一致），而不是串行。
- `recheckNotApplicable` 记录“上次评估的 latest tag/time”，只有变化时才拉详情。
- `getIndex(force=true)` 增加最小强制刷新间隔（如 10 分钟），避免连点“立即检查”重复全量。

**收益：** 减少重复流量和检查尾延迟；对“用户一直不装”的待更新场景收益最大。
**风险：** 缓存过期策略要短，避免 UI 看到旧 release notes。

#### P1-4 APK 缓存清理

**证据：** 中位 3.5 MB、P90 11.6 MB、最大 146 MB；`cacheDir/apk` 只增不减；更新 20 个模块可留 70–230 MB。

**方案：**
- 安装确认完成后删除对应文件（`InstallOutcome.Completed` / pending 结算后）。
- 保留“最近 N 个”或按 7 天 LRU 清理；保留正在下载/暂停的任务。
- `clearAll` 保留；诊断里展示缓存大小已有基础。

**收益：** 直接回收几十到几百 MB；避免用户以为“更新失败”其实是缓存占满。
**风险：** 删除后重装需要重新下载（可接受）；不要在下载完成前删。

#### P1-5 PackageManager 扫描缓存

**证据：** `getInstalledModules(force)` 完全忽略 `force`；多个 screen/diagnostics/detail 各自触发全量扫描。

**方案：**
- 增加 `scannedAt`，`force=false` 且 30s 内直接返回缓存；`force=true` 才重新扫描。
- 在 `PACKAGE_ADDED/REPLACED/REMOVED` 广播后失效缓存。
- worker 和 UI 共享同一进程缓存。

**收益：** 降低大设备（300+ 包）的启动/切屏卡顿；减少重复扫描。
**风险：** 广播延迟导致短时间旧数据；30s TTL 足够。

#### P1-6 后台自动更新的约束与并发

**证据：** 自动更新只要求联网；10 个更新约 35 MB（中位），P90 可到 116 MB；worker 串行下载。

**方案：**
- `Constraints` 增加 `setRequiresBatteryNotLow(true)`；自动下载增加“仅非计费网络”开关（默认开）。
- 复用 `BatchInstallUseCase` 的 3 路并发下载 + 串行安装，而不是 worker 里逐条下载安装。
- 对静默 root 安装保留“包名必须匹配”更严格的 gate（交互安装可保持 Warn）。
- 失败模块按指数退避，避免每 12h 重试同一个坏包。

**收益：** 避免蜂窝流量意外、缩短批量更新时间、减少无意义重试。
**风险：** 约束会导致更新延迟；提供“立即检查”手动路径即可。

### P2：语义与体验

- **P2-1 每模块通道/预发布开关。** 只有 2.8% 模块发 beta；全局 Beta 影响面过大。可在 Installed 行/详情提供“跟踪预发布”，默认关闭；对 4 个仅预发布模块自动提示。
- **P2-2 “已安装版本高于仓库”状态。** 2.5% 模块版本码回退；当前显示“已是最新”，实际是仓库编号倒退。检测 `installed.versionCode > max(stable,beta)` 且名称不同，显示“仓库版本号低于已安装”，提供手动选择 release 的入口，不要自动降级。
- **P2-3 按发版时间排序。** `UpdateChecker` 的 `sortedByDescending { versionCode }` 改为按 `latestTimeFor`；Repository/Home 的 “Recently updated” 从 `updatedAt` 改为 `latestReleaseTime ?: updatedAt`（53.9% 模块差 >30 天）。
- **P2-4 诊断与可观测性。** 持久化 `lastCheckAt`、`lastCheckResult`（checked/updates/ambiguous/failures）、索引水位线/页数；Settings 显示“上次检查 X 前”；Diagnostics 导出这些字段。
- **P2-5 pending 感知。** worker 读取 `PendingInstallRepository`，对已 pending 的包不重复通知/下载；或在通知里显示“等待安装”。
- **P2-6 清理死代码/统一语义。** `ModuleVersion.isNewerThan` 无生产调用且与时间戳护栏不同；要么删除，要么让 `UpdateChecker` 使用它并显式传入发布时间。`hasChannel(NIGHTLY)` 同理。

---

## 5. 建议路线图

### 第一批（1–2 天，低风险，直接见效）

1. P0-1 Beta 取较新者 + 成对 target（含真实 tag 单测）。
2. P0-2 skip 改 tag（带旧格式兼容）。
3. P0-4 歧义/详情失败通知 + updates 计数修正。
4. P0-5 通知去重 + `setOnlyAlertOnce`。
5. P0-6 移除 Nightly 选项（旧偏好映射 Beta）。
6. P0-3 严格 release 校验（先加日志/失败路径，再启用）。
7. P1-4 APK 安装后清理。

### 第二批（约 1 周，结构性收益）

1. P1-1 增量索引（水位线 + 每 24h 全量兜底）。
2. P1-2 端点/镜像重构（实时分页为权威源；备份仅首屏；删死镜像）。
3. P1-3 详情 ETag/LRU + 并发 + force 冷却。
4. P1-5 PackageManager 扫描 TTL。
5. P1-6 自动更新约束 + 复用 batch。

### 第三批（体验/长期）

1. P2-1 每模块预发布开关。
2. P2-2 “仓库落后于已安装”状态。
3. P2-3 发版时间排序。
4. P2-4 诊断指标。
5. P2-5 pending 感知。
6. P2-6 清理死代码。

---

## 6. 验证与指标

### 6.1 必须补的回归测试

- Beta 通道：stable `60-3.5.0`、beta `37-2.7.0`、已安装 `40-3.0` → 应提示 stable（P0-1）。
- Skip：code=1 的模块跳 `1-1.0` 后发布 `1-1.1` → 仍提示（P0-2）。
- 详情/索引不一致：index `42-2.0.5`、detail `14-1.1.1` → prepare 失败且不下载（P0-3）。
- 多 APK 且 resolver 返回 Choose → worker 仍发通知，`updates` 计数包含 ambiguous（P0-4）。
- 通知去重：同一 set 第二次检查不发新通知；tag 变化后发（P0-5）。
- 增量刷新：水位线命中只请求 1 页；水位线之后有新 release 时请求下一页；新模块只能靠全量发现（P1-1）。
- 端点：origin 403 → 回退分页；备份索引不参与“最新版本”判定（P1-2）。
- `latestReleaseFor` 严格模式找不到 tag → null，不返回“第一个 APK”（P0-3）。

### 6.2 线上指标（建议写进 Diagnostics 导出）

- 每次检查：耗时、请求数、字节数、页数/是否增量、详情失败数、ambiguous 数、通知是否被去重。
- 更新结果：发现数、安装成功/失败/跳过、失败原因分布、pending 数。
- 索引：`newestLatestReleaseTime`、缓存年龄、全量/增量、端点/镜像。
- 用户侧：最后一次通知时间、用户点击率（如果可统计）、跳过/忽略数量。

### 6.3 外部契约测试

- 用 `LIVE_REPO_TEST=1` 增加断言：
  - 所有 `latestRelease` tag 可解析；
  - 若模块有 beta 且 beta < stable，`targetFor(BETA)` 不得低于 stable；
  - 4 个 beta-only 模块不会让 `findUpdates` 崩溃；
  - `module-list` 第 1 页与第 2 页的 `latestReleaseTime` 边界有序（增量刷新的前提）；
  - 详情 `latestRelease` tag 必须出现在 `releases` 中（严格模式前提）。

---

## 7. 局限

- 单次快照；仓库端点/镜像随时可能变化，P1-2 需要可配置而不是硬编码。
- 未解析 APK 内的真实 `versionName`，因此“tag 名 vs APK 名”的漂移比例无法直接测量；但同码异名的结构性比例（7.1%）已足以说明时间戳护栏的必要性。
- 无法获得设备侧安装分布，因此“20 个已安装模块”的更新量是量级估算，不是用户实测。
- Beta 取较新者会与 LSPosed Manager 的行为分叉；这是产品选择，需要在设置文案中明确。

---

## 附：一句话版本

**当前机制在“检测规则”和“安装执行”上已经相当扎实；真正值得改的是：Beta 回退、skip 语义、严格版本校验、通知去重/歧义通知，以及把 40 页全量刷新换成基于 `latestReleaseTime` 水位的增量刷新。**
