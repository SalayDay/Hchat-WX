package h.Hchat.hooks.items.theme

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class GlobalFontSettingsProvider : SimpleFeatureSettingsProvider(
    GlobalFontFeature.ID,
    "全局字体",
    "导入 ttf/otf 替换全局字体，并可调字重",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)