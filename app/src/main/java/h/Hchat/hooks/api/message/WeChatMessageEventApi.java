package h.Hchat.hooks.api.message;

import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.event.EventBus;
import h.Hchat.event.EventHandler;
import h.Hchat.event.Events;
import h.Hchat.hooks.api.contact.WeChatAccountApi;
import h.Hchat.hooks.api.model.WeChatParsedMessage;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * 微信消息事件 API。
 */
public final class WeChatMessageEventApi {
    public interface Logger {
        void log(String message);
    }

    private final DexFinder dexFinder;
    private final WeChatAccountApi accountApi;
    private final WeChatMessageParseApi parseApi;
    private final EventBus eventBus;
    private final Logger logger;
    private volatile boolean hooked;
    private volatile boolean patHooked;
    private final ConcurrentHashMap<String, Long> recentPatEvents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentDiagnosticLogs = new ConcurrentHashMap<>();

    public WeChatMessageEventApi(DexFinder dexFinder,
                                 WeChatAccountApi accountApi,
                                 WeChatMessageParseApi parseApi,
                                 EventBus eventBus,
                                 Logger logger) {
        this.dexFinder = dexFinder;
        this.accountApi = accountApi;
        this.parseApi = parseApi;
        this.eventBus = eventBus;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return dexFinder != null && dexFinder.addMsgClasses != null
                && !dexFinder.addMsgClasses.isEmpty()
                && parseApi != null && eventBus != null;
    }

