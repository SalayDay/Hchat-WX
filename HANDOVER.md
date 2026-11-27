# Hchat 开发交接文档

> 生成时间：2026-11-27
> 用途：开新对话时把这份文档贴进首条消息，接手方即可继续工作，不必回溯历史。
>
> 2026-11-28 更新：当前状态已更新到 6.7.7.39；下方旧实现及故障排查章节保留历史背景。后续已完成 API 102 原生迁移和多轮修补。最新 [6.7.7.39 红包灰字修复](docs/RED_PACKET_NOTICE_FIX_6_7_7_39.md) 动态登记微信原生领取提示，防止被净化隐藏；不回写红包 XML、不补插重复灰字。保留 [6.7.7.38 表情与撤回修复](docs/EMOJI_RECALL_FIX_6_7_7_38.md)。菜单入口勘误见 [6.7.7.37 记录](docs/LOG_REVIEW_6_7_7_37.md)，不要再按旧文中“首参不是菜单”的结论定位。

---

## 一、当前状态速览

| 项 | 值 |
|---|---|
| 仓库目录 | `D:\UserData\Desktop\AI\Hchat\Hchat-alt-entry-alt-entry` |
| 分支 | `alt-entry` |
| fork | `https://github.com/SalayDay/Hchat-WX`，分支 `alt-entry` |
| 同步范围 | API 102 原生迁移、Rust JNI 重编译、这几天的功能与修补；以 Git 提交为准 |
| 推送授权 | 用户于 2026-11-28 明确要求将本轮全部更新同步到 fork；不自动创建 PR |
| 工作区状态 | 推送后用 `git status --short --branch` 与远端引用核对，不沿用旧快照 |
| 最新交付版本 | **6.7.7.39** |
| APK 路径 | `dist/Hchat-release-6.7.7.39.apk`（本地交付，不提交 Git） |
| APK SHA-256 | `390286C5C517B9A2E956C4F186B19E0C20BC1CCCA295FED0824BDEA7A41DDBC9` |
| 签名 | `4fad3e676491c50817373a2097340e71f59d309e36e9e816d046148632a00ba0`（必须保持不变） |
| 测试 | 36 套件 / 175 用例 / 0 失败 / 0 错误；含 R8 Release 构建成功 |

**推送策略（重要）**：用户明确要求「需要推的时候我会说」。**不要**自动 `git push`，也**不要**每次问要不要推。本地 commit 照常做。

---

## 二、必须遵守的约定

完整规则见仓库根 `AGENTS.md`（新对话先读它）。关键几条：

1. **不确定微信内部类/方法/字段时必须先用 dexsql 逆向确认**，不能凭记忆猜混淆名。
2. 版本号四段 `主.次.修订.补丁`，versionCode 自动推导。**同一轮修补只加最后一位**（如 6.7.7.26 → 6.7.7.27），不要顶大版本。
3. 反射一律走 `h.Hchat.utils.KavaReflector`；错误日志走 `HLog.e(...)`。
4. 新增功能/API/设置入口/构建流程时，同步更新 `docs/FEATURE_FRAMEWORK.md`。
5. 提交信息和 PR 说明用中文。
6. 不提交 keystore、APK、`local.properties`、构建缓存。
7. 只支持 `arm64-v8a` / 64 位微信。
8. 交付 APK 时**直接给文件**，不要只给路径。
9. 收尾必须执行并汇报：`git status --short`、`git diff --check`、`git diff --stat`。

---

## 三、构建方法

