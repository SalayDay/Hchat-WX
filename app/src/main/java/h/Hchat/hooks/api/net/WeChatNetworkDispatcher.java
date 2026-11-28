package h.Hchat.hooks.api.net;

import android.app.Activity;
import android.content.Context;

import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.utils.HLog;
import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;

/**
 * 微信网络发包器缓存与发包封装。
 * 负责 Hook 网络队列方法以缓存 dispatcher 实例，并提供统一 send()。
 */
public class WeChatNetworkDispatcher {
    public interface Logger {
        void log(String message);
    }

    /**
     * 进程级发送守卫：在每个被 hook 的网络队列方法进入前评估请求，
     * 命中即整次丢弃（不发给微信服务器）。用于反检测功能拦截风控/埋点上报 CGI。
     *
     * 守卫注册与 hook 安装解耦（静态表 + volatile 读），无论谁先装、谁后装都生效；
     * 守卫抛异常按"放行"处理（fail-open），绝不因为守卫拖垮正常收发。
     */
    public interface SendGuard {
        boolean shouldBlock(Object request, String uri);
    }

    private static final CopyOnWriteArrayList<SendGuard> SEND_GUARDS = new CopyOnWriteArrayList<>();
    /** URI 提取方法缓存：类名 -> getUri()，含负缓存（反射扫不到的类也只扫一次）。 */
    private static final ConcurrentHashMap<String, Method> URI_METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> URI_METHOD_MISSING = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final AtomicBoolean URI_DIAG_LOGGED = new AtomicBoolean(false);
    private static final AtomicLong GUARD_BLOCK_COUNTER = new AtomicLong();

    /** 注册进程级发送守卫；同一实例只注册一次。 */
    public static void registerSendGuard(SendGuard guard) {
        if (guard != null) SEND_GUARDS.addIfAbsent(guard);
    }

    /** 注销发送守卫（功能销毁时调用）。 */
    public static void unregisterSendGuard(SendGuard guard) {
        if (guard != null) SEND_GUARDS.remove(guard);
    }

