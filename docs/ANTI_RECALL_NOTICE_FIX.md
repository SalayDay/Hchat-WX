# 防撤回提示位置修复（6.7.7.35）

日期：2026-11-28。修复目标：连续收到多条消息时，撤回中间一条，提示却出现在会话末尾。

## 根因与修复

1. 提示插入方法会调用 `MsgInfoStorageLogic` 的时间函数。该函数把传入秒时间转为毫秒，并取“会话末条 createTime + 1”和输入时间中的较大值；普通插入有意追加到末尾，不能直接用于历史定位。
2. 旧定位器错误地在系统消息 Listener 自身的声明类里寻找 `static (String,long)->long`，还偏好固定方法名 `m`。微信 8.0.76/80 的 Listener 都没有这样的声明；时间覆盖未安装时，旧代码静默退回当前时间，所以提示被推到末尾。
3. 新定位使用时间函数中的两个 `[oneliang] fix recv msg ...` 字符串及 `MicroMsg.MsgInfoStorageLogic`，并与系统提示插入入口的直接调用集合交叉验证；必须唯一命中且符合静态 `(String,long)->long`。不写死混淆名。
4. 单独升级时间定位缓存版本为 `2`，旧方法描述符被废弃；保留其它 DexFinder 缓存，避免因本次修复整包重扫。有效缓存仍直接解析使用。
5. 原消息只按会话和服务端消息 ID 关联，不再把服务端 ID 当作本地 msgId 查询。优先持久化记录的精确毫秒时间，缓存只作缺失时的补充；过滤其它会话、其它 ID 及已变为撤回通知的记录。
6. 提示用原记录时间 + 1ms 插入，通过原生 `Hooker.intercept(Chain)` 仅在当前线程、当前插入、当前会话的作用域内覆盖时间。嵌套插入恢复外层状态；普通插入不会误用外层指定时间；异常退出也释放作用域。
7. 找不到原记录时间或不能安装指定时间覆盖时明确返回失败并记录错误，不再静默把提示插到末尾。原消息的防撤回拦截不因提示失败被撤销。
8. 原生系统提示入口的调用改为 `invokeOrThrow`，避免反射异常被吞后仍报告插入成功。

## 本地 APK 逆向证据

下列名称仅为核对依据，不作为运行时硬编码。

| 链路 | 微信 8.0.80 | 微信 8.0.76 |
| --- | --- | --- |
| 系统提示入口 | `zx3.l.a(String,String,String):void` | `gr3.l.b(String,String,String):void` |
| 同步调用的时间函数 | `w41.ba.o(String,long):long` | `e01.x9.m(String,long):long` |
| 时间字段 setter | `lm.d8.d1(long)` | `dm.c8.e1(long)` |
| 核心存储插入 | `storage.g9.Zb(storage.f9)` | `storage.f9.H9(storage.e9)` |

时间函数的锚点：

```text
MicroMsg.MsgInfoStorageLogic
[oneliang] fix recv msg db create time, last:%s
[oneliang] fix recv msg create time, check lastRecvMsgCreateTime %s, fixedTime is :%s
```

两版本入口均直接、同步调用时间函数并将返回值写入 `field_createTime` 后交原生存储；没有向 Handler/Runnable 转发。最终插入及消息 convertTo 没有重新计算该字段，持久化直接使用 `field_createTime`。

## 6.7.7.36：真机日志暴露的三个后续问题

真机日志（8.0.80 / 6.7.7.35）显示：

```text
[DexFinder] 系统提示时间调用链定位失败 | IllegalArgumentException:
            Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V not a method
[DexFinder] 本地消息API: insert=null system=zx3.l#a createTime=null msg=null ctor=-1
[AntiRecall] 插入提示失败: talker=wxid_... newmsgid=...
```

1. **定位器被构造器条目打断**。`MethodData.getInvokes()` 会混进构造器和字段访问，
   `getMethodInstance()` 对它们抛 `IllegalArgumentException`（如 `StringBuilder.<init>`）。
   原实现把这个异常交给外层 catch，整轮循环直接中断，语义候选虽然已经算出来也白算。
   修复：构造器条目逐条跳过，外层方法解析也单独容错。
2. **唯一命中时不再依赖调用链**。dexsql 已确认 `w41.ba.o(String,long)long` 是
   `public static`，三个锚点字符串都命中同一方法，且 `zx3.l.a` 确实调用它。
   现在「调用链唯一命中」优先；调用链解析不到但语义候选唯一时也直接采用，并记录日志；
   多候选则明确报错而不是瞎猜。
