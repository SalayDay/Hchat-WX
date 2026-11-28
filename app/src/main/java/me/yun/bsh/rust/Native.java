package me.yun.bsh.rust;

final class Native {
    private static volatile boolean loaded;
    private static Runnable libraryLoader;

    private Native() {}

    static synchronized void setLibraryLoader(Runnable loader) {
        libraryLoader = loader;
    }

    static synchronized void ensureLoaded() {
        if (loaded) return;
        if (libraryLoader != null) libraryLoader.run();
        else System.loadLibrary("bsh_rs");
        loaded = true;
    }

    static native long createEngine();
    static native void destroyEngine(long engine);
    static native String eval(long engine, String source);
    static native Object evalValue(long engine, String source);
    static native Object evalFile(long engine, String path);
    static native Object evalSource(long engine, String source, String sourceName);
    static native void setVariable(long engine, String name, Object value);
    static native Object getVariable(long engine, String name);
    static native void unsetVariable(long engine, String name);
    static native String scriptMethods(long engine);
    static native Object invokeMethod(long engine, String name, Object[] args);
    static native Object tryInvokeHook(long engine, String name, Object[] args, boolean[] busy);
    static native void importClass(long engine, String name);
    static native void defineMethod(long engine, String name, Object instance, String className, int argc, boolean varargs);
    static native void reset(long engine);
    static native String validate(String source);
    static native byte[] compileAst(String source);
    static native Object evalAst(long engine, byte[] ast, String sourceName);
}
