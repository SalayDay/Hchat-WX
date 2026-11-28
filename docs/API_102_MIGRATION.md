# LibXposed API 102 原生迁移说明

日期：2026-11-27
基线：`alt-entry` 分支 `7efada6`
范围：模块全部 Hook、公共 API、设置界面、脚本插件 Hook 接口
状态：源码迁移完成，本地 `:app:compileReleaseKotlin` 与 `:app:compileReleaseJavaWithJavac` 通过；**未做真机、多版本微信和 R8 验证**

## 一、这次改了什么

| 项目 | 迁移前 | 迁移后 |
| --- | --- | --- |
| 模块入口 | `XposedModule` + 自建 `XC_LoadPackage` 参数壳 | `onPackageReady` 直接构造不可变 `HostRuntime` |
| Hook 注册 | 自建 `XC_MethodHook` before/after 调度器 | 原生 `hook(executable).setPriority(...).setExceptionMode(...).intercept(Hooker)` |
| 回调模型 | `MethodHookParam`（args / result / throwable / extras / returnEarly） | 独立 Hooker 的 `intercept(Chain)`，参数与返回值直接由 Chain 表达 |
| 卸载句柄 | `XC_MethodHook.Unhook` | `XposedInterface.HookHandle` |
| 反射 | `XposedHelpers` + 本地封装 | 统一 `KavaReflector`（KavaRef） |
| 日志 | `XposedBridge.log` | `HLog` → `XposedInterface.log(...)` |
| 配置 | 已是 FastKV（不是 XSharedPreferences） | 不变；宿主侧不写框架 RemotePreferences |
| 模块元数据 | `assets/xposed_init` + 旧 `xposedmodule` 清单项 | `META-INF/xposed/{module.prop,java_init.list,scope.list}`，`minApiVersion=102` |
| 脚本 Hook | `hookBefore/hookAfter/hookReplace` + `MethodHookParam` | `hook(member, functionName)` + `ScriptHookChain`（interceptor 语义） |

已删除的源码：`app/src/main/java/de/robv/android/xposed/**`（4 个文件）与 `h/Hchat/compat/{RuntimeHooks,ModuleHook,CompatHelpers}.kt`。
`h/Hchat/compat/kavaref/**` 保留，它是 KavaRef 的本地适配，不属于旧 Xposed 模型。

## 二、原生实现约定

```kotlin
private class SomeHooker(private val owner: XxxRuntime) : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val args = chain.args.toTypedArray()      // 原生 args 是不可变 List，改参必须复制
        // ... 读取或修改 args ...
        val result = chain.proceed(args)          // 原方法（及后续拦截器）
        // ... 处理 result ...
        return result                             // 或被替换后的值
    }
}
```

- 短路：不调用 `proceed()`，直接 `return 期望值`。
- 改返回值：先 `proceed()`，再 `return` 新值。
- 需要前后成对的状态（深度计数、临时标记、extras 类数据）用局部变量 + `try/finally`，不放 ThreadLocal、不放成员。
- `Chain` 只在本次 `intercept` 内有效，禁止存字段、传异步线程或延迟使用。
- 默认 `ExceptionMode.PROTECTIVE`：Hooker 自身异常由框架吞掉并放行宿主；`proceed()` 抛出的宿主异常照常向上传播，不得重试 `proceed`。
- 统一注册：`HookRegistry.get().hook(member, hooker, priority)`，批量入口 `hookAllMethods` / `hookAllConstructors`，句柄由注册表记账，`unhookAll()` 随功能卸载。
- 日志：`HLog.e(...)` / `HLog.boot(...)`；反射：`KavaReflector.*`。

## 三、故意保留的旧字符串

以下位置仍出现 `de.robv.android.xposed` 等字样，它们是**反检测匹配规则和历史说明文本**，不是依赖：

- `hooks/items/antidetect/AntiDetectPolicy.kt`：栈帧/包名黑名单
- `hooks/items/antidetect/StackTraceGuardFeature.kt`、`XposedProbeFeature.kt`：注释说明
- `hooks/items/subscribe/RemoveSubscribeAdsFeature.kt`：调用栈过滤
- `ui/miuix/ModuleChangelog.kt`：历史更新说明

