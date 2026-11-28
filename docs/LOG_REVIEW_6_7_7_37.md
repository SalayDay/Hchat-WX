# 6.7.7.36 日志复核与 6.7.7.37 修补

## 最新日志已经证明的行为

- `chat_message_enter_animation` 和 `chat_purify` 已定位两参、payload 三参及 SynchronizedAdapter 三参，共三条绑定入口。动效开关为关，不应期待播放。
- `AntiRecall` 日志中 `noticeTime=originalTime+1ms`，没有出现系统提示时间方法缺失/插入失败。原生插入调用已成功返回；这不等于手机上的位置和可见性已全部验证。
- 主进程持续记录采集心跳，采集并未停在启动阶段。日志文件可能包含此前 PID 的历史记录，不能据此认定旧进程仍活着。
- `chat_purify` 明确命中了系统提示隐藏；它只看 type 10000，不区分 Hchat 防撤回提示和其它系统提示。希望看到防撤回提示时，需要关闭“实用 → 美化 → 界面净化 → 隐藏系统灰字”。本轮保持用户既有设置及全隐藏语义，不按提示文字猜白名单。

## 菜单定位的勘误与修补

此前文档和代码声称 `viewitems.o0.a` 首参不是菜单，这一判断错误。对本机 8.0.80 与 8.0.76 APK 的方法、接口和实际调用链复核如下（仅作逆向依据，不硬编码）：

| 目标 | 微信 8.0.80 | 微信 8.0.76 |
| --- | --- | --- |
| 聊天菜单打开 | `viewitems.m0.h(View):void` | `viewitems.q0.g(View):void` |
| 原生创建回调 | `viewitems.o0.a(ak5.h4,View,ContextMenuInfo):void` | `viewitems.m0.a(kc5.g4,View,ContextMenuInfo):void` |
| 原生选择回调 | `viewitems.r0.onMMMenuItemSelected(MenuItem,int):void` | `viewitems.p0.onMMMenuItemSelected(MenuItem,int):void` |
| 菜单实现 | `ak5.h4 implements ContextMenu` | `kc5.g4 implements ContextMenu` |
| MMPopupMenu | `zu5.s0` | `ym5.r` |
| 通用 popup / wrapper | `ak5.e5 / ak5.d5` | `kc5.d5 / kc5.c5` |

打开入口通过 MMPopupMenu.f 同步调用创建回调，接着显示网格/列表；点击分别经 OnClickListener 和 AdapterView.OnItemClickListener 分发到原生选择回调。该 viewitems 路径未调用 presenter；presenter 中存在同名函数并不能证明本路径会调用它。

实际修补：

1. SingleMessageMenuLocator 恢复原生创建/选择回调，按同一方法的日志字符串与精确参数接口定位。创建锚点为 `MicroMsg.ChattingItem` + `msg is null!`；首参必须实现 ContextMenu。点击锚点为 `MicroMsg.ChattingItem` + `context item select failed, null dataTag`；签名为 `(MenuItem,int):void`。缓存使用 `menu_create_native_v2` / `menu_click_native_v2`，不再读取错误的 presenter 定位结果。
2. AddEmoji 的聊天入口改为使用同一定位器。删除被替换的 ChatPresenterMenuLocator，不保留未经本调用链验证的 presenter 兜底。其它调用者仍复用共享定位器。
3. AddEmoji 的 getter 缓存改用可保存 null 负结果的同步 HashMap。ConcurrentHashMap 不允许 null，原代码在无法解析某个 tag 时会抛异常。
4. 新 ChatMenuPopupLocator 通过以下同一方法内的字符串定位 `(View):void` 菜单打开入口，并按运行时版本、APK、热更新、ClassLoader 缓存：

   ```text
   MicroMsg.ChattingItem
   openContextMenu[%s] left:%s, top:%s, width:%s, height:%s
   openContextMenu posX:%s, posY:%s
   ```

