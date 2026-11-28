package h.Hchat;

import android.app.Application;
import android.content.Context;
import android.os.Process;

import androidx.annotation.NonNull;

import h.Hchat.crash.CrashReportRuntime;
import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.media.WeChatImageApi;
import h.Hchat.hooks.core.DexInstallScheduler;
import h.Hchat.hooks.core.FeatureContext;
import h.Hchat.hooks.core.FeatureManager;
import h.Hchat.hooks.core.FeatureRegistry;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.hooks.core.HostRuntime;
import h.Hchat.hooks.core.ModuleRuntime;
import h.Hchat.hooks.items.fakelocation.FakeLocationFeature;
import h.Hchat.hooks.items.custombottombar.CustomBottomBarFeature;
import h.Hchat.hooks.items.conversationtabs.ConversationTabsFeature;
import h.Hchat.hooks.items.hotupdate.DisableHotUpdateFeature;
import h.Hchat.hooks.items.miniprogrambaselib.FakeMiniProgramBaseLibFeature;
import h.Hchat.hooks.items.miniprogramsplashad.SkipGlobalMiniProgramSplashAdsFeature;
import h.Hchat.hooks.items.miniprogramvideoad.SkipMiniProgramVideoAdsFeature;
import h.Hchat.hooks.items.subscribe.RemoveSubscribeAdsFeature;
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutRuntime;
import h.Hchat.hooks.items.script.ScriptPluginRuntime;
import h.Hchat.hooks.items.script.agent.CodexWebGuard;
import h.Hchat.hooks.items.script.agent.ModuleResourceMenuFallback;
import h.Hchat.hooks.items.script.agent.ScriptPluginAgentLocalReverseTools;
import h.Hchat.hooks.items.tablet.WeChatTabletFeature;
import h.Hchat.loader.utils.NativeLibraryLoader;
import h.Hchat.preferences.ConfigStore;
import h.Hchat.preferences.TermsGate;
import h.Hchat.dexkit.DexBridgeHolder;
import h.Hchat.event.EventBus;
import h.Hchat.ui.UIRegistry;
import h.Hchat.utils.HLog;
import h.Hchat.utils.KavaReflector;

import org.luckypray.dexkit.DexKitBridge;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口（LibXposed API 102 原生实现）。
 *
 * 启动流程：
 * 1. onPackageReady → 过滤目标包 + 目标进程
 * 2. 小程序子进程安装必要的缓存 Hook 和显式声明的小程序脚本插件
 * 3. 主进程在 Tinker attach 后优先安装自定义底栏 Hook
 * 4. Application.onCreate 后异步初始化完整模块
 *
 * 进程过滤：普通功能只在微信主进程加载；:appbrand* 使用独立的轻量运行时。
 *
 * 入口直接消费原生 PackageReadyParam，宿主信息保存为不可变 HostRuntime；
 * 所有 Hook 都是独立的静态 Hooker，注册表只保存原生 HookHandle。
 */
public class ModuleEntry extends XposedModule {
    private static final String TAG = "[Hchat:Entry]";
    private static final String WECHAT_PKG = "com.tencent.mm";
    private static final String TINKER_APPLICATION = "com.tencent.tinker.loader.app.TinkerApplication";
    private static final String TINKER_ATTACH = "onBaseContextAttached";
    private static final Object DEXKIT_CREATE_LOCK = new Object();
    private static final Map<String, DexKitBridge> DEXKIT_BRIDGES = new ConcurrentHashMap<>();

    /**
     * DexKit native 引擎的空闲回收窗口。
     *
     * 必须覆盖整个安装窗口：几十个功能的定位任务是串行跑的，失败重试退避为
     * 1/3/6 秒，窗口过短会让引擎"释放—重扫—再释放"。重扫要把整包 dex 重新解析，
     * 峰值内存比保持驻留更高，所以这里给 60 秒。
     */
    private static final long DEXKIT_IDLE_RELEASE_MS = 60_000L;

