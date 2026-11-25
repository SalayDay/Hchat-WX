# Hchat 的 Rust 脚本引擎

脚本执行仅使用 LSPilot/BeanShell-Rust v0.1.0（上游提交 3dc027d），不包含 Java BeanShell 解释器，不设置自动回退。Java/Kotlin 代码只负责 Android/JNI 桥接、现有插件 API、纯文本语法糖预处理与快照加密。

## 源码与构建

- `third_party/beanshell-rust/`：上游 Rust 源码、许可证及本地补充的解析/AST 快照 JNI；序列化依赖保存在 `vendor/`，可离线重建。
- `app/src/main/java/me/yun/bsh/rust/`：JNI 生命周期入口。
- `app/src/main/java/bshrs/`：反射、按引擎隔离的 ClassLoader 与 Android DEX 转换。
- `app/src/main/java/h/Hchat/hooks/items/script/engine/`：现有脚本接口适配、语法检查与 AES-GCM 快照。
- `app/src/main/jniLibs/arm64-v8a/libbsh_rs.so`：Android 预编译库；仅支持项目既定的 arm64 微信进程。

配置具有 `aarch64-linux-android` 标准库的 Rust 和 Android NDK，然后执行：

```sh
python3 scripts/build_rust_bsh.py --target-dir /绝对路径/独立构建目录 --cargo /绝对路径/cargo --linker /绝对路径/aarch64-linux-android27-clang
```

该命令使用离线锁定依赖，核对 AArch64、JNI 导出与 16KB 页对齐，成功后才覆盖模块中的 `.so`。Android Gradle 打包直接使用预编译库，不在每次 APK 构建时下载 Rust/NDK。

## 兼容边界

`main.java`、`info.prop`、公开全局函数及回调名称不变。`__hchat_interpreter` 改为 Rust 适配对象，直接依赖 `bsh.*` 内部类型的插件需要修改。

语法检查使用 Rust 解析器，不执行初始化器或顶层语句；运行和校验共用注解、默认参数、字符串模板及隐式构造的纯文本预处理。插件变量和动态类加载器按引擎隔离，卸载和加载失败时关闭原生实例。

新快照是加密认证后的 Rust AST；旧 Java BeanShell AST 不可直接反序列化到 Rust，须从原始脚本重新生成。快照编译不执行脚本，执行前先完成认证校验，不将明文源文件或另一个 Java 解释器作为后备路径。

## 字符串与内部类兼容

字符串的 `replaceAll`、`replaceFirst`、`split`、`matches` 使用当前 Android/JVM 的 Java 正则实现，保留分组替换、limit 参数和异常类型；Rust 未实现的字符串方法通过 Java 互操作执行，不引入 Java BeanShell 解释器。

导入外部类后，`Outer.Inner` 按外部类的真实包名继续解析；缓存同时记录脚本别名与真实类名，新增导入清理失败查找缓存。此规则同样用于快照里的类构造。

## 集合、继承字段与回调

Android/JVM 下的 `List`、`Set`、`Map` 使用平台真实集合，跨 JNI 调用保留集合类型、对象身份和修改结果。`toArray()` 与带类型数组的重载均调用平台实现；脚本数组容量足够时保留复用、末尾空值和写回语义。无 JVM 的独立运行环境仍使用原有内建集合。

字段查找在原有声明字段和父类查找之后补查公开继承字段，包含接口常量，例如 `AlertDialog.BUTTON_POSITIVE`。源码和 Rust AST 快照使用相同的运行路径，不改变快照格式。

每个插件单独注册回调错误处理器。外部进入的接口代理无返回值回调（例如 `Runnable.run`、`OnShowListener.onShow`）出现运行时异常时，记录完整错误到模块错误日志和该插件的 `log.txt`，中止本次回调。同步脚本调用、有返回值回调、生成子类回调及非运行时异常仍保留原有异常传播；这不是全局异常拦截，也不能代替修复脚本错误。

## 普通脚本对象跨 Java 集合

`SendTask` 等普通脚本类无需继承 Java 类，就能存入平台列表、集合、映射和队列。Java 侧使用按插件隔离的对象包装器，取回时恢复原 Rust 脚本对象，保留字段、方法、引用身份及修改结果；具名脚本类声明的 Java 接口也会保留。

包装器采用弱引用缓存，失效句柄按批清理，插件卸载时释放对应原生对象和代理登记。公开 `set`、`get`、方法调用与后台回调使用相同的归属检查，拒绝将一个插件的脚本对象交给另一个插件解释器。快照格式不变，执行快照时同样适用。

## 基本类型的方法契约

布尔、字符、各类数值在引擎里是未装箱的 `Value`，没有自己的方法表。但它们必须满足
`java.lang.Object` 的契约，否则 `Boolean.TRUE.equals(x)`、`Integer.valueOf(n).equals(m)`
这类插件常用写法会直接抛 `No such method: equals() on boolean`：

- `equals(any)` 按值比较（基本类型之间走同一套数值归一，不会因 int/long 宽度不同而失配）
- `hashCode()` 对齐 `java.lang.*` 包装类（`Boolean` 为 1231/1237，`Long` 取上下半异或）
- `toString()` 与 `compareTo(other)` 同包装类语义

实现走原生分支，不为了调用一次 `equals` 就分配一个 Java 对象——这些都在消息回调热路径上。
契约之外的方法名仍然报错，不会把拼写错误吞掉。

## 重载选择

lambda / 方法引用 / 脚本对象的实参在候选签名里打分时，除了「是不是接口」之外还要带上
宿主侧的 SAM 匹配质量（参数个数对齐的接口得分更高）。只用 `(1, 0)` 这类常量分数会让
可调用与不可调用的两个重载打平，进而报
`Ambiguous script method: get(String, HashMap, Lambda)`。

## 验证口径

分别核对真实 Rust JNI 行为、Android 动态类转换、APK 编译和设备运行。JVM 测试成功不等于 Android ART、LSPosed 或微信中所有插件已验证；设备仍需检查脚本启停、异步回调、动态类、快照和多插件隔离。