5. 菜单样式在原生打开方法的 proceed 前建立 ThreadLocal 作用域，finally 恢复。PopupWindow 显示回调只处理该同步调用内、与原聊天 View 同一 rootView 的弹窗。不使用全局 View.performLongClick、两秒时间窗或“子项必须可点击”的猜测。
6. `content=v` 静态强候选为 `ev5.v`（SelectableTextHelper 的选择手柄，构造时 new PopupWindow(this)），它不属于聊天菜单打开作用域，应继续跳过。`e5/d5` 为通用组件，不能仅按短类名把所有此类弹窗都认作菜单。
7. 同一 popup 反复打开时，样式从保存的原始 width/padding 计算，避免不断变宽；关闭功能或销毁时恢复背景、padding、clipToOutline 和宽度。首次实际应用时记录完整 popup/content 类名。

这是一张圆角半透明卡片；未新增系统级实时背景模糊，不能把它表述成已经验证的实时磨砂效果。

## AddMsg 99ms 的含义与诊断

该数字只出现在启动阶段的一次慢派发记录中，且同 key 10 秒限频，不能据此推导每条消息都慢 99ms，也不能据此证明后续从未慢。

现有计时在 chain.proceed() 之后开始，覆盖账号查询、消息解析、MessageReceived 总线回调及条件性的 RedPacketDetected 回调。EventBus 和消息观察监听器均在当前线程同步执行。把代码放在 proceed 后仍会占用该线程，不能声称“因此不影响上屏”。本轮不改为全局异步，以保留通知抑制、红包及其它监听器的线程和顺序。

新增可诊断阶段：`account`、`parse`、`message_post`、`red_packet_post`、`total`；慢时同时记录线程、消息类型和内容长度，**不记录正文、wxid 或金额**。观察 API 增加兼容重载 `subscribe(ownerId, listener)`，内置监听器使用稳定功能 ID。慢监听器日志记录 owner/source/kind/thread，阈值为 20ms，10 秒限频，采集关闭时不做日志格式化。

原有 `subscribe(listener)` 保持可用，订阅顺序、相等 listener 去重、取消订阅、同线程同步交付和异常隔离均保持。新增测试覆盖这些契约。

## 回归与未解决边界

新增回归覆盖：菜单打开方法在返回前同步 show、作用域异常/嵌套/线程隔离、其它晚到弹窗无时间窗口；ContextMenu 具体实现也能匹配、Context 不会误判、静态/抽象错误形状被排除；消息监听器同步顺序、去重/取消、异常隔离和快照迭代。

本次只核对本机具备的微信 8.0.76 和 8.0.80 APK，没有其它默认版本样本；静态链证据和单元测试不能代替手机上的实际显示验证。

本日志仍有下列独立问题，未以修改日志措辞伪装成已修复：微信群通话铃声入口未定位、首页分组主动刷新入口缺失、HomeTextColor 严格定位失败后使用名称兜底。聊天沉浸式及首页分割线的实际效果也没有本日志中的成功命中证明。

## 最终验证与交付

```text
:app:testDebugUnitTest          31 套件 / 129 用例 / 0 失败 / 0 错误
:app:minifyReleaseWithR8       BUILD SUCCESSFUL
:app:assembleRelease           BUILD SUCCESSFUL
git diff --check               CLEAN
```

交付 APK：`dist/Hchat-release-6.7.7.37.apk`

```text
versionName 6.7.7.37   versionCode 6070737
大小 6662148 字节
APK SHA-256 ED76693017C13F2F1B1A0EB0046637F4E66448DB210DEDE4B49B228CDBB78153
签名证书 SHA-256 4fad3e676491c50817373a2097340e71f59d309e36e9e816d046148632a00ba0
ABI arm64-v8a only
```

本包包含本轮原生聊天菜单定位、菜单作用域修复、消息分段诊断和观察器 owner 诊断；未改变事件同步顺序或系统灰字全隐藏的设置语义。
