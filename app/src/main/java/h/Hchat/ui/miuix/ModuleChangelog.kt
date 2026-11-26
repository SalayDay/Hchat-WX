package h.Hchat.ui.miuix

/**
 * 模块内置更新日志。
 *
 * ## 为什么放在代码里
 *
 * 之前「设置 → 历史版本」是去远端接口拉版本列表，每一项还带「下载此版本」——
 * 等于把发版和分发都绑在一个第三方服务上，断网/服务下线就只能看到报错。
 * 现在改成**随包发布的更新日志**：只读本地数据，无网络请求、无下载入口。
 *
 * ## 维护约定
 *
 * 每次发布新的 6.7.x（以及之后的大版本）时，在 [entries] **最前面**补一条：
 * 版本号 + 面向用户的变更点（一行一条，短句，动词开头）。
 * 不要再把下载链接写进来；用户从各自的渠道取包。
 */
internal data class ModuleChangelogEntry(
    val version: String,
    val notes: List<String>
) {
    /** 由版本名推导的 versionCode；解析失败为 0。 */
    val versionCode: Int = changelogVersionCode(version) ?: 0
}

/**
 * 四段版本名 → versionCode，规则与 `app/build.gradle.kts` 保持一致：
 * `主 * 1_000_000 + 次 * 10_000 + 修订 * 100 + 补丁`。
 *
 * 放在顶层函数而不是 object 成员：`entries` 初始化时就会构造条目，
 * 若条目反过来调用 object 自己的方法，等于在自身初始化过程中重入。
 */
private fun changelogVersionCode(name: String): Int? {
    val parts = name.split('.')
    if (parts.size != 4) return null
    val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
    if (numbers.any { it < 0 || it > 99 }) return null
    return numbers[0] * 1_000_000 + numbers[1] * 10_000 + numbers[2] * 100 + numbers[3]
}

internal object ModuleChangelog {
    /** 新的在上。版本号按发布顺序倒序排列。 */
    val entries: List<ModuleChangelogEntry> = listOf(
        ModuleChangelogEntry(
            "6.7.7.18",
            listOf(
                "新增「群聊管理员标签显示」：群聊昵称前显示群主 / 管理员 / 部分真名标签（实用 - 群组，三个开关默认关闭）"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.17",
            listOf(
                "「图片保存为表情」明确只处理普通图片：表情消息（type 47）不再放行，菜单文案改为「保存为表情」"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.16",
            listOf(
                "新增「图片保存为表情」：长按聊天图片或朋友圈图片，把普通图片存成微信自定义表情（实用 - 聊天，默认关闭）"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.15",
            listOf(
                "快捷已读：修复滑动时卡顿 —— 按下不再触发全树遍历，拖拽目标推迟到确认上滑后解析并缓存底栏",
                "快捷已读：全部已读改到后台线程执行，抬手不再阻塞主线程的数据库往返",
                "快捷已读：拖动阈值只换算一次，避免每帧重复 TypedValue 计算"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.14",
            listOf(
                "脚本引擎：布尔/数值等基本类型也能调用 equals/hashCode/toString/compareTo，修复 Boolean.TRUE.equals 报 No such method",
                "脚本引擎：lambda 实参按接口 SAM 匹配度打分，修复 get(url, headers, resp -> ...) 报 Ambiguous script method",
                "小程序强制分享：改按结构定位菜单体系，8.0.76/8.0.80 都能挂上",
                "截图转发按钮：转发动作 id 与浮层类名改为多锚点尝试，8.0.76 也能定位",
                "公众号广告拦截：混淆类名改为运行期按候选表解析，兼容 8.0.76/8.0.80"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.13",
            listOf(
                "修复脚本插件 hook 回调报 NoSuchFieldException: 恢复兼容层类的 keep 规则，param.thisObject 等字段名不再被 R8 改写",
                "设置注入诊断日志改为跟随 HLog 开关，设置入口异常时不再无迹可查"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.12",
            listOf(
                "修复微信设置页 Hchat 入口消失：兼容层现在会采用 after 钩子对返回值的改写",
                "方法定位恢复沿继承链查找（对齐旧 XposedHelpers 语义）"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.11",
            listOf(
                "Xposed API 82 迁移到 LibXposed API 102：入口改为 XposedModule，底层 hook 全部走 intercept 链",
                "移除设置页调试分组：记录运行日志开关与日志查看器页面",
                "保留崩溃证据与看门狗面包屑所依赖的日志存储内核"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.10",
            listOf(
                "修复指定骰子猜拳失效：表情发送入口改为多锚点定位，兼容微信 8.0.80 的 SendEmojiTask 链路",
                "定位缓存版本 +1，升级后自动重新定位，无需清数据"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.9",
            listOf(
                "修复更新日志弹窗无法关闭：补关闭按钮并接管返回键",
                "内容限高滚动，关闭入口不随列表滚走"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.8",
            listOf(
                "设置 → 历史版本：改为随包发布的更新日志，去掉远端下载入口",
                "链接解析：支持动图，GIF/PNG 按原始字节发送，微信里会动",
                "链接解析：小红书改为原图优先，取不到再退回 jpg 变体",
                "链接解析：支持合并转发（聊天记录）里的作品链接"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.7",
            listOf(
                "设置页切 tab 不再整页重组，底栏高亮改为点击即跟手",
                "液态玻璃底栏在翻页动画期间自动关闭，动画结束恢复",
                "明暗主题色板改为缓存，不再每次重组重建",
                "安装包回到 6.7.7 分支，签名不变，可直接覆盖安装"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.6",
            listOf(
                "反检测：release 包也能正确识别并隐藏模块自身的调用栈帧",
                "日志落盘轮询空闲期从 1.2 秒放宽到 15 秒，减少后台唤醒",
                "栈帧过滤增加快路径，抓栈开销比 6.7.7.1 更低"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.5",
            listOf(
                "订阅号广告：新增广告卡帧任务门，只拦广告服务，正常品牌卡不受影响",
                "公众号文章广告检查日志改为「结果变化才写」，不再刷屏",
                "修掉广告取数任务的重复触发"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.4",
            listOf(
                "订阅号广告：直接丢弃广告卡拉取任务，冷启动少一次跨 ZIDL 取数",
                "挂载日志新增「拉取任务门」",
                "日志轮询改为自适应周期"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.3",
            listOf(
                "订阅号广告：卡片门诊断改为按方法名锚定，并标出被框架代理的实际帧",
                "反检测：新增模块自身类名登记表，正式包不再漏帧"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.2",
            listOf(
                "订阅号广告：挂载日志补齐微信版本号",
                "启动早期也能读到宿主版本，不再显示「未知」"
            )
        ),
        ModuleChangelogEntry(
            "6.7.7.1",
            listOf(
                "订阅号广告：首次命中时输出各门挂载状态与 Fj 调用方栈",
                "便于区分「没挂上」与「没命中」"
            )
        )
    )

    /**
     * 当前安装版本对应的条目。
     *
     * 先按版本名精确匹配；热修包（如 6.7.7.10 还没补条目）则退化为
     * "versionCode 不超过当前版本的最近一条"，避免整页都没有「当前」标记。
     */
    fun current(versionCode: Int, versionName: String = ""): ModuleChangelogEntry? {
        entries.firstOrNull { versionName.isNotBlank() && it.version == versionName }?.let { return it }
        return entries
            .filter { it.versionCode in 1..versionCode }
            .maxByOrNull { it.versionCode }
    }
}