package h.Hchat.hooks.api.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.model.DatabaseChange;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;

/**
 * 微信 SqliteDB wrapper 变更与查询监听 API。
 */
public final class WeChatDatabaseListenerApi {
    public interface Logger {
        void log(String message);
    }

    public interface Listener {
        void onDatabaseChanged(DatabaseChange change);
    }

    public interface QueryInterceptor {
        String onQuery(String sql);
    }

    public final class Subscription {
        private final Listener listener;
        private volatile boolean active = true;

        private Subscription(Listener listener) {
            this.listener = listener;
        }

        public void unsubscribe() {
            if (!active) return;
            active = false;
            listeners.remove(listener);
        }

        public boolean isActive() {
            return active;
        }
    }

    public final class QuerySubscription {
        private final QueryInterceptor interceptor;
        private volatile boolean active = true;

        private QuerySubscription(QueryInterceptor interceptor) {
            this.interceptor = interceptor;
        }

        public void unsubscribe() {
            if (!active) return;
            active = false;
            queryInterceptors.remove(interceptor);
        }

        public boolean isActive() {
            return active;
        }
    }

    private static final String MESSAGE_TOKEN = "message";
    private static final String MSG_SOURCE_OPEN = "<msgsource";
    private static final String MSG_SOURCE_CLOSE = "</msgsource>";

    private final DexFinder dexFinder;
    private final ClassLoader classLoader;
    private final Logger logger;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<QueryInterceptor> queryInterceptors = new CopyOnWriteArrayList<>();
    private final Set<Method> hookedMethods = ConcurrentHashMap.newKeySet();
    private final Set<Method> hookedWrapperMutationMethods = ConcurrentHashMap.newKeySet();
    private final Set<Method> hookedWrapperInsertMethods = ConcurrentHashMap.newKeySet();
    // One logical operation can traverse wrapper, WCDB and Android SQLite hooks.
    //
    // 深度计数器用单元素 int[] 而不是 ThreadLocal<Integer>：自动装箱会把每一次
    // insert/update/rawQuery 都变成一个 Integer 对象分配。微信的写库频率是每秒
    // 数十到数百次，全天累计几十万次分配，直接把 GC 压力顶上去（GC 就是耗电）。
    // 数组元素读写零分配；线程数有限，常驻的 int[] 可以忽略。
    private final ThreadLocal<int[]> mutationDepth = ThreadLocal.withInitial(() -> new int[1]);
    private final ThreadLocal<int[]> queryDepth = ThreadLocal.withInitial(() -> new int[1]);
    private volatile boolean installed;
    private volatile int hookedMethodCount;
    private volatile Class<?> hookedWrapperClass;

    public WeChatDatabaseListenerApi(DexFinder dexFinder, Logger logger) {
        this(dexFinder, null, logger);
    }

    public WeChatDatabaseListenerApi(DexFinder dexFinder, ClassLoader classLoader, Logger logger) {
        this.dexFinder = dexFinder;
        this.classLoader = classLoader;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return (dexFinder != null && dexFinder.sqliteDbWrapperClass != null) || classLoader != null;
    }

    public boolean isInstalled() {
        return installed;
    }

    public int hookedMethodCount() {
        return hookedMethodCount;
    }

    public int hookedWrapperMutationMethodCount() {
        return hookedWrapperMutationMethods.size();
    }

    public int hookedWrapperInsertMethodCount() {
        return hookedWrapperInsertMethods.size();
    }

    public boolean isOperational() {
        Class<?> wrapperClass = dexFinder != null ? dexFinder.sqliteDbWrapperClass : null;
        return wrapperClass != null
                && wrapperClass == hookedWrapperClass
                && !hookedWrapperInsertMethods.isEmpty();
    }

    public Subscription subscribe(Listener listener) {
        if (listener == null) return null;
        listeners.addIfAbsent(listener);
        return new Subscription(listener);
    }

