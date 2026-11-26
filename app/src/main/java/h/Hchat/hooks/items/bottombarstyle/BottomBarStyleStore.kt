package h.Hchat.hooks.items.bottombarstyle

import android.content.Context
import androidx.compose.ui.graphics.Color
import h.Hchat.preferences.ConfigStore

/**
 * 悬浮底栏的动效与红点样式。
 *
 * 底栏是模块自己的 Compose 内容，所以这些样式**直接改源码里的绘制参数**，
 * 不需要 hook 微信任何东西，也不会和其它功能抢属性。
 */
object BottomBarStyleStore {
    const val FEATURE_ID = "bottom_bar_style"
    const val KEY_JELLY = "jelly"
    const val KEY_BADGE_BREATH = "badge_breath"
    const val KEY_BADGE_COLOR = "badge_color"

    val COLORS = intArrayOf(0xFFFF3B30.toInt(), 0xFF8E9AAF.toInt(), 0xFFD98CA0.toInt(), 0xFF6E8B74.toInt())
    val COLOR_LABELS = arrayOf("微信红", "莫兰迪灰蓝", "柔粉", "雾绿")

    fun jellyEnabled(context: Context): Boolean = read(context).getBoolean(KEY_JELLY, false)

    fun breathEnabled(context: Context): Boolean = read(context).getBoolean(KEY_BADGE_BREATH, false)

    fun badgeColor(context: Context): Color {
        val index = read(context).getInt(KEY_BADGE_COLOR, 0).coerceIn(0, COLORS.size - 1)
        return Color(COLORS[index])
    }

    fun badgeColorIndex(context: Context): Int =
        read(context).getInt(KEY_BADGE_COLOR, 0).coerceIn(0, COLORS.size - 1)

    fun setBadgeColorIndex(context: Context, index: Int) {
        read(context).edit().putInt(KEY_BADGE_COLOR, index.coerceIn(0, COLORS.size - 1)).apply()
    }

    private fun read(context: Context) =
        ConfigStore(context).getFeaturePrefs(FEATURE_ID)
}