3. **解析次数上限从 3 提到 8**。时间定位依赖系统提示入口的调用关系，会随
   ClassLoader 就绪时序变化，上限过小会让整个进程生命周期永久失效。

同一份日志还暴露了两个与本功能无关的问题，一并修复：

- **绑定入口只挂到 1 个**：`chat_purify` / `chat_message_enter_animation` 显示
  `已挂载 ×1 (SynchronizedAdapter#onBindViewHolder(3))`。原因是 `bind_v3` 缓存
  由上一版「按 `com.tencent.mm` 前缀区分微信类」的错误规则生成，而缓存键没升级，
  旧结果被直接复用。缓存键升到 `bind_v4`，并要求缓存里至少 2 条重载才可信
  （聊天列表必然同时存在两参与 payload 三参）。
- **菜单美化仍跳过的后续勘误（6.7.7.37）**：6.7.7.36 曾采用 `performLongClick` 返回后建立两秒窗口及可点击子项判断，但此方案会错过原回调内部同步出现的菜单，也不能覆盖 AdapterView 统一点击的列表。现在依据 8.0.76/80 原生聊天菜单打开链建立同线程作用域，删除该全局长按 Hook 和时间窗口。`e5/d5` 是通用组件；`content=v` 的选择手柄应继续排除，不能仅按短类名染色。详见 [6.7.7.37 日志复核](LOG_REVIEW_6_7_7_37.md)。

## 回归范围与边界

新增测试覆盖：A/B/C/D 连续消息中撤回 B 后提示时间位于 B/C 之间；数据库毫秒时间优先于事件缓存的整秒时间；本地/服务端 ID 碰撞；其它会话；撤回命令不作原消息；原记录缺失；秒/毫秒转换；指定插入作用域的目标过滤、嵌套、普通插入隔离、异常恢复及跨线程隔离。

只具备 8.0.76 和 8.0.80 APK；其余项目默认版本集合未做本次逆向核对。新增 13 个回归用例全部通过，全量为 28 套件 / 114 用例 / 0 失败 / 0 错误。`:app:testDebugUnitTest :app:assembleRelease`（含 R8）构建成功，Gradle 退出码为 0；这些结果不能代替手机实测。

交付：`dist/Hchat-release-6.7.7.36.apk`，versionCode `6070736`，大小 `6660796` 字节。APK SHA-256：`1C9AA6FAD8EB8691A9063E193236A5E37E301525805C362F75F349FE1E276CC7`。签名证书 SHA-256：`4fad3e676491c50817373a2097340e71f59d309e36e9e816d046148632a00ba0`（与此前一致）。

极端边界：微信部分历史查询只按 createTime 排序，没有统一的 msgId 次序约束。如果两条原消息时间相同，或原消息与下一条仅相隔 1ms，`+1ms` 无法跨所有分支保证严格相邻；本次不改写其它消息时间或全局排序，以免影响聊天历史与翻页。已修复的是使用撤回发生时间/当前时间把提示固定推到会话末尾的错误，精确同毫秒顺序仍需设备场景验证。

## 6.7.7.38：模块提示来源登记与净化豁免

6.7.7.37 真机日志再次确认指定时间调用已返回，但提示仍不可见。原因是 `chat_purify` 对所有 `type=10000` 行设置 `GONE`。微信 8.0.76/80 系统提示入口的第三参数是显示名替换值，不是来源标记，不能用来塞 `msgSource`。

6.7.7.38 为模块自有提示增加精确来源登记：在同步原生 storage insert Hook 中读取已核实的原消息字段并捕获真实本地 `msgId`；登记以账号、会话、原始 `field_createTime` 和完整内容 SHA-256 为身份。净化仅在完整字段和本地 ID 均匹配时保留模块提示，其它系统提示仍隐藏，不按提示文本白名单。Hook 入口由系统提示 Listener 的直接 invoke 与 `(native Message):long` 形状唯一定位，缓存独立版本化。若无法安装/捕获/登记，本次受保护插入明确失败，避免报告“已保护”但界面仍隐藏。旧版本未登记来源的历史提示不会被自动认领。

关闭净化开关、功能销毁及列表复用时，仅恢复本功能自己隐藏过的 View；不会把宿主原本为 GONE 的行强行显示。6.7.7.38 的完整表情存储证据、来源登记设计和验证边界见 [表情收藏与撤回提示修复记录](EMOJI_RECALL_FIX_6_7_7_38.md)。