```powershell
$proj = "D:\UserData\Desktop\AI\Hchat\Hchat-alt-entry-alt-entry"
Set-Location $proj
$env:JAVA_HOME = "D:\Java\jdk21"
$env:ANDROID_HOME = "D:\Android\Sdk"
$env:ANDROID_SDK_ROOT = "D:\Android\Sdk"
$env:GRADLE_USER_HOME = "D:\gradle-home"
# 签名口令和别名由本机环境变量或 CI Secrets 注入，不得写进文档、源码或提交历史。
if (-not $env:HCAT_STORE_PASSWORD -or -not $env:HCAT_KEY_PASSWORD -or -not $env:HCAT_KEY_ALIAS) {
    throw "请先通过本机安全环境或 CI Secrets 配置签名变量"
}
$env:HCAT_VERSION_NAME = "6.7.7.39"          # 后续发布只递增补丁位
$env:HCAT_APK_NAME = "Hchat-release-6.7.7.39.apk"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

& "$env:JAVA_HOME\bin\java.exe" -classpath "gradle\wrapper\gradle-wrapper.jar" `
  org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest :app:assembleRelease `
    --console=plain --max-workers=2 -x checkDebugAarMetadata -x checkReleaseAarMetadata
```

- 产物落在 `dist\Hchat-release-<版本>.apk`
- 构建耗时约 **10-25 分钟**，建议用 `run_in_background` + `job_output wait`
- **遇到 `Incremental compilation failed: Can't log after extracting messages`**：删掉
  `app\build\kotlin` 和 `app\build\intermediates\built_in_kotlinc` 再构建。这是本地 Kotlin
  增量编译缓存的已知问题，不是代码错误。
- keystore：`app\keystore\。。.jks`（文件名是两个全角句号）

### 验证 APK

```powershell
$apk = "$proj\dist\Hchat-release-6.7.7.39.apk"
& "D:\Android\Sdk\build-tools\36.0.0\aapt2.exe" dump badging $apk | Select-String "package:"
& "D:\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs $apk | Select-Object -First 2
Get-FileHash $apk -Algorithm SHA256
```

签名 SHA-256 必须是 `4fad3e67...00ba0`，否则覆盖安装会失败。

---

## 四、逆向工具

- **dexsql**（本机已装）：`D:\Tools\dexsql\dexsql.exe`
- 也可用 MCP 工具 `mcp__dexsql__*`（推荐，支持 SQL 查询 + smali 反编译）
- 微信 APK 样本：
  - `app\build\wechat-analysis\wechat-8.0.76.apk`
  - `app\build\wechat-analysis\wechat-8.0.80.apk`
- **测试机实际运行 8.0.80**，优先按 8.0.80 适配，再对 8.0.76 做兼容确认。

常用 SQL 表：`classes`、`methods`、`fields`、`method_strings`、`method_calls`。
注意列名：字符串表用 `method_class` / `method_name` / `value`，不是 `class_descriptor`。

---

## 五、本轮的 8 个新功能（本次对话全部工作）

设置入口按用户要求**全部放在「实用 → 美化」**。

| featureId | 功能 | 目录 | 默认 |
|---|---|---|---|
| `add_emoji_from_image` | 图片保存为表情 | `items/addemoji/` | 关 |
| `chat_message_enter_animation` | 消息入场动效 | `items/chatanimation/` | 关 |
| `bottom_bar_auto_hide` | 悬浮底栏滑动隐藏 | `items/bottombar/` | 关 |
| `bottom_bar_style` | 底栏动效与红点（果冻/呼吸/配色） | `items/bottombarstyle/` | 全关 |
| `global_background` | 全局背景（含离线模糊） | `items/theme/` | 关 |
| `global_font` | 全局字体 + 字重 | `items/theme/` | 关 |
| `theme_pack` | 主题包导入导出 | `items/theme/` | — |
| `chat_immersion` | 聊天沉浸式 | `items/theme/` | 关 |
| `chat_purify` | 界面净化（分割线/系统灰字） | `items/purify/` | 两开关 |
| `menu_style` | 长按菜单磨砂卡片 | `items/menustyle/` | 关 |

新增的共享定位器（在 `hooks/api/message/`）：
- `SingleMessageMenuLocator.kt` —— 聊天长按菜单原生创建/点击（6.7.7.37 恢复正确入口）
- `ChatMenuPopupLocator.kt` —— 聊天长按菜单打开作用域（6.7.7.37 新增）
- `ChatAdapterBindLocator.kt` —— 聊天列表 RecyclerView 绑定入口

