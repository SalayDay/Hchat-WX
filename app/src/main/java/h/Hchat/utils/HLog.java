package h.Hchat.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import h.Hchat.hooks.core.ModuleRuntime;
import h.Hchat.preferences.HchatStorage;

/**
 * 模块日志。
 *
 * 原实现每次调用都会 `new RuntimeException(message)`，目的是让 LSPosed 把日志
 * 记录成错误级别。但构造 Throwable 会填充完整调用栈，而本模块有 600+ 处
 * `HLog.e` 调用，其中不少位于消息回调、RecyclerView 绑定这类高频路径上：
 * 每条日志都会额外产生几十个 StackTraceElement 对象，既占内存又拖慢主线程。
 *
 * 当前策略：
 * 1. 默认只输出消息文本，不构造 Throwable（异常类型和 message 仍然保留）；
 * 2. 同一段文本 10 秒内只输出一次，避免循环 Hook 把 logcat 环形缓冲刷爆；
 * 3. 需要完整堆栈时调用 {@link #setStackTraceEnabled} 打开；
 * 4. 需要把日志显示在模块设置页里时，打开 {@link #setCaptureEnabled}，
 *    日志会额外写入内存环形缓冲（默认关闭，不产生任何额外开销）。
 */
public final class HLog {
    private static final String TAG = "[Hchat]";
    private static final long DEDUP_WINDOW_MS = 10_000L;
    private static final int DEDUP_MAX_ENTRIES = 128;

    /** 「记录运行日志」开关所在的配置文件名。 */
    public static final String PREFS_NAME = "Hchat_debug";
    /** 「记录运行日志」开关的键名，默认关闭。 */
    public static final String KEY_CAPTURE_ENABLED = "log_capture_enabled";
    /** 内存缓冲最多保留多少条，超出后丢弃最旧的。 */
    public static final int MAX_CAPTURE_ENTRIES = 2000;

    private static volatile boolean stackTraceEnabled = false;

