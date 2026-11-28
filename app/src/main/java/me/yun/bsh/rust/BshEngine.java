package me.yun.bsh.rust;

import bshrs.Bridge;
import java.io.Closeable;
import java.util.function.Supplier;

public final class BshEngine implements Closeable {
    private volatile long handle;

    public BshEngine() {
        Native.ensureLoaded();
        handle = Native.createEngine();
        if (handle == 0) throw new IllegalStateException("无法创建 Rust BeanShell 引擎");
        Bridge.registerEngine(handle);
    }

    public static void setLibraryLoader(Runnable loader) { Native.setLibraryLoader(loader); }

    public static String validate(String source) {
        Native.ensureLoaded();
        return Native.validate(source);
    }

    public static byte[] compileAst(String source) {
        Native.ensureLoaded();
        return Native.compileAst(source);
    }

    private <T> T access(Supplier<T> action) {
        if (handle == 0) throw new IllegalStateException("Rust 脚本引擎已关闭");
        Long previous = Bridge.enter(handle);
        try { return action.get(); }
        finally { Bridge.exit(previous); }
    }

    public String eval(String source) { return access(() -> Native.eval(handle, source)); }
    public Object evalValue(String source) { return access(() -> Native.evalValue(handle, source)); }
    public Object evalFile(String path) { return access(() -> Native.evalFile(handle, path)); }
    public Object evalSource(String source, String sourceName) { return access(() -> Native.evalSource(handle, source, sourceName)); }
    public Object evalAst(byte[] data, String sourceName) { return access(() -> Native.evalAst(handle, data, sourceName)); }
    public Object getVariable(String name) { return access(() -> Native.getVariable(handle, name)); }
    public String scriptMethods() { return access(() -> Native.scriptMethods(handle)); }
    public Object invokeMethod(String name, Object... args) { return access(() -> Native.invokeMethod(handle, name, args)); }

    /**
     * 同步、不排队的脚本函数调用，只给宿主 Hook 回调使用。
     *
     * <p>Hook 回调必须留在拿到 interceptor chain 的那个线程上，排队到别的线程会破坏
     * chain 的同线程约束。这里拿不到解释器执行权时立即返回 {@code null} 并把
     * {@code busy[0]} 置为 {@code true}，调用方据此直接放行宿主方法，既不阻塞也不排队。</p>
     */
    public Object tryInvokeHook(String name, Object[] args, boolean[] busy) {
        return access(() -> Native.tryInvokeHook(handle, name, args, busy));
    }
    public void setVariable(String name, Object value) { access(() -> { Native.setVariable(handle, name, value); return null; }); }
    public void unsetVariable(String name) { access(() -> { Native.unsetVariable(handle, name); return null; }); }
    public void addClassLoader(ClassLoader loader) { access(() -> { Bridge.addScriptLoader(handle, loader); return null; }); }
    public void setCallbackErrorHandler(java.util.function.BiConsumer<String, Throwable> handler) {
        access(() -> { Bridge.setCallbackErrorHandler(handle, handler); return null; });
    }
    public void importClass(String name) { access(() -> { Native.importClass(handle, name); return null; }); }
    public void defineMethod(String name, Object instance, String className, int argc, boolean varargs) {
        access(() -> { Native.defineMethod(handle, name, instance, className, argc, varargs); return null; });
    }
    public void reset() { access(() -> { Native.reset(handle); return null; }); }

    @Override public void close() {
        long old = handle;
        if (old == 0) return;
        if (Bridge.isCurrent(old)) throw new IllegalStateException("不能在脚本回调中关闭自身引擎，请使用 reloadPlugin()");
        Runnable unlock;
        try { unlock = Bridge.lockForClose(old); }
        catch (IllegalStateException error) { if (handle == 0) return; throw error; }
        try {
            if (handle == 0) return;
            handle = 0;
            try { Native.destroyEngine(old); }
            finally { Bridge.releaseEngine(old); }
        } finally { unlock.run(); }
    }
}
