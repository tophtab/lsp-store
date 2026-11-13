# 前端代码清理与职责拆分

本次重构以合入 #103 后的 `master`（`48bd869`）为基线，保留 `1.7.0` / `10700`。页面入口、导航回调、ViewModel 公开方法和 UI State 声明保持兼容；资源、安装规则、仓库 DTO、资料库备份和设置的持久化格式保持原样。

## 实现边界

三个提交依次完成残留清理、共享逻辑提取、页面与 ViewModel 职责拆分。

- 删除确认无引用的 `VersionChip`、`ExtensionAvatar`、`ModuleCardCompact`、`KomiChipSpacer`、`KomiInputChip`、`KomiChipRow`、`KomiIconButton`、`KomiSettingsValuePill`、`KomiSettingsNote`，以及无调用的 `versionFor()`、`signatureOf()` 和无用导入。
- `CatalogLoader` 统一缓存首屏、并行扫描与索引刷新、加载指示和失败回退。首页沿用普通扫描，已安装页保留强制扫描参数与 pending 清理；两页仍各自持有 `loadJob`、数据集和 `refreshTick`。
- 菜单行、卸载确认弹窗、资料库模块标题和容量格式化由内部组件共享。菜单间距、勾选状态、错误颜色以及未知容量的 `?`、`0 MB`、`0 KB` 由原调用方保留。卸载结果处理仍调用各页面自己的刷新方法。
- 已安装页拆为列表、行、进度按钮、菜单和变体弹窗；设置页拆为设置分组与弹窗；详情页分离菜单和正文；README 分离解析与渲染。
- `InstalledUpdateController`、`InstalledVariantController`、`DetailTranslationController`、`DetailProbeController` 使用原 ViewModel scope，通过状态读取与原子更新回调工作。每个 ViewModel 保留唯一公开 StateFlow，实际安装继续交给应用级协调器。

批量更新保留运行代次和延迟提示保护，单次更新保留同步占用检查与失败后的 claim 清理。翻译请求使用不复用的身份，取消后的旧任务不能清除新任务的加载指示；探测和变体弹窗也保留请求身份与取消保护。

搜索词、分段选择、滚动状态、设置弹窗输入和 `rememberSaveable` 仍由原来的 screen / content 函数持有。新控制器和跨文件组件使用内部可见性，移出的公开 UI State 字段与派生属性未改动。

## 代码统计

下表为入口文件的物理行数；拆出的协作者计入后面的总量统计。

| 文件 | 基线 | 重构后 |
| --- | ---: | ---: |
| InstalledScreen.kt | 1,166 | 336 |
| InstalledViewModel.kt | 1,080 | 488 |
| SettingsScreen.kt | 674 | 229 |
| ModuleDetailScreen.kt | 772 | 218 |
| ModuleDetailViewModel.kt | 785 | 464 |
| HomeViewModel.kt | 246 | 206 |
| DetailReadme.kt | 655 | 360 |
| LibraryScreens.kt | 540 | 504 |

README 解析另放在 300 行的 `ReadmeParser.kt` 中。

| 范围 / 指标 | 基线 | 重构后 |
| --- | ---: | ---: |
| main Kotlin 文件 | 142 | 168 |
| main Kotlin 物理行 | 24,204 | 24,394 |
| main Kotlin 非空、非注释行 | 19,923 | 20,196 |
| UI Kotlin 物理行 | 13,741 | 13,926 |
| UI Kotlin 非空、非注释行 | 12,092 | 12,362 |
| 归一化后重复的 12 行窗口 | 17 | 7 |
| 其中跨文件重复的窗口 | 4 | 0 |
| 无引用导入候选 | 69 | 0 |

总量统计覆盖 `app/src/main/java` 下全部 `.kt` 文件，包含拆出的文件，不包含测试；非空、非注释行仍包含包声明、导入和括号。注释剥离保留字符串，包括字符串中的 `/*` 和 `//`。