## 四、脚本插件 Hook ABI 变更

旧的 `hookBefore/hookAfter/hookReplace`、`XC_MethodHook.MethodHookParam`、`XC_MethodHook.Unhook` 已删除，运行时不再注入 `XposedBridgeClass` / `XposedHelpersClass` / `XC_MethodHookClass`，也不再自动 `import de.robv.android.xposed.*`。

新接口：

```java
Object token = hook(Member method, String functionName);
Object token2 = hook(Member method, String functionName, int priority);
unhook(token);
```

脚本内被调用的函数签名：

```java
Object 函数名(ScriptHookChain chain) throws Throwable {
    Object result = chain.proceed();
    return result;
}
```

`ScriptHookChain` 提供 `executable` / `thisObject` / `args`（只读副本）/ `argCount` / `arg(i)` / `getArg(i)` / `proceed()` / `proceed(Object[])` / `proceedCount()` / `hasProceeded()`，只在本次 intercept 的同一线程内有效，拦截结束后再访问会抛 `IllegalStateException`。

运行期语义：

- 返回值就是宿主返回值；不调用 `proceed()` 直接 `return` 等于短路。
- `proceed()` 抛出的宿主异常以对象形式保存在 facade 里，脚本没有恢复时由分发器原样回抛同一个对象，不跨 Rust 边界重建。
- 脚本自身异常交给框架的 `PROTECTIVE` 处理（记录并放行原方法），因此**不支持主动注入新异常**。
- 解释器执行权当前不可用（`tryLock` 失败或 Rust `try_lock` 返回 busy）时立即放行宿主，不排队、不换线程、不阻塞；日志 10 秒限频。
- 旧 `libbsh_rs.so` 缺少 `tryInvokeHook` 导出时抛 `UnsatisfiedLinkError`，分发器按「引擎不支持」fail-open：记一次错误后永久放行，不逐帧抛异常。
- 句柄按 pluginId 记账，插件卸载、禁用、重载、删除和加载失败都会回收。

旧写法对照：

| 旧脚本 | 新脚本 |
| --- | --- |
| `hookBefore(member, param -> {...})` | `hook(member, "name")` + 在 `name` 里读 `chain.args()`，需要放行时 `chain.proceed()` |
| `param.setResult(x)` | 直接 `return x` |
| `hookAfter(member, param -> {...})` | 先 `Object r = chain.proceed();` 再处理并 `return r` |
| `hookReplace(member, param -> x)` | 不调用 `proceed()`，直接 `return x` |
| `param.getObjectExtra(...)` | 用脚本内局部变量或闭包变量 |

完整语义见 `docs/SCRIPT_PLUGIN_API.md`。

## 五、验证记录

已执行（本地 Gradle，用户授权）：

```text
:app:compileReleaseKotlin          BUILD SUCCESSFUL
:app:compileReleaseJavaWithJavac   BUILD SUCCESSFUL
:app:testDebugUnitTest             26 套件 / 101 用例 / 0 失败
:app:minifyReleaseWithR8           BUILD SUCCESSFUL
:app:assembleRelease               BUILD SUCCESSFUL
git diff --check                   CLEAN
```

交付产物：`dist/Hchat-release-6.7.7.31.apk`

```text
versionName 6.7.7.31   versionCode 6070731   minSdk 27   targetSdk 37
大小 6663552 字节      SHA-256 945A3B4B8A0724DC69DB0CC59DE162CD51DA6B74558A1469AB58D4993F49C63E
签名 SHA-256 4fad3e676491c50817373a2097340e71f59d309e36e9e816d046148632a00ba0（与历史一致，可覆盖安装）
ABI arm64-v8a only
内嵌 libbsh_rs.so SHA-256 2d12b71335ede4c54e185c75f497c204c4c2c86d6181081dcfff670444ae64dc（含 tryInvokeHook）
单元测试 26 套件 / 101 用例 / 0 失败
```

R8 检查：`seeds.txt` 中可见 `SettingsInjector$LegacyInitHooker`、`AntiRecallFeature$RevokeEntryHooker` 等实现类及其
`java.lang.Object intercept(io.github.libxposed.api.XposedInterface$Chain)` 均被保留，说明 Hooker keep 规则生效。

