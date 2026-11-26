package h.Hchat.hooks.items.groupadminlabel

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class GroupAdminLabelSettingsProvider : SimpleFeatureSettingsProvider(
    GroupAdminLabelFeature.ID,
    "群聊管理员标签显示",
    "在群聊成员昵称前显示群主 / 管理员 / 部分真名标签",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)