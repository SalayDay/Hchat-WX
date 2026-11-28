# API 102 原生迁移：入口、公共 API 和设置界面 Hook 清单

日期：2026-11-27。基线：`7efada6`。本清单在业务源码迁移前整理；行号属于该基线。完整 111 个注册功能及 items 内 303 个注册/包装站点见 [功能清单](API_102_HOOK_INVENTORY.md)。包装调用、同一方法的多处安装入口和每次运行实际命中的宿主方法数量不是同一统计口径。

## 已核对的官方 102 接口

依赖已是 `io.github.libxposed:api:102.0.0`，但当前业务仍使用自建旧 API 兼容层。官方 102 使用无参 `XposedModule`、`onModuleLoaded(ModuleLoadedParam)`、`onPackageReady(PackageReadyParam)`；后者提供实际 ClassLoader。`onPackageLoaded` 的参数仅提供 `getDefaultClassLoader()`，不能照搬旧版本的构造函数、注解回调和 `getClassLoader()` 签名。

原生调用模型为 `hook(Executable).setPriority(...).setExceptionMode(...).intercept(Hooker)`。每个业务拦截器直接实现 `Hooker.intercept(Chain)`，以返回值表达短路或结果修改，以 `chain.proceed(Object[])` 传递修改后的参数。不得重建 `MethodHookParam`、before/after 调度器或返回值/异常/extra 的旧状态容器。`Chain` 不得跨线程或在回调结束后使用。

官方源码已读取，且关键签名用本机依赖 JAR 的 `javap` 交叉确认。可核验来源：

```text
https://github.com/libxposed/api/tree/102.0.0
https://raw.githubusercontent.com/libxposed/api/102.0.0/api/src/main/java/io/github/libxposed/api/XposedInterface.java
https://raw.githubusercontent.com/libxposed/api/102.0.0/api/src/main/java/io/github/libxposed/api/XposedModule.java
https://raw.githubusercontent.com/libxposed/api/102.0.0/api/src/main/java/io/github/libxposed/api/XposedModuleInterface.java
```

## 模块入口

位置均相对于 `app/src/main/java/h/Hchat/`。

| 文件、基线行号 | 类与方法或定位来源 | 保留行为 |
| --- | --- | --- |
| ModuleEntry.java:177 | android.app.Application.onCreate() | 协议门禁、崩溃报告、早期标签/底栏/悬浮入口、日志、公众号广告、后台完整初始化 |
| ModuleEntry.java:256,335,395,421 | com.tencent.tinker.loader.app.TinkerApplication.onBaseContextAttached(Context,long,long) | 小程序进程、禁用热更新、平板模式、首帧前底栏，分别注册；保留 before/after 顺序 |
| ModuleEntry.java:279 | android.app.Application.onCreate() | 小程序进程补装及显式声明的小程序脚本 |
| ModuleEntry.java:621 | finder.msgDescTextMethods 中每个 m；原实现按 m.declaringClass/m.name/(Context,boolean) 注册 | 原实现异常时返回空串，禁止反射递归重入 |

多进程必须区分主进程、`:appbrand*` 和其余微信进程；热更新/设备形态的全进程安装不能错误收窄到主进程。普通功能仍只在主进程安装。保留 Tinker 后 ClassLoader 选择、首次包判断及重复安装防护。任何后台任务都只能捕获已提取的普通参数，不能捕获原生 `Chain`。

## 公共微信 API

