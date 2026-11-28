# 6.7.7.38 表情收藏与撤回提示可见性修复

## 用户现象

6.7.7.37 的日志已经证明长按菜单入口和菜单样式命中，但图片点“保存为表情”后，表情面板没有显示新图片；撤回提示的原生插入日志成功，但打开系统灰字净化时仍可能整行不可见。

## 图片保存为表情

对微信 8.0.80 和 8.0.76 APK 的原生链复核得到以下结论：

- `getAccPath` 返回账号根目录，不能直接与 MD5 拼接成表情文件路径。
- EmojiInfo 的原图路径来自 EmojiInfo 自己的无参 `String` 方法。8.0.80 和 8.0.76 的混淆方法名不同，因此按 EmojiInfo 类、无参、返回 String，以及同一方法内的 `get icon path failed`、`capture`、`/` 三个字符串定位并缓存。
- Provider 的 `createEmojiInfo` 已经执行创建和 replace，并将新行置为临时状态、发送创建通知；它不是纯内存构造。最终字段和文件准备好后，必须再走 EmojiInfoStorage 的带通知 update。
- 更正调查早期的猜测：storage create 返回非空，的确以底层数据库写入成功为前提；问题是这个返回值无法证明文件路径正确或面板缓存已收到最终通知。本次新增文件与回读验收，不把已有数据库返回值误说成假成功。
- 自定义表情列表使用 `catalog=81`，排除 `state=4` 的查询，并按 `reserved3` 排序；已核对的列表 SQL 没有额外按 `groupId`、`temp` 或 `reserved1` 排除 `state=0/temp=0` 的记录。
- `EmojiUpdatePanelEvent` 的请求 payload 必须把唯一的非静态 int 字段设为 `1`，再调用 `IEvent.e():boolean`。返回 false 只代表当前没有面板监听器，不能当作数据库写入失败；数据库缓存脏标仍由 storage 通知负责。
- 最终结果必须 fresh 回读 `getEmojiByMd5`，检查 MD5、catalog、type、size、尺寸、state、temp、排序位、原生路径和文件。只有全部通过才报告“已存为表情”。
- 已有微信原生加密文件不能按明文 MD5 判坏，也不能调用可能在校验失败时删除共享文件的解码 API。缺失文件才在原生路径写入模块准备的原始字节，并清除最低加密标志位，保留其它字段。
- 保存请求使用单线程队列，避免同一 MD5 的文件检查、补写和排序位分配相互竞争。

本轮新增的纯判定测试覆盖：76/80 路径方法改名、事件 payload 选择、父类字段读取、临时记录拒绝、明文文件校验、原生加密文件保留、缺失文件修复和 fresh 回读门槛。

下表是 APK 静态证据，不作为运行时硬编码：

| 入口 | 微信 8.0.80 | 微信 8.0.76 |
| --- | --- | --- |
| EmojiInfo 原图路径 | `EmojiInfo.b1():String` | `EmojiInfo.T0():String` |
| 最终 update / 固定发存储通知 | `wh5.l.A4(EmojiInfo):boolean` | `ga5.l.m3(EmojiInfo):boolean` |
| create / 第二参为 notify | `wh5.l.N1(EmojiInfo,boolean):EmojiInfo` | `ga5.l.O0(EmojiInfo,boolean):EmojiInfo` |
| MD5 fresh 查询 | `wh5.l.i3(String):EmojiInfo` | `ga5.l.G1(String):EmojiInfo` |
| 存储实例及管理器单例 | `storage.n5.c()` / `n5.f()` | `storage.m5.c()` / `m5.f()` |
| 原生收藏完成处理 | `EmojiAddCustomDialogUI.x7(...)` | `EmojiAddCustomDialogUI.I6(...)` |

路径定位使用同方法精确字符串 `[cpan] get icon path failed. product id and md5 are null.`、`capture`、`/`；方法描述符缓存升级为 v3。storage update 使用 `event_update_emoji`，create 使用 `create_emoji_info_notify`。路径来源均通过 VFS 注册的 `emoji` 目录，不假定等于账号根目录加固定子目录。

## 撤回提示可见性