重复统计在去除包声明、导入、空行、注释和仅有标点的行后归一化空白，再按连续 12 行滑动比较；窗口可以重叠，并非独立重复段的数量。剩余 7 个窗口来自首页同一文件内普通列表和搜索列表的既有分支。无引用导入检查将 `by` 委托所需的 `getValue` / `setValue` 视为使用，并人工复核同名函数等误判。

本次消除了 10 个重复窗口和历史残留，但 main Kotlin 总量增加了 190 个物理行、273 个非空非注释行。增量来自职责边界、构造依赖、状态回调和公开方法转发；主要页面入口与 ViewModel 明显缩短。

## 验证

在 JDK 21、Android SDK 37.1 环境运行：

```sh
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug \
  --console=plain --stacktrace
```

完整构建成功。单元测试共 455 项，0 失败、0 错误，4 项可选联网测试按配置跳过。完整 lint 为 0 错误、22 个现存配置、依赖更新和 KTX 建议警告；警告位置均在未改动的构建配置或 `LspStoreApp.kt`。

新增 35 项行为测试，并复用原有安装、pending、变体和 README 测试：

| 测试类 | 数量 | 核验内容 |
| --- | ---: | --- |
| CatalogLoaderTest | 10 | 缓存 / 空缓存 / 无缓存、刷新替换、网络与扫描失败、取消、扫描参数差异 |
| InstalledUpdateControllerTest | 8 | 重复点击、批量取消后重启、延迟清理、pending、自身模块最后更新 |
| InstalledVariantControllerTest | 3 | 弹窗取消与替换、偏好保存与更新衔接 |
| DetailTranslationControllerTest | 10 | 并发请求、取消后重启、手动 / 自动翻译、原文切换、全部失败 |
| DetailProbeControllerTest | 3 | 探测取消与请求身份、单 APK 路径 |
| UninstallResultTest | 1 | 完成、系统接管与失败的刷新 / 提示分支 |

独立 Android 35 x86_64 模拟器上使用本地 debug APK 和测试模块核验页面。设置首屏、已安装操作菜单与 `9779ce0` 对照 APK 的文字、操作描述和可访问性节点位置一致。

已验证仓库页和已安装页搜索与清除、分段选择及详情返回后的滚动位置；设置提供者展开 / 收起及状态恢复、隐藏图标确认取消、自定义间隔的错误提示和保存；通过系统文件选择器导出 schemaVersion 1 的 JSON，并重新导入、取消和合并。

详情页已验证版本切换后更新对应 APK、QAuxiliary 的四种 APK 展示及 armAll / universal 切换、Google 翻译成功及原文恢复、README 原文 / 译文的标题、链接、徽章、图片与功能列表。已安装页变体选择能保存显式偏好，恢复自动选择能清除该偏好并保留状态格式。

同步到 `48bd869`（#103）后重新运行完整 lint、单元测试和 debug 构建。逐字比较确认 `ModuleCard` 的手势、状态和渲染实现与最新 `master` 一致，只删除未使用的组件和导入，完整保留 0.4 秒宽限、0.1 秒缩小变淡及 0.5 秒松手忽略的节奏。

新 APK 在独立模拟器上通过短按、提前松手、正常忽略、移出后返回取消、原始卡片矩形边缘释放、同卡片 / 跨卡片多指及系统取消回归。跨卡片核验覆盖捕获前、动画期间和就绪后，以及两种松手顺序；第二根手指落在页头或导航区、主手指先抬起后的拖动也不能误触发忽略或滚动。取消后立即开始的新按压保留完整宽限时间，就绪时的缩小变淡和取消后的恢复通过截图核验。

仓库搜索使用更新时间与星数排序相反的测试索引，确认详情返回后搜索词、最高星数排序和全部可见卡片的位置一致。关闭系统动画后，425 毫秒松手仍取消，700 毫秒松手能正常忽略。模拟器 crash buffer 中没有 `FATAL EXCEPTION`；临时索引、资料库和设置数据在回归后恢复。

两个卸载入口使用模拟器上的测试偏好核验确认弹窗，取消后包仍存在。本次模拟器未执行真实 root 安装 / 卸载；安装协调、规则和结果分支由现有 JVM 测试覆盖。
