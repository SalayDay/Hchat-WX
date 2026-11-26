package h.Hchat.hooks.items.chatanimation

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class ChatMessageEnterAnimationSettingsProvider : SimpleFeatureSettingsProvider(
    ChatMessageEnterAnimationFeature.ID,
    "消息入场动效",
    "新消息气泡以缩放淡入的方式出现，列表更跟手",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)