    public void installAddMsgHook() {
        if (!isAvailable()) return;
        if (hooked) {
            installPatMsgHook();
            return;
        }
        int count = 0;
        for (Class<?> clazz : dexFinder.addMsgClasses) {
            for (Method method : KavaReflector.declaredMethods(clazz)) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes == null || parameterTypes.length == 0) continue;
                final List<Integer> addMsgArgIndexes = new ArrayList<>();
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (parseApi.isLikelyAddMsgClass(parameterTypes[i])) addMsgArgIndexes.add(i);
                }
                if (addMsgArgIndexes.isEmpty()) continue;
                HookRegistry.get().hook(method, new AddMessageHooker(this, addMsgArgIndexes));
                count++;
            }
        }
        hooked = count > 0;
        log("AddMsg事件Hook: " + count);
        installPatMsgHook();
    }

    public EventBus.Subscription subscribeMessage(EventHandler<Events.MessageReceived> handler) {
        return eventBus != null ? eventBus.subscribe(Events.MessageReceived.class, handler)
                : null;
    }

    public EventBus.Subscription subscribeRedPacket(EventHandler<Events.RedPacketDetected> handler) {
        return eventBus != null ? eventBus.subscribe(Events.RedPacketDetected.class, handler)
                : null;
    }

    public EventBus.Subscription subscribePat(EventHandler<Events.PatDetected> handler) {
        return eventBus != null ? eventBus.subscribe(Events.PatDetected.class, handler)
                : null;
    }

    public void unsubscribe(EventBus.Subscription subscription) {
        if (eventBus != null) eventBus.unsubscribe(subscription);
    }

    private void dispatchAddMsg(Object addMsg, AddMsgTiming timing) {
        try {
            long stageStarted = diagnosticNow();
            String selfWxId;
            try {
                selfWxId = accountApi != null ? accountApi.selfWxId() : "";
            } finally {
                timing.accountMs += diagnosticElapsed(stageStarted);
            }

            stageStarted = diagnosticNow();
            WeChatParsedMessage parsed;
            try {
                parsed = parseApi.parseAddMsg(addMsg, selfWxId);
            } finally {
                timing.parseMs += diagnosticElapsed(stageStarted);
            }
            if (parsed == null) return;
            timing.type = parsed.type;
            timing.contentLength = parsed.content != null ? parsed.content.length() : 0;

            stageStarted = diagnosticNow();
            try {
                eventBus.post(new Events.MessageReceived(
                        parsed.xml, parsed.sender, parsed.talker, parsed.content,
                        String.valueOf(parsed.type), parsed.createTimeSeconds, parsed.msgSvrId,
                        parsed.msgSource, parsed.selfWxId));
            } finally {
                timing.messagePostMs += diagnosticElapsed(stageStarted);
            }
            if (parsed.redPacketMessage) {
                stageStarted = diagnosticNow();
                try {
                    eventBus.post(new Events.RedPacketDetected(
                            "WeChatMessageEventApi",
                            parsed.xml,
                            parsed.sender,
                            parsed.talker,
                            parsed.nativeUrl,
                            parsed.exclusiveRecvUser));
                } finally {
                    timing.redPacketPostMs += diagnosticElapsed(stageStarted);
                }
            }
        } catch (Throwable e) {
            log("派发消息事件失败: " + e.getMessage());
        }
    }

    private void reportAddMsgTiming(AddMsgTiming timing) {
        if (timing == null || timing.totalMs < 20L) return;
        try {
            if (!h.Hchat.utils.HLog.isCaptureEnabled() || !claimDiagnosticLog("message.addmsg")) return;
            StringBuilder detail = new StringBuilder(128)
                    .append("account=").append(timing.accountMs).append("ms")
                    .append(" parse=").append(timing.parseMs).append("ms")
                    .append(" message_post=").append(timing.messagePostMs).append("ms")
                    .append(" red_packet_post=").append(timing.redPacketPostMs).append("ms")
                    .append(" total=").append(timing.totalMs).append("ms")
                    .append(" thread=").append(Thread.currentThread().getName());
            if (timing.type >= 0) detail.append(" type=").append(timing.type);
            if (timing.contentLength >= 0) detail.append(" content_length=").append(timing.contentLength);
            h.Hchat.utils.SlowPathLog.report(
                    "message.addmsg", timing.totalMs, 20L, "AddMsg 事件派发", detail.toString());
        } catch (Throwable ignored) {
            // Diagnostics must never change message delivery.
        }
    }

    private boolean claimDiagnosticLog(String key) {
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            Long previous = recentDiagnosticLogs.putIfAbsent(key, now);
            if (previous == null) return true;
            if (now - previous < 10_000L) return false;
            return recentDiagnosticLogs.replace(key, previous, now);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long diagnosticNow() {
        try {
            return h.Hchat.utils.SlowPathLog.now();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static long diagnosticElapsed(long startNanos) {
        if (startNanos < 0L) return 0L;
        try {
            return h.Hchat.utils.SlowPathLog.elapsedSince(startNanos);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static final class AddMsgTiming {
        private long accountMs;
        private long parseMs;
        private long messagePostMs;
        private long redPacketPostMs;
        private long totalMs;
        private int type = -1;
        private int contentLength = -1;
    }

    private void installPatMsgHook() {
        if (patHooked || dexFinder == null || dexFinder.patDisplayTemplateMethod == null
                || eventBus == null) {
            return;
        }
        HookRegistry.get().hook(dexFinder.patDisplayTemplateMethod, new PatMessageHooker(this));
        patHooked = true;
        log("拍一拍事件Hook: " + dexFinder.patDisplayTemplateMethod.getDeclaringClass().getName()
                + "#" + dexFinder.patDisplayTemplateMethod.getName());
    }

    private void dispatchPatMsg(Object patMsg, Object talkerArg) {
        try {
            if (patMsg == null) return;
            String fromUser = readFieldString(patMsg, "d");
            String pattedUser = readFieldString(patMsg, "e");
            String template = readFieldString(patMsg, "f");
            long createTime = readPatCreateTime(patMsg);
            long svrId = readPatSvrId(patMsg);
            if (TextUtils.isEmpty(fromUser) || TextUtils.isEmpty(pattedUser)) return;
            if (!isRecentPat(createTime)) return;
            String key = fromUser + ":" + pattedUser + ":" + svrId + ":" + createTime + ":" + template;
            long now = System.currentTimeMillis();
            Long previous = recentPatEvents.put(key, now);
            if (previous != null && now - previous < 300000L) return;
            cleanupPatEvents(now);
            String talker = talkerArg instanceof String ? (String) talkerArg : "";
            eventBus.post(new Events.PatDetected(
                    fromUser,
                    pattedUser,
                    template,
                    talker,
                    createTime,
                    svrId));
        } catch (Throwable e) {
            log("派发拍一拍事件失败: " + e.getMessage());
        }
    }

    private long readPatCreateTime(Object patMsg) {
        if (hasField(patMsg.getClass(), "g")) return readFieldLong(patMsg, "g");
        return readFieldLong(patMsg, "i");
    }

    private long readPatSvrId(Object patMsg) {
        if (hasField(patMsg.getClass(), "g")) return readFieldLong(patMsg, "i");
        return readFieldLong(patMsg, "n");
    }

    private boolean isRecentPat(long createTime) {
        if (createTime <= 0L) return true;
        long millis = createTime > 100000000000L ? createTime : createTime * 1000L;
        return Math.abs(System.currentTimeMillis() - millis) <= 300000L;
    }

    private String readFieldString(Object obj, String field) {
        Object value = KavaReflector.readField(obj, field);
        return value != null ? String.valueOf(value) : "";
    }

    private long readFieldLong(Object obj, String field) {
        Object value = KavaReflector.readField(obj, field);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private boolean hasField(Class<?> clazz, String name) {
        return KavaReflector.findFieldRecursive(clazz, name) != null;
    }

    private void cleanupPatEvents(long now) {
        if (recentPatEvents.size() < 128) return;
        recentPatEvents.entrySet().removeIf(entry -> now - entry.getValue() > 300000L);
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatMessageEventApi] " + message);
    }

    private static final class AddMessageHooker implements XposedInterface.Hooker {
        private final WeChatMessageEventApi owner;
        private final List<Integer> indexes;
        private AddMessageHooker(WeChatMessageEventApi owner, List<Integer> indexes) {
            this.owner = owner; this.indexes = new ArrayList<>(indexes);
        }
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            List<Object> args = chain.getArgs();
            // 保持现有顺序：先执行宿主，再在当前线程同步派发模块事件。
            // 后置同步回调仍会占用当前线程，不能仅凭位置在 proceed 后就断言不影响上屏；
            // 各段耗时只测量模块派发，不包含宿主方法耗时。
            Object result = chain.proceed();
            long started = diagnosticNow();
            AddMsgTiming timing = new AddMsgTiming();
            try {
                for (int index : indexes) {
                    if (index >= 0 && index < args.size() && args.get(index) != null) {
                        owner.dispatchAddMsg(args.get(index), timing);
                    }
                }
            } finally {
                timing.totalMs = diagnosticElapsed(started);
                owner.reportAddMsgTiming(timing);
            }
            return result;
        }
    }

    private static final class PatMessageHooker implements XposedInterface.Hooker {
        private final WeChatMessageEventApi owner;
        private PatMessageHooker(WeChatMessageEventApi owner) { this.owner = owner; }
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            List<Object> args = chain.getArgs();
            if (!args.isEmpty()) owner.dispatchPatMsg(args.get(0), args.size() > 1 ? args.get(1) : null);
            return chain.proceed();
        }
    }

}
