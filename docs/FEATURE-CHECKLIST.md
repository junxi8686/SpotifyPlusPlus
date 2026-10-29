# Spotify++ 功能清单（每次更新后逐条核对）

> **这份文件的用途**：不是"介绍功能"，是**核对清单**。每次合并上游、每次改代码之后，从第二节第一行读到最后一行，逐项确认它还在、还能点、还能用。
>
> **维护规则（必须遵守）**
> 1. 新增功能 → 在对应分组里加一行，`状态` 写 ✅，并在第四节变更日志记一行。
> 2. 移除功能 → 不删行，`状态` 改成 ❌，写清原因和日期；这样下次不会有人以为是自己搞丢的。
> 3. 每次合并上游后 → 逐行核对。**任何一行从 ✅ 变成不可用，都算合并丢了功能，必须补回来。**
> 4. 判断"还在不在"的硬标准：**用户能不能点到**。类还在但没有任何调用点 = 丢了（2026-10-29 的导出/导入就是这样丢的）。

状态图例：✅ 当前可用 ｜ ⚠️ 部分可用 ｜ ❌ 已丢失/已移除 ｜ 🚧 恢复中

---

## 一、核对方法

1. **代码层**：本文件里每行的"代码位置"必须能在 `app/src/main/java/com/spotifyplusplus/` 下找到，且**至少有一个调用点**。查法：`grep -r "类名" app/src/main/java`，只有定义、没有调用 = ❌。
2. **字符串层**：每个功能的界面文案键，必须在 `values/`、`values-zh-rCN/`、`app/translation/strings-template.xml` 三处都存在。运行时能取到但没人引用的键 = 孤儿，说明功能没接上。
3. **界面层**（真机）：按"入口"列描述的位置点进去，能看见、能操作。
4. **构建层**：`:app:assembleDebug` 与 `:app:testDebugUnitTest` 必须 `GRADLE_EXIT=0`、failures=0、errors=0。
5. **死开关检查**：设置项光"被读取"不算接通。必须追到它的值真的进入某个请求或行为分支；如果读取之后被硬编码的常量挡掉（例如 `if (flag && …)` 而 `flag` 恒为 `false`），那就是死开关，记 ❌ 或 ⚠️。2026-10-29 的「Spicy 手动令牌」就是这样：能存能备份能显示，但 `strictSpicy` 硬编码 `false`，填了不生效。

---

## 二、功能清单

### A. 歌词来源与获取

| # | 功能 | 入口 / 怎么验 | 代码位置 | 状态 |
|---|---|---|---|---|
| A1 | 七项打分选歌词来源 | 播放任意歌曲，歌词下方来源行显示选中的来源；换一首不同来源的歌看是否合理 | `lyrics/catalog/CatalogRanker`、`CatalogRankingAlgorithm` | ✅ |
| A2 | 一轮竞速所有到期来源 | 日志出现 `racePlan`；首句歌词出现速度应快于顺序探测 | `lyrics/catalog/AcquisitionPlanner`、`LyricsSessionManager` | ✅ |
| A3 | 来源面板：逐行检查与状态 | 歌词页齿轮 →「选择歌词来源」；每行应显示 检查中/已找到/未找到/失败，可单点重试 | `hooks/LyricsSourcePickerDialog`、`lyrics/catalog/CatalogPickerModel` | ✅ |
| A4 | 来源面板：20 秒看门狗解锁 | 面板里某行卡在"检查中"超过 20 秒，应自动解锁并提示可重试 | `LyricsSourcePickerDialog.RUN_TIMEOUT_MS` | ✅ |
| A5 | 按名称手动搜索歌词 | 来源面板 →「按名称搜索…」→ 选来源 → 输入 → 结果逐条点选 | `lyrics/providers/ManualLyricsSearch`、`ManualLyricsQuery`、`SmartSearch` | ✅ |
| A6 | 手动令牌歌词来源 | 设置 →「Spicy 手动令牌」可填/查看/删除 | `lyrics/providers/SpicyManualTokenStore`、`settings/panel/PanelDialogs` | ⚠️ **死开关**：能存、能备份、能显示，但唯一消费者是 `LyricsFetchCoordinator` 里 `if (strictSpicy && …)`，而 `strictSpicy` 硬编码 `false`，填了不生效。待决定删除或启用 |
| A7 | SpicyLyrics.org 来源 + 客户端密钥 | 设置 →「SpicyLyrics.org 客户端密钥」填入；来源面板该行可用 | `LyricsFetchCoordinator`、`SpotifyTokenStore` | ⚠️ 少了「显示」按钮，见 E9 |
| A8 | QQ 音乐按槽位节流 | 日志 `qq request id=` 之间有明显间隔，不并发刷 | `lyrics/providers/LyricsRepository` | ✅ |
| A9 | 来源请求守卫 | 同一来源重复请求被合并 | `lyrics/providers/SourceRequestGuard` | ✅ |
| A10 | Apple Music **不能**作为来源 | 来源面板与设置里都不应出现 Apple Music 选项 | — | ✅ 硬底线 |