    /**
     * 创建后台初始化线程，统一降到后台优先级。
     *
     * 冷启动必现掉帧的主因：这些线程跑的是 DexKit 全 dex 解析 / 反射定位这类
     * CPU 密集任务，默认优先级和微信主线程相同（0），于是和首页渲染抢 CPU。
     * 用 BACKGROUND 而不是 LOWEST：后台任务仍需在合理时间内完成，
     * LOWEST 可能饿死功能安装。
     */
    private static Thread backgroundThread(Runnable task, String name) {
        return new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, name);
    }

    // 全局基础设施实例（模块生命周期内单例）
    private final EventBus eventBus = EventBus.get();
    private final UIRegistry uiRegistry = UIRegistry.get();

    private FeatureManager featureManager;
    private FeatureContext featureContext;

    /** 一个进程只处理首个目标包，避免重复安装。 */
    private final AtomicBoolean packageInstalled = new AtomicBoolean();

    /** 进程名由原生模块加载回调提供。 */
    @NonNull
    private String processName = "";

    public ModuleEntry() {
        // LibXposed 反射实例化入口：无参构造，生命周期回调由框架驱动。
    }

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam loadedParam) {
        super.onModuleLoaded(loadedParam);
        processName = loadedParam.getProcessName();
        ModuleRuntime.bind(this);
        log(android.util.Log.INFO, TAG + " onModuleLoaded: " + processName);
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam readyParam) {
        if (!isWeChatPackage(readyParam.getPackageName())) return;
        if (!packageInstalled.compareAndSet(false, true)) return;
        if (!readyParam.isFirstPackage()) {
            // 非首个包也只在当前进程安装一次；这里只记录，不用它做硬门禁，
            // 避免宿主先加载其它包时整个模块静默不工作。
            log(android.util.Log.INFO, TAG + " 当前包不是进程首个包，仍按一次性安装: "
                    + readyParam.getPackageName());
        }
        HostRuntime runtime = new HostRuntime(
                readyParam.getPackageName(),
                processName,
                readyParam.getClassLoader(),
                readyParam.getApplicationInfo(),
                readyParam.isFirstPackage());
        try {
            initializePackage(runtime);
        } catch (Throwable failure) {
            HLog.e(TAG + " 进程入口安装失败: " + processName, failure);
        }
    }

    /** 初始化目标宿主包；只在当前进程首次包就绪时执行。 */
    private void initializePackage(HostRuntime runtime) {
        // 1. 包名过滤
        if (!isWeChatPackage(runtime.getPackageName())) return;
        if (!Process.is64Bit()) {
            HLog.e(TAG + " 当前版本仅支持 ARM64 微信进程，跳过32位进程: " + runtime.getProcessName());
            return;
        }

        // 热更新加载发生在 Tinker attach 阶段，必须早于普通功能初始化安装。
        installHotUpdateEarlyHook(runtime);

        // 平板模式需要在微信所有进程保持一致，避免升级/降级后登录态校验看到不同设备形态。
        installTabletEarlyHook(runtime);

        // 2. 子进程只安装明确需要的最小 Hook。
        if (!isMainProcess(runtime)) {
            if (isAppBrandProcess(runtime)) {
                installAppBrandProcessHook(runtime);
            }
            return;
        }

        // 3. Mars CDN 管理器可能早于模块 API 初始化创建，先按稳定类名捕获实例。
        WeChatImageApi.installMarsCdnManagerHook(
                runtime.getClassLoader(),
                message -> HLog.e("[Hchat:WechatApi] " + message));

        // 底栏在 LauncherUI 首帧前创建，必须早于完整 DexKit 初始化安装 Hook。
        installCustomBottomBarEarlyHook(runtime);

        // 4. Hook Application.onCreate，等 Application 完全初始化后再启动模块
        try {
            HookRegistry.get().hook(requireMethod(Application.class, "onCreate"),
                    new MainApplicationHooker(this, runtime));
        } catch (Throwable failure) {
            HLog.e(TAG + " Application.onCreate Hook 安装失败", failure);
        }
    }

    // ============ 包名与进程判断 ============

    /**
     * 判断是否为目标微信包。
     * 支持 com.tencent.mm 以及以 com.tencent.mm 开头的分身包名。
     */
    private boolean isWeChatPackage(String packageName) {
        return packageName != null && packageName.startsWith(WECHAT_PKG);
    }

    /**
     * 判断是否为主进程。
     * 微信主进程的 processName 等于 packageName。
     */
    private boolean isMainProcess(HostRuntime runtime) {
        String process = runtime.getProcessName();
        return process == null || process.isEmpty() || process.equals(runtime.getPackageName());
    }

    private boolean isAppBrandProcess(HostRuntime runtime) {
        String process = runtime.getProcessName();
        String packageName = runtime.getPackageName();
        return process != null && packageName != null && process.startsWith(packageName + ":appbrand");
    }

    private void installAppBrandProcessHook(HostRuntime runtime) {
        Method attach = findTinkerAttach(runtime.getClassLoader());
        if (attach != null) {
            try {
                HookRegistry.get().hook(attach, new AppBrandAttachHooker(this, runtime));
            } catch (Throwable failure) {
                HLog.e(TAG + " 小程序进程早期入口安装失败: " + failure.getMessage(), failure);
            }
        } else {
            HLog.e(TAG + " 小程序进程早期入口未找到: " + TINKER_APPLICATION + "#" + TINKER_ATTACH);
        }
        try {
            HookRegistry.get().hook(requireMethod(Application.class, "onCreate"),
                    new AppBrandApplicationHooker(this, runtime));
        } catch (Throwable failure) {
            HLog.e(TAG + " 小程序进程兜底入口安装失败: " + failure.getMessage(), failure);
        }
    }

    private void installAppBrandProcessHook(Context context,
                                            ClassLoader classLoader,
                                            HostRuntime runtime,
                                            boolean applicationReady) {
        if (!TermsGate.INSTANCE.isAccepted(context)) return;
        // 子进程也接入同一份日志存储；初始化只排队，不阻塞 attach 主线程。
        HLog.initCapture(context);
        boolean failed = false;
        if (FakeMiniProgramBaseLibFeature.isEnabled(context)
                && !FakeMiniProgramBaseLibFeature.installAppBrandProcessHook(context, classLoader)) {
            failed = true;
        }
        if (SkipMiniProgramVideoAdsFeature.isEnabled(context)
                && !SkipMiniProgramVideoAdsFeature.install(context, classLoader)) {
            failed = true;
        }
        if (SkipGlobalMiniProgramSplashAdsFeature.isEnabled(context)) {
            SkipGlobalMiniProgramSplashAdsFeature.scheduleAppBrandProcessHook(context, classLoader);
        }
        if (FakeLocationFeature.isEnabled(context)
                && !FakeLocationFeature.installAppBrandProcessHook(context, classLoader)) {
            failed = true;
        }
        if (applicationReady) {
            ScriptPluginRuntime.installAppBrandProcess(
                    context,
                    classLoader,
                    runtime.getProcessName()
            );
        }
        if (failed && applicationReady) {
            HLog.e(TAG + " 小程序进程Hook安装失败: " + runtime.getProcessName());
        }
    }

    // ============ 模块初始化 ============

    private void installHotUpdateEarlyHook(HostRuntime runtime) {
        Method attach = findTinkerAttach(runtime.getClassLoader());
        if (attach == null) {
            HLog.e(TAG + " 热更新早期入口未找到: " + TINKER_APPLICATION + "#" + TINKER_ATTACH);
            return;
        }
        try {
            HookRegistry.get().hook(attach, new HotUpdateAttachHooker(this, runtime));
        } catch (Throwable failure) {
            HLog.e(TAG + " 热更新早期入口安装失败: " + failure.getMessage(), failure);
        }
    }

    private void installHotUpdateForAttach(HostRuntime runtime,
                                           Context context,
                                           Object tinkerApplication,
                                           String stage) {
        try {
            if (!TermsGate.INSTANCE.isAccepted(context)) return;
            if (!DisableHotUpdateFeature.isEnabled(context)) return;
            if ("before".equals(stage)) {
                DisableHotUpdateFeature.installEarly(context, runtime.getClassLoader());
                return;
            }
            backgroundThread(() -> {
                DexInstallScheduler.runDexKitTask(() -> {
                    try {
                        ClassLoader hostClassLoader = resolveTinkerClassLoader(tinkerApplication);
                        if (hostClassLoader == null) {
                            hostClassLoader = resolveHostClassLoader(context, runtime);
                        }
                        NativeLibraryLoader nativeLibraryLoader = new NativeLibraryLoader();
                        nativeLibraryLoader.loadDexKit(context, ModuleEntry.this.getClass().getClassLoader());
                        DexKitBridge dexKit = createDexKitBridge(
                                hostClassLoader, runtime.getApplicationInfo().sourceDir);
                        DisableHotUpdateFeature.install(context, hostClassLoader, dexKit);
                    } catch (Throwable failure) {
                        HLog.e(TAG + " 热更新后置安装失败: " + failure.getMessage(), failure);
                    }
                });
            }, "Hchat-HotUpdateHook").start();
        } catch (SecurityException security) {
            throw security;
        } catch (Throwable failure) {
            HLog.e(TAG + " 热更新早期Hook失败(" + stage + "): " + failure.getMessage(), failure);
        }
    }

    private void installTabletEarlyHook(HostRuntime runtime) {
        Method attach = findTinkerAttach(runtime.getClassLoader());
        if (attach == null) {
            HLog.e(TAG + " 平板模式早期入口未找到: " + TINKER_APPLICATION + "#" + TINKER_ATTACH);
            return;
        }
        try {
            HookRegistry.get().hook(attach, new TabletAttachHooker(this, runtime));
        } catch (Throwable failure) {
            HLog.e(TAG + " 平板模式早期入口安装失败: " + failure.getMessage(), failure);
        }
    }

    private void installCustomBottomBarEarlyHook(HostRuntime runtime) {
        Method attach = findTinkerAttach(runtime.getClassLoader());
        if (attach == null) return;
        try {
            HookRegistry.get().hook(attach, new CustomBottomBarAttachHooker(this, runtime));
        } catch (Throwable failure) {
            HLog.e(TAG + " 自定义底栏早期入口安装失败: " + failure.getMessage(), failure);
        }
    }

    private void installCustomBottomBarEarly(Context context, ClassLoader classLoader) {
        try {
            if (!TermsGate.INSTANCE.isAccepted(context)) return;
            CustomBottomBarFeature.installEarly(context, classLoader);
        } catch (Throwable failure) {
            HLog.e(TAG + " 自定义底栏早期Hook失败: " + failure.getMessage(), failure);
        }
    }

    /**
     * 公众号信息流广告的安装。
     *
     * ## 为什么不在 Tinker attach 阶段装（6.6.6.5 的做法，已回退）
     *
     * 1. attach 阶段 ClassLoader 还没切到补丁后的版本，这时候 Class.forName 拿到的可能是
     *    随后被替换掉的旧 Class，hook 会挂在废弃类上——白装，还查不出来；
     * 2. 这次安装要做最多 10 次方法 Hook，在 Application 构造期间做，会把启动主线程
     *    长时间按住，日志采集线程也起不来，表现就是"设置页抓不到日志"；
     * 3. 提前把 wx.i1 / mu0.i 这类 Flutter 运行时类拉进内存并解析整张方法表，
     *    这些反射对象和类结构会一直常驻，进程内存明显上涨。
     *
     * ## 为什么要早于 initModule
     *
     * 冷启动时 native 会拿本地缓存主动推一轮广告卡。这一轮发生在用户点开订阅号信息流时，
     * 远晚于 onCreate；而 initModule 的后台线程此时还卡在 DexKit 全量解析上（秒级）。
     * 所以 onCreate 之后单独起一条只做反射的线程，既足够早，又不占主线程。
     */
    private void installSubscribeAdsAfterStartup(Context context, HostRuntime runtime) {
        try {
            if (!TermsGate.INSTANCE.isAccepted(context)) return;
            if (!RemoveSubscribeAdsFeature.isEnabled(context)) return;
            final ClassLoader classLoader = resolveHostClassLoader(context, runtime);
            backgroundThread(() -> {
                try {
                    RemoveSubscribeAdsFeature.install(context, classLoader);
                } catch (Throwable failure) {
                    HLog.e(TAG + " 信息流广告安装失败: " + failure.getMessage(), failure);
                }
            }, "Hchat-SubscribeAds").start();
        } catch (Throwable failure) {
            HLog.e(TAG + " 信息流广告安装线程启动失败: " + failure.getMessage(), failure);
        }
    }

    private void installTabletHookForAttach(HostRuntime runtime,
                                            Context context,
                                            Object tinkerApplication,
                                            String stage) {
        try {
            if (!TermsGate.INSTANCE.isAccepted(context)) return;
            if (!WeChatTabletFeature.isEnabled(context)) return;
            ClassLoader resolvedClassLoader = resolveTinkerClassLoader(tinkerApplication);
            if (resolvedClassLoader == null) {
                resolvedClassLoader = resolveHostClassLoader(context, runtime);
            }
            final ClassLoader hostClassLoader = resolvedClassLoader;
            // 缓存安装不使用 DexKit，避免主线程等待后台预热持有的串行门。
            if (WeChatTabletFeature.installCached(context, hostClassLoader)) return;
            DexInstallScheduler.runDexKitTask(() -> {
                if (WeChatTabletFeature.installCached(context, hostClassLoader)) return;
                if (!"after".equals(stage)) {
                    if (!DisableHotUpdateFeature.isEnabled(context)) return;
                    NativeLibraryLoader nativeLibraryLoader = new NativeLibraryLoader();
                    nativeLibraryLoader.loadDexKit(context, ModuleEntry.this.getClass().getClassLoader());
                    DexKitBridge dexKit = createDexKitBridge(
                            runtime.getClassLoader(), runtime.getApplicationInfo().sourceDir);
                    WeChatTabletFeature.install(context, runtime.getClassLoader(), dexKit);
                    return;
                }
                NativeLibraryLoader nativeLibraryLoader = new NativeLibraryLoader();
                nativeLibraryLoader.loadDexKit(context, ModuleEntry.this.getClass().getClassLoader());
                DexKitBridge dexKit = createDexKitBridge(
                        hostClassLoader, runtime.getApplicationInfo().sourceDir);
                WeChatTabletFeature.install(context, hostClassLoader, dexKit);
            });
        } catch (SecurityException security) {
            throw security;
        } catch (Throwable failure) {
            HLog.e(TAG + " 平板模式早期Hook失败(" + stage + "): " + failure.getMessage(), failure);
        }
    }

    private ClassLoader resolveTinkerClassLoader(Object tinkerApplication) {
        if (tinkerApplication == null) return null;
        Object value = KavaReflector.invokeMethod(tinkerApplication, "getClassLoader");
        return value instanceof ClassLoader ? (ClassLoader) value : null;
    }

    private void initModule(Context hostContext, HostRuntime runtime) {
        try {
            // 0. 尽早恢复日志存储（崩溃证据与看门狗面包屑仍依赖它），此时只是一次配置读取。
            HLog.initCapture(hostContext);

            // 1. 加载 Native 库（DexKit）
            NativeLibraryLoader nativeLibraryLoader = new NativeLibraryLoader();
            nativeLibraryLoader.loadDexKit(hostContext, getClass().getClassLoader());
            nativeLibraryLoader.loadSilkCodec(hostContext, getClass().getClassLoader());

            // 2. 创建模块自身 Context
            Context moduleContext = createModuleContext(hostContext);
            ConfigStore configStore = new ConfigStore(hostContext);

            ModuleResourceMenuFallback.INSTANCE.install(resolveHostClassLoader(hostContext, runtime));

            DexInstallScheduler.runDexKitTask(() -> {
                // 3. 初始化 DexKit。优先使用 Tinker 之后的运行时 ClassLoader。
                String apkPath = runtime.getApplicationInfo().sourceDir;
                ClassLoader hostClassLoader = resolveHostClassLoader(hostContext, runtime);
                DexKitBridge dexKit = createDexKitBridge(hostClassLoader, apkPath);
                DexFinder finder = new DexFinder(dexKit, hostClassLoader, hostContext);
                WeChatImageApi.installMarsCdnManagerHook(
                        finder.marsCdnManagerClass,
                        message -> HLog.e("[Hchat:WechatApi] " + message));

                // 4. 创建共享 DexKit 服务
                DexBridgeHolder dexBridgeHolder = new DexBridgeHolder(
                        dexKit, finder, hostClassLoader, apkPath);
                ScriptPluginAgentLocalReverseTools.install(dexBridgeHolder, hostContext);

                // 5. 创建统一上下文
                featureContext = new FeatureContext(
                        hostContext,
                        moduleContext != null ? moduleContext : hostContext,
                        hostClassLoader,
                        runtime,
                        dexKit,
                        finder,
                        eventBus,
                        configStore,
                        dexBridgeHolder,
                        uiRegistry
                );

                // 6. 注册并安装所有功能模块
                featureManager = FeatureRegistry.createDefaultManager();
                featureManager.installAll(featureContext);

                // 6.5 后台预热设置页 UI 依赖的 Compose/miuix 类：
                // 设置页首次打开要在微信进程主线程加载几百个类，是「进设置页卡一下」
                // 的主因之一；类加载线程安全，挪到后台低优先级线程做完，
                // 用户首次进入时只剩组合计算本身。失败静默，不影响任何功能。
                try {
                    h.Hchat.ui.miuix.SettingsUiPrewarm.INSTANCE.prewarm(featureContext.hostClassLoader());
                } catch (Throwable t) {
                    HLog.e(TAG + " UI 类预热失败(忽略): " + t);
                }

                // codex 网页版保活：微信进程重启后把上次开着的服务自动拉回来
                CodexWebGuard.INSTANCE.restore(hostContext);

                // 7. 防崩兜底：微信 8.0.76 消息描述文本方法对 null 调 isEmpty() 崩溃（收到特殊小程序电商卡片消息触发）
                installMsgDescTextFallback(finder);
            });

        } catch (Throwable failure) {
            HLog.e(TAG + " 初始化失败: " + failure, failure);
        }
    }

    /**
     * 防崩兜底：微信 8.0.76 的 pt0.r.l(Context, boolean) 存在未初始化局部变量，
     * 收到特殊小程序电商卡片消息（type 44 + ecsInfo）时 String.isEmpty() 对 null 调用导致崩溃。
     * 这里 hook 该方法，原方法抛错时兜底返回空串。
     */
    private void installMsgDescTextFallback(DexFinder finder) {
        try {
            finder.resolveMsgDescTextApi();
            if (finder.msgDescTextMethods.isEmpty()) {
                HLog.e("[Hchat:WechatApi] 消息描述防崩兜底未定位到方法");
                return;
            }
            for (Method method : finder.msgDescTextMethods) {
                if (method == null) continue;
                try {
                    HookRegistry.get().hook(method, new MsgDescTextFallbackHooker());
                } catch (Throwable failure) {
                    HLog.e("[Hchat:WechatApi] 消息描述防崩兜底安装失败: " + method, failure);
                }
            }
        } catch (Throwable failure) {
            HLog.e(TAG + " 消息描述防崩兜底安装失败: " + failure.getMessage(), failure);
        }
    }

    /**
     * 创建模块自身的 Context。
     * 通过 createPackageContext 获取模块自身的 Context，
     * 以便访问模块资源。
     */
    private Context createModuleContext(Context hostContext) {
        try {
            return hostContext.createPackageContext(
                    "h.Hchat",
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ClassLoader resolveHostClassLoader(Context hostContext, HostRuntime runtime) {
        ClassLoader lpClassLoader = runtime.getClassLoader();
        ClassLoader contextClassLoader = null;
        ClassLoader threadClassLoader = null;
        try {
            contextClassLoader = hostContext.getClassLoader();
        } catch (Throwable ignored) {
        }
        try {
            threadClassLoader = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ignored) {
        }

        if (isTinkerClassLoader(contextClassLoader)) return contextClassLoader;
        if (isTinkerClassLoader(threadClassLoader)) return threadClassLoader;
        if (contextClassLoader != null) return contextClassLoader;
        if (threadClassLoader != null) return threadClassLoader;
        return lpClassLoader;
    }

    private DexKitBridge createDexKitBridge(ClassLoader classLoader, String apkPath) {
        synchronized (DEXKIT_CREATE_LOCK) {
            String key = apkPath != null ? apkPath : "";
            DexKitBridge cached = DEXKIT_BRIDGES.get(key);
            if (cached != null) return cached;
            // 仅缓存轻量入口；实际查询才加载 dex，连续空闲后释放 native 分析资源。
            //
            // 空闲窗口取 60 秒，不是越小越好：
            // - 释放后再次查询会重新走 nativeInitDexKit，把整包 dex 重新解析一遍，
            //   峰值内存和耗时都比"保持驻留"更高；
            // - 安装阶段要串行跑几十个定位任务，中间间隔（含 1/3/6 秒退避重试）很容易
            //   超过 10 秒，10 秒窗口会造成"释放—重扫—再释放"的反复，内存曲线反而更抖；
            // - 定位结果已落盘到 DexMethodCache / DexFinder 缓存，二次启动通常不再扫描，
            //   所以 60 秒后释放不会带来后续重扫成本。
            // 需要更激进的释放可以改这个常量，但不要低于安装窗口的量级。
            DexKitBridge bridge = DexKitBridge.createManaged(apkPath, DEXKIT_IDLE_RELEASE_MS);
            DEXKIT_BRIDGES.put(key, bridge);
            return bridge;
        }
    }

    private boolean isTinkerClassLoader(ClassLoader classLoader) {
        if (classLoader == null) return false;
        String text = String.valueOf(classLoader);
        return text.contains("/tinker/") || text.contains("DelegateLastClassLoader");
    }

    /** 反射获取 Tinker attach 入口；类或方法不存在时返回 null，由调用方记录缺失。 */
    private static Method findTinkerAttach(ClassLoader classLoader) {
        Class<?> clazz = KavaReflector.loadClass(TINKER_APPLICATION, classLoader);
        return KavaReflector.findMethodRecursive(
                clazz, TINKER_ATTACH, Context.class, long.class, long.class);
    }

    private static Method requireMethod(Class<?> clazz, String name, Class<?>... parameters) {
        Method method = KavaReflector.findMethodRecursive(clazz, name, parameters);
        if (method == null) {
            throw new IllegalStateException("找不到目标方法: " + clazz.getName() + "#" + name);
        }
        return method;
    }

    private void log(int priority, String message) {
        try {
            log(priority, "Hchat", message);
        } catch (Throwable ignored) {
        }
    }

    // ============ 原生 Hooker ============

    /** 主进程：Application.onCreate 前后完成崩溃采集、早期入口和模块初始化。 */
    private static final class MainApplicationHooker implements XposedInterface.Hooker {
        private final ModuleEntry owner;
        private final HostRuntime runtime;

        MainApplicationHooker(ModuleEntry owner, HostRuntime runtime) {
            this.owner = owner;
            this.runtime = runtime;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Application app = (Application) chain.getThisObject();
            if (app == null) return chain.proceed();
            if (TermsGate.INSTANCE.isAccepted(app)) {
                CrashReportRuntime.prepare(app);
            }
            Object result;
            try {
                result = chain.proceed();
            } catch (Throwable failure) {
                // 原实现 after 仍会在宿主异常时执行；此时进程即将退出，
                // 只补做崩溃采集与日志恢复的低风险部分，再原样抛出。
                runAfter(app);
                throw failure;
            }
            runAfter(app);
            return result;
        }

        private void runAfter(Application app) {
            if (TermsGate.INSTANCE.isAccepted(app)) {
                CrashReportRuntime.install(app, ModuleEntry.class.getClassLoader());
                // 标签栏先捕获首页根视图，不能等待后台 DexKit 初始化后再挂载。
                try {
                    ConversationTabsFeature.installEarly(
                            owner.resolveHostClassLoader(app, runtime));
                } catch (Throwable failure) {
                    HLog.e(TAG + " 标签分组早期安装失败", failure);
                }
                // 悬浮入口不依赖 DexKit，先注册生命周期，避免错过首个 Activity。
                try {
                    FloatingShortcutRuntime.INSTANCE.install(app);
                } catch (Throwable failure) {
                    HLog.e(TAG + " 悬浮快捷菜单早期安装失败", failure);
                }
            }
            owner.installCustomBottomBarEarly(
                    app, owner.resolveHostClassLoader(app, runtime));

            // 「记录运行日志」不能等 initModule 的后台线程：
            // 那条线程要先 loadDexKit 再全量解析 dex（秒级），一旦它被拖慢，
            // 设置页看到的就是"日志抓不到"。这里在 onCreate 返回时就恢复采集，
            // initCapture 自身幂等，后面 initModule 再调一次不会有额外开销。
            try {
                HLog.initCapture(app);
            } catch (Throwable failure) {
                HLog.e(TAG + " 日志采集早期初始化失败: " + failure.getMessage(), failure);
            }

            // 公众号信息流广告：onCreate 之后立刻用独立后台线程安装，
            // 早于 initModule 的 DexKit 全量解析，但不在 attach 阶段做重活。
            owner.installSubscribeAdsAfterStartup(app, runtime);

            backgroundThread(() -> owner.initModule(app, runtime), "Hchat-Init").start();
        }
    }

    /** 小程序子进程：Tinker attach 后使用补丁后的 ClassLoader 安装轻量功能。 */
    private static final class AppBrandAttachHooker implements XposedInterface.Hooker {
        private final ModuleEntry owner;
        private final HostRuntime runtime;

        AppBrandAttachHooker(ModuleEntry owner, HostRuntime runtime) {
            this.owner = owner;
            this.runtime = runtime;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Context context = (Context) chain.getArg(0);
            Object tinkerApplication = chain.getThisObject();
            Object result = chain.proceed();
            if (context == null) return result;
            ClassLoader loader = owner.resolveTinkerClassLoader(tinkerApplication);
            if (loader == null) {
                loader = owner.resolveHostClassLoader(context, runtime);
            }
            owner.installAppBrandProcessHook(context, loader, runtime, false);
            return result;
        }
    }

    /** 小程序子进程兜底：Application.onCreate 之后补装。 */
    private static final class AppBrandApplicationHooker implements XposedInterface.Hooker {
        private final ModuleEntry owner;
        private final HostRuntime runtime;

        AppBrandApplicationHooker(ModuleEntry owner, HostRuntime runtime) {
            this.owner = owner;
            this.runtime = runtime;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Application app = (Application) chain.getThisObject();
            Object result = chain.proceed();
            if (app == null) return result;
            ClassLoader loader = owner.resolveHostClassLoader(app, runtime);
            owner.installAppBrandProcessHook(app, loader, runtime, true);
            return result;
        }
    }

    /** 屏蔽热更新：attach 前安装早期拦截，attach 后再定位并补齐。 */
    private static final class HotUpdateAttachHooker implements XposedInterface.Hooker {
        private final ModuleEntry owner;
        private final HostRuntime runtime;

        HotUpdateAttachHooker(ModuleEntry owner, HostRuntime runtime) {
            this.owner = owner;
            this.runtime = runtime;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Context context = (Context) chain.getArg(0);
            Object tinkerApplication = chain.getThisObject();
            if (context == null) return chain.proceed();
            owner.installHotUpdateForAttach(runtime, context, tinkerApplication, "before");
            Object result;
            try {
                result = chain.proceed();
            } catch (Throwable failure) {
                owner.installHotUpdateForAttach(runtime, context, tinkerApplication, "after");
                throw failure;
            }
            owner.installHotUpdateForAttach(runtime, context, tinkerApplication, "after");
            return result;
        }
    }

    /** 平板模式：所有微信进程在 attach 前后都尝试按缓存或 DexKit 安装。 */
    private static final class TabletAttachHooker implements XposedInterface.Hooker {
        private final ModuleEntry owner;
        private final HostRuntime runtime;

        TabletAttachHooker(ModuleEntry owner, HostRuntime runtime) {
            this.owner = owner;
            this.runtime = runtime;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Context context = (Context) chain.getArg(0);
            Object tinkerApplication = chain.getThisObject();
            if (context == null) return chain.proceed();
            owner.installTabletHookForAttach(runtime, context, tinkerApplication, "before");
            Object result;
            try {
                result = chain.proceed();
            } catch (Throwable failure) {
                owner.installTabletHookForAttach(runtime, context, tinkerApplication, "after");
                throw failure;
            }
            owner.installTabletHookForAttach(runtime, context, tinkerApplication, "after");
            return result;
        }
    }

    /** 自定义底栏：必须赶在 LauncherUI 首帧前安装。 */
    private static final class CustomBottomBarAttachHooker implements XposedInterface.Hooker {
        private final ModuleEntry owner;
        private final HostRuntime runtime;

        CustomBottomBarAttachHooker(ModuleEntry owner, HostRuntime runtime) {
            this.owner = owner;
            this.runtime = runtime;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Context context = (Context) chain.getArg(0);
            Object tinkerApplication = chain.getThisObject();
            Object result = chain.proceed();
            if (context == null) return result;
            ClassLoader classLoader = owner.resolveTinkerClassLoader(tinkerApplication);
            if (classLoader == null) {
                classLoader = owner.resolveHostClassLoader(context, runtime);
            }
            owner.installCustomBottomBarEarly(context, classLoader);
            return result;
        }
    }

    /** 消息描述文本防崩兜底：原实现异常时返回空串，不重试、不二次调用。 */
    private static final class MsgDescTextFallbackHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            try {
                return chain.proceed();
            } catch (Throwable failure) {
                HLog.e("[Hchat:WechatApi] 消息描述文本兜底: "
                        + chain.getExecutable().toGenericString(), failure);
                return "";
            }
        }
    }
}