| 文件、基线行号 | 类与方法或实际目标表达式 | 回调语义 |
| --- | --- | --- |
| hooks/api/ui/WeChatLifecycleApi.java:114 | android.app.Activity.onResume/onPause/onDestroy() | 生命周期事件 |
| hooks/api/ui/WeChatCurrentActivityApi.java:37 | 同上三方法，独立注册 | 当前 Activity 的弱引用 |
| hooks/api/ui/WeChatActivityStartApi.java:73 | Activity/ContextWrapper 声明的 startActivity/startActivityForResult，参数含精确 Intent 类型 | 启动事件 |
| hooks/api/ui/WeChatChatPageApi.java:215,250,272 | dexFinder.chatPageStartMethod / chatPageFragmentEnterMethod / chatPageFragmentExitMethod | 当前会话、聊天进入与退出 |
| hooks/api/media/WeChatImageApi.java:325,342,348 | com.tencent.mars.cdn.CdnManager 或 finder.marsCdnManagerClass 的所有构造器及 startC2CDownload 重载 | 早期缓存 CDN 管理器实例 |
| hooks/api/net/WeChatNetworkDispatcher.java:137 | netQueueClass 与 candidates 的 declaredMethods，isSendMethodCandidate(method,null) 过滤 | 发包队列缓存及发送守卫，可能短路 |
| hooks/api/payment/WeChatTransferApi.kt:132 | dexFinder.transferQueryResponseMethod | 转账查询实例关联、错误码/消息/JSON 回调 |
| hooks/api/message/WeChatLocalMessageApi.kt:43 | finder.localMessageCreateTimeMethod | ThreadLocal 会话匹配时指定时间 |
| hooks/api/message/WeChatMessageEventApi.java:63 | dexFinder.addMsgClasses 中，参数满足 parseApi.isLikelyAddMsgClass(type) 的所有方法 | AddMsg 消息事件 |
| hooks/api/message/WeChatMessageEventApi.java:138 | dexFinder.patDisplayTemplateMethod | 拍一拍事件 |
| hooks/api/runtime/WeChatDatabaseListenerApi.java:155 | dexFinder.sqliteDbWrapperClass 及父类、com.tencent.wcdb.database.SQLiteDatabase、com.tencent.wcdb.compat.SQLiteDatabase、android.database.sqlite.SQLiteDatabase | 最低优先级数据库变更与查询观察 |
| hooks/api/sns/WeChatSnsPostObserver.kt:55 | 缓存 sns_info_replace_by_id_v2；否则字符串 com.tencent.mm.plugin.sns.storage.SnsInfoStorage + replaceUserBySnsId 定位 | 唯一非抽象 boolean(long,SnsInfo)，成功持久化后派发，保留广告/本地记录/重入过滤 |
| hooks/api/sns/SnsContextMenuDispatcher.kt:78,85,92 | SnsContextMenuLocator.menuCreateMethods/menuClickMethods/foldBindCompletionMethods 的全部成员 | 朋友圈菜单创建、点击及折叠卡片长按 |

网络候选过滤必须保留：排除 Object 基础方法；1 或 2 个参数；第二参若有只能 int/Integer；第一参不能 primitive、String、Object，且拥有无参 getType():int；返回值仅 boolean/Boolean/int/void。

数据库候选必须保留：insert/insertWithOnConflict/replace/replaceOrThrow 返回 long 且含 ContentValues；update/updateWithOnConflict 返回 int 且含 ContentValues；delete(String,String,String[]):int；仅 wrapper 开放按参数形状匹配的混淆重载；query 返回 Cursor 子类型、名称为 rawQuery/rawQueryWithFactory 且包含 String 参数。深度计数及重入保护用 finally 成对恢复，不能因宿主异常漏恢复。

## 设置入口的全部注册路径

文件为 `hooks/api/ui/SettingsInjector.java`。

| 基线行号 | 类与方法或动态目标 | 用途 |
| --- | --- | --- |
| 183,190 | dex.settingsUIClass 沿继承链的 initView() | 旧设置页 Preference 注入 |
| 217 | dex.settingsUIClass 首个 onPreferenceTreeClick | 设置点击 |
| 430 | ProxyBuilder.forClass(dex.baseSettingItemClass) 生成类的构造器(AppCompatActivity) | 动态设置项 InvocationHandler |
| 446 | dex.settingGroupPersonalInfoClass 中首个返回 dex.settingLocationClass 的方法 | 最高优先级设置排序链 |
| 477,640 | dex.settingItemClassesProviderClass 中首个无参、返回 Map 子类的方法 | 复制并扩充设置项 Map |
| 580 | dex.baseSettingPrefUIClass 与 dex.mainSettingsUIClass 的 superImportUIComponents(HashSet子类) | 组件集合扩充 |
| 626 | android.content.Context.getString(int) | 模块独有负资源 ID 标题 |
| 713 | com.tencent.mm.ui.LauncherUI.onCreate(Bundle)，沿继承链解析 | Intent 设置入口 |
| 730 | com.tencent.mm.ui.LauncherUI.onNewIntent(Intent)，沿继承链解析 | Intent 设置入口 |
| 772 | com.tencent.mm.ui.HomeUI$PlusActionView 全部声明构造器 | 长按加号入口 |
| 859 | dex.plusSubMenuAdapterMethod | 加号菜单项追加 |
| 873 | dex.plusSubMenuOnItemClickMethod | 模块菜单点击短路 |
| 1230,1253 | 实际 adapter 类型沿继承链的非 static getView(int,View子类,ViewGroup子类):View子类 | 模块菜单图标 |
| 1309,1339 | dex.plusSubMenuHelperClass 所有非 static、无参、boolean 返回方法 | 菜单填充后追加 |
| 1319,1352 | helperClass.superclass 向上的所有非 static boolean() / boolean(int) | 菜单显示前追加 |

