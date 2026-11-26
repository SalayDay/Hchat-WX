package h.Hchat.hooks.items.addemoji

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class AddEmojiSettingsProvider : SimpleFeatureSettingsProvider(
    AddEmojiFeature.ID,
    "图片添加到表情",
    "长按聊天图片或朋友圈图片，直接加入自定义表情",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)