---

## 六、⚠️ 踩过的坑（务必先读，否则会重复犯错）

这一轮同一条路走错了很多次，全部记录下来。

### 1. 聊天列表是 RecyclerView，`getView` 不存在

`com.tencent.mm.ui.chatting.adapter.k` 声明了 `getView(int, View, ViewGroup)`，
但**全 dex 没有任何调用点**。聊天列表架构是 RecyclerView + `ChattingDataAdapterV3`。

→ 正确做法：挂**父类**里覆写 AndroidX 的 `onBindViewHolder(ViewHolder, int)`
（覆写名不会被混淆）。适配器自己声明的是被混淆的 per-item 回调
（实测叫 `C0`），挂上去**不保证被调用**。见 `ChatAdapterBindLocator`。

### 2. `onScrolled` 不是 RecyclerView 的方法

`RecyclerView.onScrolled(int,int)` 属于 `OnScrollListener` **接口**（8.0.80 里是 `w2`），
类上根本没有，反射永远失败——日志里那句「找不到 onScrolled」就是这么来的。

→ `scrollBy` / `onScrollChanged` 在**触摸滚动**时都不保证经过。
用 `RecyclerView.onTouchEvent(MotionEvent)` + `computeVerticalScrollOffset()`
相邻差值判断方向。

### 3. 长按菜单首参是 ContextMenu 的具体实现（6.7.7.37 勘误）

本段此前认定 `viewitems.o0#a(ak5.h4, View, ContextMenuInfo)` 首参不是菜单，这个结论错误。8.0.80 的 `ak5.h4` 和 8.0.76 的 `kc5.g4` 均直接实现 `android.view.ContextMenu`；MMPopupMenu.f 直接向 viewitems 创建回调传入该实例，点击也由原生 viewitems 选择回调接收。

`SingleMessageMenuLocator` 按同方法日志 `MicroMsg.ChattingItem` + `msg is null!`（创建）或 `context item select failed, null dataTag`（点击），结合 ContextMenu / MenuItem 参数形状定位；不能因短类名被混淆就改挂没有本调用链证据的 presenter 方法。错误的 ChatPresenterMenuLocator 已删除。完整 8.0.76/80 调用关系见 [最新勘误](docs/LOG_REVIEW_6_7_7_37.md)。

### 4. `addItemDecoration` 和 `ItemDecoration` 都被混淆

方法名 → `P`，参数类型 → `RecyclerView$a`。按名字和类型都找不到。

→ 按**形状**批量挂：`RecyclerView` 上「返回 void、单个非基本类型参数」的方法全挂，
运行时再看参数类名是否含 `Divider`/`Separator`/`ItemOffset`。

### 5. `MethodHookParam.result = x` 不会跳过原方法

必须用 `param.setResult(x)`。直接赋 `result` 不会置 `returnEarly`，原方法照跑。
拦截 `addItemDecoration` 时就踩了这个坑。

### 6. 启动期日志必须走 `HLog.boot()`

功能在**启动期**安装，用户是进设置页之后才开日志采集。`HLog.log()` 在
`captureEnabled == false` 时**直接丢弃**，所以「开了采集却看不到功能日志」。

→ `BaseFeature` 已加 `logBoot()`，安装结果走它；采集一开启自动补写
（日志里带「（启动期）」前缀）。

### 7. 8.0.80 表情消息由**方法**返回，不是字段

`view.tag` 是 `chatting.viewitems.ws`，消息对象由 `ws#c()` 返回。
只遍历字段永远取不到。

→ 先找「无参、返回 `com.tencent.mm.storage.*`」的 getter 并缓存，再退回字段遍历。

### 8. `md5` 是 EmojiInfo 表主键

同一张图不可能两行。已存在时必须**复用那一行**改 catalog 走 update，
否则插入撞主键。而且必须先压缩、再按**最终字节**算 md5（文件名就是 md5）。

### 9. 列表项 View 会被回收复用

