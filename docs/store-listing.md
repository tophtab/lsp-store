# 上架商店（F-Droid）

唯一在跟的渠道是 **F-Droid**。IzzyOnDroid 已放弃，原因见文末「已关闭的渠道」。

现状：**MR 已提过，被以"活跃开发不足 3 个月"关闭，暂缓到 2027-01**（见第 1 节）。
metadata 已备好，重开只需你的 GitLab 账号。

- `io.github.tophtab.lspstore.yml` —— F-Droid 的构建元数据，已用 `fdroid lint`（fdroidserver 2.4.5）验证通过。
- 商店页的描述、图标、截图来自本仓库的 `fastlane/metadata/android/`，不要另外维护一份。
- 自建/侧载分发（GitHub Releases + 模块索引）不受影响，继续作为签名一致的主渠道。

## 0. 前置：需要一个包含 fastlane 元数据的 tag

F-Droid **只读最新 release/tag 里的** `fastlane/`（F-Droid 文档原话：
"F-Droid will only use metadata files from the latest release"）。

当前最新 tag 是 `v1.0.2`（2026-10-24 14:33），已带上最新的 `fastlane/`，
这一条现在满足。以后每次发版只要记得 tag 打在 fastlane 有改动之后即可。

## 1. 状态：暂缓（时间门槛）

MR 已提过一次，**被 F-Droid 关闭**：理由是应用"活跃开发不足 3 个月"——同一周才建仓库。
审核原话（duckniii / seeker）：

> Closing this because This app doesn't meet the policy that it needs to be maintained for
> more than 3 months. and this was created last week.

对应 [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) 原文：

> The application should have been actively developed for at least 3 months, unless:
> 1. The application has been proven to have some users.
> 2. The author has been maintaining other apps on F-Droid.
> 3. The application is developed only for an event.

这不是永久拒绝，是"还不到时候"。时间线：仓库 2026-10-24 公开（首个 commit 2026-10-17），
所以**最早 2027-01-24 再 re-open**（按首个 commit 算 2027-01-17），期间不要反复重开 MR。

重开时能拿出的"已有用户"证据（豁免条件 1）：

| 来源 | 数据（2026-10-24） |
|---|---|
| 模块索引 `assets.lsposed.org` 上 1.0.0 APK | 666 次下载 |
| GitHub Release 1.0.0 / 1.0.1 | 20 / 32 次下载 |
| 本仓库 | 5 star、1 fork |

认不认由审核决定，但至少在门槛之外多一条论据。重开前还要：

- 把 `docs/store-listing/io.github.tophtab.lspstore.yml` 的 `versionName` / `versionCode` /
  `commit` / `CurrentVersion` 更新成那时的最新 release（现在仍钉在 1.0.1 / `727c413`）；
- 确认最新 tag 的 `fastlane/` 是最新的（见上一节）。

## 2. F-Droid 主仓库

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

## 3. 已关闭的渠道

### IzzyOnDroid —— 政策性拒绝（不要重投）

Issue：[`IzzyOnDroid/repodata#772`](https://codeberg.org/IzzyOnDroid/repodata/issues/772)，
2026-10-24 08:56 提（CEST），当天关闭，标签 `llm/substantial` + `status/declined`。
唯一一条回复（machiav3lli）：

> Vibe coded, can't be accepted under our current policy.

这句话不是随口说的，对应他们 [App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/)
的 **AI policy** 一节，原文最后有一段 Specifically, this means：

> - **Vibe-coded apps will be rejected.**
> - Readme, Changelogs and similar documentation files are allowed to include LLM-generated
>   texts, but the code itself should be free of it.
> - Using LLMs for research, brainstorming, inspiration, debugging, look-ups, and comparable
>   „read-only“ tasks, is acceptable – provided their output is not included in the app's code.

所以边界很清楚：**文档可以用 AI 写，代码不行**，而且只读用途（查资料 / 调试 / 头脑风暴）
可以，产出落到代码里就不行。他们列的理由是训练数据的版权与隐私、被剥削的标注工人、能耗与
用水、抓取型爬虫搞垮基础设施（点名了 Codeberg 被 AI bot 打挂那次）。

为什么不去申诉：

- 提交时模板里的 “AI Tools Usage / Assistance Level” 我们填的是 *to be confirmed*，
  两条 AI Accountability 勾选框也没勾；维护者于是自己判定为 `llm/substantial`。
- 政策明写：“Any lack of transparency discovered by us, can lead to the project being
  degraded to rejected state.” 再去争“这不算 vibe coding”属于不诚实的申报，只会把状态
  弄得更差；改描述、换账号重投同理。
- 这个 issue 的 tracker 恰好挂在 Codeberg 上，但拦你的是 IzzyOnDroid 自己的政策，不是
  Codeberg 的规则。

结论：**这个渠道不再尝试**。原先准备好的 issue 正文已从本文档删掉，避免以后误投。

技术上也确实没有损失：IzzyOnDroid 的卖点是用上游自己签名的 APK（避开 F-Droid 重签导致的
两个签名问题），而这一点用 GitHub Releases + 可复现构建同样能满足。

## 4. 结论

- 分发渠道现在只有一条：**GitHub Releases**（作者签名，模块索引走这条）。F-Droid 等
  2027-01 时间门槛过了再重投。
- 不再申请其它第三方商店 / 可信任仓库：IzzyOnDroid 这类带 AI 条款的仓库已经明确不收，
  继续投等于重复上面那条 issue 的结局。