迁移语义审计（静态）：逐文件比对了旧 `setResult` 短路点、`chain.proceed` 调用次数、参数修改是否带回、嵌套 lambda 的非局部 return、
死条件等；未发现迁移引入的“该短路却放行 / 该放行却短路 / 原方法被执行两次”问题。过程中修掉的问题：
`MomentsContactFilterFeature` 的返回类型错误、`GiftMessageHook` 的重复变量声明、`ProtobufPacketHook` 的 lambda 变量遮蔽、
两处 `if (false || …)` 死条件、`FEATURE_FRAMEWORK` 之外若干 CRLF 被改写的文件。

未执行、不能声称已验证：

- 真机运行、微信 8.0.76 / 8.0.80 实机行为
- 脚本引擎 Rust 侧 `tryInvokeHook` 的 ARM64 `.so` 重建（见下）
- 8.0.49 / 8.0.58 / 8.0.66 / 8.0.68 / 8.0.72 / 8.0.74 / 8.0.77 / 8.0.78 横向验证

## 六、风险与后续

1. **脚本引擎 native 已重建（本轮完成）**：`app/src/main/jniLibs/arm64-v8a/libbsh_rs.so` 已用
   `scripts/build_rust_bsh.py` 重新编译，新库 ELF64 / DYN / AArch64、LOAD 段 0x4000（16KB）对齐，
   导出符号已包含 `tryInvokeHook`（旧库没有）。SHA-256：`2d12b71335ede4c54e185c75f497c204c4c2c86d6181081dcfff670444ae64dc`。

   本机为了能编译，额外补了这些环境（都在仓库外，不影响提交）：

   ```text
   宿主工具链   rustup 的 stable-x86_64-pc-windows-gnu（本机无 MSVC link.exe）
   宿主链接器   WinLibs MinGW gcc 16.2.0（已在 PATH，x86_64-w64-mingw32-gcc.exe 可用）
   Rust 目标    aarch64-linux-android（已安装）
   交叉链接器   NDK 27.2 的 clang.exe + -C link-arg=--target=aarch64-linux-android27
   依赖缓存     cargo fetch --locked --target aarch64-linux-android
   readelf      NDK 的 llvm-readelf.exe 复制为 D:\...\Hchat\.toolshim\readelf.exe
   构建目录     D:\...\Hchat\.cargo-target-rustbsh（仓库外）
   ```

   复现命令（在仓库根目录）：

   ```powershell
   $env:PATH = "D:\UserData\Desktop\AI\Hchat\.toolshim;C:\Users\86702\.cargo\bin;$llvmBin;$mingwBin;$env:PATH"
   $env:RUSTUP_TOOLCHAIN = 'stable-x86_64-pc-windows-gnu'
   $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS = '-C link-arg=--target=aarch64-linux-android27'
   python scripts\build_rust_bsh.py --target-dir D:\UserData\Desktop\AI\Hchat\.cargo-target-rustbsh `
     --cargo C:\Users\86702\.cargo\bin\cargo.exe --linker <ndk>\clang.exe
   ```
2. **R8**：新规则用 `-keep class * implements io.github.libxposed.api.XposedInterface$Hooker` 保留 Hooker 构造器与 `intercept`；`ScriptHookChain` 额外整类保留。release 包已实测 `seeds.txt` 中 Hooker 实现类及其 `intercept` 均被保留。
3. **设置入口**：`SettingsInjector` 的 Map / SettingLocation 返回值替换依赖 after 语义，原生实现是先 `proceed()` 再返回新对象，与其他模块共存时的优先级需要真机确认。
4. **异常传播**：`PROTECTIVE` 下 Hooker 自身异常被吞，若某个功能行为依赖异常中断原方法，需要改为显式短路。
5. **ConfigStore 未改**：配置仍是宿主私有目录里的 FastKV。若将来要用框架 RemotePreferences，只能作为独立数据迁移（宿主侧只读）。
6. **`docs/FEATURE_FRAMEWORK.md` 未改动**：该文件在 `HEAD` 中以 CRLF 提交，而本机 `core.autocrlf=true`，任何字节级修改都会产生 2206 行整文件 diff；本轮把迁移说明集中到本文件与两份清单，避免污染提交。另外该文件 `catalog 不是 0x51` 那句里含一个 NUL 字节（写成 `\0x51` 的显示效果），属于既有内容问题，需要单独修复。

## 六、真机日志反馈后的修复（6.7.7.29）

真机日志（微信 8.0.80 / 6.7.7.28）显示功能「已挂载但无效果」。用 dexsql 打开 8.0.80 APK 核对后确认了根因：

```text
com.tencent.mm.ui.chatting.adapter.k                 (ChattingDataAdapterV3，锚点)
  → yt3.y1
    → com.tencent.mm.view.recyclerview.WxRecyclerAdapter
      → qw5.n0                                        (声明两参 + 三参)
        → com.tencent.mm.ui.recyclerview.SynchronizedAdapter   (只声明三参 payload)
          → androidx.recyclerview.widget.f2
