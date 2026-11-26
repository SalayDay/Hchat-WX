package h.Hchat.hooks.items.bottombar

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class BottomBarAutoHideSettingsProvider : SimpleFeatureSettingsProvider(
    BottomBarAutoHideFeature.ID,
    "悬浮底栏滑动隐藏",
    "首页消息列表向下浏览时收起底栏，向上滑或停止后弹回",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)