隐藏系统灰字时如果改 `layoutParams.height = 0`，复用到正常消息上那条会跟着消失。
只改 `visibility`，并在绑到非系统消息时恢复 `VISIBLE`（复用自愈）。

### 10. 不要用 PowerShell 做带中文/反引号的字符串替换

多次把 Kotlin 文件或 markdown 写坏（文件变成一行 `System.Text.UTF8Encoding`、
换行符被整体改写导致 4000+ 行 diff）。**用 `edit` / `write` 工具**改文件，
不要用 `pwsh -replace`。

---

## 七、尚未验证 / 待办

### 必须真机验证
**6.7.7.26 的三处修复都还没在真机确认**，因为定位点这一轮改过两次：

1. **图片保存为表情** —— 长按聊天图片看是否出现「保存为表情[H]」。
   若没有，日志会多打诊断行，能区分「取不到消息对象」/「菜单注入失败」/「已注入」。
2. **消息入场动效 + 隐藏系统灰字** —— 确认绑定回调真的被调用。
3. **隐藏首页分割线** —— 确认 `addItemDecoration` 候选命中。

### 已知未完成
- **聊天沉浸式「消息从栏下穿过」**：只做了「栏透明」，没做 `clipToPadding=false`
  + padding 补偿。用户说等他抓日志（需要聊天页顶栏/输入栏的类名、高度、列表
  padding 值）。
- **引用回复小尾巴配色**：未做。
- **微信原生底栏红点改色**：未做（目前只改模块自己的悬浮底栏）。
- **头像挂件**：用户明确说不做。
- **全局 OverScroll 回弹**：不做（会和微信手势打架）。
- **实时高斯模糊**：技术上做不到（`RenderEffect` 模糊 View 自身内容而非背后），
  已改为「背景图离线模糊」，要在文档/设置页口径一致。

---

## 八、新对话开场建议

把这份文档贴进去，然后说明要做什么。如果要先验证上一轮的修复，建议这样说：

> 先按 AGENTS.md 的规则接手。当前 HEAD 是 113fe87，最新 APK 是 6.7.7.26。
> 上一轮修了三处定位错误（菜单入口、绑定回调、addItemDecoration），但还没真机验证。
> 这是最新日志：<贴日志>

如果要继续开发新功能：

> 继续开发。先读 AGENTS.md 和 docs/FEATURE_FRAMEWORK.md。
> 新增开关一律放「实用 → 美化」。版本号继续 6.7.7.x。

---

## 九、关键文件索引

| 用途 | 路径 |
|---|---|
| 功能注册表 | `app/src/main/java/h/Hchat/hooks/core/FeatureRegistry.kt` |
| 设置页（所有 UI） | `app/src/main/java/h/Hchat/ui/miuix/MiuixSettingsPage.kt` |
| 设置页子文件 | `MiuixSettingsPagePages.kt` / `MiuixSettingsPageSections.kt` |
| 日志 | `app/src/main/java/h/Hchat/utils/HLog.java` |
| 反射统一入口 | `app/src/main/java/h/Hchat/utils/KavaReflector.kt` |
| 功能基类 | `app/src/main/java/h/Hchat/hooks/core/BaseFeature.kt` |
| 安装调度 | `app/src/main/java/h/Hchat/hooks/core/DexInstallScheduler.kt` |
| 功能架构文档 | `docs/FEATURE_FRAMEWORK.md` |
| DexKit 定位缓存 | `app/src/main/java/h/Hchat/dexkit/DexMethodCache.kt` |

### 设置页代码分布（重要）

`MiuixSettingsPage.kt` 有 **16000+ 行**，改动时注意：

- 分组列表：搜 `title = "美化"`（约 3272 行）
- 搜索关键词：搜 `listOf("` 附近的 `-> listOf(`
- 详情页分发：搜 `-> HchatExtraToggleMiuixPage(` 或 `-> .*MiuixPage(context, provider, onBack)`
- 新增页面函数可追加到文件末尾

新增一个开关的完整接线是 **4 处**：import、分组 ID、搜索词、详情页分支。