微信 8.0.80 的系统提示三参第三项和 8.0.76 对应参数都是显示名替换占位符，不是 `msgSource` 或 replace flag，不能通过该参数写模块来源标记。

因此本版采用来源登记：

1. 受保护提示插入时，在原生同步 storage 插入作用域中匹配 talker、type=10000、指定的原始毫秒时间和无服务端 ID消息。
2. Hook 微信存储插入方法，读取真实消息对象的 `field_talker`、`field_content`、`field_createTime`、`field_msgId`、`field_msgSvrId`、`field_type`，并取得 storage 返回的真实本地 msgId。
3. 以当前账号、会话、原始时间和完整内容计算 SHA-256，成功返回本地 msgId 后登记来源；进程内立即可用，配置存储用于重启恢复。
4. 界面净化只对这组身份完全匹配且本地 msgId 相同的模块提示放行。其它 type=10000 系统消息仍隐藏；不按“撤回”等正文词语放行。
5. 关闭开关、功能销毁和列表复用时，只恢复本功能自己曾改为 GONE 的 View，不强行修改宿主原本隐藏的 View。

如果真实 storage 插入入口不能唯一定位，受保护提示会失败关闭并记录错误，避免提示已插入却无法登记来源。普通 `insertSystemMessage` 仍保留原有返回语义，不依赖保护专用捕获 Hook。

两版 Listener 都返回 void，内部存储失败只记日志后正常返回，因此旧版“提示已按原消息定位”不能单独证明落库。新保护入口通过 Listener 的直接调用、`com.tencent.mm.storage` 声明类、非静态具体方法、单一原生消息参数和 primitive long 返回唯一定位：80 为 `storage.g9.Zb(storage.f9):long`，76 为 `storage.f9.H9(storage.e9):long`；逐条隔离构造器解析失败，缓存有独立版本号。

净化消息获取保留两版已证实的 `adapter.getItem(position)` bridge 回退，该方法从适配器内存列表取消息，不查数据库。只读取真实 `field_createTime`，不使用可能解析分享时间的 `getCreateTime()` override，也不把任意带 `getType()` 的对象当作消息。

旧版本没有来源登记的历史提示不会自动认领。更新并重启微信后，新发生的撤回才能验证新的保护逻辑；已有历史系统提示可通过关闭“隐藏系统灰字”显示。来源落盘只保存内容身份哈希与本地 ID，包含账号隔离；同一身份的多次插入分别按本地 ID登记。

## 验证边界

本轮逆向证据覆盖微信 8.0.76 和 8.0.80；其它微信版本没有对应 APK 证据。源码测试和 Release 构建不能代替手机验证。安装后应在微信 8.0.80 上重新测试：长按普通图片保存、打开自定义表情面板，以及开启系统灰字净化时撤回中间消息并检查提示位置和可见性。

## 构建与交付验证

- 全量单元测试：35 套件，153 用例，0 失败，0 错误；相对 6.7.7.37 新增 24 个回归。
- `:app:testDebugUnitTest :app:assembleRelease`：成功，包含 R8；最终构建退出码 0。
- 已执行 `git status --short`、`git diff --stat` 和差异空白检查；检查结果 CLEAN。保留原工作区既有迁移改动，没有推送或创建 PR。
- 安装包：`dist/Hchat-release-6.7.7.38.apk`，versionName `6.7.7.38`，versionCode `6070738`，仅 `arm64-v8a`。
- 文件大小：6,675,996 字节。
- APK SHA-256：`885687DCE60130DDB2FF4933764B29BF7156FD4E148391E17652BD56BF6F7E6B`。
- 签名证书 SHA-256：`4fad3e676491c50817373a2097340e71f59d309e36e9e816d046148632a00ba0`，与 6.7.7.37 一致。

验证日志：`build/emoji-recall-6.7.7.38-final.log`；机器核验结果：`build/emoji-recall-6.7.7.38-verification.json`。

建议设备操作：覆盖安装后强停并重启微信；对之前未显示的图片重新执行一次“保存为表情”，以补齐原生路径文件和最终通知；保持“隐藏系统灰字”开启，重新发送 A/B/C 并撤回 B，检查新生成提示的可见性和位置。已生成的旧版提示不会自动补来源登记。
