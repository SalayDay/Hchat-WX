package h.Hchat.hooks.items.theme

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class GlobalBackgroundSettingsProvider : SimpleFeatureSettingsProvider(
    GlobalBackgroundFeature.ID,
    "全局背景",
    "首页 / 通讯录 / 发现 / 我 统一背景图，可离线模糊",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)