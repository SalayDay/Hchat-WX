package h.Hchat.hooks.items.bottombarstyle

import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

/**
 * 悬浮底栏样式：Tab 果冻缩放、红点呼吸、红点颜色。
 *
 * 底栏由 `CustomBottomBarContent` 绘制，样式在绘制时直接读这里配置的偏好，
 * 所以本功能不需要安装任何 hook。
 */
class BottomBarStyleFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "底栏动效与红点"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(BottomBarStyleSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        // 纯样式项：底栏绘制时自行读取配置，没有需要安装的 hook。
    }

    companion object {
        const val ID = BottomBarStyleStore.FEATURE_ID
    }
}

class BottomBarStyleSettingsProvider : SimpleFeatureSettingsProvider(
    BottomBarStyleFeature.ID,
    "底栏动效与红点",
    "Tab 果冻缩放、红点呼吸光晕与红点配色",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)