```

RecyclerView 走 `onBindViewHolder(holder, position, payloads)`，虚分发命中**最派生**的
`SynchronizedAdapter` 三参版本；旧定位器只沿继承链取**第一个**命中的两参方法（`qw5.n0`），
所以「消息入场动效」「界面净化-隐藏系统灰字」实际收不到回调。

修复：

1. `ChatAdapterBindLocator` 改为收集继承链上**所有** `com.tencent.mm` 类的两参/三参重载并全部安装，
   走到 AndroidX 基类即停止（避免挂到 `androidx.recyclerview.widget.Adapter` 误伤全 App 列表）。
2. 返回 `ChatAdapterBinding`，把锚点适配器类传给 Hooker，运行时用 `matches(thisObject)` 过滤，
   防止公共基类被其它列表复用时误触发。
3. 缓存键升到 `bind_v3`，旧的错误条目自动失效。
4. 增加命中诊断：动效首次播放、系统提示首次隐藏、菜单弹窗被跳过的容器类名，都会写日志，
   下一次日志能直接判断「回调有没有到」。

已知仍未解决（需要下一次日志确认）：

- `长按菜单磨砂卡片` 的过滤条件是内容视图类名含 `menu`，但 8.0.80 的 `com.tencent.mm.ui.base`
  包下**没有任何类名含 menu**（dexsql 核对），所以该功能目前必然不生效；本轮只加了诊断日志
  （会打印真实 popup/content 类名），拿到类名后再改成精确匹配。
- `聊天沉浸式` 依赖视图类名含 `ChatFooter`，8.0.80 里也没有这样的类（只有事件类
  `ChatFooterBottomVisibleChangedEvent`），底部输入区因此不会变透明。
- `屏蔽通话铃声` 日志显示「未定位微信群通话铃声入口」，属于 DexKit 定位缺口。

## 七、消息接收延迟的处理（6.7.7.30）

用户反馈「接收消息有延迟」。逐项排查后定位到聊天绑定热路径上我们自己加的开销：

1. **同一次绑定被处理多遍**。6.7.7.29 为了覆盖 payload 局部刷新，把继承链上多个
   `onBindViewHolder` 重载全挂上了；而 RecyclerView 的一次绑定会沿
   `SynchronizedAdapter#三参 → qw5.n0#三参 → qw5.n0#两参` 逐层回调，于是我们的
   Hooker 会进入 **3 次**，每条消息做 3 遍反射。
   修复：用 ThreadLocal 深度计数只在**最外层**处理一次，内层直接 `proceed()`。

2. **每次绑定都遍历整条字段表**。`holderItemView()` / `holderMessage()` 原来每次都在
   holder 的继承链上逐个字段读，找出 itemView 和消息对象。
   修复：按 holder 类缓存命中的字段（含“没有该字段”的负结果），第一次扫描后每条消息
   只读一个字段。

3. **慢路径可观测**。新增 `h.Hchat.utils.SlowPathLog`，在以下位置记录超过阈值的耗时
   （同一 key 10 秒限频，正常情况只有两次时钟读取）：
   `消息绑定处理 8ms`、`AddMsg 事件派发 20ms`、`数据库变更派发 30ms`。
   日志前缀 `[Hchat:Slow]`，下次真机日志能直接看出延迟出在绑定、事件派发还是数据库回调。