### B. 歌词显示与动效（上游基线，防回退）

| # | 功能 | 入口 / 怎么验 | 代码位置 | 状态 |
|---|---|---|---|---|
| B1 | 全屏歌词 + 卡拉OK逐字填充 | 播放页 → 歌词；逐字高亮跟随 | `hooks/NativeSpicyShellViewImpl`、`lyrics/LyricVisuals` | ✅ |
| B2 | 纸艺着色器背景（29 种） | 设置 → 背景效果，逐个切 | `ambient/PaperShaderCatalog`、`resources/paper-shaders/*.agsl` | ✅ |
| B3 | 网格渐变背景 | 设置 → 背景效果选网格渐变 | `ambient/MeshGradientShader` 等 | ✅ |
| B4 | 实时歌词卡（播放页） | 播放页中部一行当前歌词 | `hooks/NowPlayingInjector`、`lyrics/LiveLyricCardView` | ✅ |
| B5 | 悬浮窗 / 画中画歌词 | 设置 → 画中画分组开启后返回桌面 | `hooks/LyricsPipController` | ✅ |
| B6 | 布局编辑器（实时调整） | 歌词页 → 布局编辑入口 | `hooks/LyricsLayoutEditController` | ✅ |
| B7 | 隐藏 Spotify 原生歌词卡 | — | `hooks/SpotifyLyricsCardHider` | ❌ 死代码，未启用、也无开关（原有实现会写坏 Spotify 歌词表，已停用） |
| B8 | 「忽略 Spotify 自带歌词」开关 | — | `LyricsSourcePreferences.ignoresSpotifyLyrics`（恒 false） | ❌ 已移除，仅剩孤儿字符串 |

### C. 中文与注音

| # | 功能 | 入口 / 怎么验 | 代码位置 | 状态 |
|---|---|---|---|---|
| C1 | 简繁转换（显示层） | 设置 → 歌词文字；切换后中文按简体/原文显示 | `lyrics/providers/ChineseScriptVariants`、`resources/chinese-script/*` | ✅ |
| C2 | 日语注音（含 eza 式拼音/粤拼） | 设置 → 注音格式，切到 eza 式看变化 | `lyrics/language/EzaChineseRomanizer`、`SpicyJapaneseChineseProcessor` | ✅ |
| C3 | 歌词语言偏好（原文 / 中文翻译） | 设置 →「歌词语言偏好」 | `Settings.LYRICS_LANGUAGE_PREFERENCE` | ✅ |
| C4 | 歌词文档语言判定 | 中英混排歌曲，选源与注音不应错 | `lyrics/language/DocumentLanguage` | ✅ |

### D. 翻译与 AI