    /**
     * 在队列 hook 回调里评估守卫。返回 true 表示该请求应被丢弃。
     * 丢弃时调用方负责用 param.setResult() 给出与返回类型匹配的"失败"值。
     */
    private static boolean shouldGuardBlock(Object[] args, String[] blockedUriOut) {
        try {
            if (args == null || args.length == 0 || args[0] == null || SEND_GUARDS.isEmpty()) return false;
            Object request = args[0];
            String uri = resolveRequestUri(request);
            if (uri == null) return false;
            for (SendGuard guard : SEND_GUARDS) {
                try {
                    if (guard.shouldBlock(request, uri)) {
                        blockedUriOut[0] = uri;
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 从请求对象上提取 CGI URI（getUri():String），带缓存；提取不到返回 null（放行）。 */
    private static String resolveRequestUri(Object request) {
        try {
            Class<?> clazz = request.getClass();
            String key = clazz.getName();
            Method uriMethod = URI_METHOD_CACHE.get(key);
            if (uriMethod == null) {
                if (URI_METHOD_MISSING.contains(key)) return null;
                uriMethod = KavaReflector.findMethodRecursive(clazz, "getUri");
                if (uriMethod != null && uriMethod.getParameterTypes().length == 0
                        && uriMethod.getReturnType() == String.class) {
                    URI_METHOD_CACHE.put(key, uriMethod);
                } else {
                    URI_METHOD_MISSING.add(key);
                    return null;
                }
            }
            Object uri = KavaReflector.invoke(uriMethod, request);
            if (uri instanceof String && !((String) uri).isEmpty()) {
                if (URI_DIAG_LOGGED.compareAndSet(false, true)) {
                    logStatic("风控上报守卫URI提取可用: " + key + " -> " + uri);
                }
                return (String) uri;
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void logStatic(String message) {
        HLog.e("[Hchat:NetworkDispatcher] " + message);
    }

    private final Logger logger;
    /** Hook 回调线程写、任意线程读，必须 volatile 才能保证可见性与发布顺序。 */
    private volatile Object dispatcherInstance;
    private volatile Method dispatcherMethod;
    private volatile int dispatcherArgCount = 1;
    private List<Class<?>> queueCandidateClasses = Collections.emptyList();
    private final Set<Method> hookedQueueMethods = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public WeChatNetworkDispatcher(Logger logger) {
        this.logger = logger;
    }

    public void hookNetworkQueue(Class<?> netQueueClass) {
        hookNetworkQueue(netQueueClass, Collections.emptyList());
    }

    public synchronized void hookNetworkQueue(Class<?> netQueueClass, List<Class<?>> candidates) {
        LinkedHashSet<Class<?>> classes = new LinkedHashSet<>();
        if (netQueueClass != null) classes.add(netQueueClass);
        if (candidates != null) classes.addAll(candidates);
        queueCandidateClasses = new ArrayList<>(classes);
        if (classes.isEmpty()) {
            log("网络队列类为null，且没有候选类");
            return;
        }
        int hooked = 0;
        for (Class<?> queueClass : classes) {
            if (queueClass == null) continue;
            try {
                for (Method method : KavaReflector.declaredMethods(queueClass)) {
                    if (!isSendMethodCandidate(method, null) || !hookedQueueMethods.add(method)) continue;
                    try {
                        HookRegistry.get().hook(method, new NetworkSendHooker(this, method));
                        hooked++;
                    } catch (Throwable hookError) {
                        hookedQueueMethods.remove(method);
                        log("网络队列方法Hook失败: " + method.toGenericString() + " " + hookError.getMessage());
                    }
                }
            } catch (Throwable e) {
                log("网络队列类扫描失败: " + queueClass.getName() + " " + e.getMessage());
            }
        }
        if (hooked == 0 && hookedQueueMethods.isEmpty()) {
            log("网络队列候选类无合适方法可Hook: " + classes.size());
        } else if (hooked > 0) {
            log("网络队列补装Hook: classes=" + classes.size() + " methods=" + hooked);
        }
    }

    public boolean send(Object request) {
        if (request == null) return false;
        Object realRequest = unwrapRequest(request);
        if (realRequest == null) return false;

        if (dispatcherInstance == null || dispatcherMethod == null) {
            ensureReady(realRequest.getClass());
        } else if (methodPriority(dispatcherMethod) < 100) {
            tryCacheFromObject(dispatcherInstance, realRequest.getClass());
        }

        if (dispatcherInstance != null && dispatcherMethod != null) {
            try {
                Object result;
                if (dispatcherArgCount == 2) {
                    result = KavaReflector.invokeOrThrow(dispatcherMethod, dispatcherInstance, realRequest, 0);
                } else {
                    result = KavaReflector.invokeOrThrow(dispatcherMethod, dispatcherInstance, realRequest);
                }
                return normalizeSendResult(result, dispatcherMethod);
            } catch (Throwable invokeErr) {
                try {
                    Method fallbackMethod = KavaReflector.findCompatibleMethod(
                            dispatcherInstance.getClass(), dispatcherMethod.getName(),
                            dispatcherArgCount == 2
                                    ? new Object[]{realRequest, Integer.valueOf(0)}
                                    : new Object[]{realRequest});
                    if (fallbackMethod == null) throw invokeErr;
                    if (dispatcherArgCount == 2) {
                        Object result = KavaReflector.invokeOrThrow(fallbackMethod,
                                dispatcherInstance, realRequest, 0);
                        return normalizeSendResult(result, fallbackMethod);
                    } else {
                        Object result = KavaReflector.invokeOrThrow(fallbackMethod,
                                dispatcherInstance, realRequest);
                        return normalizeSendResult(result, fallbackMethod);
                    }
                } catch (Throwable e) {
                    log("sendNetworkRequest 异常: " + e.getMessage()
                            + " method=" + dispatcherMethod.toGenericString());
                }
            }
        } else {
            log("sendNetworkRequest 失败: dispatcher=" + (dispatcherInstance != null)
                    + " method=" + (dispatcherMethod != null));
        }
        return false;
    }

    private boolean normalizeSendResult(Object result, Method method) {
        if (result instanceof Boolean) return (Boolean) result;
        if (result instanceof Number) return ((Number) result).intValue() >= 0;
        return method != null && method.getReturnType() == void.class;
    }

    public boolean isReady() {
        return dispatcherInstance != null && dispatcherMethod != null;
    }

    public boolean hasDispatcherInstance() {
        return dispatcherInstance != null;
    }

    public boolean hasDispatcherMethod() {
        return dispatcherMethod != null;
    }

    private boolean ensureReady(Class<?> requestClass) {
        if (requestClass == null) return false;
        if (dispatcherInstance != null && dispatcherMethod != null) return true;
        return tryCacheFromClassStatics(queueCandidateClasses, requestClass);
    }

    private boolean isSendMethodCandidate(Method method, Class<?> requestClass) {
        try {
            if (method == null) return false;
            String name = method.getName();
            if ("equals".equals(name) || "hashCode".equals(name) || "toString".equals(name)
                    || "wait".equals(name) || "notify".equals(name) || "notifyAll".equals(name)) {
                return false;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params == null || (params.length != 1 && params.length != 2)) return false;
            if (params.length == 2 && params[1] != int.class && params[1] != Integer.class) return false;
            Class<?> first = params[0];
            if (first == null || first.isPrimitive() || first == String.class || first == Object.class) return false;
            if (requestClass != null && !first.isAssignableFrom(requestClass)) return false;
            if (requestClass == null && !isNetworkRequestType(first)) return false;
            Class<?> rt = method.getReturnType();
            return rt == boolean.class || rt == Boolean.class || rt == int.class || rt == void.class;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isNetworkRequestType(Class<?> type) {
        if (type == null || type.isPrimitive()) return false;
        Method getType = KavaReflector.findMethodRecursive(type, "getType");
        return getType != null
                && getType.getParameterTypes().length == 0
                && getType.getReturnType() == int.class;
    }

    private Object unwrapRequest(Object request) {
        if (request instanceof Object[]) {
            Object[] array = (Object[]) request;
            if (array.length == 1) return array[0];
        }
        return request;
    }

    private boolean tryCacheFromObject(Object candidate, Class<?> requestClass) {
        if (candidate == null || requestClass == null) return false;
        try {
            Method best = null;
            for (Method method : KavaReflector.declaredMethods(candidate.getClass())) {
                if (isSendMethodCandidate(method, requestClass)) {
                    if (best == null || methodPriority(method) > methodPriority(best)) {
                        best = method;
                    }
                }
            }
            if (best != null) {
                cacheDispatcher(candidate, best, "网络发包器方法匹配");
                return true;
            }
        } catch (Throwable ignored) {}

        try {
            for (Field field : KavaReflector.declaredFields(candidate.getClass())) {
                Object value = KavaReflector.readField(field, candidate);
                if (value == null || value == candidate) continue;
                try {
                    Method best = null;
                    for (Method method : KavaReflector.declaredMethods(value.getClass())) {
                        if (isSendMethodCandidate(method, requestClass)) {
                            if (best == null || methodPriority(method) > methodPriority(best)) {
                                best = method;
                            }
                        }
                    }
                    if (best != null) {
                        cacheDispatcher(value, best, "网络发包器方法匹配");
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private int methodPriority(Method method) {
        if (method == null) return 0;
        int score = 0;
        String name = method.getName();
        if ("g".equals(name) || "f".equals(name)) score += 80;
        if ("h".equals(name)) score += 70;
        if ("doScene".equals(name) || "doSceneImp".equals(name)) score += 80;
        if ("d".equals(name) || "cancel".equals(name)) score -= 100;
        Class<?> rt = method.getReturnType();
        if (rt == boolean.class || rt == Boolean.class) score += 100;
        if (rt == int.class || rt == Integer.class) score += 20;
        if (rt == void.class) score -= 50;
        if (method.getParameterCount() == 2) score += 10;
        return score;
    }

    private boolean tryCacheFromObjectDeep(Object candidate, Class<?> requestClass,
                                           int depth, Set<Object> visited) {
        if (candidate == null || requestClass == null) return false;
        try {
            if (visited != null) {
                if (visited.contains(candidate)) return false;
                visited.add(candidate);
            }
        } catch (Throwable ignored) {}
        if (tryCacheFromObject(candidate, requestClass)) return true;
        if (depth <= 0) return false;

        try {
            Class<?> cur = candidate.getClass();
            while (cur != null && cur != Object.class) {
                for (Field field : KavaReflector.declaredFields(cur)) {
                    try {
                        Object value = KavaReflector.readField(field, candidate);
                        if (shouldSkipDeepScan(value)) continue;
                        if (tryCacheFromObjectDeep(value, requestClass, depth - 1, visited)) return true;
                    } catch (Throwable ignored) {}
                }
                cur = cur.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean tryCacheFromClassStatics(List<Class<?>> classes, Class<?> requestClass) {
        if (classes == null || requestClass == null) return false;
        for (Class<?> clazz : classes) {
            if (tryCacheFromClassStatics(clazz, requestClass)) return true;
        }
        return false;
    }

    private boolean tryCacheFromClassStatics(Class<?> clazz, Class<?> requestClass) {
        if (clazz == null || requestClass == null) return false;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            Class<?> cur = clazz;
            while (cur != null && cur != Object.class) {
                for (Field field : KavaReflector.declaredFields(cur)) {
                    try {
                        if (!KavaReflector.isStatic(field)) continue;
                        Object value = KavaReflector.readField(field, (Object) null);
                        if (shouldSkipDeepScan(value)) continue;
                        if (tryCacheFromObjectDeep(value, requestClass, 3, visited)) {
                            log("网络发包器主动缓存成功: " + clazz.getName());
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
                cur = cur.getSuperclass();
            }
        } catch (Throwable ignored) {}

        try {
            for (Method method : KavaReflector.declaredMethods(clazz)) {
                try {
                    if (!KavaReflector.isStatic(method)) continue;
                    if (method.getParameterTypes().length != 0) continue;
                    Class<?> rt = method.getReturnType();
                    if (rt == null || rt == void.class || rt.isPrimitive() || rt == String.class) continue;
                    Object value = KavaReflector.invoke(method, null);
                    if (shouldSkipDeepScan(value)) continue;
                    if (tryCacheFromObjectDeep(value, requestClass, 3, visited)) {
                        log("网络发包器主动缓存成功: " + clazz.getName() + "#" + method.getName());
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean shouldSkipDeepScan(Object value) {
        if (value == null) return true;
        try {
            Class<?> clazz = value.getClass();
            if (clazz.isPrimitive() || clazz.isEnum() || clazz.isArray()) return true;
            if (value instanceof String || value instanceof Number || value instanceof Boolean
                    || value instanceof Character || value instanceof Context || value instanceof Activity
                    || value instanceof Class || value instanceof Method || value instanceof Field) {
                return true;
            }
            String name = clazz.getName();
            return name.startsWith("java.")
                    || name.startsWith("android.view.")
                    || name.startsWith("android.widget.")
                    || name.startsWith("android.graphics.");
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void cacheDispatcher(Object instance, Method method, String reason) {
        if (instance == null || method == null) return;
        // 发布顺序有讲究：读者以 dispatcherInstance != null 作为"已就绪"标志。
        // 先把 argCount / method 写好，最后才写 instance，配合 volatile 写屏障，
        // 保证读到 instance 的线程一定能看到配套的 method 与参数个数。
        // 原来先写 instance 再写 argCount，并发首发时可能拿到 count=1 的默认值，
        // 用错误的参数个数反射调用 → 发送间歇性失败。
        dispatcherArgCount = method.getParameterTypes().length;
        dispatcherMethod = method;
        dispatcherInstance = instance;
        log(reason + ": " + instance.getClass().getName() + "#" + method.getName());
    }

    private void log(String message) {
        if (logger != null) logger.log(message);
    }

    private static final class NetworkSendHooker implements XposedInterface.Hooker {
        private final WeChatNetworkDispatcher owner;
        private final Method method;
        private NetworkSendHooker(WeChatNetworkDispatcher owner, Method method) {
            this.owner = owner; this.method = method;
        }
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (owner.dispatcherInstance == null
                    || owner.methodPriority(method) > owner.methodPriority(owner.dispatcherMethod)) {
                owner.cacheDispatcher(chain.getThisObject(), method, "网络发包器已缓存");
            }
            if (!SEND_GUARDS.isEmpty()) {
                String[] blockedUriOut = new String[1];
                if (owner.shouldGuardBlock(chain.getArgs().toArray(), blockedUriOut)) {
                    long blockedCount = GUARD_BLOCK_COUNTER.incrementAndGet();
                    if (blockedCount <= 20 || blockedCount % 200 == 0) {
                        owner.log("已拦截风控上报 CGI(累计" + blockedCount + "次): " + blockedUriOut[0]);
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class || returnType == Boolean.class) return Boolean.FALSE;
                    if (returnType == int.class || returnType == Integer.class) return Integer.valueOf(0);
                    return null;
                }
            }
            return chain.proceed();
        }
    }

}