    /**
     * 是否把日志写入内存缓冲。
     *
     * <p>由设置页开关或 {@link #initCapture} 从配置里恢复。默认 false，
     * 此时 {@link #log} 不会做任何额外工作。
     */
    private static volatile boolean captureEnabled = false;
    /** 用户在设置页主动拨过开关后，后台初始化不得再用旧配置覆盖。 */
    private static volatile boolean captureSetExplicitly = false;
    private static final Object STORE_LOCK = new Object();
    private static volatile LogCaptureStore store;
    private static volatile long storeGeneration = -1;
    private static long storeRevision = -1;
    private static volatile String captureError = "";
    private static final ArrayDeque<CaptureEntry> PENDING = new ArrayDeque<>();
    private static Boolean requestedEnabled;
    private static boolean requestedClear;
    private static long requestRevision;
    private static boolean initScheduled;
    private static boolean pollScheduled;
    private static final java.util.concurrent.ScheduledExecutorService CAPTURE_WORKER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                Thread worker = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    task.run();
                }, "Hchat-log-capture");
                worker.setDaemon(true);
                return worker;
            });
    private static final String SOURCE = " PID=" + android.os.Process.myPid()
            + " CL=" + Integer.toHexString(System.identityHashCode(HLog.class.getClassLoader()));

    private static final ConcurrentHashMap<String, Long> LAST_LOGGED = new ConcurrentHashMap<>();

    /**
     * 启动期关键日志暂存区。
     *
     * <p>用户通常是冷启动后几秒才去打开「记录运行日志」，而功能定位/挂载结果在
     * 启动第一秒就打完了，等开关打开时采集缓冲里只剩后半段——排查时最需要的那几行
     * 恰好丢了。这类日志走 {@link #boot}，先在这里暂存，开关一打开就补写进缓冲。
     */
    private static final int MAX_BOOT_ENTRIES = 64;
    private static final ArrayDeque<String> BOOT = new ArrayDeque<>(MAX_BOOT_ENTRIES);

    private static final Object CAPTURE_LOCK = new Object();
    private static final ArrayDeque<CaptureEntry> CAPTURE =
            new ArrayDeque<>(MAX_CAPTURE_ENTRIES + 1);
    /** 每写入/清空一次就 +1，方便设置页用极低成本判断"有没有新日志"。 */
    private static final AtomicLong CAPTURE_REVISION = new AtomicLong();
    /**
     * 时间戳格式化只在写入时做一次，读取端直接拷贝字符串。
     *
     * <p>如果放到读取端，设置页每 800ms 轮询都要把最多 2000 条重新 format 一遍，
     * 日志密集时这条开销会持续压在 UI 线程上。SimpleDateFormat 非线程安全，
     * 用 ThreadLocal 按线程各持一份。
     */
    private static final ThreadLocal<SimpleDateFormat> CAPTURE_FORMAT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
                }
            };

    /** 与 CAPTURE_FORMAT 配对复用，避免每条日志都 new 一个 Date。 */
    private static final ThreadLocal<Date> CAPTURE_DATE =
            new ThreadLocal<Date>() {
                @Override
                protected Date initialValue() {
                    return new Date();
                }
            };

    private HLog() {
    }

    /** 排查问题时打开，恢复带完整堆栈的日志。 */
    public static void setStackTraceEnabled(boolean enabled) {
        stackTraceEnabled = enabled;
    }

    /** 诊断开关是否打开；设置注入等模块用它决定是否输出明细日志。 */
    public static boolean isStackTraceEnabled() {
        return stackTraceEnabled;
    }

    /** 初始化和同步共用一个后台工作线程，调用方不执行磁盘 IO。 */
    public static void initCapture(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        synchronized (CAPTURE_LOCK) {
            if (initScheduled) return;
            initScheduled = true;
            if (!pollScheduled) {
                pollScheduled = true;
                // 自调度而不是 fixedDelay：空闲（采集关闭、没有待写内容）时把周期从
                // 1.2 秒拉到 15 秒，避免在用户从没开过日志的情况下每 1.2 秒唤醒一次
                // 去 open/read store 文件。
                CAPTURE_WORKER.execute(HLog::pollLoop);
            }
        }
        CAPTURE_WORKER.execute(() -> {
            try {
                mainProcess = isMainProcess(app);
                SharedPreferences prefs = HchatStorage.preferences(app, PREFS_NAME);
                boolean initial = prefs.getBoolean(KEY_CAPTURE_ENABLED, false);
                synchronized (CAPTURE_LOCK) {
                    if (!captureSetExplicitly) captureEnabled = initial;
                    flushBootLocked();
                }
                store = new LogCaptureStore(HchatStorage.storageDir(app));
                syncStore();
            } catch (Throwable failure) {
                captureError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                synchronized (CAPTURE_LOCK) { initScheduled = false; }
            }
        });
    }

    public static void setCaptureEnabled(boolean enabled) {
        synchronized (CAPTURE_LOCK) {
            captureSetExplicitly = true;
            requestedEnabled = enabled;
            requestRevision++;
            setCaptureState(enabled, true);
        }
        CAPTURE_WORKER.execute(HLog::syncStore);
    }

    public static String captureError() { return captureError; }

    /** 仅供 IO 调用方等待此前的初始化、开关和尾部提交完成。 */
    public static void flushCapture() {
        try {
            CAPTURE_WORKER.submit(HLog::syncStore).get();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            captureError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
    }

    private static void syncStore() {
        synchronized (STORE_LOCK) {
            if (store == null) return;
            List<CaptureEntry> pending;
            Boolean enabled;
            boolean clear;
            long request;
            synchronized (CAPTURE_LOCK) {
                pending = new ArrayList<>(PENDING);
                enabled = requestedEnabled;
                clear = requestedClear;
                request = requestRevision;
            }
            try {
                if (storeGeneration < 0 && !clear) {
                    LogCaptureStore.Snapshot initial = store.update(null, false,
                            java.util.Collections.emptyList(), -1, captureEnabled);
                    storeGeneration = initial.generation;
                }
                if (enabled == null && !clear && pending.isEmpty() && !store.hasChanged(storeRevision)) {
                    captureError = "";
                    return;
                }
                List<String> lines = new ArrayList<>(pending.size());
                for (CaptureEntry entry : pending) lines.add(entry.line);
                LogCaptureStore.Snapshot snapshot = store.update(enabled, clear, lines,
                        storeGeneration, captureEnabled);
                synchronized (CAPTURE_LOCK) {
                    PENDING.removeAll(pending);
                    storeGeneration = snapshot.generation;
                    // IO 期间的新开关/清空请求仍留在队列，不能被旧快照覆盖。
                    if (request == requestRevision) {
                        requestedEnabled = null;
                        requestedClear = false;
                        // 用户在本进程明确拨过开关时以本地为准：store 里的 active 只用于
                        // 跨进程共享，不能被别的进程/旧文件覆盖掉刚打开的采集。
                        if (!captureSetExplicitly) captureEnabled = snapshot.enabled;
                        flushBootLocked();
                        if (snapshot.revision != storeRevision || !pending.isEmpty() || clear) {
                            CAPTURE.clear();
                            for (String line : snapshot.lines) CAPTURE.addLast(new CaptureEntry(line));
                            CAPTURE.addAll(PENDING);
                            while (CAPTURE.size() > MAX_CAPTURE_ENTRIES) CAPTURE.pollFirst();
                            CAPTURE_REVISION.incrementAndGet();
                            storeRevision = snapshot.revision;
                        }
                    }
                }
                captureError = "";
            } catch (Throwable failure) {
                // 保留请求与 pending，下一轮在同一线程重试。
                captureError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            }
        }
    }

    /** 采集开着、或有待写内容时的轮询周期。 */
    private static final long POLL_ACTIVE_MS = 1_200L;

    /** 采集关闭且无待写内容时的轮询周期：只保留"别的进程改了文件"的感知。 */
    private static final long POLL_IDLE_MS = 15_000L;

    /**
     * 自调度的磁盘同步循环。
     *
     * <p>原来用 {@code scheduleWithFixedDelay(syncStore, 1200, 1200)}：用户从没打开过
     * 「记录运行日志」时，微信进程里也会每 1.2 秒醒一次、把 store 文件 open 一遍再读
     * 一个 long（一天约 7 万次），只为确认"没有变化"。这条线程是后台优先级，但唤醒、
     * 取锁、一次文件读的开销都白花。
     *
     * <p>现在按空闲状态自适应：有内容要写就 1.2 秒，什么都没有就 15 秒。开关切换、
     * 清空、退出时仍各自 {@code execute(syncStore)} 立即同步，所以用户可见的行为不变。
     */
    private static void pollLoop() {
        try {
            syncStore();
        } catch (Throwable ignored) {
        }
        maybeHeartbeat();
        long delay = isPollIdle() ? POLL_IDLE_MS : POLL_ACTIVE_MS;
        try {
            CAPTURE_WORKER.schedule(HLog::pollLoop, delay,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // 线程池已关闭（进程收尾）：停止轮询即可，显式写入仍走 execute(syncStore)。
        }
    }

    /** 心跳间隔：足够区分“采集还活着”和“已经停了”，又不至于刷屏。 */
    private static final long HEARTBEAT_MS = 30_000L;
    private static volatile long lastHeartbeatMs;
    /** 只有主进程写心跳：多进程共用一份日志文件，子进程心跳只会把日志刷成噪声。 */
    private static volatile boolean mainProcess = true;

    private static boolean isMainProcess(Context context) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/cmdline"))) {
            String line = reader.readLine();
            if (line != null) {
                int end = line.indexOf('\0');
                if (end > 0) line = line.substring(0, end);
                return line.equals(context.getPackageName());
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * 采集开启时每 30 秒写一条心跳。
     *
     * <p>日志页只能看到内存缓冲，一旦采集被意外关掉，用户看到的就是“启动后再也没有新日志”。
     * 有心跳就能一眼区分：心跳在涨说明采集正常（只是被操作没产生日志），
     * 心跳停了说明采集真的断了。
     */
    private static void maybeHeartbeat() {
        if (!captureEnabled || !mainProcess) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastHeartbeatMs < HEARTBEAT_MS) return;
        lastHeartbeatMs = now;
        int size;
        synchronized (CAPTURE_LOCK) {
            size = CAPTURE.size();
        }
        log("[Hchat:Log] 采集心跳 PID=" + android.os.Process.myPid()
                + " 缓冲=" + size + " 条", null);
    }

    /**
     * 是否处于空闲：采集关闭、没有待写请求、缓冲没有新行。
     *
     * <p>此时 store 里不可能有新内容，唯一的理由是感知其他进程的写入，不需要 1.2 秒的粒度。
     */
    private static boolean isPollIdle() {
        synchronized (CAPTURE_LOCK) {
            return !captureEnabled && requestedEnabled == null && !requestedClear && PENDING.isEmpty();
        }
    }

    /** 高频功能命中只在采集开启时输出，复用日志去重窗口。 */
    public static void event(String message) {
        if (captureEnabled && !isDuplicate("event:" + message)) log(message, null);
    }

    /**
     * 启动期关键日志（功能挂载结果、定位成败这类只输出一次的信息）。
     *
     * <p>与 {@link #e} 的区别：采集还没打开时不会丢掉，而是暂存到 {@link #BOOT}，
     * 等用户打开「记录运行日志」时补写进采集缓冲。补写行会带「（启动期）」前缀，
     * 表示它的实际发生时间早于当前时间戳。
     */
    public static void boot(String message) {
        if (message == null) return;
        String text = message;
        boolean capture;
        synchronized (CAPTURE_LOCK) {
            capture = captureEnabled;
            if (!capture) {
                if (BOOT.size() >= MAX_BOOT_ENTRIES) BOOT.pollFirst();
                BOOT.addLast(text);
            }
        }
        if (capture) appendCapture(text);
        bridgeLog(android.util.Log.INFO, TAG + " " + text, null);
    }

    /**
     * 采集打开时把暂存的启动日志补写进缓冲。
     * 调用方通常已持有 {@link #CAPTURE_LOCK}，synchronized 可重入，这里重复加锁是安全的。
     */
    private static void flushBootLocked() {
        synchronized (CAPTURE_LOCK) {
            if (!captureEnabled || BOOT.isEmpty()) return;
            List<String> buffered = new ArrayList<>(BOOT);
            BOOT.clear();
            for (String line : buffered) appendCapture("（启动期）" + line);
        }
    }

    private static void setCaptureState(boolean enabled, boolean explicit) {
        boolean changed = captureEnabled != enabled;
        captureEnabled = enabled;
        // 采集一旦打开，把启动期暂存的关键日志补进来，否则用户永远看不到
        // "功能到底挂没挂上"这类只输出一次的记录。
        if (enabled) flushBootLocked();
        // 自检记录直接写缓冲，不经过框架日志桥：即使宿主日志桥异常，
        // 用户也能立刻确认设置页与日志写入端使用的是同一份运行状态。
        if (explicit || (enabled && changed)) {
            int size;
            synchronized (CAPTURE_LOCK) {
                size = CAPTURE.size();
            }
            appendCapture("[Hchat:Log] " + (enabled
                    ? "日志采集已开启，进程 PID=" + android.os.Process.myPid() + " 缓冲=" + size + " 条"
                    : "日志采集已关闭；以下仅保留本次关闭前的历史记录"), true);
        }
    }

    public static boolean isCaptureEnabled() {
        return captureEnabled;
    }

    /** 当前缓冲里的日志条数。 */
    public static int captureSize() {
        synchronized (CAPTURE_LOCK) {
            return CAPTURE.size();
        }
    }

    /** 快照版本号，写入和清空都会让它变化。 */
    public static long captureRevision() {
        return CAPTURE_REVISION.get();
    }

    public static final class CaptureSnapshot {
        public final long revision;
        public final List<String> lines;

        CaptureSnapshot(long revision, List<String> lines) {
            this.revision = revision;
            this.lines = java.util.Collections.unmodifiableList(lines);
        }
    }

    /** 日志和版本在同一临界区读取，防止界面把旧内容标记成新版本。 */
    public static CaptureSnapshot captureSnapshotState() {
        synchronized (CAPTURE_LOCK) {
            return new CaptureSnapshot(CAPTURE_REVISION.get(), captureSnapshot());
        }
    }
    /** 取一份日志快照，无日志时返回空列表。 */
    public static List<String> captureSnapshot() {
        synchronized (CAPTURE_LOCK) {
            List<String> lines = new ArrayList<>(CAPTURE.size());
            for (CaptureEntry entry : CAPTURE) {
                lines.add(entry.line);
            }
            return lines;
        }
    }

    /** 取整段日志文本，用于复制到剪贴板。 */
    public static String captureText() {
        List<String> lines = captureSnapshot();
        if (lines.isEmpty()) return "";
        StringBuilder builder = new StringBuilder(lines.size() * 64);
        for (String line : lines) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    public static void clearCapture() {
        synchronized (CAPTURE_LOCK) {
            requestedClear = true;
            requestRevision++;
            CAPTURE.clear();
            PENDING.clear();
            CAPTURE_REVISION.incrementAndGet();
        }
        CAPTURE_WORKER.execute(HLog::syncStore);
    }

    public static void e(String message) {
        log(String.valueOf(message), null);
    }

    public static void e(String message, Throwable throwable) {
        log(String.valueOf(message), throwable);
    }

    private static void log(String message, Throwable throwable) {
        String text = message == null ? "null" : message;
        // 采集模式走去重：否则高频路径上的同一条日志会不断 append + 刷 logcat，
        // 打开"记录运行日志"反而把微信拖慢。
        boolean duplicate = isDuplicate(text);
        // 堆栈模式也去重。线上抓包时用户开着堆栈开关，而"视频号详情解析失败"
        // 这类错误是每次进页面都报一次；原实现每条都要 new RuntimeException 抓堆栈
        // （异常构造本身就要遍历一次栈）再走框架日志桥的同步跨进程序列化，
        // 高频路径上直接把帧率吃掉。现在同一位置 10 秒内只有第一条带完整堆栈。
        if (duplicate) return;

        String suffix = throwable == null
                ? ""
                : " | " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();

        if (captureEnabled) {
            appendCapture(text + suffix + (stackTraceEnabled ? stackSummary(throwable) : ""));
        }

        try {
            if (stackTraceEnabled) {
                bridgeLog(android.util.Log.ERROR, TAG + " " + text, throwable);
            } else {
                // 始终同步写框架日志。原来采集开启时跳过日志桥省一次 IPC，
                // 结果是"开了记录运行日志，LSPosed 日志里的模块输出反而全部消失"——
                // 用户按老习惯去 LSPosed/Vector 导日志时抓到的就是空的。采集模式本来就是
                // 排查用的短期开关，双写（内存缓冲 + 框架日志）才是正确语义；
                // 去重已经在上面的 isDuplicate 里做完，不会造成刷屏。
                bridgeLog(android.util.Log.ERROR, TAG + " " + text + suffix, null);
            }
        } catch (Throwable bridgeFailure) {
            // 日志桥不可用时仍保留模块采集记录，不能让日志异常中断宿主功能。
        }
    }

    /** 通过当前代次的 LibXposed 接口写日志；接口未就绪时静默跳过。 */
    private static void bridgeLog(int priority, String message, Throwable error) {
        try {
            if (error == null) {
                ModuleRuntime.api().log(priority, TAG, message);
            } else {
                ModuleRuntime.api().log(priority, TAG, message, error);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void appendCapture(String text) {
        appendCapture(text, false);
    }

    private static void appendCapture(String text, boolean force) {
        long now = System.currentTimeMillis();
        String line;
        try {
            // 复用 Date 实例：采集开启后每条日志都会走到这里，每条 new Date 都是
            // 一次无谓的小对象分配（进而推高 GC 频率）。
            Date date = CAPTURE_DATE.get();
            date.setTime(now);
            line = CAPTURE_FORMAT.get().format(date) + SOURCE + "  " + text;
        } catch (Throwable ignored) {
            line = text;
        }
        synchronized (CAPTURE_LOCK) {
            if (!force && !captureEnabled) return;
            if (CAPTURE.size() >= MAX_CAPTURE_ENTRIES) CAPTURE.pollFirst();
            CAPTURE.addLast(new CaptureEntry(line));
            if (PENDING.size() >= MAX_CAPTURE_ENTRIES) PENDING.pollFirst();
            PENDING.addLast(CAPTURE.peekLast());
            CAPTURE_REVISION.incrementAndGet();
        }
    }

    /** 把异常的前几帧压成多行文本，只在打开堆栈开关时使用。 */
    private static String stackSummary(Throwable throwable) {
        if (throwable == null) return "";
        StackTraceElement[] frames = throwable.getStackTrace();
        if (frames == null || frames.length == 0) return "";
        StringBuilder builder = new StringBuilder();
        int count = Math.min(frames.length, 8);
        for (int i = 0; i < count; i++) {
            builder.append("\n    at ").append(frames[i]);
        }
        return builder.toString();
    }

    private static boolean isDuplicate(String text) {
        long now = SystemClock.elapsedRealtime();
        Long previous = LAST_LOGGED.get(text);
        if (previous != null && now - previous < DEDUP_WINDOW_MS) return true;
        // 原来超过上限直接 clear() 全表。启动期 91 个功能各打几条不同日志，
        // 一次冷启动就能把表撑到上限，于是"每写满一次清一次"——去重窗口在
        // 最需要它的启动期反而被打穿，同一段日志反复刷屏。
        // 先清已过期条目（窗口外的本来就不会再命中），只有仍然超限才兜底清空。
        if (LAST_LOGGED.size() > DEDUP_MAX_ENTRIES) {
            LAST_LOGGED.entrySet().removeIf(entry -> now - entry.getValue() >= DEDUP_WINDOW_MS);
            if (LAST_LOGGED.size() > DEDUP_MAX_ENTRIES) LAST_LOGGED.clear();
        }
        LAST_LOGGED.put(text, now);
        return false;
    }

    private static final class CaptureEntry {
        final String line;

        CaptureEntry(String line) {
            this.line = line;
        }
    }
}