    public void unsubscribe(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    public QuerySubscription subscribeQuery(QueryInterceptor interceptor) {
        if (interceptor == null) return null;
        queryInterceptors.addIfAbsent(interceptor);
        return new QuerySubscription(interceptor);
    }

    public void unsubscribeQuery(QueryInterceptor interceptor) {
        if (interceptor != null) queryInterceptors.remove(interceptor);
    }

    public synchronized void install() {
        if (!isAvailable()) return;
        Class<?> wrapperClass = dexFinder != null ? dexFinder.sqliteDbWrapperClass : null;
        if (isOperational()) return;
        if (wrapperClass != hookedWrapperClass) {
            hookedWrapperMutationMethods.clear();
            hookedWrapperInsertMethods.clear();
        }
        int count = 0;
        Class<?> cur = wrapperClass;
        while (cur != null && cur != Object.class) {
            count += hookDatabaseClass(cur, true);
            cur = cur.getSuperclass();
        }
        count += hookNamedDatabaseClass("com.tencent.wcdb.database.SQLiteDatabase");
        count += hookNamedDatabaseClass("com.tencent.wcdb.compat.SQLiteDatabase");
        count += hookNamedDatabaseClass("android.database.sqlite.SQLiteDatabase");
        if (wrapperClass != null) hookedWrapperClass = wrapperClass;
        hookedMethodCount = hookedMethods.size();
        installed = isOperational();
        log("数据库变更监听Hook: wrapper=" + (wrapperClass != null ? wrapperClass.getName() : "null")
                + " added=" + count + " total=" + hookedMethodCount
                + " wrapperMutations=" + hookedWrapperMutationMethods.size()
                + " wrapperInserts=" + hookedWrapperInsertMethods.size()
                + " operational=" + installed);
    }

    private String operationOf(Method method, boolean allowObfuscatedWrapperMethod) {
        if (method == null) return null;
        Class<?>[] params = method.getParameterTypes();
        Class<?> returnType = method.getReturnType();
        String name = method.getName();
        if (returnType == long.class && hasContentValuesArg(params)
                && ("insert".equals(name)
                || "insertWithOnConflict".equals(name)
                || "replace".equals(name)
                || "replaceOrThrow".equals(name))) {
            return DatabaseChange.INSERT;
        }
        if (returnType == int.class && hasContentValuesArg(params)
                && ("update".equals(name) || "updateWithOnConflict".equals(name))) {
            return DatabaseChange.UPDATE;
        }
        if ("delete".equals(method.getName())
                && returnType == int.class
                && params.length == 3
                && params[0] == String.class
                && params[1] == String.class
                && params[2] == String[].class) {
            return DatabaseChange.DELETE;
        }
        if (!allowObfuscatedWrapperMethod) return null;
        if (returnType == long.class && isWrapperInsertSignature(params)) {
            return DatabaseChange.INSERT;
        }
        if (returnType == int.class && isWrapperUpdateSignature(params)) {
            return DatabaseChange.UPDATE;
        }
        if (returnType == int.class && isWrapperDeleteSignature(params)) {
            return DatabaseChange.DELETE;
        }
        return null;
    }

    private boolean isWrapperInsertSignature(Class<?>[] params) {
        return params != null
                && params.length == 3
                && params[0] == String.class
                && params[1] == String.class
                && ContentValues.class.isAssignableFrom(params[2]);
    }

    private boolean isWrapperUpdateSignature(Class<?>[] params) {
        return params != null
                && params.length == 4
                && params[0] == String.class
                && ContentValues.class.isAssignableFrom(params[1])
                && params[2] == String.class
                && params[3] == String[].class;
    }

    private boolean isWrapperDeleteSignature(Class<?>[] params) {
        return params != null
                && params.length == 3
                && params[0] == String.class
                && params[1] == String.class
                && params[2] == String[].class;
    }

    private int hookNamedDatabaseClass(String className) {
        if (classLoader == null) return 0;
        try {
            return hookDatabaseClass(KavaReflector.loadClass(className, classLoader), false);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int hookDatabaseClass(Class<?> dbClass, boolean allowObfuscatedWrapperMethod) {
        if (dbClass == null) return 0;
        int count = 0;
        for (Method method : KavaReflector.declaredMethods(dbClass)) {
            String operation = operationOf(method, allowObfuscatedWrapperMethod);
            boolean query = isRawQueryMethod(method);
            if (operation == null && !query) continue;
            if (hookedMethods.contains(method)) {
                if (allowObfuscatedWrapperMethod && operation != null) {
                    hookedWrapperMutationMethods.add(method);
                    if (DatabaseChange.INSERT.equals(operation)) {
                        hookedWrapperInsertMethods.add(method);
                    }
                }
                continue;
            }
            try {
                XposedInterface.Hooker hooker = query
                        ? new DatabaseQueryHooker(this)
                        : new DatabaseMutationHooker(this, operation, method);
                HookRegistry.get().hook(method, hooker, XposedInterface.PRIORITY_LOWEST);
                hookedMethods.add(method);
                if (allowObfuscatedWrapperMethod && operation != null) {
                    hookedWrapperMutationMethods.add(method);
                    if (DatabaseChange.INSERT.equals(operation)) {
                        hookedWrapperInsertMethods.add(method);
                    }
                }
                count++;
            } catch (Throwable error) {
                log("数据库方法Hook失败: " + method + " " + error.getMessage());
            }
        }
        return count;
    }

    private boolean isRawQueryMethod(Method method) {
        if (method == null || !Cursor.class.isAssignableFrom(method.getReturnType())) return false;
        String name = method.getName();
        if (!"rawQuery".equals(name) && !"rawQueryWithFactory".equals(name)) return false;
        for (Class<?> type : method.getParameterTypes()) {
            if (type == String.class) return true;
        }
        return false;
    }

    private boolean enterQuery() {
        int[] depth = queryDepth.get();
        int current = depth[0];
        depth[0] = current + 1;
        return current == 0;
    }

    private void leaveQuery() {
        int[] depth = queryDepth.get();
        if (depth[0] > 0) depth[0]--;
    }

    private void transformQuery(Object[] args) {
        if (args == null || queryInterceptors.isEmpty()) return;
        int sqlIndex = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String) {
                sqlIndex = i;
                break;
            }
        }
        if (sqlIndex < 0) return;
        String sql = (String) args[sqlIndex];
        if (TextUtils.isEmpty(sql)) return;
        String rewritten = sql;
        for (QueryInterceptor interceptor : queryInterceptors) {
            try {
                String next = interceptor.onQuery(rewritten);
                if (next != null) rewritten = next;
            } catch (Throwable error) {
                log("数据库查询拦截器异常: " + error.getMessage());
            }
        }
        if (!rewritten.equals(sql)) args[sqlIndex] = rewritten;
    }

    private void enterMutation() {
        mutationDepth.get()[0]++;
    }

    private boolean leaveMutation() {
        int[] depth = mutationDepth.get();
        if (depth[0] <= 1) {
            depth[0] = 0;
            return true;
        }
        depth[0]--;
        return false;
    }

    private void normalizeLegacyMsgSource(String operation, Object database, Object[] args) {
        if (args == null || args.length == 0) return;
        if (!DatabaseChange.INSERT.equals(operation) && !DatabaseChange.UPDATE.equals(operation)) return;
        // 这个方法挂在全进程每一次 insert/update 上。原来的实现无论写的是哪张表，
        // 都要先取 ContentValues、再对表名 toLowerCase()（每次都分配一个 String）。
        // 微信绝大部分写入都是 rcontact、各类缓存/统计表，跟消息毫无关系。
        // 这里先用零分配的方式判掉表名，绝大多数数据库写入到此为止。
        String table = args[0] instanceof String ? (String) args[0] : tableArg(args);
        if (!looksLikeMessageTable(table)) return;
        ContentValues values = contentValuesArg(args);
        if (values == null) return;
        if (!isMessageTable(table, values)) return;
        if (!values.containsKey("msgSource")) return;
        String current = contentValueString(values, "msgSource");
        if (!TextUtils.isEmpty(current)) return;
        String source = msgSourceFromLvBuffer(values);
        if (TextUtils.isEmpty(source)) return;
        values.put("msgSource", source);
    }

    private boolean isMessageTable(String table, ContentValues values) {
        if (!looksLikeMessageTable(table)) return false;
        return values != null
                && (values.containsKey("msgSource")
                || values.containsKey("lvbuffer")
                || values.containsKey("msgId")
                || values.containsKey("msgSvrId"));
    }

    /**
     * 表名是否为消息表：{@code message} / {@code message_*} / {@code *_message}。
     * 用逐字符比较代替 {@code toLowerCase()}，避免每次写库分配一个临时字符串。
     */
    private static boolean looksLikeMessageTable(String table) {
        if (table == null) return false;
        int length = table.length();
        if (length < 7) return false;
        if (length == 7) return asciiRegionMatches(table, 0, "message");
        if (asciiRegionMatches(table, 0, "message_")) return true;
        if (asciiRegionMatches(table, length - 8, "_message")) return true;
        return false;
    }

    /** 以 ASCII 不区分大小写比较 {@code expected}（必须为小写）与 {@code source} 的指定区段。 */
    private static boolean asciiRegionMatches(String source, int offset, String expected) {
        int length = expected.length();
        if (offset < 0 || offset + length > source.length()) return false;
        for (int i = 0; i < length; i++) {
            char actual = source.charAt(offset + i);
            char wanted = expected.charAt(i);
            if (actual == wanted) continue;
            // 只有 A-Z 需要折叠，其他字符必须完全一致。
            if (actual < 'A' || actual > 'Z') return false;
            if ((char) (actual + 32) != wanted) return false;
        }
        return true;
    }

    private String msgSourceFromLvBuffer(ContentValues values) {
        if (values == null || !values.containsKey("lvbuffer")) return "";
        try {
            byte[] bytes = values.getAsByteArray("lvbuffer");
            if (bytes != null && bytes.length > 0 && containsAsciiTag(bytes, "<msgsource")) {
                String source = extractMsgSource(new String(bytes, StandardCharsets.UTF_8));
                if (!TextUtils.isEmpty(source)) return source;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object raw = values.get("lvbuffer");
            return raw != null ? extractMsgSource(String.valueOf(raw)) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 在原始字节里找 ASCII 片段。lvbuffer 是消息的 protobuf 原始数据，动辄几十 KB；
     * 先按字节确认存在 {@code <msgsource}，就能省掉一次全量 String 解码
     * （解码后还要再扫一遍，等于每条消息白白分配两份几十 KB 的字符串）。
     */
    private static boolean containsAsciiTag(byte[] bytes, String tag) {
        int tagLength = tag.length();
        int limit = bytes.length - tagLength;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < tagLength; j++) {
                int actual = bytes[i + j];
                char wanted = tag.charAt(j);
                if (actual == wanted) continue;
                if (actual < 'A' || actual > 'Z') continue outer;
                if ((char) (actual + 32) != wanted) continue outer;
            }
            return true;
        }
        return false;
    }

    private String contentValueString(ContentValues values, String key) {
        if (values == null || TextUtils.isEmpty(key) || !values.containsKey(key)) return "";
        try {
            String value = values.getAsString(key);
            return value != null ? value : "";
        } catch (Throwable ignored) {
            Object raw = values.get(key);
            return raw != null ? String.valueOf(raw) : "";
        }
    }

    private String extractMsgSource(String value) {
        if (TextUtils.isEmpty(value)) return "";
        // 原来对整串调用 toLowerCase() 再 indexOf：lvbuffer 有多长就分配多长的临时
        // 小写副本，每条消息一次。改成原地大小写不敏感查找，零临时分配。
        int start = indexOfIgnoreCase(value, MSG_SOURCE_OPEN, 0);
        if (start < 0) return "";
        int end = indexOfIgnoreCase(value, MSG_SOURCE_CLOSE, start + MSG_SOURCE_OPEN.length());
        if (end < 0) return "";
        end += MSG_SOURCE_CLOSE.length();
        return value.substring(start, Math.min(end, value.length())).trim();
    }

    private static int indexOfIgnoreCase(String source, String expectedLower, int from) {
        int limit = source.length() - expectedLower.length();
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (asciiRegionMatches(source, i, expectedLower)) return i;
        }
        return -1;
    }

    private boolean hasContentValuesArg(Class<?>[] params) {
        if (params == null) return false;
        for (Class<?> param : params) {
            if (ContentValues.class.isAssignableFrom(param)) return true;
        }
        return false;
    }

    private void dispatchIfSuccessful(String operation, Method method, Object[] args, Object result) {
        if (listeners.isEmpty() || args == null) return;
        long resultValue = resultToLong(result);
        if (DatabaseChange.INSERT.equals(operation)) {
            if (resultValue < 0) return;
            dispatch(new DatabaseChange(
                    operation,
                    tableArg(args),
                    nullColumnHackArg(args),
                    contentValuesArg(args),
                    null,
                    null,
                    resultValue,
                    method.getName()));
            return;
        }
        if (DatabaseChange.UPDATE.equals(operation)) {
            if (resultValue <= 0) return;
            dispatch(new DatabaseChange(
                    operation,
                    tableArg(args),
                    null,
                    contentValuesArg(args),
                    whereClauseArg(args),
                    stringArrayArg(args),
                    resultValue,
                    method.getName()));
            return;
        }
        if (DatabaseChange.DELETE.equals(operation)) {
            if (resultValue <= 0 || args.length < 3) return;
            dispatch(new DatabaseChange(
                    operation,
                    tableArg(args),
                    null,
                    null,
                    whereClauseArgForDelete(args),
                    stringArrayArg(args),
                    resultValue,
                    method.getName()));
        }
    }

    private void dispatch(DatabaseChange change) {
        for (Listener listener : listeners) {
            try {
                listener.onDatabaseChanged(change);
            } catch (Throwable e) {
                log("数据库变更监听回调失败: " + e.getMessage());
            }
        }
    }

    private long resultToLong(Object result) {
        return result instanceof Number ? ((Number) result).longValue() : 0;
    }

    private String tableArg(Object[] args) {
        if (args == null || args.length == 0) return "";
        if (args[0] instanceof String) return (String) args[0];
        for (Object arg : args) {
            if (!(arg instanceof String)) continue;
            String value = (String) arg;
            if (indexOfIgnoreCase(value, MESSAGE_TOKEN, 0) >= 0) return value;
        }
        return "";
    }

    private String nullColumnHackArg(Object[] args) {
        if (args == null) return "";
        for (int i = 1; i < args.length; i++) {
            Object value = args[i];
            if (value instanceof String) return (String) value;
            if (value instanceof ContentValues) return "";
        }
        return "";
    }

    private ContentValues contentValuesArg(Object[] args) {
        if (args == null) return null;
        for (Object value : args) {
            if (value instanceof ContentValues) return (ContentValues) value;
        }
        return null;
    }

    private String whereClauseArg(Object[] args) {
        if (args == null) return "";
        boolean afterValues = false;
        for (Object value : args) {
            if (value instanceof ContentValues) {
                afterValues = true;
                continue;
            }
            if (afterValues && value instanceof String) return (String) value;
        }
        return "";
    }

    private String whereClauseArgForDelete(Object[] args) {
        if (args == null) return "";
        for (int i = 1; i < args.length; i++) {
            Object value = args[i];
            if (value instanceof String) return (String) value;
        }
        return "";
    }

    private String[] stringArrayArg(Object[] args) {
        if (args == null) return null;
        Object value = null;
        for (Object arg : args) {
            if (arg instanceof String[]) {
                value = arg;
                break;
            }
        }
        return value instanceof String[] ? (String[]) value : null;
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatDatabaseListenerApi] " + message);
    }