| # | 功能 | 入口 / 怎么验 | 代码位置 | 状态 |
|---|---|---|---|---|
| D1 | 在线免账号翻译（默认） | 设置 → 翻译后端选默认 | `lyrics/processing/*` | ✅ |
| D2 | 百度翻译后端（自备 AppID/密钥） | 设置 → 翻译后端选百度 → 填入 AppID 与密钥 | `lyrics/processing/BaiduTranslateBackend`、`BaiduTranslateCredentials` | ✅ |
| D3 | 只显示来源自带翻译 | 设置 → 翻译后端选第三项 | `settings/values/TranslationBackend` | ✅ |
| D4 | AI 翻译 + 候选裁决 | 设置 → AI 分组填入密钥后翻译一首冷门歌 | `lyrics/ai/AiLyricCandidateArbiter` 等 | ⚠️ 缺上游「付费结果回放/快照」，可能重复请求 |
| D5 | 翻译失败提示 | 断网后触发翻译，应有失败提示而非一直"翻译中" | `lyrics/processing/TranslationFailureNotice`、`ui/LyricsErrorText` | ✅ |

### E. 设置面板

| # | 功能 | 入口 | 代码位置 | 状态 |
|---|---|---|---|---|
| E1 | 设置面板可打开/关闭/缩放 | 歌词页齿轮 | `settings/panel/SettingsPanel`、`ui/PanelSurface` | ✅ |
| E2 | 「行为」分组 | 设置面板 | `settings/registry/ControlRegistry` | ✅ |
| E3 | 「歌词来源」分组 + 来源排序 | 设置面板 | 同上 | ✅ |
| E4 | 「当前歌曲歌词」信息行 | 设置面板 | 同上 | ✅ |
| E5 | 动画 / 背景 / 字体 / 布局等分组 | 设置面板逐段 | 同上 | ✅ |
| E6 | 翻译 / AI / 注音 / 画中画 / 车机 / 调试分组 | 设置面板逐段 | 同上 | ✅ |
| E7 | 全量中文本地化 | 系统语言中文时全部文案为中文 | `values-zh-rCN/strings.xml`（810 键） | ✅ |
| E8 | **导出设置与数据** | 设置 → 备份分组 →「导出设置与数据」 | `settings/BackupPicker.startExport`、`settings/SpicyBackup` | ✅ |
| E9 | **导入设置与数据** | 设置 → 备份分组 →「导入设置与数据」 | `BackupPicker.startImport` / `startDownloadsRestore` | ✅ |
| E10 | **自定义字体路径编辑** | 布局编辑器 → 字体 → 「编辑字体路径」（未设时显示「未设置」，文件不存在时报告缺失） | `settings/FontPathState`、`settings/editor/EditorComposites` | ✅ |
| E11 | **SpicyLyrics.org 客户端密钥「显示」** | 设置 → 客户端密钥 → 显示 | `settings/panel/PanelDialogs.revealSpicyKey` | ✅ |

> E8–E11 是 2026-10-29 核对 1.5 面板时发现的、被合并弄丢的入口，**当天已全部恢复**。根因：合并把自建面板换成上游注册表驱动，动作行没被重新登记，底层类还在但没有任何调用点。恢复时补了三条测试钉住这个类别：`PanelControlsTest.everyRegisteredPanelCompositeHasARenderer`（任何注册了却没渲染器的 composite 都会红）、备份行登记断言、字体路径入口断言。

### F. 凭据与备份逻辑

| # | 功能 | 入口 / 怎么验 | 代码位置 | 状态 |
|---|---|---|---|---|
| F1 | 凭据加密存储（Spicy 令牌 / SpicyLyrics.org 密钥 / 百度 AppID+密钥） | 填入后杀进程重开仍在 | `BaiduTranslateCredentials`、`SpicyManualTokenStore`、`SpotifyTokenStore` | ✅ |
| F2 | 恢复备份时保护本机可用密钥 | 用旧备份覆盖导入后，本机原密钥不应变空 | `SpicyBackup.planCredentialRestore` | ✅ |
| F3 | 解不开的密钥逐项提示重填 | 跨设备导入后弹窗列出需重填的项 | `SpicyBackup.RestoreOutcome` | ✅ |
| F4 | 备份/恢复覆盖全部设置与数据 | 导出→改设置→导入→设置回到导出时 | `SpicyBackup` | ✅ |