未做设备实测：以上是静态分析与代码级修复，实际提速幅度需要真机对比；如果日志里
`[Hchat:Slow]` 指出了其它环节，再针对该环节处理。

## 八、日志采集“只有启动期日志”（6.7.7.31）

用户反馈日志页只显示启动期内容，之后不再增长。排查后确认有三处会让它看起来“停了”：

1. **跨进程状态覆盖本地开关**。`HLog.syncStore()` 每次同步都会用日志文件里的 `enabled` 覆盖内存里的
   `captureEnabled`；一旦另一个微信进程或旧文件把 `active` 写成 `false`，本进程会静默停止采集，
   而设置页开关仍显示“开”。修复：本进程明确拨过开关时以本地为准，文件只用于跨进程共享。
2. **页面停在窗口最上方**。日志页只渲染 `takeLast(N)` 的窗口，新行追加在末尾；不自动滚动时
   用户看到的一直是窗口里最早的那几行，误以为“后面不抓了”。修复：`logLines` 变化后自动滚到底。
3. **无法区分“没日志”和“没在抓”**。修复：采集开启时每 30 秒写一条
   `[Hchat:Log] 采集心跳 PID=… 缓冲=N 条`；开启那一条也带上当前缓冲条数。
   心跳在涨＝采集正常（只是操作没产生日志）；心跳停了＝采集真的断了。

仍未排除的可能：如果真实原因是「模块 Hook 没触发」，心跳会正常但功能日志仍然没有——
这正好能和“采集停止”区分开，下一次日志就能定性。

## 九、长按菜单入口修复与抢红包副标题（6.7.7.32）

### 长按菜单里的功能项一直不出现

**勘误（6.7.7.37）**：此前把 `ui.chatting.viewitems` 三参创建回调的首参误认作聊天上下文，改为 presenter 委托，这个判断错误。8.0.80 的 `ak5.h4` 和 8.0.76 的 `kc5.g4` 均实现 `ContextMenu`，MMPopupMenu 直接调用该原生创建回调和对应选择回调；本路径未调用 presenter。现已恢复按稳定日志锚点与 ContextMenu 参数接口定位的原生入口，删除错误替代定位器，AddEmoji 及原共享菜单调用者均复用该入口。完整证据及菜单样式修补见 [6.7.7.37 日志复核](LOG_REVIEW_6_7_7_37.md)。

### 自动抢红包后气泡下方不显示“谁领取了红包”

**勘误（6.7.7.39）**：用户确认指的是红包卡片下方的领取系统灰字，不是卡片内部领取状态。此前把它归因为未回写 `receivertitle`、断言“不是美化”缺乏依据，现已撤回该结论。

8.0.76/80 的普通和 Union 开包 `onGYNetEnd` 原本就会在成功、服务端 `SystemMsgContext` 非空且原生会话条件满足时，同步调用 `LuckyMoneyUtil` 的 `(String content,String talker):void` helper 插入 type=10000 提示。模块先 `chain.proceed()` 再处理金额，原生逻辑没有被跳过；系统灰字净化却会隐藏这个原生提示。不能通过改红包卡片 XML 或另造一条中文提示来修复，否则会丢失原生链接或重复插入。

6.7.7.39 在经调用链唯一验证的原生 helper 上建立精确内容/会话作用域，捕获真实 storage 本地 msgId 并复用来源登记。净化对登记成功的新领取提示保留；不改文案、链接、时间与原生成功门槛，也不改变抢包金额统计/通知顺序。普通与 Union、手动与静默共用入口；服务端未返回上下文、群聊原生不生成、本版之前历史行、以及未确认经过此入口的“别人领取了你的红包”服务端提示不冒称已覆盖。详见 [6.7.7.39 红包领取灰字修复](RED_PACKET_NOTICE_FIX_6_7_7_39.md)。

## 十、清单与依据

- [全部 111 个功能与业务 Hook 站点](API_102_HOOK_INVENTORY.md)
- [入口、公共 API、设置界面 Hook 与迁移边界](API_102_PLATFORM_INVENTORY.md)
- 官方接口依据：`https://github.com/libxposed/api/tree/102.0.0`（`XposedInterface` / `XposedModule` / `XposedModuleInterface` 源码），并用本地 `api-102.0.0` JAR 的 `javap` 交叉确认。
