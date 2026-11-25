package h.Hchat.hooks.items.quickread

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.custombottombar.CustomBottomBarFeature
import h.Hchat.hooks.items.custombottombar.FloatingBottomBarTouchState
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import kotlin.math.abs

class QuickMarkReadFeature : BaseFeature() {
    private var dragController: QuickMarkReadDragController? = null

    override fun featureId(): String = ID

    override fun name(): String = "快捷已读"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(QuickMarkReadSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        QuickMarkReadRuntime.install(context)
        val controller = QuickMarkReadDragController(context.hostClassLoader(), ::logError)
        dragController = controller
        controller.install()
    }

    companion object {
        const val ID = "quick_mark_read"
    }
}

private class QuickMarkReadDragController(
    private val classLoader: ClassLoader,
    private val logger: (String, Throwable?) -> Unit
) {
    @Volatile private var installed = false
    private var pending: PendingDrag? = null
    private var session: DragSession? = null

    /**
     * 底栏 / 悬浮栏的解析缓存。
     *
     * 原来每次 ACTION_DOWN 都要 `findViewWithTag` 全树 DFS 两次 + `traverse(root)`
     * 一次 + 每个 ViewGroup 再来一遍 `collectTexts(depth<=4)`。聊天列表本身有几百个
     * 子 View，这一串同步跑在 UI 线程上，正是"滑动第一下卡一下"的来源：
     * 手指按下去到列表真正开始滚动之间，白等了好几趟全树遍历。
     *
     * 现在只在第一次需要时解析一次并缓存引用，后续手势直接命中；布局变化（缓存视图
     * 脱离窗口 / 不再可见 / 尺寸归零）时自动失效重解析。
     */
    private var cachedRoot: ViewGroup? = null
    private var cachedBottomNav: ViewGroup? = null
    private var cachedFloatingBar: ViewGroup? = null
    /** 「微信」页签的屏幕区域；拿到后即可在 DOWN 阶段零成本排除无关手势。 */
    private var cachedHomeRegion: ScreenRect? = null

    private val markReadExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Hchat-QuickRead-Mark").apply {
                isDaemon = true
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND
                )
            }
        }
    }

    fun install(): Boolean {
        if (installed) return true
        val methods = collectDispatchMethods()
        if (methods.isEmpty()) return false
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                val event = param.args?.getOrNull(0) as? MotionEvent ?: return
                if (handleTouch(activity, event)) {
                    param.setResult(true)
                }
            }
        }
        var hookedCount = 0
        var lastError: Throwable? = null
        for (method in methods) {
            runCatching {
                HookRegistry.get().hook(method, callback)
                hookedCount++
            }.onFailure {
                lastError = it
            }
        }
        installed = hookedCount > 0
        if (!installed) {
            logger("快捷已读拖拽Hook安装失败", lastError)
        }
        return installed
    }

    private fun collectDispatchMethods(): Set<Method> {
        // 只挂继承链上最派生的 dispatchTouchEvent。LauncherUI 及其父类若各自声明了
        // 该方法且存在 super 调用链，一个事件会先穿过子类再穿到父类——
        // 每层都挂 hook 等于一次触摸进 N 个回调。
        val launcherClass = KavaReflector.loadClass(LAUNCHER_UI, classLoader)
        var current: Class<*>? = launcherClass
        while (current != null && Activity::class.java.isAssignableFrom(current)) {
            val found = KavaReflector.findDeclaredMethod(
                current,
                DISPATCH_TOUCH_EVENT,
                MotionEvent::class.java
            )
            if (found != null) return setOf(found)
            if (current == Activity::class.java) break
            current = current.superclass
        }
        val fallback = KavaReflector.findDeclaredMethod(
            Activity::class.java,
            DISPATCH_TOUCH_EVENT,
            MotionEvent::class.java
        )
        return if (fallback != null) setOf(fallback) else emptySet()
    }

    private fun handleTouch(activity: Activity, event: MotionEvent): Boolean {
        if (!isLauncherUi(activity)) {
            clearGesture(resetActive = true)
            return false
        }
        if (!QuickMarkReadRuntime.isDragEnabled(activity)) {
            clearGesture(resetActive = true)
            return false
        }
        // 手势目标只在底部导航/悬浮栏的「微信」标签附近，触摸区域预判放在
        // 任何树遍历之前：屏幕上 2/3 区域的按下（聊天列表滚动、点消息等）
        // 直接短路，避免每次 DOWN 都全树 DFS。
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val screenH = activity.resources.displayMetrics.heightPixels
            if (event.rawY < screenH * BOTTOM_GESTURE_MIN_RATIO) {
                clearGesture(resetActive = false)
                return false
            }
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startPendingSession(activity, event)
            MotionEvent.ACTION_MOVE -> moveSession(event)
            MotionEvent.ACTION_UP -> finishSession(event)
            MotionEvent.ACTION_CANCEL -> cancelSession()
            else -> session != null
        }
    }

    /**
     * DOWN 只记坐标，**不做任何视图树遍历**。
     *
     * 目标解析推迟到确认是"向上拖拽"之后再付这次成本（见 [moveSession]）。
     * 普通点击、列表滑动、下拉刷新这些手势都到不了那一步，于是彻底不再为
     * 快捷已读付出遍历开销 —— 这是滑动卡顿的主因。
     *
     * 唯一还会在 DOWN 阶段读的是「上一次解析出的微信页签区域」这个缓存矩形：
     * 命中它之外的手势直接丢弃，连 pending 都不建；缓存还没建立时（进程内首次
     * 触摸）退化成只记录坐标，交由 [moveSession] 判定。
     */
    private fun startPendingSession(activity: Activity, event: MotionEvent): Boolean {
        cachedHomeRegion?.let { region ->
            if (activity.window?.decorView === cachedRoot && !region.contains(event.rawX, event.rawY)) {
                clearGesture(resetActive = false)
                return false
            }
        }
        pending = PendingDrag(activity, event.rawX, event.rawY)
        return false
    }

    private fun moveSession(event: MotionEvent): Boolean {
        val current = session
        if (current == null) {
            val pendingDrag = pending ?: return false
            if (!shouldStartDrag(pendingDrag, event.rawX, event.rawY)) return false
            pending = null
            val target = resolveTarget(pendingDrag.activity, event.rawX, event.rawY)
            if (target == null) return false
            val started = DragSession(
                target.root,
                target.target,
                pendingDrag.downRawX,
                pendingDrag.downRawY
            )
            session = started
            target.target.source?.parent?.requestDisallowInterceptTouchEvent(true)
            target.target.source?.bringToFront()
            started.moveTo(event.rawX, event.rawY)
            return true
        }
        current.moveTo(event.rawX, event.rawY)
        return true
    }

    /** 用 Activity 的资源算 dp，避免为了判断是否起拖而先解析出目标视图。 */
    private fun shouldStartDrag(pendingDrag: PendingDrag, rawX: Float, rawY: Float): Boolean {
        val dx = rawX - pendingDrag.downRawX
        val dy = rawY - pendingDrag.downRawY
        val threshold = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            DRAG_START_THRESHOLD_DP,
            pendingDrag.activity.resources.displayMetrics
        )
        return dy <= -threshold && abs(dy) > abs(dx) * 0.8f
    }

    private fun finishSession(event: MotionEvent): Boolean {
        val current = session
        if (current == null) {
            // DOWN 之后没有形成拖拽（普通点击/滚动），这里什么都不用做。
            pending = null
            return false
        }
        current.moveTo(event.rawX, event.rawY)
        val shouldMarkRead = current.shouldMarkRead()
        if (shouldMarkRead) {
            markAllReadAsync(current)
        } else {
            current.reset()
        }
        session = null
        return true
    }

    /**
     * 已读动作放到后台线程。
     *
     * `markAllRead` 会扫一遍 rconversation 未读会话，再逐个会话做 DB update +
     * 原生已读调用，是**几十到上百次**数据库往返。原来直接在 UI 线程同步执行，
     * 抬手瞬间必然掉帧（会话多时能卡到几百毫秒）。
     *
     * 视图恢复（清理拖拽浮层、还原 alpha）必须回主线程。
     */
    private fun markAllReadAsync(current: DragSession) {
        val context = current.target.contextView.context
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        runCatching {
            markReadExecutor.execute {
                var success = false
                try {
                    success = QuickMarkReadRuntime.markAllRead(context, true) >= 0
                } catch (error: Throwable) {
                    logger("快捷已读触发失败", error)
                }
                val ok = success
                mainHandler.post {
                    if (ok) current.restoreImmediately() else current.reset()
                }
            }
        }.onFailure {
            logger("快捷已读任务提交失败", it)
            current.reset()
        }
    }

    private fun cancelSession(): Boolean {
        pending = null
        val current = session ?: return false
        current.reset()
        session = null
        return true
    }

    private fun clearGesture(resetActive: Boolean) {
        pending = null
        val current = session ?: return
        if (resetActive) current.reset()
        session = null
    }

    /**
     * 解析本次拖拽的目标。
     *
     * 只有「确认是向上拖拽」之后才会走到这里，所以这里的视图树遍历不再影响
     * 普通点击与列表滑动。底栏 / 悬浮栏按 [resolveBottomNav] / [resolveFloatingBar]
     * 的缓存复用，稳定手势下这里只剩一次子树扫描。
     */
    private fun resolveTarget(activity: Activity, rawX: Float, rawY: Float): ResolvedTarget? {
        val root = activity.window?.decorView as? ViewGroup ?: return null
        if (cachedRoot !== root) {
            cachedRoot = root
            cachedBottomNav = null
            cachedFloatingBar = null
            cachedHomeRegion = null
        }
        if (containsSettingsPage(root)) return null
        val target = findUnreadTargetAt(root, rawX, rawY)
        // 缓存「微信」页签区域，供后续 DOWN 阶段零成本排除无关手势。
        cachedHomeRegion = resolvePrimaryHomeRegion(root, rawX, rawY)
        return target?.let { ResolvedTarget(root, it) }
    }

    /**
     * 取「微信」页签的可拖拽区域（悬浮栏优先，其次原生底栏）。
     *
     * 复用已缓存的底栏引用，不额外遍历；解析失败时返回 null，
     * 下次 DOWN 就退化成「只记录坐标」，不影响正确性。
     */
    private fun resolvePrimaryHomeRegion(root: ViewGroup, rawX: Float, rawY: Float): ScreenRect? {
        resolveFloatingBar(root)?.let { floatingBar ->
            findFloatingHomeRegion(root, floatingBar, rawX, rawY)?.let { return it }
            val touchState = (0 until floatingBar.childCount)
                .asSequence()
                .mapNotNull { floatingBar.getChildAt(it).tag as? FloatingBottomBarTouchState }
                .firstOrNull()
            val bounds = touchState?.snapshot()
            if (bounds != null) {
                val rootLocation = IntArray(2)
                val rootWindowLocation = IntArray(2)
                root.getLocationOnScreen(rootLocation)
                root.getLocationInWindow(rootWindowLocation)
                val offsetX = rootWindowLocation[0] - rootLocation[0]
                val offsetY = rootWindowLocation[1] - rootLocation[1]
                return ScreenRect(
                    left = bounds.left + offsetX,
                    top = bounds.top + offsetY,
                    right = bounds.left + offsetX +
                        (bounds.right - bounds.left) / BOTTOM_TAB_LABELS.size,
                    bottom = bounds.bottom + offsetY
                )
            }
        }
        val bottomNav = resolveBottomNav(root) ?: return null
        return findBottomTabRegion(bottomNav, HOME_TAB_LABEL)
    }

    /**
     * 设置页是否盖在最上层。
     *
     * 不走 `root.findViewWithTag` 全树 DFS：设置页是悬浮在最顶层的 overlay 子 View，
     * 从末尾往前查几层就够，且大多数情况下 decorView 只有 1~3 个直接子 View。
     */
    private fun containsSettingsPage(root: ViewGroup): Boolean {
        for (index in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(index)
            if (child.tag == HCHAT_SETTINGS_PAGE_TAG) return true
            if (child is ViewGroup && child.visibility == View.VISIBLE) {
                for (inner in child.childCount - 1 downTo 0) {
                    if (child.getChildAt(inner).tag == HCHAT_SETTINGS_PAGE_TAG) return true
                }
            }
        }
        return false
    }

    private fun findUnreadTargetAt(root: ViewGroup, rawX: Float, rawY: Float): DragTarget? {
        resolveFloatingBar(root)?.let { floatingBar ->
            findFloatingHomeRegion(root, floatingBar, rawX, rawY)?.let { homeRegion ->
                return syntheticDotTarget(root, homeRegion, rawX, rawY)
            }
        }
        val bottomNav = resolveBottomNav(root) ?: return null
        val homeRegion = findBottomTabRegion(bottomNav, HOME_TAB_LABEL) ?: return null
        if (!homeRegion.contains(rawX, rawY)) return null
        val matches = ArrayList<BadgeHit>()
        traverse(bottomNav) { badge ->
            if (!isUnreadBadgeCandidate(badge)) return@traverse
            if (!hitTest(badge, rawX, rawY)) return@traverse
            val width = viewWidth(badge)
            val height = viewHeight(badge)
            val location = IntArray(2)
            badge.getLocationOnScreen(location)
            val dx = rawX - (location[0] + width / 2f)
            val dy = rawY - (location[1] + height / 2f)
            val distance = dx * dx + dy * dy
            val area = width * height
            matches += BadgeHit(badge, distance, area)
        }
        if (matches.isNotEmpty()) {
            return matches.minWithOrNull(
                compareBy<BadgeHit> { it.distance }
                    .thenBy { it.area.takeIf { area -> area > 0 } ?: Int.MAX_VALUE }
            )?.view?.let { dragTargetFromView(it) }
        }
        return syntheticDotTarget(root, homeRegion, rawX, rawY)
    }

    /** 底栏容器：缓存命中直接返回，脱离窗口/不可见/尺寸归零时重解析。 */
    private fun resolveBottomNav(root: ViewGroup): ViewGroup? {
        cachedBottomNav?.let { cached ->
            if (isUsable(cached) && cached.rootView === root.rootView) return cached
        }
        val found = findBottomNavContainer(root)
        cachedBottomNav = found
        return found
    }

    /** 悬浮底栏：同上，按 tag 查一次后缓存。 */
    private fun resolveFloatingBar(root: ViewGroup): ViewGroup? {
        cachedFloatingBar?.let { cached ->
            if (isUsable(cached) && cached.rootView === root.rootView) return cached
        }
        val found = findFloatingBottomBar(root)
        cachedFloatingBar = found
        return found
    }

    private fun isUsable(view: View): Boolean =
        view.isAttachedToWindow && view.isShown && view.visibility == View.VISIBLE &&
            view.width > 0 && view.height > 0

    private fun findFloatingBottomBar(root: ViewGroup): ViewGroup? {
        return root.findViewWithTag<View>(CustomBottomBarFeature.FLOATING_BOTTOM_BAR_TAG)
            ?.takeIf { it.isShown && it.visibility == View.VISIBLE }
            ?.let { it as? ViewGroup }
    }

    private fun findFloatingHomeRegion(
        root: ViewGroup,
        floatingBar: ViewGroup,
        rawX: Float,
        rawY: Float
    ): ScreenRect? {
        val touchState = (0 until floatingBar.childCount)
            .asSequence()
            .mapNotNull { floatingBar.getChildAt(it).tag as? FloatingBottomBarTouchState }
            .firstOrNull()
            ?: return null
        val bounds = touchState.snapshot() ?: return null
        val rootScreenLocation = IntArray(2)
        val rootWindowLocation = IntArray(2)
        root.getLocationOnScreen(rootScreenLocation)
        root.getLocationInWindow(rootWindowLocation)
        val rawWindowX = rawX - rootScreenLocation[0] + rootWindowLocation[0]
        val rawWindowY = rawY - rootScreenLocation[1] + rootWindowLocation[1]
        val barRect = ScreenRect(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom
        )
        if (!barRect.contains(rawWindowX, rawWindowY)) return null
        return ScreenRect(
            left = barRect.left,
            top = barRect.top,
            right = barRect.left + barRect.width() / BOTTOM_TAB_LABELS.size,
            bottom = barRect.bottom
        )
    }

    private fun isUnreadBadgeCandidate(view: View): Boolean {
        if (!view.isShown || view.visibility != View.VISIBLE) return false
        val width = viewWidth(view)
        val height = viewHeight(view)
        if (width <= 0 || height <= 0) return false
        val textView = view as? TextView
        val text = textView?.text?.toString()?.trim().orEmpty()
        val hasUnreadText = textView != null && UNREAD_TEXT.matches(text)
        val isPlainDot = text.isEmpty() &&
            view.background != null &&
            width in dp(view, 4f)..dp(view, 24f) &&
            height in dp(view, 4f)..dp(view, 24f) &&
            width <= height * 2 &&
            height <= width * 2
        if (!hasUnreadText && !isPlainDot) return false
        if (hasUnreadText) {
            if (width !in dp(view, 6f)..dp(view, 72f)) return false
            if (height !in dp(view, 6f)..dp(view, 36f)) return false
            if (!isLightText(textView.currentTextColor)) return false
        }
        return true
    }

    private fun hitTest(view: View, rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val extra = dp(view, 28f)
        val left = location[0] - extra
        val top = location[1] - extra
        val right = location[0] + viewWidth(view) + extra
        val bottom = location[1] + viewHeight(view) + extra
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }

    private fun syntheticDotTarget(root: ViewGroup, homeRegion: ScreenRect, rawX: Float, rawY: Float): DragTarget? {
        if (!homeRegion.contains(rawX, rawY)) return null
        val size = dp(root, 10f).coerceAtLeast(1)
        return DragTarget(
            contextView = root,
            source = null,
            screenLeft = (rawX - size / 2f).toInt(),
            screenTop = (rawY - size / 2f).toInt(),
            width = size,
            height = size,
            text = null,
            textColor = Color.WHITE,
            textSizePx = 0f,
            typeface = null,
            gravity = 0,
            includeFontPadding = false,
            paddingLeft = 0,
            paddingTop = 0,
            paddingRight = 0,
            paddingBottom = 0,
            background = syntheticDotDrawable()
        )
    }

    private fun findBottomTabRegion(bottomNav: ViewGroup, label: String): ScreenRect? {
        val location = IntArray(2)
        bottomNav.getLocationOnScreen(location)
        val navLeft = location[0]
        val navTop = location[1]
        val navWidth = viewWidth(bottomNav)
        val navHeight = viewHeight(bottomNav)
        if (navWidth <= 0 || navHeight <= 0) return null
        val labelView = findTextView(bottomNav, label) ?: return null
        val labelLocation = IntArray(2)
        labelView.getLocationOnScreen(labelLocation)
        val centerX = labelLocation[0] + viewWidth(labelView) / 2f
        val tabWidth = (navWidth / BOTTOM_TAB_LABELS.size.toFloat()).coerceAtLeast(dp(bottomNav, 48f).toFloat())
        return ScreenRect(
            left = centerX - tabWidth / 2f,
            top = navTop.toFloat(),
            right = centerX + tabWidth / 2f,
            bottom = navTop + navHeight * 0.72f
        ).intersect(
            ScreenRect(
                left = navLeft.toFloat(),
                top = navTop.toFloat(),
                right = navLeft + navWidth.toFloat(),
                bottom = navTop + navHeight.toFloat()
            )
        )
    }

    private fun findTextView(view: View, text: String): TextView? {
        if (view is TextView && view.text?.toString()?.trim() == text) return view
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findTextView(group.getChildAt(index), text)?.let { return it }
        }
        return null
    }

    private fun dragTargetFromView(view: View): DragTarget {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val textView = view as? TextView
        return DragTarget(
            contextView = view,
            source = view,
            screenLeft = location[0],
            screenTop = location[1],
            width = viewWidth(view).coerceAtLeast(1),
            height = viewHeight(view).coerceAtLeast(1),
            text = textView?.text,
            textColor = textView?.currentTextColor ?: Color.WHITE,
            textSizePx = textView?.textSize ?: 0f,
            typeface = textView?.typeface,
            gravity = textView?.gravity ?: 0,
            includeFontPadding = textView?.includeFontPadding ?: false,
            paddingLeft = view.paddingLeft,
            paddingTop = view.paddingTop,
            paddingRight = view.paddingRight,
            paddingBottom = view.paddingBottom,
            background = view.background?.constantState?.newDrawable()?.mutate()
        )
    }

    private fun syntheticDotDrawable(): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(250, 81, 81))
        }
    }

    private fun hitTest(view: View, rawX: Float, rawY: Float, extra: Int): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val left = location[0] - extra
        val top = location[1] - extra
        val right = location[0] + viewWidth(view) + extra
        val bottom = location[1] + viewHeight(view) + extra
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }

    private fun findBottomNavContainer(root: ViewGroup): ViewGroup? {
        val rootHeight = root.height.takeIf { it > 0 }
            ?: root.resources.displayMetrics.heightPixels
        val candidates = ArrayList<ViewGroup>()
        traverse(root) { view ->
            val group = view as? ViewGroup ?: return@traverse
            if (!isBottomContainer(group, rootHeight)) return@traverse
            val labels = LinkedHashSet<String>()
            collectTexts(group, depth = 0, labels)
            if (BOTTOM_TAB_LABELS.count { label -> labels.any { it == label } } >= 2) {
                candidates += group
            }
        }
        return candidates.minWithOrNull(
            compareBy<ViewGroup> { viewHeight(it).takeIf { height -> height > 0 } ?: Int.MAX_VALUE }
                .thenBy { viewWidth(it).takeIf { width -> width > 0 } ?: Int.MAX_VALUE }
        )
    }

    private fun isBottomContainer(group: ViewGroup, rootHeight: Int): Boolean {
        val location = IntArray(2)
        group.getLocationOnScreen(location)
        val top = location[1]
        val bottom = top + viewHeight(group)
        val height = viewHeight(group)
        return top >= rootHeight * 0.62f &&
            bottom >= rootHeight * 0.88f &&
            height <= rootHeight * 0.28f
    }

    private fun collectTexts(view: View, depth: Int, out: MutableSet<String>) {
        if (depth > 4) return
        if (view is TextView) {
            view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            view.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        }
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            collectTexts(group.getChildAt(index), depth + 1, out)
        }
    }

    private fun traverse(view: View, block: (View) -> Unit) {
        block(view)
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            traverse(group.getChildAt(index), block)
        }
    }

    private fun isLightText(color: Int): Boolean {
        return Color.red(color) >= 180 && Color.green(color) >= 180 && Color.blue(color) >= 180
    }

    private fun isLauncherUi(activity: Activity): Boolean {
        return activity.javaClass.name == LAUNCHER_UI
    }

    companion object {
        private const val LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI"
        private const val DISPATCH_TOUCH_EVENT = "dispatchTouchEvent"
        private const val HCHAT_SETTINGS_PAGE_TAG = "Hchat:MiuixSettingsPage"
        private const val HOME_TAB_LABEL = "微信"
        // 手势起点只在底部导航附近；屏幕底部 34% 以下的 DOWN 才值得做树遍历。
        private const val BOTTOM_GESTURE_MIN_RATIO = 0.66f
        // 起拖阈值：向上 18dp 且以纵向为主。与拖动过程中的已读阈值（42dp）分开。
        private const val DRAG_START_THRESHOLD_DP = 18f
        private val UNREAD_TEXT = Regex("""\d+\+?|…|\.{2,3}""")
        private val BOTTOM_TAB_LABELS = setOf("微信", "通讯录", "发现", "我")

        private fun viewWidth(view: View): Int {
            return view.width.takeIf { it > 0 } ?: view.measuredWidth
        }

        private fun viewHeight(view: View): Int {
            return view.height.takeIf { it > 0 } ?: view.measuredHeight
        }

        private fun dp(view: View, value: Float): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                view.resources.displayMetrics
            ).toInt()
        }
    }

    private data class BadgeHit(
        val view: View,
        val distance: Float,
        val area: Int
    )

    private data class ScreenRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        fun width(): Float = (right - left).coerceAtLeast(0f)

        fun contains(x: Float, y: Float): Boolean {
            return x >= left && x <= right && y >= top && y <= bottom
        }

        fun intersect(other: ScreenRect): ScreenRect {
            return ScreenRect(
                left = maxOf(left, other.left),
                top = maxOf(top, other.top),
                right = minOf(right, other.right),
                bottom = minOf(bottom, other.bottom)
            )
        }
    }
}