### G. 配套应用与车机

| # | 功能 | 入口 / 怎么验 | 代码位置 | 状态 |
|---|---|---|---|---|
| G1 | 配套应用存在性探测 | 未装 HyperGlow 时日志无 bind 失败风暴；装了则正常推送 | `hooks/SpicyBridgeBackoff`、`SpicyLyricBridgePublisher` | ✅ 仅单测覆盖，真机需装 HyperGlow |
| G2 | 逐级退避 1s→60s | 日志退避间隔递增 | `hooks/SpicyBridgeBackoff` | ✅ |
| G3 | Android Auto 歌词显示 | 连车机 | `auto/**` | ⚠️ 未在车机验证过 |

### H. 诊断与文档

| # | 功能 | 入口 / 怎么验 | 代码位置 | 状态 |
|---|---|---|---|---|
| H1 | 诊断报告与上传 | 设置 → 调试 → 诊断 | `diagnostics/**` | ✅ |
| H2 | 诊断数据政策页可访问 | 诊断弹窗里的政策链接应能打开 | `BuildStamp.POLICY_URL` → `docs/DIAGNOSTIC_DATA_POLICY.md` | ✅ |
| H3 | 五语言项目文档 | `docs/readme.md` 与四个翻译版 | `docs/README.*.md` | ✅ |

### I. 构建产物

| # | 功能 | 怎么验 | 状态 |
|---|---|---|---|
| I1 | versionName/versionCode 与 `BuildStamp.VERSION` 一致 | `aapt2 dump badging` 对比 | ✅ |
| I2 | 包名 `com.spotifyplusplus`、应用名 `Spotify++` | 同上 | ✅ |
| I3 | APK 落在 `artifacts/SpotifyPlusPlus-<版本>.apk` | 构建后目录 | ✅ |
| I4 | 全量测试全绿 | `:app:testDebugUnitTest` | ✅ 350 类 / 2520 用例 / 0 失败 / 0 错误 / 1 跳过 |

### J. 硬底线（任何情况不得违反）

| # | 底线 | 状态 |
|---|---|---|
| J1 | Apple Music 不得作为歌词来源（同名动效风格可保留） | ✅ |
| J2 | `docs/` 与 `NOTICE` 不得出现上游仓库 URL | ✅ |
| J3 | 测试不全绿不发布 | ✅ |
| J4 | 不为变绿删除/跳过/@Ignore 测试 | ✅ |
| J5 | 主版本号永不加到 2 | ✅ |

---

## 三、每次更新后的核对流程

1. 打开本文件，从 A1 读到 J5。
2. 每一行先在代码里 `grep` 类名，确认**有调用点**（不只是有定义）。
3. 再在真机上按"入口"点一遍，能看见、能操作。
4. 任何一行不通过 → 记下编号，先补回来，再谈发布。
5. 补回来的行，`状态` 改回 ✅，并在第四节记一行。
6. 新增功能 → 加行 + 记日志。

---

## 四、变更日志

| 日期 | 版本 | 内容 |
|---|---|---|
| 2026-10-29 | 1.6.0 | 合并上游 v2.1.0 + v2.1.4（378 文件）；恢复三组被合并丢失的入口（导出/导入、字体路径编辑、客户端密钥显示，并补 3 条测试钉住这类丢失）；来源面板两处显示修复（探测不再依赖会话「当前曲目」、已找到不再显示成没查过）；本文件首次建立，登记 60 项功能与 5 条硬底线 |
