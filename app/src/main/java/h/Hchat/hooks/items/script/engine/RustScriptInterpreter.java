package h.Hchat.hooks.items.script.engine;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import javax.crypto.SecretKey;
import me.yun.bsh.rust.BshEngine;

public final class RustScriptInterpreter implements Closeable {
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final String DEFAULT_IMPORTS =
            "import org.json.*;"
            + "import android.widget.*;import android.view.*;import android.text.*;"
            + "import android.os.*;import android.graphics.*;import android.content.*;import android.app.*;"
            + "import java.util.stream.*;import java.util.regex.*;import java.util.function.*;import java.util.*;"
            + "import java.net.*;import java.math.*;import java.io.*;import java.lang.*;";
    private final BshEngine engine;
    /** 宿主 Hook 回调的 busy 标记。只在调用线程上复用，不跨线程共享。 */
    private static final ThreadLocal<boolean[]> HOOK_BUSY = ThreadLocal.withInitial(() -> new boolean[1]);
    /** {@link #tryInvokeHook} 拿不到解释器执行权时的返回标记。脚本不可能拿到这个对象。 */
    public static final Object HOOK_BUSY_MARK = new Object();
    public static final class Environment {
        public String cwd = System.getProperty("user.dir", ".");
    }
    private final Environment environment = new Environment();
    private volatile boolean closed;

    public RustScriptInterpreter() {
        engine = new BshEngine();
        try {
            engine.setVariable("__hchat_interpreter", this);
            engine.setVariable("bsh", environment);
            eval(DEFAULT_IMPORTS);
            String sourceBridge = "__hchat_source_" + java.util.UUID.randomUUID().toString().replace('-', '_');
            engine.setVariable(sourceBridge, this);
            eval("Object source(String path) { return " + sourceBridge + ".source(path); }");
        } catch (RuntimeException | Error error) {
            engine.close();
            throw error;
        }
    }

    public Object eval(String source) {
        requireOpen();
        return engine.evalValue(prepare(source));
    }

    public void set(String name, Object value) {
        requireOpen();
        if ("bsh.cwd".equals(name)) environment.cwd = Objects.toString(value, ".");
        else engine.setVariable(name, value);
    }

    /**
     * 同步调用一个脚本函数，只给宿主 Hook 回调使用。
     *
     * <p>宿主 interceptor chain 只能在收到它的线程上使用，所以这里既不排队也不阻塞：
     * 解释器执行权当前不可用时返回 {@link #HOOK_BUSY_MARK}，调用方必须直接放行宿主方法。
     * 脚本自身抛出的异常按原来的方式以 RuntimeException 形式抛给调用方。</p>
     */
    public Object tryInvokeHook(String functionName, Object[] args) {
        requireOpen();
        boolean[] busy = HOOK_BUSY.get();
        busy[0] = false;
        Object value = engine.tryInvokeHook(functionName, args, busy);
        return busy[0] ? HOOK_BUSY_MARK : value;
    }

    public Object get(String name) {
        requireOpen();
        return "bsh.cwd".equals(name) ? environment.cwd : engine.getVariable(name);
    }

    public void unset(String name) {
        requireOpen();
        engine.unsetVariable(name);
    }

    public Object source(String filename) throws IOException {
        requireOpen();
        Path file = resolve(filename);
        String source = prepare(readSource(file));
        return engine.evalSource(source, file.toString());
    }

    public void addClassLoader(ClassLoader loader) {
        requireOpen();
        engine.addClassLoader(Objects.requireNonNull(loader, "loader"));
    }

    public void setCallbackErrorHandler(java.util.function.BiConsumer<String, Throwable> handler) {
        requireOpen();
        engine.setCallbackErrorHandler(handler);
    }

    public Set<String> methods() {
        requireOpen();
        Set<String> result = new LinkedHashSet<>();
        String declarations = engine.scriptMethods();
        if (declarations != null && !declarations.isEmpty()) {
            for (String declaration : declarations.split("\n")) {
                int separator = declaration.lastIndexOf('|');
                String name = (separator < 0 ? declaration : declaration.substring(0, separator)).trim();
                if (!name.isEmpty()) result.add(name);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public void compileSnapshot(String inputPath, String outputPath, SecretKey key) throws IOException {
        requireOpen();
        Path input = resolve(inputPath);
        Path output = resolve(outputPath);
        byte[] ast = BshEngine.compileAst(prepare(readSource(input)));
        RustScriptSnapshot.writeAtomic(output, ast, key);
    }

    public Object evalSnapshot(String filename, SecretKey key) throws IOException {
        requireOpen();
        Path file = resolve(filename);
        byte[] ast;
        try (InputStream input = Files.newInputStream(file)) {
            ast = RustScriptSnapshot.read(input, key);
        }
        return evalAst(ast, file);
    }

    public Object evalSnapshot(InputStream input, SecretKey key) throws IOException {
        requireOpen();
        return evalAst(RustScriptSnapshot.read(input, key), resolve("snapshot stream"));
    }

    public Object evalSnapshot(byte[] data, SecretKey key) throws IOException {
        requireOpen();
        return evalAst(RustScriptSnapshot.decode(data, key), resolve("snapshot bytes"));
    }

    private Object evalAst(byte[] ast, Path file) {
        return engine.evalAst(ast, file.toString());
    }

    private static String prepare(String source) {
        return RustScriptSyntaxValidator.terminated(RustScriptSyntaxValidator.preprocess(source));
    }

    private static String readSource(Path file) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = RustScriptSnapshot.readBounded(input, MAX_SOURCE_BYTES);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("脚本不是有效的 UTF-8：" + file, e);
        }
    }

    private Path resolve(String filename) {
        Path path = Paths.get(Objects.requireNonNull(filename, "filename"));
        if (!path.isAbsolute()) path = Paths.get(environment.cwd).resolve(path);
        return path.toAbsolutePath().normalize();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Rust 脚本解释器已关闭");
    }

    @Override public void close() {
        engine.close();
        closed = true;
    }
}