private class PendingDrag(
    val activity: Activity,
    val downRawX: Float,
    val downRawY: Float
)

private class ResolvedTarget(
    val root: ViewGroup,
    val target: DragTarget
)

private class DragSession(
    val root: ViewGroup,
    val target: DragTarget,
    private val downRawX: Float,
    private val downRawY: Float
) {
    private val dragView = createDragView(root, target)
    private val originalAlpha = target.source?.alpha
    private val originalTranslationX = dragView.translationX
    private val originalTranslationY = dragView.translationY
    // 阈值只在构造时算一次：MOVE 每帧都调 shouldMarkRead，
    // 原来每次都走一遍 TypedValue.applyDimension（内部按 resources 重新换算并分配数组）。
    private val markReadThresholdPx = dp(target.contextView, 42f)
    private var hapticSent = false
    private var cleaned = false

    init {
        target.source?.alpha = 0f
        root.overlay.add(dragView)
    }

    fun moveTo(rawX: Float, rawY: Float) {
        val dx = rawX - downRawX
        val dy = rawY - downRawY
        dragView.translationX = originalTranslationX + dx
        dragView.translationY = originalTranslationY + dy
        if (shouldMarkRead() && !hapticSent) {
            target.contextView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            hapticSent = true
        }
    }

    fun shouldMarkRead(): Boolean {
        val dy = dragView.translationY - originalTranslationY
        val dx = dragView.translationX - originalTranslationX
        return dy <= -markReadThresholdPx && abs(dy) > abs(dx) * 0.45f
    }

    fun reset() {
        dragView.animate()
            .translationX(originalTranslationX)
            .translationY(originalTranslationY)
            .setDuration(120L)
            .withEndAction { cleanup() }
            .start()
        target.source?.parent?.requestDisallowInterceptTouchEvent(false)
    }

    fun restoreImmediately() {
        dragView.animate().cancel()
        cleanup()
        target.source?.parent?.requestDisallowInterceptTouchEvent(false)
    }

    companion object {
        private fun createDragView(root: ViewGroup, target: DragTarget): View {
            val rootLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            val width = target.width
            val height = target.height
            val left = target.screenLeft - rootLocation[0]
            val top = target.screenTop - rootLocation[1]
            val ghost = if (target.text != null) {
                TextView(target.contextView.context).apply {
                    text = target.text
                    setTextColor(target.textColor)
                    textSize = target.textSizePx / resources.displayMetrics.scaledDensity
                    typeface = target.typeface
                    gravity = target.gravity
                    includeFontPadding = target.includeFontPadding
                    setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, target.paddingBottom)
                    background = target.background
                }
            } else {
                View(target.contextView.context).apply {
                    background = target.background
                }
            }
            ghost.alpha = target.source?.alpha ?: 1f
            ghost.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            ghost.layout(left, top, left + width, top + height)
            ghost.translationX = target.source?.translationX ?: 0f
            ghost.translationY = target.source?.translationY ?: 0f
            ghost.elevation = (target.source?.elevation ?: 0f) + dp(target.contextView, 8f)
            return ghost
        }

        private fun dp(view: View, value: Float): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                view.resources.displayMetrics
            ).toInt()
        }
    }

    private fun cleanup() {
        if (cleaned) return
        cleaned = true
        runCatching { root.overlay.remove(dragView) }
        if (originalAlpha != null) {
            target.source?.alpha = originalAlpha
        }
    }
}

private data class DragTarget(
    val contextView: View,
    val source: View?,
    val screenLeft: Int,
    val screenTop: Int,
    val width: Int,
    val height: Int,
    val text: CharSequence?,
    val textColor: Int,
    val textSizePx: Float,
    val typeface: Typeface?,
    val gravity: Int,
    val includeFontPadding: Boolean,
    val paddingLeft: Int,
    val paddingTop: Int,
    val paddingRight: Int,
    val paddingBottom: Int,
    val background: Drawable?
)
