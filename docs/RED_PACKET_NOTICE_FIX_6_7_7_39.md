# 6.7.7.39：红包领取系统灰字与界面净化冲突

## 用户确认的目标

用户明确选择的是**红包卡片下方的系统灰字**（例如“你领取了某人的红包”），不是红包卡片内部的领取状态副标题。本版基于 6.7.7.38，保留图片表情原生路径/最终存储通知修复与防撤回提示来源登记，不重做上述工作。

## 已核对的原生链

本次只有微信 8.0.80、8.0.76 APK 可供静态核验，名称只用于证据，不作为运行时混淆名硬编码。

| 环节 | 微信 8.0.80 | 微信 8.0.76 |
| --- | --- | --- |
| 普通开包回调 | `luckymoney.model.h6.onGYNetEnd(int,String,JSONObject):void` | `luckymoney.model.f6.onGYNetEnd(int,String,JSONObject):void` |
| Union 开包回调 | `luckymoney.model.i6.onGYNetEnd(int,String,JSONObject):void` | `luckymoney.model.g6.onGYNetEnd(int,String,JSONObject):void` |
| 原生领取灰字插入 helper | `luckymoney.model.o5.q(String,String):void` | `luckymoney.model.m5.q(String,String):void` |
| 真实存储插入结果 | `storage.g9.Zb(storage.f9):long` | `storage.f9.H9(storage.e9):long` |

两种开包回调都读取服务端 JSON 的 `SystemMsgContext`，在原生成功、内容非空与会话过滤条件满足时，调用同一版本的灰字 helper。第一参数是服务端提示完整正文，第二参数为请求构造时的 `sessionUserName`，并非响应里的发送者昵称。当前静默请求的第七参数 `requestTalker` 与原生构造器该会话字段对应。

helper 内构造原生消息，设置 isSend=0、status=3、talker、content、微信原生创建时间和 type=10000，再同步调用原生存储插入。它不额外写红包专属 msgSource、paymsgid 或 nativeUrl 字段，也不重写服务端提示正文。正文可能含详情链接，不能当成任意中文纯文本替换。

`RedPacketSilentHandler.OpenResponseHooker` 已先执行 `chain.proceed()`，然后才取金额、统计、通知和清理；因此静默请求并没有跳过这段原生提示生成。

## 问题与修复

系统灰字净化按 type=10000 隐藏行；6.7.7.38 仅保护了模块登记过的防撤回提示，红包的原生本地提示仍会被隐藏。当前证据支持这是同类可见性冲突，不支持旧文档所述“静默领取必须回写红包 XML”。

6.7.7.39 的处理如下：

1. 在公共 API 的预热及有限重试任务里，按普通和 Union 开包回调中的 `SystemMsgContext`、`insertLocalSysMsgIfNeed error: %s` 和各自原生日志标签定位调用者。
2. 从调用者的直接调用中筛出唯一静态 `(String,String):void` helper，再验证该 helper 直接调用了已经确认的原生消息存储插入。helper 自身没有字符串，不能把调用者日志锚点错放到 helper matcher 上。
3. 描述符缓存独立版本化，复用实时 runtimeKey（微信版本、APK、热更新和 ClassLoader）。有效缓存不重扫；定位经 `DexInstallScheduler` 串行门执行。普通与 Union 两条链都验证完整才保存缓存，不能首个命中就忽略另一入口。
4. Hook 只为该 helper 建立当前线程、完整正文和精确会话的登记作用域；真正插入前先登记 pending 身份，存储返回真实本地 ID 后确认并持久化。净化仍只查内存，不在每次绑定中查消息库；账号也改用 `cachedSelfWxId()`，避免账号尚未就绪时反复查 userinfo。
5. 原生 helper 只执行一次；不补插新消息，不改红包卡片 XML，不改 SystemMsgContext、详情链接、原生时间或开包的成功/会话过滤条件。
6. 原生提示的登记失败只记错误并放行微信，不得因为保护不可用取消收红包或原生提示。模块自建防撤回提示继续保留原有失败关闭策略。嵌套及异常退出恢复外层作用域，红包原生提示不继承模块指定时间覆盖。
7. 不依赖自动抢红包开关：手动领取和静默领取只要经过已核实入口，都可登记。金额统计、通知和回复的同步顺序保持不变。

## 文档勘误

旧版诊断把“卡片内状态”与“卡片下方系统灰字”混在一起，误称必须改写 `receivertitle`/`sendertitle`；但本次原生开包响应解析器没有读取这两个 XML 字段，原生 UI 主要更新红包业务记录，不是证据充分的聊天 XML 回写。相关历史说明已在 API 102 迁移记录中更正，旧诊断日志改为只记录 `SystemMsgContext` 是否存在，不输出正文或猜测字段。

## 覆盖边界与设备复测

- 本版修复**新产生且经过原生开包提示 helper 的本地领取行**。本版之前未登记的历史红包灰字不会自动认领；关闭“隐藏系统灰字”可查看旧行。
- 原生回调没有 SystemMsgContext，或者原生会话过滤不生成提示时，不伪造一条成功提示。群红包是否显示领取灰字仍遵循微信自己的条件。
- “别人领取了你的红包”可能属于带服务端消息 ID 的独立入站路径，本次没有运行时样本能证明它全部经过同一 helper，因此不冒称全覆盖，也不因正文包含“领取/红包”就放行。
- 已核到红包详情 URI span，但没有把任意包含红包域名的正文当作领取来源；直播评论中同名“领取”资源也已排除。
- 更新后强停并重启微信，开启“隐藏系统灰字”，分别测试手动与静默领取**新的私聊红包**，检查灰字及其原生详情链接；同时验证普通系统灰字仍隐藏、图片存表情和新防撤回提示不回退。
- APK 静态核验、单元测试及 Release 构建不代替手机实测；其它微信版本未做本次适配验证。

## 本地验证与交付

- 全量单元测试：**36 套件、175 用例、0 失败、0 错误、0 跳过**；相对 6.7.7.38 新增 22 个回归。
- 新定位器的 19 项回归覆盖 76/80 改名、同方法精确锚点、普通/Union 两路完整验证、直接存储调用、缓存部分损坏和构造器解析异常。作用域新增 3 项回归覆盖完整 SystemMsgContext 匹配、宿主 fail-open 与模块 fail-closed 策略、嵌套时间及异常恢复；原表情/撤回/可见性测试仍通过。
- `:app:testDebugUnitTest :app:assembleRelease`（含 R8）：成功，退出码 **0**。最终 APK DEX 中复核到新增提示登记分支及定位字符串；这不是手机 Hook 命中的证明。
- 执行 `git status --short`、`git diff --stat` 和与当前 CRLF 工作区对应的差异空白检查；结果 **CLEAN**。保留既有迁移改动，没有推送或创建 PR。
- 安装包：`dist/Hchat-release-6.7.7.39.apk`；versionName **6.7.7.39**，versionCode **6070739**，仅 **arm64-v8a**，大小 **6,677,828 字节**。
- APK SHA-256：`390286C5C517B9A2E956C4F186B19E0C20BC1CCCA295FED0824BDEA7A41DDBC9`。
- 签名证书 SHA-256：`4fad3e676491c50817373a2097340e71f59d309e36e9e816d046148632a00ba0`；与 6.7.7.38 一致，已通过 apksigner 核验。
- 构建日志：`build/packet-tip-6.7.7.39.log`；APK/回归机器校验结果：`build/packet-tip-6.7.7.39-verification.json`。
