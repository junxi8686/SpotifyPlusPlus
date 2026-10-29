# 仓库维护须知（给后续接手的人 / 任何自动化 agent）

## 一、每次更新前先读功能清单

**`docs/FEATURE-CHECKLIST.md`** 是本仓库的功能核对清单。每次合并上游、每次改代码之后：

1. 打开它，从 A1 读到 J5，**逐条确认功能还在、还能点、还能用**。
2. 判断标准是「用户能不能点到」，不是「类还在不在」。类还在但没有任何调用点 = 丢了。
3. 任何一行从 ✅ 掉下来，先补回来，再谈发布。
4. 新增功能必须往清单里加一行，并在第四节变更日志记一笔。
5. 移除功能不删行，把状态改成 ❌ 并写原因和日期。

这条规则来自一次真实的教训：2026-10-29 合并上游 v2.1.4 时，设置面板从自建结构换成了上游的注册表驱动结构，结果「导出/导入设置与数据」「自定义字体路径编辑」「客户端密钥显示」三组入口被静默丢掉——底层类都在，但没有任何调用点，编译和测试全绿，问题直到用户手动去点才发现。

## 二、硬底线（任何情况不得违反）

1. **Apple Music 是正常的歌词来源**。2026-10-29 用户明确要求把它加回来，且要求「代码与原版一致、并加入来源竞选」；旧规则「绝不能让 Apple Music 作为来源」**已作废**。它的动效风格（同名）一直是保留的。
2. `docs/` 与 `NOTICE` 里不得出现上游仓库 URL；上游作者署名按 AGPL-3.0 保留在 `NOTICE` 与 `LICENSE`。
3. 测试不全绿不发布。**默认只发预发布（prerelease）**；只有用户明确要求"发正式版"时才发 `prerelease=false`，且要在发布说明里写清是从哪个版本升级过来的完整更新日志。
4. 不允许为了变绿去删除、跳过或 `@Ignore` 测试。
5. 主版本号永不加到 2。

## 三、构建与验证

- 构建：`:app:assembleDebug`，测试：`:app:testDebugUnitTest`；两者都要 `GRADLE_EXIT=0`、`failures=0`、`errors=0`。
- 发布前必须改两处版本号：`app/src/main/java/com/spotifyplusplus/BuildStamp.java` 的 `VERSION` 与 `app/build.gradle` 的 `versionName`/`versionCode`，两处必须一致。
- 界面文案键必须同时存在于 `app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh-rCN/strings.xml` 和 `app/translation/strings-template.xml`（有测试钉住这一致性）。

## 四、修改说明的同步

改了本仓库相对上游的行为，除了更新 `docs/FEATURE-CHECKLIST.md`，还要同步 `docs/MODIFICATIONS.md`（AGPL-3.0 §5(a) 要求的修改声明）。两份文件说法冲突时，以代码为准，并把两份都改对。