原有 add(hook(...)) 存在句柄重复记账；原生注册表必须保证同一句柄只登记一次。不能根据注释虚构没有实际注册的 onDestroy Hook。

## 设置界面临时回调

| 文件、基线行号 | 宿主目标 | 用途 |
| --- | --- | --- |
| ui/miuix/MiuixSettingsPage.kt:1195,1199,1203,1207 | 实际 Activity 类型/父类的 dispatchKeyEvent、onKeyDown、onKeyUp、onBackPressed 重载 | 设置界面返回导航 |
| 同文件:1510,1633,1740,1905,2049,2169,2289,2432,2578,2685 | 实际 Activity 类型/父类的 onActivityResult 重载 | 文件、媒体、字体、主题等系统选择结果 |
| ui/miuix/ScriptPluginAgentSkillsPage.kt:426 | 实际 Activity 类型/父类的 onActivityResult 重载 | 技能导入 |

items 下图标/头像/消息气泡/会话分组等选择器的临时 onActivityResult/onDestroy Hook 已在完整功能清单逐文件列出。临时句柄仍须在成功、失败、取消及 Activity 销毁路径清理。

## 基础设施与配置边界

- HookRegistry 本身不指定微信目标。原生化后只接受真实 Method/Constructor 与原生 Hooker、保存原生 HookHandle；保留安装代次、ThreadLocal 租约、失效任务取消、统一卸载和 SelfFrameRegistry。
- DexInstallScheduler 的 EARLY/BRIDGE/WARMUP、串行门、有限重试及代次捕获不依赖旧 API，保留。
- FeatureContext 不再持有伪造 XC_LoadPackage 参数；改传实际宿主运行信息。
- 全仓没有使用 XSharedPreferences；现有可写配置是宿主私有目录里的 FastKV，经 ConfigStore/HchatStorage 访问，不应替换成宿主中只读的框架 RemotePreferences。若将来需要模块应用的跨进程偏好，读取端必须使用原生 getRemotePreferences(group)，其写端与数据迁移需单独设计。
- dexkit/DexFinder.java、SettingsDexFinder.java、loader/utils/NativeLibraryLoader.java、utils/HLog.java 的旧 XposedBridge 用途为日志，迁移为持有模块 API 后的原生日志。
- module.prop 的最低版本需与 102 目标一致；清理 assets/xposed_init、旧 Manifest metadata 及旧兼容层 R8 保留规则，同时保留 META-INF/xposed/java_init.list 与 scope.list。
- compat/kavaref 是反射依赖的本地适配，不是旧 Xposed 模型，不按目录名误删。

## 脚本及迁移风险

用户已确认脚本 Hook ABI 同步迁移。旧的 hookBefore/hookAfter/hookReplace 暴露 Consumer/Function<XC_MethodHook.MethodHookParam> 和 XC_MethodHook.Unhook，运行时还自动注入/导入旧类。必须删除这些旧类型及调度模型；外部旧脚本需要改写，不能宣称无需修改即可兼容。

新脚本 Hook 采用 interceptor 模型，编译在模块中的原生 Hooker 负责框架注册与调用；解释器仅访问受限的同步调用链接口，不获得 XposedModule、HookBuilder 或可跨回调使用的 Chain。解释器的必要接口适配不模拟旧 MethodHookParam、before/after、result、throwable 或 extras。

重点回归：设置 Map/集合返回值、优先级、构造器、宿主异常传播与显式恢复、原始 Invoker、数据库深度恢复、Tinker 早期 ClassLoader、卸载与异步补装竞态、脚本 Hook 资源归属及动态代码保护。动态 Dex 和 Resource 行为差异需在迁移说明中明确记录。静态审计不等于多版本真机验证。