    private static final class DatabaseQueryHooker implements XposedInterface.Hooker {
        private final WeChatDatabaseListenerApi owner;
        private DatabaseQueryHooker(WeChatDatabaseListenerApi owner) { this.owner = owner; }
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object[] args = chain.getArgs().toArray();
            boolean outermost = owner.enterQuery();
            try {
                if (outermost) owner.transformQuery(args);
                return chain.proceed(args);
            } finally {
                owner.leaveQuery();
            }
        }
    }

    private static final class DatabaseMutationHooker implements XposedInterface.Hooker {
        private final WeChatDatabaseListenerApi owner;
        private final String operation;
        private final Method method;
        private DatabaseMutationHooker(WeChatDatabaseListenerApi owner, String operation, Method method) {
            this.owner = owner; this.operation = operation; this.method = method;
        }
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object[] args = chain.getArgs().toArray();
            Object result = null;
            boolean succeeded = false;
            owner.enterMutation();
            try {
                owner.normalizeLegacyMsgSource(operation, chain.getThisObject(), args);
                result = chain.proceed(args);
                succeeded = true;
                return result;
            } finally {
                long dispatchStarted = h.Hchat.utils.SlowPathLog.now();
                boolean outermost = owner.leaveMutation();
                if (succeeded && outermost) owner.dispatchIfSuccessful(operation, method, args, result);
                h.Hchat.utils.SlowPathLog.report("db.mutation",
                        h.Hchat.utils.SlowPathLog.elapsedSince(dispatchStarted),
                        30L, "数据库变更派发", operation + " " + method.getName());
            }
        }
    }

}
