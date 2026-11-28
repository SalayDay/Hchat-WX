# API 102 原生迁移前的完整功能与 Hook 清单

审计日期：2026-11-27。源码基线：`7efada6`，分支：`alt-entry`。本清单在业务源码修改前生成。

- 注册功能入口共 111 个（包括微信公共 API）；下列全部保留，不以删除功能完成迁移。
- items 范围共 303 个 Hook 注册或包装调用点，其中 18 个为转发，不等同于运行时方法数量；271 个匿名旧回调加 1 个具名回调。
- 固定目标列出源码类名、方法名及签名；动态目标列出实际定位器、过滤条件及注册表达式。没有用猜测的混淆名称替代动态结果。
- 行号均指迁移前的源码基线，不表示迁移后的行号。
- 入口、公共 API 与设置界面附加 Hook 见 `API_102_PLATFORM_INVENTORY.md`。
- 完成迁移不等于真机已验证；外部脚本 Hook ABI 按用户确认同步迁移，不保留 API 86 兼容层。

## 全部注册功能

每行：顺序、注册表行号、类、featureId、功能名、源码位置。源码位置相对于 `app/src/main/java/h/Hchat/`。

```text
REGISTRY_BEGIN
001 R123 SettingsFeature | "settings" | "设置入口" | hooks/items/settings/SettingsFeature.kt:18
002 R124 WechatApiFeature | "wechat_api" | "微信公共API" | hooks/api/core/WechatApiFeature.java:33
003 R126 FloatingShortcutFeature | "floating_shortcut_menu" | "悬浮快捷菜单" | hooks/items/floatingshortcut/FloatingShortcutFeature.kt:10
004 R127 CustomBottomBarFeature | "custom_bottom_bar" | "自定义底栏" | hooks/items/custombottombar/CustomBottomBarFeature.kt:67
005 R128 RoundAvatarFeature | "round_avatar" | "圆角头像" | hooks/items/roundavatar/RoundAvatarFeature.kt:34
006 R129 CustomFriendAvatarFeature | "custom_friend_avatar" | "自定义头像" | hooks/items/customfriendavatar/CustomFriendAvatarFeature.kt:51
007 R130 RealNameTailFeature | "real_name_tail" | "实名尾字" | hooks/items/realtail/RealNameTailFeature.kt:17
008 R131 MemberTitleFeature | "member_title" | "群员头衔" | hooks/items/membertitle/MemberTitleFeature.kt:12
009 R132 AutoRedPacketFeature | "auto_redpacket" | "自动抢红包" | hooks/items/payment/core/AutoRedPacketFeature.kt:14
010 R133 AutoGiftFeature | "auto_gift" | "自动抢礼物" | hooks/items/payment/gift/AutoGiftFeature.kt:14
011 R134 WeChatTabletFeature | "wechat_tablet" | "平板模式" | hooks/items/tablet/WeChatTabletFeature.kt:22
012 R135 AutoTransferFeature | "auto_transfer" | "自动收款" | hooks/items/payment/transfer/AutoTransferFeature.kt:24
013 R136 FakeWalletBalanceFeature | "fake_wallet_balance" | "伪造零钱" | hooks/items/payment/fakebalance/FakeWalletBalanceFeature.kt:30
014 R137 AntiRecallFeature | "anti_recall" | "防撤回" | hooks/items/antirecall/AntiRecallFeature.kt:55
015 R138 MultiRecallFeature | "multi_recall" | "多选撤回" | hooks/items/multirecall/MultiRecallFeature.kt:25
016 R139 DisableHotUpdateFeature | "disable_hot_update" | "屏蔽热更新" | hooks/items/hotupdate/DisableHotUpdateFeature.kt:19
017 R140 ProfileIdFeature | "profile_id" | "资料页显示ID" | hooks/items/profileid/ProfileIdFeature.kt:24
018 R141 HideChatMenuFeature | "hide_chat_menu" | "隐藏长按菜单" | hooks/items/hidemenu/HideChatMenuFeature.kt:23
019 R142 QuickContactEditFeature | "quick_contact_edit" | "快捷设置备注和标签" | hooks/items/quickcontactedit/QuickContactEditFeature.kt:41
020 R143 QuickGroupChatLabelFeature | "quick_group_chat_label" | "快捷设置群聊标签" | hooks/items/grouplabel/QuickGroupChatLabelFeature.kt:33
021 R144 QuickMomentsFeature | "quick_moments" | "快捷查看朋友圈" | hooks/items/quickmoments/QuickMomentsFeature.kt:36
022 R145 QuickMarkReadFeature | "quick_mark_read" | "快捷已读" | hooks/items/quickread/QuickMarkReadFeature.kt:27
023 R146 QuickTerminateFeature | "quick_terminate" | "快捷终止" | hooks/items/quickterminate/QuickTerminateFeature.kt:10
024 R147 AutoReplyFeature | "auto_reply" | "自动回复" | hooks/items/autoreply/AutoReplyFeature.kt:11
025 R148 AutoMessageForwardFeature | "auto_message_forward" | "消息自动转发" | hooks/items/automessageforward/AutoMessageForwardFeature.kt:12
026 R149 ConversationGroupFeature | "conversation_group" | "聊天分组" | hooks/items/conversationgroup/ConversationGroupFeature.kt:8
027 R150 ConversationTabsFeature | "conversation_tabs" | "标签分组" | hooks/items/conversationtabs/ConversationTabsFeature.kt:7
028 R151 MessageAffixFeature | "message_affix" | "发送文本格式" | hooks/items/messageaffix/MessageAffixFeature.kt:17
029 R152 TypingReportBlockFeature | "block_typing_report" | "拦截正在输入上报" | hooks/items/typingreport/TypingReportBlockFeature.kt:19
030 R153 PatBlockFeature | "disable_pat" | "禁止拍一拍" | hooks/items/patblock/PatBlockFeature.kt:20
031 R154 AutoOriginalImageFeature | "auto_original_image" | "自动勾选原图" | hooks/items/autooriginal/AutoOriginalImageFeature.kt:20
032 R155 AutoViewOriginalFeature | "auto_view_original" | "自动查看原图" | hooks/items/autovieworiginal/AutoViewOriginalFeature.kt:20
033 R156 RemoveForwardLimitFeature | "remove_forward_limit" | "移除转发限制" | hooks/items/forwardlimit/RemoveForwardLimitFeature.kt:24
034 R157 AtAllNotificationBlockFeature | "block_at_all_notification" | "屏蔽艾特所有人" | hooks/items/atallnotify/AtAllNotificationBlockFeature.kt:11
035 R158 CallMediaLimitFeature | "call_media_limit" | "移除通话媒体限制" | hooks/items/callmedialimit/CallMediaLimitFeature.kt:22
036 R159 CallRingtoneBlockFeature | "call_ringtone_block" | "屏蔽通话铃声" | hooks/items/callmedialimit/CallRingtoneBlockFeature.kt:20
037 R160 CustomNotificationFeature | "custom_notification" | "自定义通知" | hooks/items/customnotify/CustomNotificationFeature.kt:10
038 R161 KeywordNotificationFeature | "keyword_notification" | "关键词通知" | hooks/items/keywordnotify/KeywordNotificationFeature.kt:10
039 R162 TextSpeechFeature | "text_speech" | "文字转语音播报" | hooks/items/textspeech/TextSpeechFeature.kt:17
040 R163 TextVoiceFeature | "text_voice" | "文本转语音" | hooks/items/textvoice/TextVoiceFeature.kt:12
041 R164 ZombieCheckFeature | "zombie_check" | "僵尸粉检测" | hooks/items/zombiecheck/ZombieCheckFeature.kt:23
042 R165 WeChatKeepAliveFeature | "wechat_keep_alive" | "微信强保活" | hooks/items/keepalive/WeChatKeepAliveFeature.kt:7
043 R166 QuoteDeleteClearFeature | "quote_delete_clear" | "删除键清引用" | hooks/items/quoteclear/QuoteDeleteClearFeature.kt:27
044 R167 SwipeQuoteFeature | "swipe_quote" | "滑动手势" | hooks/items/swipequote/SwipeQuoteFeature.kt:50
045 R168 AudioTransformFeature | "audio_transform" | "音频转换" | hooks/items/audiotransform/AudioTransformFeature.kt:7
046 R169 FakeVoiceDurationFeature | "fake_voice_duration" | "伪造语音时长" | hooks/items/fakevoiceduration/FakeVoiceDurationFeature.kt:20
047 R170 ChatTimeStyleFeature | "chat_time_style" | "会话时间样式" | hooks/items/chattime/ChatTimeStyleFeature.kt:34
048 R171 InputHintFeature | "input_hint" | "输入框提示" | hooks/items/inputhint/InputHintFeature.kt:24
049 R172 LegacyChatInputFeature | "restore_legacy_chat_input" | "恢复旧版聊天输入框" | hooks/items/legacychatinput/LegacyChatInputFeature.kt:12
050 R173 LinkParseFeature | "link_parse" | "链接解析" | hooks/items/linkparse/LinkParseFeature.kt:10
051 R174 MessageBubbleFeature | "message_bubble" | "消息气泡" | hooks/items/messagebubble/MessageBubbleFeature.kt:38
052 R175 ChatMessageEnterAnimationFeature | "chat_message_enter_animation" | "消息入场动效" | hooks/items/chatanimation/ChatMessageEnterAnimationFeature.kt:32
053 R176 BottomBarAutoHideFeature | "bottom_bar_auto_hide" | "悬浮底栏滑动隐藏" | hooks/items/bottombar/BottomBarAutoHideFeature.kt:33
054 R177 BottomBarStyleFeature | "bottom_bar_style" | "底栏动效与红点" | hooks/items/bottombarstyle/BottomBarStyleFeature.kt:15
055 R178 ChatPurifyFeature | "chat_purify" | "界面净化" | hooks/items/purify/ChatPurifyFeature.kt:32
056 R179 MenuStyleFeature | "menu_style" | "长按菜单磨砂卡片" | hooks/items/menustyle/MenuStyleFeature.kt:27
057 R180 GlobalBackgroundFeature | "global_background" | "全局背景" | hooks/items/theme/GlobalBackgroundFeature.kt:41
058 R181 GlobalFontFeature | "global_font" | "全局字体" | hooks/items/theme/GlobalFontFeature.kt:29
059 R182 ThemePackFeature | "theme_pack" | "主题包" | hooks/items/theme/ThemePackFeature.kt:30
060 R183 ChatImmersionFeature | "chat_immersion" | "聊天沉浸式" | hooks/items/theme/ChatImmersionFeature.kt:31
061 R184 MessageTextColorFeature | "message_text_color" | "消息文本颜色" | hooks/items/messagetextcolor/MessageTextColorFeature.kt:39
062 R185 HomeTextColorFeature | "home_text_color" | "首页文字颜色" | hooks/items/hometextcolor/HomeTextColorFeature.kt:50
063 R186 HideChatAvatarFeature | "hide_chat_avatar" | "隐藏头像" | hooks/items/hideavatar/HideChatAvatarFeature.kt:26
064 R187 MessageBlockFeature | "message_block" | "屏蔽消息" | hooks/items/messageblock/MessageBlockFeature.kt:22
065 R188 FakeLocationFeature | "fake_location" | "虚拟定位" | hooks/items/fakelocation/FakeLocationFeature.kt:23
066 R189 GameEmojiFeature | "game_emoji_result" | "指定骰子猜拳" | hooks/items/gameemoji/GameEmojiFeature.kt:22
067 R190 FakeMiniProgramBaseLibFeature | "fake_mini_program_base_lib" | "兼容低版本小程序" | hooks/items/miniprogrambaselib/FakeMiniProgramBaseLibFeature.kt:22
068 R191 MiniProgramForceShareFeature | "mini_program_force_share" | "小程序强制分享" | hooks/items/miniprogramforceshare/MiniProgramForceShareFeature.kt:36
069 R192 SkipMiniProgramVideoAdsFeature | "skip_mini_program_video_ads" | "跳过小程序视频广告" | hooks/items/miniprogramvideoad/SkipMiniProgramVideoAdsFeature.kt:15
070 R193 SkipGlobalMiniProgramSplashAdsFeature | "skip_global_mini_program_splash_ads" | "跳过全局小程序开屏广告" | hooks/items/miniprogramsplashad/SkipGlobalMiniProgramSplashAdsFeature.kt:20
071 R194 QQMusicOrderFeature | "qq_music_order" | "QQ点歌" | hooks/items/musicorder/QQMusicOrderFeature.kt:12
072 R195 MomentsAutoLikeFeature | "moments_auto_like" | "朋友圈自动点赞" | hooks/items/moments/MomentsAutoLikeFeature.kt:29
073 R196 MomentsAutoCommentFeature | "moments_auto_comment" | "朋友圈自动评论" | hooks/items/moments/MomentsAutoCommentFeature.kt:29
074 R197 MomentsAutoForwardFeature | "moments_auto_forward" | "朋友圈自动转发" | hooks/items/moments/MomentsAutoForwardFeature.kt:34
075 R198 MomentsAutoRefreshFeature | "moments_auto_refresh" | "朋友圈自动刷新" | hooks/items/moments/MomentsAutoRefreshFeature.kt:16
076 R199 MomentsKeywordBlockFeature | "moments_keyword_block" | "朋友圈关键词屏蔽" | hooks/items/moments/MomentsKeywordBlockFeature.kt:37
077 R200 MomentsContactFilterFeature | "moments_contact_filter" | "朋友圈过滤" | hooks/items/moments/MomentsContactFilterFeature.kt:34
078 R201 MomentsBottomDetailFeature | "moments_bottom_detail" | "朋友圈底部详情" | hooks/items/moments/MomentsBottomDetailFeature.kt:28
079 R202 MomentsPostNotificationFeature | "moments_post_notification" | "朋友圈发布通知" | hooks/items/moments/MomentsPostNotificationFeature.kt:35
080 R203 OriginalMomentsUploadFeature | "original_moments_upload" | "朋友圈原图上传" | hooks/items/moments/OriginalMomentsUploadFeature.kt:22
081 R204 MomentsUploadTailFeature | "moments_upload_tail" | "朋友圈上传尾巴" | hooks/items/moments/MomentsUploadTailFeature.kt:16
082 R205 SnsAntiRecallFeature | "sns_anti_recall" | "朋友圈防撤回" | hooks/items/moments/SnsAntiRecallFeature.kt:27
083 R206 MomentsFakeInteractionFeature | "moments_fake_interaction" | "朋友圈伪互动" | hooks/items/momentsfake/MomentsFakeInteractionFeature.kt:21
084 R207 GroupChatLabelFeature | "group_chat_label" | "群聊标签" | hooks/items/grouplabel/GroupChatLabelFeature.kt:7
085 R208 GroupAdminLabelFeature | "group_admin_label" | "群聊管理员标签显示" | hooks/items/groupadminlabel/GroupAdminLabelFeature.kt:35
086 R209 GroupLeaveMonitorFeature | "group_leave_monitor" | "进退群监控" | hooks/items/groupleave/GroupLeaveMonitorFeature.kt:10
087 R210 GroupRenameMonitorFeature | "group_rename_monitor" | "改名监控" | hooks/items/grouprename/GroupRenameMonitorFeature.kt:11
088 R211 RemoveMomentsAdsFeature | "remove_moments_ads" | "去除朋友圈广告" | hooks/items/moments/RemoveMomentsAdsFeature.kt:14
089 R212 MomentsVideoAutoPlayFeature | "moments_video_autoplay" | "禁止朋友圈视频自动播放" | hooks/items/moments/MomentsVideoAutoPlayFeature.kt:64
090 R213 EditMessageFeature | "edit_message" | "修改聊天记录" | hooks/items/editmsg/EditMessageFeature.kt:32
091 R214 EmojiSaveFeature | "emoji_save" | "保存表情" | hooks/items/emojisave/EmojiSaveFeature.kt:35
092 R215 AddEmojiFeature | "add_emoji_from_image" | "图片保存为表情" | hooks/items/addemoji/AddEmojiFeature.kt:42
093 R216 EmojiCollectionLimitFeature | "emoji_collection_limit" | "扩展表情收藏上限" | hooks/items/emojilimit/EmojiCollectionLimitFeature.kt:20
094 R217 ScreenshotForwardButtonFeature | "screenshot_forward_button" | "隐藏截图转发按钮" | hooks/items/screenshot/ScreenshotForwardButtonFeature.kt:53
095 R218 StackTraceGuardFeature | "anti_detect_stack" | "隐藏模块调用栈" | hooks/items/antidetect/StackTraceGuardFeature.kt:53
096 R219 XposedProbeFeature | "anti_detect_xposed_probe" | "伪装Xposed探测" | hooks/items/antidetect/XposedProbeFeature.kt:44
097 R220 TelemetryGuardFeature | "anti_detect_telemetry" | "拦截风控上报" | hooks/items/antidetect/TelemetryGuardFeature.kt:42
098 R221 VoicePreviewFeature | "voice_preview" | "语音消息预览" | hooks/items/voicepreview/VoicePreviewFeature.kt:35
099 R222 VoiceForwardFeature | "voice_forward" | "语音转发保存" | hooks/items/voiceforward/VoiceForwardFeature.kt:60
100 R223 SelectedMessagesFeature | "selected_messages" | "群发助手" | hooks/items/selectedmessages/SelectedMessagesFeature.kt:38
101 R224 MessageForwardFeature | "message_forward" | "转发" | hooks/items/messageforward/MessageForwardFeature.kt:57
102 R225 ScheduledTaskFeature | "scheduled_task" | "定时任务" | hooks/items/scheduledtask/ScheduledTaskFeature.kt:24
103 R226 FakeScanCameraFeature | "fake_scan_camera" | "模拟相机扫码" | hooks/items/fakescancamera/FakeScanCameraFeature.kt:20
104 R227 ProtobufPacketFeature | "protobuf_packet" | "Protobuf" | hooks/items/protobuf/ProtobufPacketFeature.kt:10
105 R228 StatusTextLimitFeature | "status_text_limit" | "解除状态词长度限制" | hooks/items/statuslimit/StatusTextLimitFeature.kt:13
106 R229 RemoveSubscribeAdsFeature | "remove_subscribe_ads" | "去除公众号信息流广告" | hooks/items/subscribe/RemoveSubscribeAdsFeature.kt:106
107 R230 RemoveArticleAdsFeature | "remove_article_ads" | "去除公众号文章内广告" | hooks/items/articleads/RemoveArticleAdsFeature.kt:28
108 R231 SportStepFeature | "sport_step" | "微信运动步数" | hooks/items/sportstep/SportStepFeature.kt:30
109 R232 FinderMediaDownloadFeature | "finder_media_download" | "视频号媒体下载" | hooks/items/shortvideo/FinderMediaDownloadFeature.kt:35
110 R233 HchatExtraFeature | "hchat_extra" | "分支扩展功能" | hooks/items/hchatextra/HchatExtraFeature.kt:67
111 R234 ScriptPluginFeature | "script_plugin" | "脚本插件" | hooks/items/script/ScriptPluginFeature.kt:12
REGISTRY_END
```

## 功能实现中的全部 Hook 站点

`FILE` 为相对源码路径；`L` 为行号；`TARGET` 为注册目标；`ORIGIN` 为来源函数；`LOCAL_LOCATOR` 为进一步定位入口。无直接 Hook 的功能由公共 API、事件或设置渲染实现，其功能入口已完整列在上表。

```text
SITES_BEGIN

FILE hooks/items/addemoji/AddEmojiFeature.kt
L149 [包装调用] hook TARGET=method CALLBACK=after IN=installChatMenu@144
L156 [包装调用] hook TARGET=method CALLBACK=before IN=installChatMenu@144
L263 [转发] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=callback IN=hook@259
ORIGIN installChatMenu L144 fun installChatMenu(): Boolean
  148: val createHooked = ChatPresenterMenuLocator.createMethods(context, logger).count { method ->
  149: hook(method,
  155: val clickHooked = ChatPresenterMenuLocator.clickMethods(context, logger).count { method ->
  156: hook(method,
  164: return createHooked > 0 && clickHooked > 0
ORIGIN hook L259 fun hook(method: Method, callback: XC_MethodHook): Boolean
  259: fun hook(method: Method, callback: XC_MethodHook): Boolean {
  260: if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
  261: if (!hookedMethods.add(method)) return true
  262: return runCatching {
  263: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
  266: hookedMethods.remove(method)
  267: logger("图片保存为表情 Hook 安装失败: ${method.toGenericString()}", it)
  CALLER L149 hook(method, object : XC_MethodHook() {
  CALLER L156 hook(method, object : XC_MethodHook() {

FILE hooks/items/antidetect/StackTraceGuardFeature.kt
L100 [终端] HookRegistry.get().hook TARGET=method CALLBACK=stackHook IN=onFeatureInstall@75
ORIGIN onFeatureInstall L75 fun onFeatureInstall(context: FeatureContext)
  76: val config = context.configStore()
  80: val stackHook =
  94: var installed = 0
  95: for (owner in arrayOf(Thread::class.java, Throwable::class.java)) {
  96: val methods = KavaReflector.declaredMethods(owner)
  97: .filter { it.name == "getStackTrace" && it.parameterTypes.isEmpty() }
  98: for (method in methods) {
  100: HookRegistry.get().hook(method, stackHook)
  103: logError("挂载 ${owner.name}#${method.name} 失败", e)
  109: return
  111: HLog.e("$TAG 栈帧过滤已挂载 owners=2 methods=$installed，微信 ${wechatVersion()}")

FILE hooks/items/antidetect/XposedProbeFeature.kt
L97 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=installPackageManagerHooks@81
L125 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installPackageManagerHooks@81
ORIGIN installPackageManagerHooks L81 fun installPackageManagerHooks(loader: ClassLoader): Int
  82: val pmClass = runCatching {
  83: Class.forName("android.app.ApplicationPackageManager", false, loader)
  87: return 0
  89: var installed = 0
  90: for (method in pmClass.declaredMethods.toList()) {
  94: (method.name == "getInstalledPackages" || method.name == "getInstalledApplications")
  95: && method.parameterTypes.size == 1 -> {
  97: HookRegistry.get().hook(method,
  117: logError("挂载 ${method.name} 失败", e)
  121: (method.name == "getPackageInfo" || method.name == "getApplicationInfo")
  122: && method.parameterTypes.isNotEmpty()
  123: && method.parameterTypes[0] == String::class.java -> {
  125: HookRegistry.get().hook(method,
  139: logError("挂载 ${method.name} 失败", e)
  144: return installed

FILE hooks/items/antirecall/AntiRecallFeature.kt
L110 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installDexHooks@101
L255 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installMessageStorageRevokeHook@249
L285 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installMsgProcessingClearHook@279
L306 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installLegacySelfRecallMediaCleanupHook@300
L464 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookDatabaseUpdates@458
ORIGIN installDexHooks L101 fun installDexHooks(context: FeatureContext): Boolean
  101: fun installDexHooks(context: FeatureContext): Boolean {
  102: val revokeEntries = locateRevokeEntries(context)
  107: var installed = false
  108: revokeEntries.map { it.method }.forEach { method ->
  110: HookRegistry.get().hook(method,
  136: if (revokeEntries.isEmpty()) {
  140: return revokeEntryInstalled &&
  LOCAL_LOCATOR locateRevokeEntries L1024 fun locateRevokeEntries(context: FeatureContext): List<RevokeEntry>
ORIGIN installMessageStorageRevokeHook L249 fun installMessageStorageRevokeHook(context: FeatureContext, revokeEntries: List<RevokeEntry>)
  250: if (messageStorageHookInstalled) return
  251: val methods = locateMessageStorageUpdateMethods(context, revokeEntries)
  252: var installed = false
  253: methods.forEach { method ->
  255: HookRegistry.get().hook(method,
  274: if (methods.isEmpty()) {
  LOCAL_LOCATOR locateMessageStorageUpdateMethods L321 fun locateMessageStorageUpdateMethods( context: FeatureContext, revokeEntries: List<RevokeEntry> ): List<Method>
ORIGIN installMsgProcessingClearHook L279 fun installMsgProcessingClearHook(context: FeatureContext)
  280: if (msgProcessingHookInstalled) return
  281: val methods = locateMsgProcessingClearMethods(context)
  282: var installed = false
  283: methods.forEach { method ->
  285: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateMsgProcessingClearMethods L1046 fun locateMsgProcessingClearMethods(context: FeatureContext): List<Method>
ORIGIN installLegacySelfRecallMediaCleanupHook L300 fun installLegacySelfRecallMediaCleanupHook(context: FeatureContext)
  301: if (legacyCleanupHookInstalled) return
  302: val methods = locateLegacySelfRecallMediaCleanupMethods(context)
  303: var installed = false
  304: methods.forEach { method ->
  306: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateLegacySelfRecallMediaCleanupMethods L1077 fun locateLegacySelfRecallMediaCleanupMethods(context: FeatureContext): List<Method>
ORIGIN hookDatabaseUpdates L458 fun hookDatabaseUpdates(className: String, context: FeatureContext): Int
  458: fun hookDatabaseUpdates(className: String, context: FeatureContext): Int {
  459: val dbClass = KavaReflector.loadClass(className, context.hostClassLoader())
  460: ?: return 0
  461: var count = 0
  462: for (method in KavaReflector.declaredMethods(dbClass)) {
  463: if (!isUpdateMethod(method)) continue
  464: HookRegistry.get().hook(method,
  472: return count
  LOCAL_LOCATOR isUpdateMethod L475 fun isUpdateMethod(method: Method): Boolean
  CALLER L454 count += hookDatabaseUpdates("com.tencent.wcdb.database.SQLiteDatabase", context)
  CALLER L455 count += hookDatabaseUpdates("android.database.sqlite.SQLiteDatabase", context)

FILE hooks/items/articleads/RemoveArticleAdsFeature.kt
L139 [终端] HookRegistry.get().hook TARGET=finish CALLBACK=after IN=install@109
L150 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=install@109
ORIGIN install L109 fun install()
  110: val version = context.hostContext().packageManager
  115: return
  117: val loader = context.hostClassLoader()
  118: val web = KavaReflector.loadClass("com.tencent.xweb.WebView", loader)
  120: val mmWeb = KavaReflector.loadClass("com.tencent.mm.ui.widget.MMWebView", loader)
  122: val client = KavaReflector.loadClass("com.tencent.mm.plugin.webview.core.f3", loader)
  124: val activity = KavaReflector.loadClass("com.tencent.mm.plugin.webview.ui.tools.WebViewUI", loader)
  126: val finish = KavaReflector.findDeclaredMethod(client, "p", web, String::class.java)
  128: check(finish.returnType == Void.TYPE && !KavaReflector.isStatic(finish))
  129: evaluate = KavaReflector.findMethodRecursive(
  132: getUrl = KavaReflector.findMethodRecursive(mmWeb, "getUrl")
  136: val webField = KavaReflector.findDeclaredField(activity, "f")
  138: check(webField.type == mmWeb && evaluate.returnType == Void.TYPE && getUrl.returnType == String::class.java)
  148: for (name in listOf("onResume", "onPause", "onDestroy")) {
  149: val method = KavaReflector.findDeclaredMethod(activity, name) ?: continue
  150: HookRegistry.get().hook(method,

FILE hooks/items/autooriginal/AutoOriginalImageFeature.kt
L59 [包装调用] hookMethod TARGET=method CALLBACK=trailing lambda / caller IN=install@51
L74 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookMethod@69
ORIGIN install L51 fun install(): Boolean
  52: var installed = 0
  53: TARGET_CLASSES.forEach { className ->
  54: val clazz = KavaReflector.loadClass(className, context.hostClassLoader()) ?: return@forEach
  55: val method = KavaReflector.findDeclaredMethod(clazz, "onCreate", Bundle::class.java)
  56: ?: return@forEach
  57: if (hookedMethods.contains(method)) {
  59: } else if (hookMethod(method)) {
  66: return installed > 0
ORIGIN hookMethod L69 fun hookMethod(method: Method): Boolean
  69: fun hookMethod(method: Method): Boolean {
  70: if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) {
  71: return false
  73: return runCatching {
  74: HookRegistry.get().hook(method,
  90: hookedMethods.add(method)
  93: HLog.e("$TAG 安装图片发送页面 Hook 失败: ${method.toGenericString()}", it)
  CALLER L59 } else if (hookMethod(method)) {

FILE hooks/items/autovieworiginal/AutoViewOriginalFeature.kt
L83 [终端] HookRegistry.get().hook TARGET=onResume!! CALLBACK=after IN=install@67
L90 [终端] HookRegistry.get().hook TARGET=onDestroy!! CALLBACK=before IN=install@67
L127 [终端] HookRegistry.get().hook TARGET=callback CALLBACK=after IN=bindPageListener@111
ORIGIN install L67 fun install(): Boolean
  68: if (installed) return true
  69: val activityClass = KavaReflector.loadClass(
  74: return false
  76: val onResume = KavaReflector.findDeclaredMethod(activityClass, "onResume")
  77: val onDestroy = KavaReflector.findDeclaredMethod(activityClass, "onDestroy")
  78: if (!isHookable(onResume) || !isHookable(onDestroy)) {
  80: return false
  82: return runCatching {
  LOCAL_LOCATOR isHookable L242 fun isHookable(method: Method?): Boolean
ORIGIN bindPageListener L111 fun bindPageListener(activity: Activity)
  112: val listener = readFieldByType(activity, PAGE_CHANGE_LISTENER_CLASS) ?: run {
  114: return
  116: val callback = KavaReflector.findMethodRecursive(
  121: if (!isHookable(callback)) {
  123: return
  125: if (!hookedPageListenerMethods.add(callback!!)) return
  LOCAL_LOCATOR isHookable L242 fun isHookable(method: Method?): Boolean

FILE hooks/items/bottombar/BottomBarAutoHideFeature.kt
L127 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=install@107
ORIGIN install L107 fun install(): Boolean
  108: if (installed) return true
  115: val viewClass = KavaReflector.loadClass(
  120: return false
  122: val method = locateOnTouchEvent(viewClass) ?: run {
  124: return false
  126: return runCatching {
  127: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateOnTouchEvent L148 fun locateOnTouchEvent(viewClass: Class<*>): Method?

FILE hooks/items/callmedialimit/CallMediaLimitFeature.kt
L81 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=install@72
L88 [终端] HookRegistry.get().hook TARGET=voipEventAccess.dispatch CALLBACK=after IN=install@72
L94 [终端] HookRegistry.get().hook TARGET=voicePlaybackGuard CALLBACK=after,before IN=install@72
ORIGIN install L72 fun install(): Boolean
  73: if (installed) return true
  74: return runCatching {
  75: val occupyMethods = locateDeviceOccupyMethods()
  76: if (!isDeviceOccupyMethodSet(occupyMethods)) return false
  77: val voicePlaybackGuard = locateVoicePlaybackGuardMethod() ?: return false
  78: val voipEventAccess = locateVoipEventAccess() ?: return false
  80: occupyMethods.forEach { method ->
  81: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateDeviceOccupyMethods L117 fun locateDeviceOccupyMethods(): List<Method>
  LOCAL_LOCATOR locateVoicePlaybackGuardMethod L175 fun locateVoicePlaybackGuardMethod(): Method?
  LOCAL_LOCATOR locateVoipEventAccess L218 fun locateVoipEventAccess(): VoipEventAccess?
  LOCAL_LOCATOR isDeviceOccupyMethodSet L307 fun isDeviceOccupyMethodSet(methods: List<Method>): Boolean

FILE hooks/items/callmedialimit/CallRingtoneBlockFeature.kt
L89 [终端] HookRegistry.get().hook TARGET=access.start CALLBACK=before IN=install@72
L118 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookDirectionMethod@117
ORIGIN install L72 fun install(): Boolean
  73: if (installed) return true
  74: return runCatching {
  75: val directionMethods = locateDirectionMethods()
  76: val multiTalkDirectionMethods = locateMultiTalkDirectionMethods()
  77: val coreAccesses = locateCoreStartAccesses()
  78: if (directionMethods.isEmpty() && multiTalkDirectionMethods.isEmpty() && coreAccesses.isEmpty()) {
  79: return false
  82: directionMethods.forEach { method ->
  83: hookDirectionMethod(method, OUTGOING_ARGUMENT_INDEX)
  85: multiTalkDirectionMethods.forEach { method ->
  86: hookDirectionMethod(method, MULTI_TALK_OUTGOING_ARGUMENT_INDEX)
  88: coreAccesses.forEach { access ->
  LOCAL_LOCATOR locateDirectionMethods L126 fun locateDirectionMethods(): List<Method>
  LOCAL_LOCATOR locateMultiTalkDirectionMethods L162 fun locateMultiTalkDirectionMethods(): List<Method>
  LOCAL_LOCATOR locateCoreStartAccesses L211 fun locateCoreStartAccesses(): List<CoreStartAccess>
ORIGIN hookDirectionMethod L117 fun hookDirectionMethod(method: Method, outgoingArgumentIndex: Int)
  117: fun hookDirectionMethod(method: Method, outgoingArgumentIndex: Int) {
  118: HookRegistry.get().hook(method,
  CALLER L83 hookDirectionMethod(method, OUTGOING_ARGUMENT_INDEX)
  CALLER L86 hookDirectionMethod(method, MULTI_TALK_OUTGOING_ARGUMENT_INDEX)

FILE hooks/items/chatanimation/ChatMessageEnterAnimationFeature.kt
L105 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=install@95
ORIGIN install L95 fun install(): Boolean
  96: if (installed) return true
  100: val method = ChatAdapterBindLocator.locate(context, logger) ?: run {
  102: return false
  104: return runCatching {
  105: HookRegistry.get().hook(method,
  111: logger("消息入场动效：已挂载 ${method.declaringClass.simpleName}#${method.name}，开关 ${if (enabled()) "开" else "关"}", null)
  114: logger("消息入场动效 Hook 安装失败: ${method.toGenericString()}", it)

FILE hooks/items/chattime/ChatTimeStyleFeature.kt
L151 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=install@144
ORIGIN install L144 fun install(): Boolean
  145: if (installed) return true
  146: val method = locateBindMethod() ?: run {
  148: return false
  150: return runCatching {
  151: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateBindMethod L353 fun locateBindMethod(): Method?

FILE hooks/items/conversationgroup/ConversationGroupFileRequest.kt
L218 [终端] XposedBridge.hookAllMethods TARGET=clazz | "onActivityResult" CALLBACK=after IN=ensure@211
ORIGIN ensure L211 fun ensure(activityClass: Class<*>): Boolean
  211: fun ensure(activityClass: Class<*>): Boolean {
  213: val installingGeneration = generation
  215: var clazz: Class<*>? = activityClass
  216: while (clazz != null && Activity::class.java.isAssignableFrom(clazz)) {
  217: if (!hooks.containsKey(clazz)) {
  218: val handles = XposedBridge.hookAllMethods(clazz, "onActivityResult",
  235: hooks[clazz] = handles
  237: clazz = clazz.superclass
  239: return hooks.values.any { it.isNotEmpty() }
  243: hooks.values.forEach { retiredHooks.addAll(it) }
  247: return false

FILE hooks/items/conversationgroup/ConversationGroupHomeProjection.kt
L165 [包装调用] hook TARGET=constructor CALLBACK=before IN=?@0
L256 [包装调用] hook TARGET=method CALLBACK=after,before IN=?@0
L271 [包装调用] hook TARGET=root CALLBACK=after IN=?@0
L337 [包装调用] hook TARGET=page CALLBACK=after,before IN=?@0
L413 [包装调用] hook TARGET=visible CALLBACK=after IN=?@0
L445 [包装调用] hook TARGET=update CALLBACK=after,before IN=?@0
L565 [转发] HookRegistry.get().hook TARGET=method CALLBACK=callback IN=?@0

FILE hooks/items/conversationgroup/ConversationGroupRuntime.kt
L187 [包装调用] hook TARGET=query CALLBACK=after IN=installInternal@159
L204 [包装调用] hook TARGET=shareRecentAdapterReset CALLBACK=after,before IN=installInternal@159
L216 [包装调用] hook TARGET=shareRecentForwardQuery CALLBACK=after IN=installInternal@159
L238 [包装调用] hook TARGET=click CALLBACK=groupClickHook() IN=installInternal@159
L239 [包装调用] hook TARGET=nativeGroupQuery CALLBACK=after,before IN=installInternal@159
L260 [包装调用] hook TARGET=nativeGroupClick CALLBACK=nativeGroupClickHook() IN=installInternal@159
L261 [包装调用] hook TARGET=nativeGroupRefresh CALLBACK=after,before IN=installInternal@159
L273 [包装调用] hook TARGET=nativeGroupMarkRead CALLBACK=before IN=installInternal@159
L280 [包装调用] hook TARGET=nativeSetPinned CALLBACK=nativeGroupPinnedHook(context.hostContext(), pinned = true) IN=installInternal@159
L284 [包装调用] hook TARGET=nativeUnsetPinned CALLBACK=nativeGroupPinnedHook(context.hostContext(), pinned = false) IN=installInternal@159
L288 [包装调用] hook TARGET=nativeHideConversation CALLBACK=nativeHideConversationHook(nativeHideConversation) IN=installInternal@159
L293 [包装调用] hook TARGET=method CALLBACK=before IN=installInternal@159
L305 [包装调用] hook TARGET=methods.click CALLBACK=before IN=installInternal@159
L313 [包装调用] hook TARGET=methods.create CALLBACK=after IN=installInternal@159
L325 [包装调用] hook TARGET=conversationMenu CALLBACK=after IN=installInternal@159
L594 [包装调用] hook TARGET=method CALLBACK=before IN=installConversationMenuClickHook@576
L725 [包装调用] hook TARGET=getStringExtra CALLBACK=before IN=installNativeGroupPageHooks@708
L740 [包装调用] hook TARGET=lifecycle CALLBACK=after IN=installNativeGroupPageHooks@708
L747 [包装调用] hook TARGET=method CALLBACK=after,before IN=installNativeGroupPageHooks@708
L2680 [转发] HookRegistry.get().hook TARGET=method CALLBACK=callback IN=hook@2677
ORIGIN installInternal L159 fun installInternal(context: FeatureContext): Boolean
  160: val query = locateQueryMethod(context) ?: return false
  161: val shareRecentAdapterReset = locateShareRecentAdapterResetMethod(context) ?: return false
  162: val shareRecentForwardQuery = locateShareRecentForwardQueryMethod(context) ?: return false
  163: val parentUpdate = locateParentUpdateMethod(context) ?: return false
  164: val nativeSetPinned = locateNativeSetPinnedMethod(context) ?: return false
  165: val nativeUnsetPinned = locateNativeUnsetPinnedMethod(context) ?: return false
  166: val nativeHideConversation = locateNativeHideConversationMethod(context) ?: return false
  167: val click = locateClickMethod(context) ?: return false
  168: val nativeGroupQuery = locateNativeGroupQueryMethod(context) ?: return false
  169: val nativeGroupClick = locateNativeGroupClickMethod(context) ?: return false
  170: val nativeGroupRefresh = locateNativeGroupRefreshMethod(context) ?: return false
  171: val nativeGroupMarkRead = locateNativeGroupMarkReadMethod(context) ?: return false
  172: val nativeGroupStatusNotify = locateNativeGroupStatusNotifyMethod(context)
  173: val nativeGroupMenuMethods = locateNativeGroupMenuMethods(context)
  174: val conversationMenu = ConversationMenuLocator.menuCreateMethod(context) { message, throwable ->
  176: } ?: return false
  179: val homeProjectionInstalled = ConversationGroupHomeProjection.install(context) {
  186: val generation = lifecycleGeneration.get()
  187: val queryInstalled = hook(query,
  204: val shareRecentAdapterInstalled = hook(
  216: val shareRecentForwardQueryInstalled = hook(
  238: val clickInstalled = hook(click, groupClickHook())
  239: val nativeGroupQueryInstalled = hook(nativeGroupQuery,
  260: val nativeGroupClickInstalled = hook(nativeGroupClick, nativeGroupClickHook())
  261: val nativeGroupRefreshInstalled = hook(nativeGroupRefresh,
  273: val nativeGroupMarkReadInstalled = hook(nativeGroupMarkRead,
  280: val nativeSetPinnedInstalled = hook(
  284: val nativeUnsetPinnedInstalled = hook(
  288: val nativeHideConversationInstalled = hook(
  292: val nativeGroupStatusInstalled = nativeGroupStatusNotify?.let { method ->
  293: hook(method,
  303: val nativePageInstalled = installNativeGroupPageHooks(context)
  304: nativeGroupMenuMethods?.let { methods ->
  305: val clickInstalled = hook(
  306: methods.click,
  313: val createInstalled = clickInstalled && hook(
  314: methods.create,
  325: val conversationMenuInstalled = hook(conversationMenu,
  330: return homeProjectionInstalled && queryInstalled && shareRecentAdapterInstalled &&
  LOCAL_LOCATOR locateQueryMethod L2199 fun locateQueryMethod(context: FeatureContext): Method?
  LOCAL_LOCATOR locateShareRecentAdapterResetMethod L2213 fun locateShareRecentAdapterResetMethod(context: FeatureContext): Method?
  LOCAL_LOCATOR locateShareRecentForwardQueryMethod L2238 fun locateShareRecentForwardQueryMethod(context: FeatureContext): Method?
  LOCAL_LOCATOR locateParentUpdateMethod L2262 fun locateParentUpdateMethod(context: FeatureContext): Method?
  LOCAL_LOCATOR locateNativeSetPinnedMethod L2281 fun locateNativeSetPinnedMethod(context: FeatureContext): Method?
  LOCAL_LOCATOR locateNativeUnsetPinnedMethod L2296 fun locateNativeUnsetPinnedMethod(context: FeatureContext): Method?
  LOCAL_LOCATOR locateClickMethod L2311 fun locateClickMethod(context: FeatureContext): Method?
  LOCAL_LOCATOR locateNativeGroupQueryMethod L2325 fun locateNativeGroupQueryMethod(context: FeatureContext): Method?
  LOCAL_LOCATOR locateNativeGroupClickMethod L2340 fun locateNativeGroupClickMethod(context: FeatureContext): Method?
  LOCAL_LOCATOR locateNativeGroupMenuMethods L2361 fun locateNativeGroupMenuMethods(context: FeatureContext): NativeGroupMenuMethods?
  LOCAL_LOCATOR locateNativeGroupRefreshMethod L2439 fun locateNativeGroupRefreshMethod(context: FeatureContext): Method?
  LOCAL_LOCATOR locateNativeGroupMarkReadMethod L2454 fun locateNativeGroupMarkReadMethod(context: FeatureContext): Method?
  LOCAL_LOCATOR locateNativeGroupStatusNotifyMethod L2471 fun locateNativeGroupStatusNotifyMethod(context: FeatureContext): Method?
  LOCAL_LOCATOR locateNativeHideConversationMethod L2485 fun locateNativeHideConversationMethod(context: FeatureContext): Method?
ORIGIN installConversationMenuClickHook L576 fun installConversationMenuClickHook(listener: Any?): Boolean
  577: listener ?: return false
  578: val method = KavaReflector.declaredFields(listener.javaClass)
  580: .filterNot { Modifier.isStatic(it.modifiers) }
  583: KavaReflector.findMethod(
  590: .firstOrNull { candidate ->
  591: candidate.returnType == Void.TYPE &&
  592: candidate.declaringClass.name.startsWith("com.tencent.mm.ui.conversation.")
  593: } ?: return false
  594: return hook(method,
ORIGIN installNativeGroupPageHooks L708 fun installNativeGroupPageHooks(context: FeatureContext): Boolean
  709: val fragmentClass = KavaReflector.loadClass(NATIVE_GROUP_FRAGMENT, context.hostClassLoader())
  710: ?: return false
  711: val lifecycle = KavaReflector.findDeclaredMethod(
  715: ) ?: return false
  716: val fragmentActivitySupport = KavaReflector.loadClass(
  719: ) ?: return false
  720: val getStringExtra = KavaReflector.findDeclaredMethod(
  724: ) ?: return false
  725: val parentExtraInstalled = hook(getStringExtra,
  740: val lifecycleInstalled = hook(lifecycle,
  745: val lifecycleContextInstalled = listOf("onPause", "onResume").all { name ->
  746: val method = KavaReflector.findDeclaredMethod(fragmentClass, name) ?: return@all false
  747: hook(method,
  771: return parentExtraInstalled && lifecycleInstalled && lifecycleContextInstalled
ORIGIN hook L2677 fun hook(method: Method, callback: XC_MethodHook): Boolean
  2677: fun hook(method: Method, callback: XC_MethodHook): Boolean {
  2678: if (!hookedMethods.add(method)) return true
  2679: return runCatching {
  2680: HookRegistry.get().hook(method, callback)
  2683: hookedMethods.remove(method)
  2684: HLog.e("$TAG Hook 安装失败: ${method.toGenericString()} ${it.message}", it)
  CALLER L187 val queryInstalled = hook(query, object : XC_MethodHook() {
  CALLER L204 val shareRecentAdapterInstalled = hook(
  CALLER L216 val shareRecentForwardQueryInstalled = hook(
  CALLER L238 val clickInstalled = hook(click, groupClickHook())
  CALLER L239 val nativeGroupQueryInstalled = hook(nativeGroupQuery, object : XC_MethodHook() {
  CALLER L260 val nativeGroupClickInstalled = hook(nativeGroupClick, nativeGroupClickHook())
  CALLER L261 val nativeGroupRefreshInstalled = hook(nativeGroupRefresh, object : XC_MethodHook() {
  CALLER L273 val nativeGroupMarkReadInstalled = hook(nativeGroupMarkRead, object : XC_MethodHook() {
  CALLER L280 val nativeSetPinnedInstalled = hook(
  CALLER L284 val nativeUnsetPinnedInstalled = hook(
  CALLER L288 val nativeHideConversationInstalled = hook(
  CALLER L293 hook(method, object : XC_MethodHook() {
  CALLER L305 val clickInstalled = hook(
  CALLER L313 val createInstalled = clickInstalled && hook(
  CALLER L325 val conversationMenuInstalled = hook(conversationMenu, object : XC_MethodHook() {
  CALLER L594 return hook(method, object : XC_MethodHook() {
  CALLER L725 val parentExtraInstalled = hook(getStringExtra, object : XC_MethodHook() {
  CALLER L740 val lifecycleInstalled = hook(lifecycle, object : XC_MethodHook() {
  CALLER L747 hook(method, object : XC_MethodHook() {

FILE hooks/items/conversationtabs/ConversationTabsRuntime.kt
L132 [终端] HookRegistry.get().hook TARGET=layout CALLBACK=after IN=installUiHooks@111
L141 [终端] HookRegistry.get().hook TARGET=resume CALLBACK=after IN=installUiHooks@111
L151 [终端] HookRegistry.get().hook TARGET=destroy CALLBACK=after IN=installUiHooks@111
ORIGIN installUiHooks L111 fun installUiHooks(classLoader: ClassLoader): Boolean
  112: val clazz = KavaReflector.loadClass("com.tencent.mm.ui.conversation.MainUI", classLoader)
  115: return false
  117: if (hookedMainUi == clazz) return true
  118: val layout = KavaReflector.findMethodRecursive(clazz, "getLayoutView") ?: run {
  120: return false
  122: val resume = KavaReflector.findMethodRecursive(clazz, "onResume") ?: return false
  123: val destroy = KavaReflector.findMethodRecursive(clazz, "onDestroy") ?: return false
  124: val rootField = KavaReflector.declaredFields(clazz).singleOrNull {
  125: !KavaReflector.isStatic(it) && it.type.name == MAIN_VIEW_CLASS
  128: return false
  130: val hooks = arrayListOf<XC_MethodHook.Unhook>()
  131: return runCatching {
  161: hookedMainUi = clazz
  164: hooks.forEach { hook -> HookRegistry.get().unhook(hook) }

FILE hooks/items/custombottombar/CustomBottomBarFeature.kt
L300 [终端] HookRegistry.get().hook TARGET=constructor CALLBACK=after IN=installBottomTabConstructorHooks@293
L322 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installFrostedDrawHook@314
L346 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=installBottomTabStateHooks@338
L408 [终端] HookRegistry.get().hook TARGET=onResume CALLBACK=after IN=installLauncherResumeHook@396
L425 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=installMainTabCreateHook@421
ORIGIN installBottomTabConstructorHooks L293 fun installBottomTabConstructorHooks(): Boolean
  294: if (bottomTabConstructorHooksInstalled) return true
  295: val clazz = bottomTabClass ?: return false
  296: val constructors = KavaReflector.declaredConstructors(clazz)
  297: if (constructors.isEmpty()) return false
  298: return runCatching {
  299: constructors.forEach { constructor ->
ORIGIN installFrostedDrawHook L314 fun installFrostedDrawHook(finalAttempt: Boolean)
  315: if (frostedDrawHookInstalled) return
  316: val method = resolveFrostedMembers(finalAttempt)?.dispatchDraw
  317: if (method == null) {
  319: return
  322: HookRegistry.get().hook(method,
  334: logger("微信底部磨砂绘制 Hook 安装失败: ${method.toGenericString()}", it)
  LOCAL_LOCATOR resolveFrostedMembers L845 fun resolveFrostedMembers(finalAttempt: Boolean): FrostedMembers?
ORIGIN installBottomTabStateHooks L338 fun installBottomTabStateHooks(): Boolean
  339: if (bottomTabStateHooksInstalled) return true
  340: val clazz = bottomTabClass ?: return false
  341: val exactSetTo = setToMethod ?: return false
  342: val methods = (KavaReflector.declaredMethods(clazz).filter(::isBottomTabStateUpdateMethod) +
  343: exactSetTo).distinctBy(Method::toGenericString)
  344: return runCatching {
  345: methods.forEach { method ->
  346: HookRegistry.get().hook(method,
ORIGIN installLauncherResumeHook L396 fun installLauncherResumeHook(): Boolean
  397: if (resumeHookInstalled) return true
  398: val launcherClass = KavaReflector.loadClass(LAUNCHER_UI_CLASS, hostClassLoader)
  399: ?: return false
  400: val onResume = KavaReflector.declaredMethods(launcherClass).singleOrNull {
  402: it.returnType == Void.TYPE &&
  403: it.parameterTypes.isEmpty() &&
  404: !Modifier.isStatic(it.modifiers) &&
  405: !Modifier.isAbstract(it.modifiers)
  406: } ?: return false
  407: return runCatching {
ORIGIN installMainTabCreateHook L421 fun installMainTabCreateHook(allowDexSearch: Boolean): Boolean
  421: fun installMainTabCreateHook(allowDexSearch: Boolean): Boolean {
  422: if (createHookInstalled) return true
  423: val method = locateMainTabCreateMethod(allowDexSearch) ?: return false
  424: return runCatching {
  425: HookRegistry.get().hook(method,
  433: logger("微信底栏创建入口 Hook 安装失败: ${method.toGenericString()}", it)
  LOCAL_LOCATOR locateMainTabCreateMethod L438 fun locateMainTabCreateMethod(allowDexSearch: Boolean): Method?

FILE hooks/items/custombottombar/CustomBottomBarIconPicker.kt
L89 [终端] XposedBridge.hookAllMethods TARGET=clazz | "onActivityResult" CALLBACK=after IN=hookActivityResult@86
L142 [终端] XposedBridge.hookAllMethods TARGET=clazz | "onDestroy" CALLBACK=after IN=hookActivityDestroy@139
ORIGIN hookActivityResult L86 fun hookActivityResult(clazz: Class<*>)
  86: fun hookActivityResult(clazz: Class<*>) {
  87: if (!resultHookedClasses.add(clazz)) return
  89: XposedBridge.hookAllMethods(clazz, "onActivityResult",
  135: }.onFailure { resultHookedClasses.remove(clazz) }
  CALLER L67 hookActivityResult(current)
ORIGIN hookActivityDestroy L139 fun hookActivityDestroy(clazz: Class<*>)
  139: fun hookActivityDestroy(clazz: Class<*>) {
  140: if (!destroyHookedClasses.add(clazz)) return
  142: XposedBridge.hookAllMethods(clazz, "onDestroy",
  153: }.onFailure { destroyHookedClasses.remove(clazz) }
  CALLER L68 hookActivityDestroy(current)

FILE hooks/items/customfriendavatar/CustomFriendAvatarFeature.kt
L133 [包装调用] hook TARGET=method CALLBACK=before IN=installNotificationHooks@125
L149 [包装调用] hook TARGET=method CALLBACK=after IN=installDesktopShortcutHook@146
L178 [包装调用] hook TARGET=avatarMembers.legacyLoad CALLBACK=after,before IN=install@176
L203 [包装调用] hook TARGET=avatarMembers.workerConstructor CALLBACK=after,before IN=install@176
L224 [包装调用] hook TARGET=method CALLBACK=before IN=install@176
L244 [包装调用] hook TARGET=avatarMembers.draw CALLBACK=before IN=install@176
L285 [包装调用] hook TARGET=method CALLBACK=after IN=installConversationMenu@282
L312 [包装调用] hook TARGET=method CALLBACK=before IN=installConversationClick@301
L400 [转发] HookRegistry.get().hook TARGET=member CALLBACK=callback IN=hook@396
ORIGIN installNotificationHooks L125 fun installNotificationHooks()
  126: NotificationManager::class.java.declaredMethods
  128: .filter { method ->
  129: val types = method.parameterTypes
  130: method.name == "notify" && types.isNotEmpty() && types.last() == Notification::class.java
  132: .forEach { method ->
  133: hook(method,
ORIGIN installDesktopShortcutHook L146 fun installDesktopShortcutHook(): Boolean
  147: if (desktopShortcutInstalled) return true
  148: val method = locateDesktopShortcutMethod() ?: return false
  149: desktopShortcutInstalled = hook(method,
  155: return desktopShortcutInstalled
  LOCAL_LOCATOR locateDesktopShortcutMethod L564 fun locateDesktopShortcutMethod(): Method?
ORIGIN install L176 fun install(): Boolean
  177: val avatarMembers = locateAvatarMembers() ?: return false
  178: val legacy = hook(avatarMembers.legacyLoad,
  203: val constructor = hook(avatarMembers.workerConstructor,
  223: val modify = avatarMembers.workerModify?.let { method ->
  224: hook(method,
  244: val draw = hook(avatarMembers.draw,
  270: return installed
  LOCAL_LOCATOR locateAvatarMembers L529 fun locateAvatarMembers(): AvatarMembers?
ORIGIN installConversationMenu L282 fun installConversationMenu()
  284: val method = ConversationMenuLocator.menuCreateMethod(context, logger) ?: return
  285: hook(method,
ORIGIN installConversationClick L301 fun installConversationClick(listener: Any?)
  302: listener ?: return
  303: val method = KavaReflector.declaredFields(listener.javaClass)
  305: .filterNot { Modifier.isStatic(it.modifiers) }
  308: KavaReflector.findMethod(callback.javaClass, "onMMMenuItemSelected", MenuItem::class.java, Integer.TYPE)
  310: .firstOrNull { it.returnType == Void.TYPE && it.declaringClass.name.startsWith("com.tencent.mm.ui.conversation.") }
  311: ?: return
  312: hook(method,
ORIGIN hook L396 fun hook(member: Member, callback: XC_MethodHook): Boolean
  396: fun hook(member: Member, callback: XC_MethodHook): Boolean {
  397: if (member is Method && (Modifier.isAbstract(member.modifiers) || member.declaringClass.isInterface)) return false
  398: if (!hookedMembers.add(member)) return true
  399: return runCatching {
  CALLER L133 hook(method, object : XC_MethodHook() {
  CALLER L149 desktopShortcutInstalled = hook(method, object : XC_MethodHook() {
  CALLER L178 val legacy = hook(avatarMembers.legacyLoad, object : XC_MethodHook() {
  CALLER L203 val constructor = hook(avatarMembers.workerConstructor, object : XC_MethodHook() {
  CALLER L224 hook(method, object : XC_MethodHook() {
  CALLER L244 val draw = hook(avatarMembers.draw, object : XC_MethodHook() {
  CALLER L285 hook(method, object : XC_MethodHook() {
  CALLER L312 hook(method, object : XC_MethodHook() {

FILE hooks/items/customfriendavatar/CustomFriendAvatarPicker.kt
L78 [终端] XposedBridge.hookAllMethods TARGET=clazz | "onActivityResult" CALLBACK=after IN=hookActivityResult@75
ORIGIN hookActivityResult L75 fun hookActivityResult(clazz: Class<*>)
  75: fun hookActivityResult(clazz: Class<*>) {
  76: if (!hookedClasses.add(clazz)) return
  78: XposedBridge.hookAllMethods(clazz, "onActivityResult",
  100: }.onFailure { hookedClasses.remove(clazz) }
  CALLER L44 hookActivityResult(activity.javaClass)
  CALLER L45 hookActivityResult(Activity::class.java)

FILE hooks/items/customnotify/CustomNotificationRuntime.kt
L180 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installNotificationHooks@171
L254 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=installNativeForegroundFeedbackGuard@233
L277 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installNativeForegroundFeedbackGuard@233
L336 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=installNativeNotificationCleanupGuard@319
L354 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installNativeNotificationCleanupGuard@319
L389 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installNativeMentionRecorder@384
L536 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=installNativeNotificationPayloadHook@533
L657 [终端] XposedBridge.hookAllConstructors TARGET=clazz CALLBACK=after IN=installNativeNotificationMarker@655
L666 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installNativeNotificationMarker@655
ORIGIN installNotificationHooks L171 fun installNotificationHooks(context: FeatureContext)
  172: methodCachePrefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
  177: for (method in NotificationManager::class.java.declaredMethods) {
  178: val types = method.parameterTypes ?: continue
  179: if (method.name != "notify" || types.isEmpty() || types.last() != Notification::class.java) continue
  180: HookRegistry.get().hook(method,
ORIGIN installNativeForegroundFeedbackGuard L233 fun installNativeForegroundFeedbackGuard(context: FeatureContext)
  234: val processors = locateNativeNotificationMethods(
  238: ::isNativeForegroundProcessor
  240: val soundPlayers = locateNativeNotificationMethods(
  244: ::isNativeNotificationSoundPlayer
  246: if (processors.isEmpty()) {
  248: return
  250: if (soundPlayers.isEmpty()) {
  253: processors.forEach { method ->
  254: HookRegistry.get().hook(method,
  276: soundPlayers.forEach { method ->
  277: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateNativeNotificationMethods L498 fun locateNativeNotificationMethods( context: FeatureContext, cacheKey: String, anchor: String, predicate: (Method) -> Boolean ): List<Method>
ORIGIN installNativeNotificationCleanupGuard L319 fun installNativeNotificationCleanupGuard(context: FeatureContext)
  320: val processors = locateNativeNotificationMethods(
  324: ) { method -> method.returnType == Void.TYPE }
  326: val cleanups = locateNativeNotificationMethods(
  330: ) { method -> method.returnType == Void.TYPE && method.parameterTypes.isEmpty() }
  331: if (processors.isEmpty() || cleanups.isEmpty()) {
  333: return
  335: processors.forEach { method ->
  336: HookRegistry.get().hook(method,
  353: cleanups.forEach { method ->
  354: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateNativeNotificationMethods L498 fun locateNativeNotificationMethods( context: FeatureContext, cacheKey: String, anchor: String, predicate: (Method) -> Boolean ): List<Method>
ORIGIN installNativeMentionRecorder L384 fun installNativeMentionRecorder( context: FeatureContext, processors: List<Method> )
  386: processors: List<Method>
  388: processors.forEach { method ->
  389: HookRegistry.get().hook(method,
ORIGIN installNativeNotificationPayloadHook L533 fun installNativeNotificationPayloadHook(context: FeatureContext)
  534: val methods = locateNativeNotificationBuilderMethods(context)
  535: methods.forEach { method ->
  536: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateNativeNotificationBuilderMethods L546 fun locateNativeNotificationBuilderMethods(context: FeatureContext): List<Method>
ORIGIN installNativeNotificationMarker L655 fun installNativeNotificationMarker(context: FeatureContext)
  656: val clazz = findNotificationItemClass(context) ?: return
  657: val constructorHooks = XposedBridge.hookAllConstructors(clazz,
  662: constructorHooks?.forEach { HookRegistry.get().add(it) }
  663: for (method in KavaReflector.declaredMethods(clazz)) {
  664: val types = method.parameterTypes ?: continue
  665: if (types.size == 1 && Context::class.java.isAssignableFrom(types[0])) {
  666: HookRegistry.get().hook(method,
  LOCAL_LOCATOR findNotificationItemClass L675 fun findNotificationItemClass(context: FeatureContext): Class<*>?

FILE hooks/items/editmsg/EditMessageFeature.kt
L568 [包装调用] hookMethod TARGET=method CALLBACK=after,before IN=hookChatMethod@567
L583 [转发] HookRegistry.get().hook TARGET=method CALLBACK=callback IN=hookMethod@579
ORIGIN hookChatMethod L567 fun hookChatMethod(method: Method, menuCreate: Boolean): Boolean
  567: fun hookChatMethod(method: Method, menuCreate: Boolean): Boolean {
  568: return hookMethod(method,
  CALLER L554 if (hookChatMethod(method, true)) menuHooked++
  CALLER L557 if (hookChatMethod(method, false)) {
ORIGIN hookMethod L579 fun hookMethod(method: Method, callback: XC_MethodHook): Boolean
  579: fun hookMethod(method: Method, callback: XC_MethodHook): Boolean {
  580: if (!isHookableMethod(method)) return false
  581: if (!hookedMethods.add(method)) return true
  582: return try {
  583: HookRegistry.get().hook(method, callback)
  586: hookedMethods.remove(method)
  587: logger("修改聊天记录Hook安装失败: ${method.name}", e)
  LOCAL_LOCATOR isHookableMethod L858 fun isHookableMethod(method: Method): Boolean
  CALLER L568 return hookMethod(method, object : XC_MethodHook() {

FILE hooks/items/emojilimit/EmojiCollectionLimitFeature.kt
L74 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installLimitHook@36
ORIGIN installLimitHook L36 fun installLimitHook(context: FeatureContext): Boolean
  37: val version = WeChatApis.version()?.current() ?: return false
  41: if (version.packageName != "com.tencent.mm") return true
  49: return true
  52: val host = context.hostContext()
  53: val loader = context.hostClassLoader()
  54: val prefs = DexMethodCache.prefs(host, "Hchat_emoji_collection_limit_method_cache")
  55: val runtimeKey = DexMethodCache.runtimeKey(host, loader)
  56: val cached = DexMethodCache.load(prefs, runtimeKey, loader, CACHE_METHOD)?.takeIf(::isLimitGetter)
  58: val method = cached ?: context.dexKitBridge().findMethod(
  59: FindMethod().matcher(
  60: MethodMatcher().declaredClass("tr.a0").name("a").paramCount(0)
  61: .returnType("int").usingEqStrings("CustomEmojiMaxSize")
  63: ).singleOrNull()?.getMethodInstance(loader)?.takeIf(::isLimitGetter)
  64: if (method == null) {
  65: DexMethodCache.clear(prefs, runtimeKey, CACHE_METHOD)
  67: return false
  70: val config = context.configStore()
  71: val enabled = HotConfig.flag(ID, KEY_ENABLED, DEFAULT_ENABLED) {
  74: HookRegistry.get().hook(method,
  79: if (cached == null) DexMethodCache.save(prefs, runtimeKey, CACHE_METHOD, method)
  80: return true

FILE hooks/items/emojisave/EmojiSaveFeature.kt
L81 [包装调用] hook TARGET=method CALLBACK=after IN=install@79
L88 [包装调用] hook TARGET=method CALLBACK=before IN=install@79
L107 [转发] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=callback IN=hook@103
ORIGIN install L79 fun install(): Boolean
  80: val createHooked = SingleMessageMenuLocator.menuCreateMethods(context, logger).count { method ->
  81: hook(method,
  87: val clickHooked = SingleMessageMenuLocator.menuClickMethods(context, logger).count { method ->
  88: hook(method,
  96: return createHooked > 0 && clickHooked > 0
ORIGIN hook L103 fun hook(method: Method, callback: XC_MethodHook): Boolean
  103: fun hook(method: Method, callback: XC_MethodHook): Boolean {
  104: if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
  105: if (!hookedMethods.add(method)) return true
  106: return runCatching {
  107: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
  110: hookedMethods.remove(method)
  111: logger("保存表情菜单 Hook 安装失败: ${method.toGenericString()}", it)
  CALLER L81 hook(method, object : XC_MethodHook() {
  CALLER L88 hook(method, object : XC_MethodHook() {

FILE hooks/items/fakelocation/FakeLocationFeature.kt
L165 [终端] HookRegistry.get().hook TARGET=callback CALLBACK=before IN=install@157
L204 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookGetter@201
ORIGIN install L157 fun install( context: Context, callbacks: List<Method>, logger: (String, Throwable?) -> Unit ): Boolean
  159: callbacks: List<Method>,
  162: callbacks.forEach { callback ->
  163: if (!hookedCallbacks.add(callback)) return@forEach
  179: return callbacks.all(hookedCallbacks::contains)
ORIGIN hookGetter L201 fun hookGetter(context: Context, method: Method, value: () -> Double)
  201: fun hookGetter(context: Context, method: Method, value: () -> Double) {
  202: if (!hookedGetters.add(method)) return
  204: HookRegistry.get().hook(method,
  211: hookedGetters.remove(method)
  CALLER L197 hookGetter(context, latitudeMethod!!) { FakeLocationSettings.latitude(context) }
  CALLER L198 hookGetter(context, longitudeMethod!!) { FakeLocationSettings.longitude(context) }

FILE hooks/items/fakescancamera/FakeScanCameraFeature.kt
L64 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=install@59
ORIGIN install L59 fun install(allowDexSearch: Boolean): Boolean
  59: fun install(allowDexSearch: Boolean): Boolean {
  60: if (qbarStringHandlerInstalled) return true
  61: var installedAny = false
  62: locateQBarStringHandlerMethods(allowDexSearch).forEach { method ->
  64: HookRegistry.get().hook(method,
  76: return installedAny || qbarStringHandlerInstalled
  LOCAL_LOCATOR locateQBarStringHandlerMethods L110 fun locateQBarStringHandlerMethods(allowDexSearch: Boolean): List<Method>

FILE hooks/items/fakevoiceduration/FakeVoiceDurationFeature.kt
L90 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=install@86
ORIGIN install L86 fun install(): Boolean
  87: if (installed) return true
  88: return runCatching {
  89: val method = locateRecorderDurationMethod() ?: return false
  90: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateRecorderDurationMethod L104 fun locateRecorderDurationMethod(): Method?

FILE hooks/items/floatingshortcut/FloatingShortcutIconPicker.kt
L78 [终端] XposedBridge.hookAllMethods TARGET=clazz | "onActivityResult" CALLBACK=after IN=hookActivityResult@75
L122 [终端] XposedBridge.hookAllMethods TARGET=clazz | "onDestroy" CALLBACK=after IN=hookActivityDestroy@119
ORIGIN hookActivityResult L75 fun hookActivityResult(clazz: Class<*>)
  75: fun hookActivityResult(clazz: Class<*>) {
  76: if (!hookedClasses.add(clazz)) return
  78: XposedBridge.hookAllMethods(clazz, "onActivityResult",
  115: }.onFailure { hookedClasses.remove(clazz) }
  CALLER L56 hookActivityResult(current)
ORIGIN hookActivityDestroy L119 fun hookActivityDestroy(clazz: Class<*>)
  119: fun hookActivityDestroy(clazz: Class<*>) {
  120: if (!destroyHookedClasses.add(clazz)) return
  122: XposedBridge.hookAllMethods(clazz, "onDestroy",
  134: destroyHookedClasses.remove(clazz)
  CALLER L57 hookActivityDestroy(current)

FILE hooks/items/forwardlimit/RemoveForwardLimitFeature.kt
L107 [包装调用] hook TARGET=method CALLBACK="转发会话数量限制" IN=hookLimitMethod@106
L113 [包装调用] hook TARGET=method CALLBACK="转发完整联系人选择页" IN=hookContactPageMethod@112
L122 [包装调用] hook TARGET=method CALLBACK="新版转发联系人配置" IN=hookUicConfigMethod@121
L151 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hook@144
ORIGIN hookLimitMethod L106 fun hookLimitMethod(method: Method): Boolean
  106: fun hookLimitMethod(method: Method): Boolean {
  107: return hook(method, "转发会话数量限制") {
  108: if (isEnabled()) it.result = false
  LOCAL_LOCATOR isEnabled L303 fun isEnabled(): Boolean
  CALLER L92 if (!hookLimitMethod(limitMethod)) return false
ORIGIN hookContactPageMethod L112 fun hookContactPageMethod(method: Method): Boolean
  112: fun hookContactPageMethod(method: Method): Boolean {
  113: return hook(method, "转发完整联系人选择页") { param ->
  114: if (!isEnabled()) return@hook
  115: val activity = param.thisObject as? Activity ?: return@hook
  116: val intent = activity.intent ?: return@hook
  LOCAL_LOCATOR isEnabled L303 fun isEnabled(): Boolean
ORIGIN hookUicConfigMethod L121 fun hookUicConfigMethod(method: Method): Boolean
  121: fun hookUicConfigMethod(method: Method): Boolean {
  122: return hook(method, "新版转发联系人配置") { param ->
  123: if (!isEnabled()) return@hook
  124: val intent = KavaReflector.invokeMethod(param.thisObject, "getIntent") as? Intent
  125: ?: return@hook
  LOCAL_LOCATOR isEnabled L303 fun isEnabled(): Boolean
  CALLER L86 if (!hookUicConfigMethod(uicConfigMethod)) return false
ORIGIN hook L144 fun hook( method: Method, label: String, before: (XC_MethodHook.MethodHookParam) -> Unit ): Boolean
  145: method: Method,
  149: if (!hookedMethods.add(method)) return true
  150: return runCatching {
  151: HookRegistry.get().hook(method,
  158: hookedMethods.remove(method)
  159: logger("$label Hook 安装失败: ${method.toGenericString()}", it)
  CALLER L107 return hook(method, "转发会话数量限制") {
  CALLER L113 return hook(method, "转发完整联系人选择页") { param ->
  CALLER L122 return hook(method, "新版转发联系人配置") { param ->

FILE hooks/items/gameemoji/GameEmojiFeature.kt
L74 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=install@67
ORIGIN install L67 fun install(): Boolean
  68: if (installed) return true
  69: val method = context.dexFinder().emojiSendMethod ?: return false
  70: if (!isEmojiSendMethod(method)) return false
  71: val fields = resolveEmojiFields(method.parameterTypes[1]) ?: return false
  73: return runCatching {
  74: HookRegistry.get().hook(method,
  LOCAL_LOCATOR resolveEmojiFields L209 fun resolveEmojiFields(emojiClass: Class<*>): Map<String, Field>?
  LOCAL_LOCATOR isEmojiSendMethod L221 fun isEmojiSendMethod(method: Method): Boolean

FILE hooks/items/groupadminlabel/GroupAdminLabelFeature.kt
L118 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=install@111
ORIGIN install L111 fun install(): Boolean
  112: if (installed) return true
  113: val method = ChatUsernameBind.locate(context, logger) ?: run {
  115: return false
  117: return runCatching {
  118: HookRegistry.get().hook(method,
  126: logger("群聊管理员标签 Hook 安装失败: ${method.toGenericString()}", it)

FILE hooks/items/grouplabel/QuickGroupChatLabelFeature.kt
L105 [包装调用] hook TARGET=create CALLBACK=after IN=installConversationMenuHook@103
L147 [包装调用] hook TARGET=method CALLBACK=before IN=installMenuClickHook@129
L347 [转发] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=callback IN=hook@343
ORIGIN installConversationMenuHook L103 fun installConversationMenuHook(): Boolean
  104: val create = ConversationMenuLocator.menuCreateMethod(context, logger) ?: return false
  105: return hook(create,
ORIGIN installMenuClickHook L129 fun installMenuClickHook(listener: Any?): Boolean
  130: listener ?: return false
  131: val method = KavaReflector.declaredFields(listener.javaClass)
  133: .filterNot { Modifier.isStatic(it.modifiers) }
  136: KavaReflector.findMethod(
  143: .firstOrNull { candidate ->
  144: candidate.returnType == Void.TYPE &&
  145: candidate.declaringClass.name.startsWith("com.tencent.mm.ui.conversation.")
  146: } ?: return false
  147: return hook(method,
ORIGIN hook L343 fun hook(method: Method, callback: XC_MethodHook): Boolean
  343: fun hook(method: Method, callback: XC_MethodHook): Boolean {
  344: if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
  345: if (!hookedMethods.add(method)) return true
  346: return runCatching {
  347: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
  350: hookedMethods.remove(method)
  351: logger("安装快捷设置群聊标签菜单 Hook 失败: ${method.toGenericString()}", it)
  CALLER L105 return hook(create, object : XC_MethodHook() {
  CALLER L147 return hook(method, object : XC_MethodHook() {

FILE hooks/items/groupleave/GroupLeaveMonitorHighlighter.kt
L91 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installTextCleanup@78
L266 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installUriClick@254
ORIGIN installTextCleanup L78 fun installTextCleanup(context: Context, logger: (String, Throwable?) -> Unit): Boolean
  79: if (textCleanupInstalled) return true
  80: val method = KavaReflector.findDeclaredMethod(
  86: if (method == null) {
  88: return false
  90: return runCatching {
  91: HookRegistry.get().hook(method,
ORIGIN installUriClick L254 fun installUriClick( context: FeatureContext, allowDexSearch: Boolean, logger: (String, Throwable?) -> Unit ): Boolean
  256: allowDexSearch: Boolean,
  259: if (uriClickInstalled) return true
  260: val methods = locateUriSpanClickMethods(context, allowDexSearch, logger)
  261: if (methods.isEmpty()) return false
  262: var count = 0
  263: var lastError: Throwable? = null
  264: for (method in methods) {
  266: HookRegistry.get().hook(method,
  294: return uriClickInstalled
  LOCAL_LOCATOR locateUriSpanClickMethods L297 fun locateUriSpanClickMethods( context: FeatureContext, allowDexSearch: Boolean, logger: (String, Throwable?) -> Unit ): List<Method>

FILE hooks/items/hchatextra/ForwardedRecordTypeLabels.kt
L53 [终端] HookRegistry.get().hook TARGET=targets.bind CALLBACK=after,before IN=install@48
ORIGIN install L48 fun install(): Boolean
  49: if (destroyed) return false
  50: if (unhook != null) return true
  51: return try {
  52: val targets = locate() ?: return false
  53: unhook = HookRegistry.get().hook(targets.bind,

FILE hooks/items/hchatextra/HchatExtraFeature.kt
L385 [包装调用] hookOnce TARGET=intercept CALLBACK=trailing lambda / caller IN=installSkipWebRisk@379
L396 [包装调用] hookOnce TARGET=safe CALLBACK=trailing lambda / caller IN=installSkipWebRisk@379
L407 [包装调用] hookAfterOnce TARGET=method CALLBACK=trailing lambda / caller IN=installRedPacketDetails@404
L420 [包装调用] hookAfterOnce TARGET=bindMethod CALLBACK=trailing lambda / caller IN=installRedPacketDetails@404
L442 [包装调用] hookOnce TARGET=method CALLBACK=trailing lambda / caller IN=installRedPacketDetails@404
L492 [包装调用] hookAfterOnce TARGET=method CALLBACK=trailing lambda / caller IN=installGroupMemberHistory@486
L499 [包装调用] hookOnce TARGET=method CALLBACK=trailing lambda / caller IN=installGroupMemberHistory@486
L522 [终端] HookRegistry.get().hook TARGET=bind CALLBACK=after,before IN=installMessageDetails@512
L590 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookOnce@587
L606 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=hookAfterOnce@603
ORIGIN installSkipWebRisk L379 fun installSkipWebRisk(): Boolean
  380: var hooked = false
  381: val intercept = locateMethod(
  383: listOf("MicroMsg.WebViewHighRiskAdH5Interceptor", "isInterceptEnabled, expt=")
  384: ) ?: return false
  390: val safe = locateMethodsInClass(
  394: ).firstOrNull { it.returnType == java.lang.Boolean.TYPE || it.returnType == java.lang.Boolean::class.java }
  395: ?: return hooked
  401: return hooked
  LOCAL_LOCATOR locateMethod L619 fun locateMethod(cacheName: String, strings: List<String>, searchPackage: String? = null): Method?
  LOCAL_LOCATOR locateMethodsInClass L757 fun locateMethodsInClass(cacheName: String, clazz: Class<*>, strings: List<String>): List<Method>
ORIGIN installRedPacketDetails L404 fun installRedPacketDetails(): Boolean
  405: var hooked = false
  406: locateRedPacketTimeFormatterMethod()?.let { method ->
  407: hooked = hookAfterOnce(method) { param ->
  408: if (!enabled(HchatExtraSettings.KEY_RED_PACKET_DETAILS, HchatExtraSettings.DEFAULT_RED_PACKET_DETAILS)) return@hookAfterOnce
  409: val timestamp = (param.args?.getOrNull(1) as? Number)?.toLong()
  411: ?: return@hookAfterOnce
  415: val bindMethod = locateMethod(
  421: if (!enabled(HchatExtraSettings.KEY_RED_PACKET_DETAILS, HchatExtraSettings.DEFAULT_RED_PACKET_DETAILS)) return@hookAfterOnce
  426: val classes = locateClasses(
  433: classes.forEach { clazz ->
  434: KavaReflector.declaredMethods(clazz)
  435: .firstOrNull { method ->
  436: method.name == "onGYNetEnd" &&
  437: method.parameterTypes.size == 3 &&
  438: method.parameterTypes[0] == Integer.TYPE &&
  439: method.parameterTypes[1] == String::class.java &&
  440: JSONObject::class.java.isAssignableFrom(method.parameterTypes[2])
  441: }?.let { method ->
  442: hooked = hookOnce(method) { param ->
  443: if (!enabled(HchatExtraSettings.KEY_RED_PACKET_DETAILS, HchatExtraSettings.DEFAULT_RED_PACKET_DETAILS)) return@hookOnce
  444: val json = param.args?.getOrNull(2) as? JSONObject ?: return@hookOnce
  449: return hooked
  LOCAL_LOCATOR locateRedPacketTimeFormatterMethod L452 fun locateRedPacketTimeFormatterMethod(): Method?
  LOCAL_LOCATOR locateMethod L619 fun locateMethod(cacheName: String, strings: List<String>, searchPackage: String? = null): Method?
  LOCAL_LOCATOR locateClasses L786 fun locateClasses(cacheName: String, stringGroups: List<List<String>>): List<Class<*>>
ORIGIN installGroupMemberHistory L486 fun installGroupMemberHistory(): Boolean
  487: var hooked = false
  488: val clazz = KavaReflector.loadClass("com.tencent.mm.plugin.profile.ui.ContactInfoUI", context.hostClassLoader())
  489: ?: return false
  490: val firstInstall = hookedClasses.add(clazz)
  491: KavaReflector.findMethodRecursive(clazz, "initView")?.let { method ->
  492: hooked = hookAfterOnce(method) { param ->
  493: val activity = param.thisObject as? Activity ?: return@hookAfterOnce
  494: if (!enabled(HchatExtraSettings.KEY_GROUP_MEMBER_HISTORY, HchatExtraSettings.DEFAULT_GROUP_MEMBER_HISTORY)) return@hookAfterOnce
  498: methodsRecursive(clazz).firstOrNull { it.name == "onPreferenceTreeClick" }?.let { method ->
  499: hooked = hookOnce(method) { param ->
  500: val activity = param.thisObject as? Activity ?: return@hookOnce
  501: if (!enabled(HchatExtraSettings.KEY_GROUP_MEMBER_HISTORY, HchatExtraSettings.DEFAULT_GROUP_MEMBER_HISTORY)) return@hookOnce
  502: val pref = groupHistoryClickedPreference(param.args)
  503: ?: return@hookOnce
  508: if (!hooked && firstInstall) hookedClasses.remove(clazz)
  509: return hooked
ORIGIN installMessageDetails L512 fun installMessageDetails(): Boolean
  513: val itemBind = locateMessageViewBindMethod()
  514: val adapterBinds = locateChattingAdapterBindMethods()
  515: val adapterClass = locateChattingDataAdapterClass()
  516: val binds = (listOfNotNull(itemBind) + adapterBinds).distinct()
  517: var complete = itemBind != null && adapterClass != null &&
  518: adapterBinds.any { it.parameterTypes.size == 2 } && adapterBinds.any { it.parameterTypes.size == 3 }
  519: for (bind in binds) {
  521: val installed = runCatching {
  567: return complete
  LOCAL_LOCATOR locateMessageViewBindMethod L642 fun locateMessageViewBindMethod(): Method?
  LOCAL_LOCATOR locateChattingAdapterBindMethods L665 fun locateChattingAdapterBindMethods(): List<Method>
  LOCAL_LOCATOR locateChattingDataAdapterClass L708 fun locateChattingDataAdapterClass(): Class<*>?
ORIGIN hookOnce L587 fun hookOnce(method: Method, block: (XC_MethodHook.MethodHookParam) -> Unit): Boolean
  587: fun hookOnce(method: Method, block: (XC_MethodHook.MethodHookParam) -> Unit): Boolean {
  588: if (!hookedMethods.add(method)) return true
  589: return runCatching {
  590: HookRegistry.get().hook(method,
  597: hookedMethods.remove(method)
  598: logger("Hchat扩展Hook安装失败: ${method.name}", it)
  CALLER L385 hooked = hookOnce(intercept) { param ->
  CALLER L396 hooked = hookOnce(safe) { param ->
  CALLER L442 hooked = hookOnce(method) { param ->
  CALLER L499 hooked = hookOnce(method) { param ->
ORIGIN hookAfterOnce L603 fun hookAfterOnce(method: Method, block: (XC_MethodHook.MethodHookParam) -> Unit): Boolean
  603: fun hookAfterOnce(method: Method, block: (XC_MethodHook.MethodHookParam) -> Unit): Boolean {
  604: if (!hookedMethods.add(method)) return true
  605: return runCatching {
  606: HookRegistry.get().hook(method,
  613: hookedMethods.remove(method)
  614: logger("Hchat扩展Hook安装失败: ${method.name}", it)
  CALLER L407 hooked = hookAfterOnce(method) { param ->
  CALLER L420 hooked = hookAfterOnce(bindMethod) { param ->
  CALLER L492 hooked = hookAfterOnce(method) { param ->

FILE hooks/items/hideavatar/HideChatAvatarFeature.kt
L74 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=install@67
ORIGIN install L67 fun install(): Boolean
  68: if (installed) return true
  69: val method = locateAvatarBindMethod() ?: run {
  71: return false
  73: return runCatching {
  74: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateAvatarBindMethod L158 fun locateAvatarBindMethod(): Method?

FILE hooks/items/hidemenu/HideChatMenuFeature.kt
L83 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=hookMenuCreate@77
ORIGIN hookMenuCreate L77 fun hookMenuCreate(method: Method): Boolean
  77: fun hookMenuCreate(method: Method): Boolean {
  78: if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) {
  79: hookedMethods.remove(method)
  80: return false
  82: return runCatching {
  83: HookRegistry.get().hook(method,
  90: hookedMethods.remove(method)
  91: HLog.e("$TAG 安装聊天长按菜单 Hook 失败: ${method.toGenericString()}", it)
  CALLER L67 } else if (hookedMethods.add(method) && hookMenuCreate(method)) {

FILE hooks/items/hometextcolor/HomeTextColorFeature.kt
L348 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=installAdapterHook@345
L387 [终端] HookRegistry.get().hook TARGET=methods.holder CALLBACK=after IN=installModernConversationHooks@380
L410 [终端] HookRegistry.get().hook TARGET=methods.bind CALLBACK=after,before IN=installModernConversationHooks@380
L683 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=installLayoutInflaterHook@680
L841 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=installRecyclerAdapterHook@838
ORIGIN installAdapterHook L345 fun installAdapterHook(method: Method?, kind: RowKind): Boolean
  345: fun installAdapterHook(method: Method?, kind: RowKind): Boolean {
  346: if (method == null) return false
  347: return runCatching {
  348: HookRegistry.get().hook(method,
  CALLER L155 contactsInstalled = installAdapterHook(
  CALLER L168 preferenceInstalled = installAdapterHook(
  CALLER L373 if (installAdapterHook(method, RowKind.CONVERSATION)) {
ORIGIN installModernConversationHooks L380 fun installModernConversationHooks(runtimeKey: String, allowDexSearch: Boolean)
  380: fun installModernConversationHooks(runtimeKey: String, allowDexSearch: Boolean) {
  383: ) return
  384: val methods = locateModernConversationMethods(runtimeKey, allowDexSearch) ?: return
  388: methods.holder,
  402: "${methodDescriptor(methods.holder)} ${it.message}",
  411: methods.bind,
  432: "${methodDescriptor(methods.bind)} ${it.message}",
  LOCAL_LOCATOR locateModernConversationMethods L440 fun locateModernConversationMethods( runtimeKey: String, allowDexSearch: Boolean ): ModernConversationMethods?
ORIGIN installLayoutInflaterHook L680 fun installLayoutInflaterHook(method: Method?): Boolean
  680: fun installLayoutInflaterHook(method: Method?): Boolean {
  681: if (method == null) return false
  682: return runCatching {
  683: HookRegistry.get().hook(method,
  CALLER L181 layoutInflaterInstalled = installLayoutInflaterHook(
ORIGIN installRecyclerAdapterHook L838 fun installRecyclerAdapterHook(method: Method?, label: String): Boolean
  838: fun installRecyclerAdapterHook(method: Method?, label: String): Boolean {
  839: if (method == null) return false
  840: return runCatching {
  841: HookRegistry.get().hook(method,
  CALLER L186 recyclerInstalled = installRecyclerAdapterHook(
  CALLER L196 recyclerPayloadInstalled = installRecyclerAdapterHook(

FILE hooks/items/hotupdate/DisableHotUpdateFeature.kt
L109 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookTinkerFlags@97
L134 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookTinkerLoader@120
L171 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookManualUpdater@159
L218 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookSyncResponseConsumer@181
L258 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookUpgradeResponse@233
L327 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookLegacyPatchService@318
ORIGIN hookTinkerFlags L97 fun hookTinkerFlags(classLoader: ClassLoader): Boolean
  98: return runCatching {
  99: val clazz = classLoader.loadClass(TINKER_APP)
  100: val method = clazz.declaredMethods.firstOrNull {
  102: it.parameterTypes.isEmpty() &&
  103: (it.returnType == Int::class.javaPrimitiveType || it.returnType == Int::class.javaObjectType)
  105: if (method == null) {
  107: return@runCatching false
  109: HookRegistry.get().hook(method,
ORIGIN hookTinkerLoader L120 fun hookTinkerLoader(classLoader: ClassLoader): Boolean
  121: return runCatching {
  122: val clazz = classLoader.loadClass(TINKER_LOADER)
  123: val methods = clazz.declaredMethods.filter { method ->
  124: method.name == "tryLoadPatchFilesInternal" &&
  125: method.parameterTypes.size >= 2 &&
  126: method.parameterTypes[0].name == TINKER_APP &&
  127: method.parameterTypes[1] == android.content.Intent::class.java
  129: if (methods.isEmpty()) {
  131: return@runCatching false
  133: methods.forEach { method ->
  134: HookRegistry.get().hook(method,
ORIGIN hookManualUpdater L159 fun hookManualUpdater( classLoader: ClassLoader, dexKit: DexKitBridge, cache: android.content.SharedPreferences, runtimeKey: String )
  161: dexKit: DexKitBridge,
  165: val method = findManualUpdaterMethod(classLoader, dexKit, cache, runtimeKey)
  166: if (method == null) {
  168: return
  171: HookRegistry.get().hook(method,
  LOCAL_LOCATOR findManualUpdaterMethod L269 fun findManualUpdaterMethod( classLoader: ClassLoader, dexKit: DexKitBridge, cache: android.content.SharedPreferences, runtimeKey: String ): Method?
ORIGIN hookSyncResponseConsumer L181 fun hookSyncResponseConsumer( classLoader: ClassLoader, dexKit: DexKitBridge, cache: android.content.SharedPreferences, runtimeKey: String )
  183: dexKit: DexKitBridge,
  188: val responseClass = runCatching { classLoader.loadClass(TINKER_SYNC_RESPONSE) }.getOrNull()
  191: return
  193: var count = 0
  196: val cached = DexMethodCache.loadListCrossProcess(cache, runtimeKey, classLoader, "sync_response_consumer")
  199: val methods = cached.ifEmpty {
  200: dexKit.findMethod(
  201: FindMethod().apply {
  208: ?.takeIf { method -> isSyncResponseConsumer(method, responseClass) }
  213: if (cached.isEmpty()) {
  214: if (methods.isNotEmpty()) DexMethodCache.saveList(cache, runtimeKey, "sync_response_consumer", methods)
  215: else DexMethodCache.clear(cache, runtimeKey, "sync_response_consumer")
  217: methods.forEach { method ->
  218: HookRegistry.get().hook(method,
  LOCAL_LOCATOR isSyncResponseConsumer L302 fun isSyncResponseConsumer(method: Method, responseClass: Class<*>): Boolean
ORIGIN hookUpgradeResponse L233 fun hookUpgradeResponse( classLoader: ClassLoader, dexKit: DexKitBridge, cache: android.content.SharedPreferences, runtimeKey: String )
  235: dexKit: DexKitBridge,
  240: val cached = DexMethodCache.loadListCrossProcess(cache, runtimeKey, classLoader, "upgrade_response")
  241: val methods = cached.ifEmpty {
  242: dexKit.findMethod(
  243: FindMethod().apply {
  250: ?.takeIf { method -> isUpgradeResponseHandler(method) }
  253: if (cached.isEmpty()) {
  254: if (methods.isNotEmpty()) DexMethodCache.saveList(cache, runtimeKey, "upgrade_response", methods)
  255: else DexMethodCache.clear(cache, runtimeKey, "upgrade_response")
  257: methods.forEach { method ->
  258: HookRegistry.get().hook(method,
  LOCAL_LOCATOR isUpgradeResponseHandler L306 fun isUpgradeResponseHandler(method: Method): Boolean
ORIGIN hookLegacyPatchService L318 fun hookLegacyPatchService(classLoader: ClassLoader)
  320: val clazz = classLoader.loadClass(LEGACY_PATCH_SERVICE)
  321: val methods = clazz.declaredMethods.filter { method ->
  322: method.parameterTypes.size == 1 &&
  323: method.parameterTypes[0].name == PATCH_RESULT &&
  324: method.returnType == Void.TYPE
  326: methods.forEach { method ->
  327: HookRegistry.get().hook(method,
  334: if (it is ClassNotFoundException) return@onFailure

FILE hooks/items/inputhint/InputHintFeature.kt
L106 [终端] HookRegistry.get().hook TARGET=attachMethod CALLBACK=after IN=installInputHook@91
ORIGIN installInputHook L91 fun installInputHook(): Boolean
  92: if (inputHookInstalled) return true
  93: val footerClass = KavaReflector.loadClass(CHAT_FOOTER_CLASS, classLoader) ?: run {
  95: return false
  97: val attachMethod = KavaReflector.declaredMethods(footerClass).singleOrNull { method ->
  98: method.name == "onAttachedToWindow" &&
  99: method.returnType == Void.TYPE &&
  100: method.parameterTypes.isEmpty()
  103: return false
  105: return runCatching {

FILE hooks/items/legacychatinput/LegacyChatInputFeature.kt
L73 [终端] HookRegistry.get().hook TARGET=readMethod CALLBACK=before IN=onFeatureInstall@20
ORIGIN onFeatureInstall L20 fun onFeatureInstall(context: FeatureContext)
  21: val loadPackageParam = context.loadPackageParam()
  22: val packageName = loadPackageParam.packageName ?: return
  23: if (!packageName.startsWith(WECHAT_PACKAGE_PREFIX)) return
  24: if (loadPackageParam.processName != null && loadPackageParam.processName != packageName) return
  30: val version = WeChatApis.version()?.current() ?: run {
  32: return
  34: if (!WeChatVersionMatch.matches(version, TARGET_MAIN_VERSION, TARGET_BUILD_NO)) return
  36: val classLoader = context.hostClassLoader()
  37: val configManagerClass = KavaReflector.loadClass(CONFIG_MANAGER_CLASS, classLoader)
  38: val configBaseClass = KavaReflector.loadClass(CONFIG_BASE_CLASS, classLoader)
  39: val inputBarConfigClass = KavaReflector.loadClass(
  45: return
  47: if (!configBaseClass.isAssignableFrom(inputBarConfigClass)) {
  49: return
  52: val readMethods = runCatching {
  53: KavaReflector.declaredMethods(configManagerClass).filter { method ->
  54: method.name == CONFIG_READ_METHOD &&
  55: method.returnType == Integer.TYPE &&
  56: method.parameterTypes.contentEquals(arrayOf(configBaseClass))
  60: return
  62: val readMethod = readMethods.singleOrNull() ?: run {
  68: return
  70: val preferences = LegacyChatInputSettings.preferences(context.hostContext())

FILE hooks/items/linkparse/LinkParseRuntime.kt
L127 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=installMessageWriteHook@113
ORIGIN installMessageWriteHook L113 fun installMessageWriteHook(): Boolean
  114: if (stopped) return false
  115: if (messageWriteHookInstalled) return true
  116: val runtimeKey = DexMethodCache.runtimeKey(host, context.hostClassLoader())
  117: if (runtimeKey.isBlank()) { HLog.e("[Hchat:LinkParse] 消息写入定位缓存不可用"); return false }
  118: val cached = DexMethodCache.load(methodPrefs, runtimeKey, context.hostClassLoader(), CACHE_MESSAGE_WRITE)
  119: ?.takeIf(::isMessageWriteMethod)
  120: val method = cached ?: findMessageWriteMethod()
  121: if (method == null) {
  122: DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_MESSAGE_WRITE)
  124: return false
  126: if (cached == null) DexMethodCache.save(methodPrefs, runtimeKey, CACHE_MESSAGE_WRITE, method)
  127: HookRegistry.get().hook(method,
  153: return true
  LOCAL_LOCATOR findMessageWriteMethod L156 fun findMessageWriteMethod(): Method?

FILE hooks/items/membertitle/MemberTitleRenderer.kt
L64 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=installUsernameBindHook@60
ORIGIN installUsernameBindHook L60 fun installUsernameBindHook(): Boolean
  61: if (usernameBindInstalled) return true
  62: val method = locateUsernameBindMethod() ?: return false
  63: return try {
  64: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateUsernameBindMethod L256 fun locateUsernameBindMethod(): Method?

FILE hooks/items/menustyle/MenuStyleFeature.kt
L105 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=install@71
ORIGIN install L71 fun install(): Boolean
  72: if (installed) return true
  73: val popupClass = KavaReflector.loadClass(
  78: return false
  80: val methods = listOfNotNull(
  81: KavaReflector.findMethodRecursive(
  83: "showAtLocation",
  89: KavaReflector.findMethodRecursive(
  98: if (methods.isEmpty()) {
  100: return false
  102: var hooked = 0
  103: methods.forEach { method ->
  105: HookRegistry.get().hook(method,
  116: logger("菜单美化：Hook 安装失败 ${method.name}", it)
  125: return installed

FILE hooks/items/messageblock/MessageBlockFeature.kt
L48 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installBlockHook@36
ORIGIN installBlockHook L36 fun installBlockHook(context: FeatureContext): Boolean
  37: if (installed) return true
  38: val parser = WeChatApis.messageParser() ?: return false
  39: val classes = context.dexFinder().addMsgClasses
  40: if (classes.isNullOrEmpty()) return false
  42: var count = 0
  43: for (clazz in classes) {
  44: for (method in KavaReflector.declaredMethods(clazz)) {
  45: if (method.returnType != Void.TYPE) continue
  46: val addMsgArgIndexes = addMsgArgIndexes(method, parser)
  47: if (addMsgArgIndexes.isEmpty()) continue
  48: HookRegistry.get().hook(method,
  74: return installed

FILE hooks/items/messagebubble/MessageBubbleFeature.kt
L116 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=install@98
ORIGIN install L98 fun install(): Boolean
  99: if (adapterBindInstalled) return true
  101: val useDarkAssets = isDarkMode(context.hostContext()) && prefs.getBoolean(
  107: if (bubbleViewIds.isEmpty()) {
  109: return false
  111: val method = locateAdapterBindMethod() ?: run {
  113: return false
  115: return runCatching {
  116: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateAdapterBindMethod L943 fun locateAdapterBindMethod(): Method?
  LOCAL_LOCATOR isDarkMode L987 fun isDarkMode(context: Context): Boolean

FILE hooks/items/messagebubble/MessageBubblePicker.kt
L62 [终端] XposedBridge.hookAllMethods TARGET=clazz | "onActivityResult" CALLBACK=after IN=hookActivityResult@59
ORIGIN hookActivityResult L59 fun hookActivityResult(clazz: Class<*>)
  59: fun hookActivityResult(clazz: Class<*>) {
  60: if (!hookedClasses.add(clazz)) return
  62: XposedBridge.hookAllMethods(clazz, "onActivityResult",
  84: }.onFailure { hookedClasses.remove(clazz) }
  CALLER L28 hookActivityResult(activity.javaClass)
  CALLER L29 hookActivityResult(Activity::class.java)

FILE hooks/items/messageforward/GallerySelectLimit.kt
L57 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=before IN=hookClass@53
ORIGIN hookClass L53 fun hookClass(className: String, methodName: String): Boolean
  53: fun hookClass(className: String, methodName: String): Boolean {
  54: val clazz = KavaReflector.loadClass(className, context.hostClassLoader()) ?: return false
  55: val method = findMethod(clazz, methodName) ?: return false
  56: return runCatching {
  57: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method,
  66: logger("$className#$methodName Hook 安装失败", it)
  LOCAL_LOCATOR findMethod L70 fun findMethod(clazz: Class<*>, name: String): Method?
  CALLER L43 val album = hookClass(ALBUM_PREVIEW_UI, INIT_VIEW)
  CALLER L44 val entry = hookClass(GALLERY_ENTRY_UI, ON_RESUME)

FILE hooks/items/messageforward/MessageForwardFeature.kt
L143 [包装调用] hook TARGET=method CALLBACK=after IN=install@138
L150 [包装调用] hook TARGET=method CALLBACK=before IN=install@138
L165 [包装调用] hook TARGET=multiCreateMethod CALLBACK=after IN=install@138
L171 [包装调用] hook TARGET=multiClickMethod CALLBACK=before IN=install@138
L184 [包装调用] hook TARGET=method CALLBACK=after IN=install@138
L195 [包装调用] hook TARGET=method CALLBACK=before IN=install@138
L222 [转发] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=callback IN=hook@218
ORIGIN install L138 fun install(): Boolean
  139: chatLivePhotoResolver.warmup()
  140: val createMethods = SingleMessageMenuLocator.menuCreateMethods(context, logger)
  141: val clickMethods = SingleMessageMenuLocator.menuClickMethods(context, logger)
  142: val createHooked = createMethods.count { method ->
  143: hook(method,
  149: val clickHooked = clickMethods.count { method ->
  150: hook(method,
  159: val multiCreateMethod = MultiSelectMessageMenuLocator.menuCreateMethod(context, logger)
  160: val multiClickMethod = MultiSelectMessageMenuLocator.menuClickMethod(context, logger)
  161: val multiExitMethod = multiClickMethod?.let {
  162: MultiSelectMessageMenuLocator.multiSelectExitMethod(context, it, logger)
  164: val multiCreateHooked = multiCreateMethod != null && multiExitMethod != null &&
  170: val multiClickHooked = multiClickMethod != null && multiExitMethod != null &&
  179: val favoriteCreateHooked = FavoriteMenuLocator.menuCreateMethods(
  183: ).count { method ->
  184: hook(method,
  190: val favoriteClickHooked = FavoriteMenuLocator.menuClickMethods(
  194: ).count { method ->
  195: hook(method,
  203: return createHooked > 0 && clickHooked > 0 &&
ORIGIN hook L218 fun hook(method: Method, callback: XC_MethodHook): Boolean
  218: fun hook(method: Method, callback: XC_MethodHook): Boolean {
  219: if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
  220: if (!hookedMethods.add(method)) return true
  221: return runCatching {
  222: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
  225: hookedMethods.remove(method)
  226: logger("转发菜单Hook安装失败: ${method.toGenericString()}", it)
  CALLER L143 hook(method, object : XC_MethodHook() {
  CALLER L150 hook(method, object : XC_MethodHook() {
  CALLER L165 hook(multiCreateMethod, object : XC_MethodHook() {
  CALLER L171 hook(multiClickMethod, object : XC_MethodHook() {
  CALLER L184 hook(method, object : XC_MethodHook() {
  CALLER L195 hook(method, object : XC_MethodHook() {

FILE hooks/items/messageforward/NativeMassSendPicker.kt
L54 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=before IN=install@46
L101 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(destroy) ?: destroy CALLBACK=after IN=installDismissCleanup@97
ORIGIN install L46 fun install(): Boolean
  47: val generation = HookRegistry.get().installationGeneration()
  48: if (HookRegistry.get().isInstallationGenerationActive(installedGeneration)) return true
  49: val method = locateNextButton() ?: run {
  51: return false
  53: return runCatching {
  54: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method,
  63: HLog.event("$TAG 已挂钩 ${method.declaringClass.name}#${method.name}")
  66: logger("微信群发选择页Hook安装失败: ${method.toGenericString()}", it)
  LOCAL_LOCATOR locateNextButton L194 fun locateNextButton(): Method?
ORIGIN installDismissCleanup L97 fun installDismissCleanup()
  98: val ui = KavaReflector.loadClass(SELECT_UI_CLASS_NAME, context.hostClassLoader()) ?: return
  99: val destroy = KavaReflector.findMethodRecursive(ui, "onDestroy") ?: return

FILE hooks/items/messagetextcolor/MessageTextColorFeature.kt
L99 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=install@92
ORIGIN install L92 fun install(): Boolean
  93: if (adapterBindInstalled) return true
  94: val method = locateAdapterBindMethod() ?: run {
  96: return false
  98: return runCatching {
  99: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateAdapterBindMethod L812 fun locateAdapterBindMethod(): Method?

FILE hooks/items/miniprogrambaselib/FakeMiniProgramBaseLibFeature.kt
L100 [终端] HookRegistry.get().hook TARGET=launchConstructor CALLBACK=before IN=install@95
L260 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookPrivateOpenUrlMethod@259
ORIGIN install L95 fun install(): Boolean
  96: if (installed) return true
  97: return runCatching {
  98: val launchConstructor = locateLaunchConstructor() ?: return false
  99: locatePrivateOpenUrlMethod() ?: return false
  LOCAL_LOCATOR locateLaunchConstructor L115 fun locateLaunchConstructor(): Constructor<*>?
  LOCAL_LOCATOR locatePrivateOpenUrlMethod L162 fun locatePrivateOpenUrlMethod(): Method?
ORIGIN hookPrivateOpenUrlMethod L259 fun hookPrivateOpenUrlMethod(method: Method, context: Context)
  259: fun hookPrivateOpenUrlMethod(method: Method, context: Context) {
  260: HookRegistry.get().hook(method,
  CALLER L73 hookPrivateOpenUrlMethod(privateOpenUrlMethod, context)

FILE hooks/items/miniprogramforceshare/MiniProgramForceShareFeature.kt
L106 [终端] HookRegistry.get().hook TARGET=add CALLBACK=before IN=installMenuHooks@90
L134 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(gate) ?: gate CALLBACK=before IN=installMenuHooks@90
L153 [终端] HookRegistry.get().hook TARGET=build CALLBACK=after IN=installMenuHooks@90
ORIGIN installMenuHooks L90 fun installMenuHooks(context: FeatureContext, enabled: () -> Boolean): Int
  91: val loader = context.hostClassLoader()
  92: var installed = 0
  93: val page = MenuTargets.resolve(loader)
  96: return 0
  98: val registry = page.registry
  102: val add = registry.declaredMethods.firstOrNull(::isMenuItemAppend)
  122: val gate = page.pageBase?.let { base ->
  123: KavaReflector.declaredMethods(base).firstOrNull { method ->
  124: method.name == page.gateName &&
  125: method.parameterCount == 2 &&
  126: method.parameterTypes[0] == Integer.TYPE &&
  127: method.parameterTypes[1] == java.lang.Boolean.TYPE &&
  128: method.returnType == java.lang.Void.TYPE
  149: val build = page.listMethod
  150: if (build == null || build.returnType != java.util.List::class.java) {
  165: return installed

FILE hooks/items/miniprogramsplashad/SkipGlobalMiniProgramSplashAdsFeature.kt
L193 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookSplashAdCheckMethod@192
ORIGIN hookSplashAdCheckMethod L192 fun hookSplashAdCheckMethod(method: Method, context: Context)
  192: fun hookSplashAdCheckMethod(method: Method, context: Context) {
  193: HookRegistry.get().hook(method,
  CALLER L93 hookSplashAdCheckMethod(method, context)
  CALLER L120 hookSplashAdCheckMethod(method, context.hostContext())

FILE hooks/items/miniprogramvideoad/SkipMiniProgramVideoAdsFeature.kt
L71 [终端] HookRegistry.get().hook TARGET=subscribeHandler CALLBACK=before IN=install@48
ORIGIN install L48 fun install(context: Context?, classLoader: ClassLoader?): Boolean
  49: if (context == null || classLoader == null) return false
  50: val loaderKey = "${classLoader.javaClass.name}@${System.identityHashCode(classLoader)}"
  52: if (installedLoaders.contains(loaderKey)) return true
  53: val bridgeClass = KavaReflector.loadClass(BRIDGE_CLASS, classLoader)
  56: return false
  58: val subscribeHandler = KavaReflector.findMethod(
  66: if (subscribeHandler == null || subscribeHandler.returnType != java.lang.Void.TYPE) {
  68: return false
  70: return runCatching {

FILE hooks/items/moments/MomentsBottomDetailFeature.kt
L119 [终端] HookRegistry.get().hook TARGET=timeMethod CALLBACK=after IN=installTimeHook@115
L140 [终端] HookRegistry.get().hook TARGET=groupMethod CALLBACK=after IN=installGroupHook@135
L166 [终端] HookRegistry.get().hook TARGET=itemMethod CALLBACK=after IN=installProfileHook@161
L182 [终端] HookRegistry.get().hook TARGET=formatterMethod CALLBACK=after IN=installProfileHook@161
L197 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installProfileHook@161
ORIGIN installTimeHook L115 fun installTimeHook(): Boolean
  116: if (timeHookInstalled) return true
  117: val timeMethod = locateTimeMethod() ?: return false
  118: return runCatching {
  LOCAL_LOCATOR locateTimeMethod L257 fun locateTimeMethod(): Method?
ORIGIN installGroupHook L135 fun installGroupHook(): Boolean
  136: if (groupHookInstalled) return true
  137: val owner = locateTimeMethod()?.declaringClass ?: return false
  138: val groupMethod = locateGroupMethod(owner) ?: return false
  139: return runCatching {
  LOCAL_LOCATOR locateTimeMethod L257 fun locateTimeMethod(): Method?
  LOCAL_LOCATOR locateGroupMethod L282 fun locateGroupMethod(owner: Class<*>): Method?
ORIGIN installProfileHook L161 fun installProfileHook(): Boolean
  162: if (profileHookInstalled) return true
  163: val formatterMethod = locateProfileFormatterMethod() ?: return false
  164: val itemMethod = locateProfileItemMethod(formatterMethod.declaringClass) ?: return false
  165: return runCatching {
  196: locateFlutterProfileSwitchMethod()?.let { method ->
  197: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateProfileFormatterMethod L306 fun locateProfileFormatterMethod(): Method?
  LOCAL_LOCATOR locateProfileItemMethod L329 fun locateProfileItemMethod(profileBaseOwner: Class<*>): Method?
  LOCAL_LOCATOR locateFlutterProfileSwitchMethod L352 fun locateFlutterProfileSwitchMethod(): Method?

FILE hooks/items/moments/MomentsContactFilterFeature.kt
L71 [终端] HookRegistry.get().hook TARGET=onCreate CALLBACK=after IN=installTimelineMenuHook@63
ORIGIN installTimelineMenuHook L63 fun installTimelineMenuHook(context: FeatureContext, className: String): Boolean
  63: fun installTimelineMenuHook(context: FeatureContext, className: String): Boolean {
  64: val activityClass = KavaReflector.loadClass(className, context.hostClassLoader()) ?: return false
  65: val onCreate = KavaReflector.findDeclaredMethod(
  69: ) ?: return false
  70: return runCatching {
  78: logError("朋友圈过滤快捷入口安装失败: $className", it)
  CALLER L48 installTimelineMenuHook(context, className)

FILE hooks/items/moments/MomentsKeywordBlockFeature.kt
L264 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=before IN=installSnsTimelineQueryHook@260
L288 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=after,before IN=installLegacyTimelineHook@280
L331 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=after,before IN=installImproveTimelineHook@327
L376 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=after,before IN=installProfileListHook@372
L423 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=before IN=installProfileSwitchHook@415
ORIGIN installSnsTimelineQueryHook L260 fun installSnsTimelineQueryHook(): Boolean
  261: if (snsTimelineQueryHookInstalled) return true
  262: val method = locateSnsTimelineQueryMethod() ?: return false
  263: return runCatching {
  264: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method,
  274: logger("朋友圈关键词屏蔽朋友圈数据库查询 Hook 安装失败: ${method.toGenericString()}", it)
ORIGIN installLegacyTimelineHook L280 fun installLegacyTimelineHook(): Boolean
  281: if (legacyTimelineHookInstalled) return true
  282: val method = locateLegacyTimelineCreateViewMethod()
  283: if (method == null) {
  285: return true
  287: return runCatching {
  288: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method,
  321: logger("朋友圈关键词屏蔽旧版时间线 Hook 安装失败: ${method.toGenericString()}", it)
ORIGIN installImproveTimelineHook L327 fun installImproveTimelineHook(): Boolean
  328: if (improveTimelineHookInstalled) return true
  329: val method = locateImproveTimelineBindMethod() ?: return false
  330: return runCatching {
  331: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method,
  366: logger("朋友圈关键词屏蔽新版时间线 Hook 安装失败: ${method.toGenericString()}", it)
ORIGIN installProfileListHook L372 fun installProfileListHook(): Boolean
  373: if (profileListHookInstalled) return true
  374: val method = locateProfileListBindMethod() ?: return false
  375: return runCatching {
  376: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method,
  409: logger("朋友圈关键词屏蔽个人主页列表 Hook 安装失败: ${method.toGenericString()}", it)
ORIGIN installProfileSwitchHook L415 fun installProfileSwitchHook(): Boolean
  416: if (profileSwitchHookInstalled) return true
  419: return true
  421: val method = locateFlutterProfileSwitchMethod() ?: return false
  422: return runCatching {
  423: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method,
  431: logger("朋友圈关键词屏蔽个人主页 Hook 安装失败: ${method.toGenericString()}", it)

FILE hooks/items/moments/MomentsUploadTailFeature.kt
L72 [终端] HookRegistry.get().hook TARGET=commit CALLBACK=before IN=install@61
ORIGIN install L61 fun install(): Boolean
  62: if (installed) return true
  63: val finder = context.dexFinder()
  64: runCatching { finder.resolveSnsUploadApi() }.onFailure {
  67: val commit = finder.snsCommitMethod ?: return false
  68: val sdkId = finder.snsSetSdkIdMethod ?: return false
  69: val sdkAppName = finder.snsSetSdkAppNameMethod ?: return false
  70: if (Modifier.isAbstract(commit.modifiers) || commit.declaringClass.isInterface) return false
  71: return runCatching {

FILE hooks/items/moments/MomentsVideoAutoPlayFeature.kt
L227 [终端] HookRegistry.get().hook TARGET=method CALLBACK=AutoPlayGuardHook(hostContext, primitive) IN=installHooks@191
ORIGIN installHooks L191 fun installHooks(): Boolean
  192: val hostContext = appContext ?: return false
  193: val bridge = bridge ?: return false
  194: val loader = classLoader ?: return false
  196: if (!isEnabled(hostContext)) {
  200: return true
  203: val loaderKey = "${loader.javaClass.name}@${System.identityHashCode(loader)}"
  205: if (installedLoaders.contains(loaderKey)) return true
  209: val methods = resolveJudgeMethods(bridge, loader, hostContext)
  210: if (methods.isEmpty()) {
  219: return true
  222: var hooked = 0
  223: for (method in methods) {
  226: val primitive = method.returnType == java.lang.Boolean.TYPE
  228: method,
  233: "$TAG Hook 成功: ${method.declaringClass.name}#${method.name}" +
  234: " (返回 ${method.returnType.simpleName})"
  238: "$TAG Hook 失败: ${method.declaringClass.name}#${method.name}: ${e.message}",
  253: return true
  LOCAL_LOCATOR isEnabled L146 fun isEnabled(context: Context?): Boolean
  LOCAL_LOCATOR resolveJudgeMethods L276 fun resolveJudgeMethods( bridge: DexKitBridge, loader: ClassLoader, context: Context ): List<Method>
NAMED_CALLBACK AutoPlayGuardHook L370

FILE hooks/items/moments/OriginalMomentsUploadFeature.kt
L72 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=install@67
L92 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=install@67
ORIGIN install L67 fun install(allowDexSearch: Boolean): Boolean
  67: fun install(allowDexSearch: Boolean): Boolean {
  68: locateSnsConvertImgMethod(allowDexSearch)?.let { convertImg2WxamMethod = it }
  70: locateImagePreviewSendMethod(allowDexSearch)?.let { method ->
  72: HookRegistry.get().hook(method,
  90: locateSnsCreatePicMethod(allowDexSearch)?.let { method ->
  92: HookRegistry.get().hook(method,
  104: return imagePreviewInstalled && createPicInstalled && convertImg2WxamMethod != null
  LOCAL_LOCATOR locateImagePreviewSendMethod L170 fun locateImagePreviewSendMethod(allowDexSearch: Boolean): Method?
  LOCAL_LOCATOR locateSnsCreatePicMethod L186 fun locateSnsCreatePicMethod(allowDexSearch: Boolean): Method?
  LOCAL_LOCATOR locateSnsConvertImgMethod L208 fun locateSnsConvertImgMethod(allowDexSearch: Boolean): Method?

FILE hooks/items/moments/RemoveMomentsAdsFeature.kt
L59 [终端] HookRegistry.get().hook TARGET=constructor CALLBACK=before IN=install@43
ORIGIN install L43 fun install(context: Context?, classLoader: ClassLoader?)
  44: if (context == null || classLoader == null) return
  45: val key = "${classLoader.javaClass.name}@${System.identityHashCode(classLoader)}"
  47: if (installedLoaders.contains(key)) return
  48: val clazz = KavaReflector.loadClass(AD_INFO_CLASS, classLoader)
  49: if (clazz == null) {
  51: return
  53: val constructor = KavaReflector.findConstructor(clazz, String::class.java)
  56: return

FILE hooks/items/moments/SnsAntiRecallFeature.kt
L87 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookDatabaseClass@81
L194 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookLegacySnsUserPageSwitch@190
ORIGIN hookDatabaseClass L81 fun hookDatabaseClass(dbClass: Class<*>?): Int
  81: fun hookDatabaseClass(dbClass: Class<*>?): Int {
  82: if (dbClass == null) return 0
  83: var count = 0
  84: for (method in KavaReflector.declaredMethods(dbClass)) {
  85: if (!isSnsWriteMethod(method) && !isSnsRawQueryMethod(method)) continue
  86: if (!hookedMethods.add(method)) continue
  87: HookRegistry.get().hook(method,
  104: return count
  LOCAL_LOCATOR isSnsWriteMethod L180 fun isSnsWriteMethod(method: Method): Boolean
  LOCAL_LOCATOR isSnsRawQueryMethod L184 fun isSnsRawQueryMethod(method: Method): Boolean
ORIGIN hookLegacySnsUserPageSwitch L190 fun hookLegacySnsUserPageSwitch(): Int
  191: val method = findEnableFlutterSnsPageMethod() ?: return 0
  192: if (!hookedMethods.add(method)) return 0
  193: return try {
  194: HookRegistry.get().hook(method,
  LOCAL_LOCATOR findEnableFlutterSnsPageMethod L208 fun findEnableFlutterSnsPageMethod(): Method?

FILE hooks/items/momentsfake/MomentsFakeForwardRuntime.kt
L470 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=after IN=installImprovePageDiagnostic@452
L789 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=before IN=installTimelineAdapterTracker@785
L817 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=before IN=installSourceTypeGuard@804
L879 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=before IN=installDeleteGuard@867
L954 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=after IN=installQueryDiagnostic@911
L964 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(timeline) ?: timeline CALLBACK=after IN=installQueryDiagnostic@911
ORIGIN installImprovePageDiagnostic L452 fun installImprovePageDiagnostic(): Boolean
  453: if (improvePageDiagnosticInstalled) return true
  454: val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
  455: val cached = DexMethodCache.load(
  461: val method = cached?.takeIf(::isImprovePageQueryMethod) ?: runCatching {
  462: context.dexKitBridge().findMethod(FindMethod().apply {
  465: .filter(::isImprovePageQueryMethod)
  467: }.onFailure { logger("朋友圈 Improve 分页诊断入口定位失败", it) }.getOrNull() ?: return false
  468: DexMethodCache.save(cachePrefs, runtimeKey, CACHE_IMPROVE_PAGE_QUERY, method)
  469: return runCatching {
  470: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method,
ORIGIN installTimelineAdapterTracker L785 fun installTimelineAdapterTracker(method: Method): Boolean
  785: fun installTimelineAdapterTracker(method: Method): Boolean {
  786: if (timelineAdapterTrackerInstalled) return true
  787: if (!isTimelineLocalReloadMethod(method)) return false
  788: return runCatching {
  789: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method,
  798: trace("朋友圈本地 Cursor Adapter 跟踪已安装: ${method.declaringClass.name}#${method.name}")
  LOCAL_LOCATOR isTimelineLocalReloadMethod L777 fun isTimelineLocalReloadMethod(method: Method): Boolean
  CALLER L559 installTimelineAdapterTracker(method)
  CALLER L574 installTimelineAdapterTracker(method)
ORIGIN installSourceTypeGuard L804 fun installSourceTypeGuard(): Boolean
  805: if (sourceTypeGuardInstalled) return true
  806: val snsInfoClass = KavaReflector.loadClass(
  809: ) ?: return false
  810: val method = KavaReflector.findMethodRecursive(
  814: ) ?: return false
  815: if (method.returnType != Void.TYPE || Modifier.isStatic(method.modifiers)) return false
  816: return runCatching {
  817: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method,
ORIGIN installDeleteGuard L867 fun installDeleteGuard(): Boolean
  868: if (deleteGuardInstalled) return true
  869: val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
  870: val cached = DexMethodCache.load(cachePrefs, runtimeKey, context.hostClassLoader(), CACHE_DELETE_CALLBACK)
  871: val method = cached?.takeIf(::isDeleteCallback) ?: runCatching {
  872: context.dexKitBridge().findMethod(FindMethod().apply {
  875: .filter(::isDeleteCallback).firstOrNull()
  876: }.getOrNull() ?: return false
  877: DexMethodCache.save(cachePrefs, runtimeKey, CACHE_DELETE_CALLBACK, method)
  878: return runCatching {
  879: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method,
ORIGIN installQueryDiagnostic L911 fun installQueryDiagnostic(): Boolean
  912: if (queryDiagnosticInstalled) return true
  913: val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
  914: val cached = DexMethodCache.load(cachePrefs, runtimeKey, context.hostClassLoader(), CACHE_USER_QUERY)
  915: val method = cached?.takeIf(::isUserQueryMethod) ?: runCatching {
  916: context.dexKitBridge().findMethod(FindMethod().apply {
  919: .filter(::isUserQueryMethod).singleOrNull()
  920: }.getOrNull() ?: return false
  921: val cachedPredicate = DexMethodCache.load(
  927: val predicate = cachedPredicate?.takeIf(::isUserQueryPredicateMethod) ?: runCatching {
  928: context.dexKitBridge().findMethod(FindMethod().apply {
  931: .filter(::isUserQueryPredicateMethod).singleOrNull()
  932: }.getOrNull() ?: return false
  933: val cachedTimeline = DexMethodCache.load(
  939: val timeline = cachedTimeline?.takeIf(::isTimelineQueryCallback) ?: runCatching {
  940: context.dexKitBridge().findMethod(FindMethod().apply {
  946: .filter(::isTimelineQueryCallback).singleOrNull()
  947: }.getOrNull() ?: return false
  948: DexMethodCache.save(cachePrefs, runtimeKey, CACHE_USER_QUERY, method)
  949: DexMethodCache.save(cachePrefs, runtimeKey, CACHE_USER_QUERY_PREDICATE, predicate)
  950: DexMethodCache.save(cachePrefs, runtimeKey, CACHE_TIMELINE_QUERY, timeline)
  954: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method,
  973: return queryDiagnosticInstalled

FILE hooks/items/momentsfake/MomentsFakeInteractionRuntime.kt
L91 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=after IN=installRecordHooks@84
L310 [终端] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=before IN=installInteractionGuard@307
ORIGIN installRecordHooks L84 fun installRecordHooks(): Boolean
  85: val snsInfoClass = KavaReflector.loadClass(SNS_INFO_CLASS, context.hostClassLoader())
  86: ?: return false
  87: val methods = KavaReflector.declaredMethods(snsInfoClass).filter(::isRecordLoadMethod)
  88: val hooked = methods.count { method ->
  89: if (!hookedMethods.add(method)) return@count true
  92: KavaReflector.accessible(method) ?: method,
  101: hookedMethods.remove(method)
  102: logger("安装朋友圈伪互动记录Hook失败: ${method.toGenericString()}", it)
  106: return hooked > 0
ORIGIN installInteractionGuard L307 fun installInteractionGuard(method: Method): Boolean
  307: fun installInteractionGuard(method: Method): Boolean {
  308: if (!guardedMethods.add(method)) return true
  309: return runCatching {
  311: KavaReflector.accessible(method) ?: method,
  339: guardedMethods.remove(method)
  340: logger("安装朋友圈伪评论交互保护失败: ${method.toGenericString()}", it)
  CALLER L109 fun installInteractionGuard(): Boolean {

FILE hooks/items/multirecall/MultiRecallFeature.kt
L63 [包装调用] hookMethod TARGET=menuCreateMethod CALLBACK=after IN=install@57
L68 [包装调用] hookMethod TARGET=menuClickMethod CALLBACK=before IN=install@57
L176 [转发] HookRegistry.get().hook TARGET=method CALLBACK=callback IN=hookMethod@173
ORIGIN install L57 fun install(): Boolean
  58: val menuCreateMethod = MultiSelectMessageMenuLocator.menuCreateMethod(context, logger)
  59: val menuClickMethod = MultiSelectMessageMenuLocator.menuClickMethod(context, logger)
  60: val exitMethod = menuClickMethod?.let {
  61: MultiSelectMessageMenuLocator.multiSelectExitMethod(context, it, logger)
  63: val createHooked = menuCreateMethod != null && exitMethod != null && hookMethod(menuCreateMethod,
  68: val clickHooked = menuClickMethod != null && exitMethod != null && hookMethod(menuClickMethod,
  75: return createHooked && clickHooked
ORIGIN hookMethod L173 fun hookMethod(method: Method, callback: XC_MethodHook): Boolean
  173: fun hookMethod(method: Method, callback: XC_MethodHook): Boolean {
  174: if (!hookedMethods.add(method)) return true
  175: return runCatching {
  176: HookRegistry.get().hook(method, callback)
  179: hookedMethods.remove(method)
  180: logger("多选撤回Hook安装失败: ${method.toGenericString()}", it)
  CALLER L63 val createHooked = menuCreateMethod != null && exitMethod != null && hookMethod(menuCreateMethod, object : XC_MethodHook() {
  CALLER L68 val clickHooked = menuClickMethod != null && exitMethod != null && hookMethod(menuClickMethod, object : XC_MethodHook() {

FILE hooks/items/patblock/PatBlockFeature.kt
L74 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=install@70
ORIGIN install L70 fun install(allowDexSearch: Boolean): Boolean
  70: fun install(allowDexSearch: Boolean): Boolean {
  71: if (installed) return true
  72: val method = locateAvatarDoubleClickMethod(allowDexSearch) ?: return false
  73: return runCatching {
  74: HookRegistry.get().hook(method,
  84: logger("禁止拍一拍 Hook 安装失败: ${method.toGenericString()}", it)
  LOCAL_LOCATOR locateAvatarDoubleClickMethod L89 fun locateAvatarDoubleClickMethod(allowDexSearch: Boolean): Method?

FILE hooks/items/payment/detect/RedPacketDatabaseHook.java
L84 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookOneDatabaseClass@66
ORIGIN hookOneDatabaseClass L66 private int hookOneDatabaseClass(String className)
  67: private int hookOneDatabaseClass(String className) {
  69: Class<?> dbClass = KavaReflector.loadClass(className, classLoader);
  70: if (dbClass == null) return 0;
  72: for (Method method : KavaReflector.declaredMethods(dbClass)) {
  73: String name = method.getName();
  82: if (!hasContentValuesArg(method)) continue;
  84: HookRegistry.get().hook(method,
  94: if (hookedCount > 0) log("数据库兜底Hook: " + className + " count=" + hookedCount);
  95: return hookedCount;
  97: return 0;
  CALLER L57 count += hookOneDatabaseClass("com.tencent.wcdb.database.SQLiteDatabase");
  CALLER L58 count += hookOneDatabaseClass("android.database.sqlite.SQLiteDatabase");

FILE hooks/items/payment/detect/RedPacketMessageHook.java
L76 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hook@54
ORIGIN hook L54 public void hook()
  56: if (hooked) return;
  57: if (dexFinder.addMsgClasses.isEmpty()) {
  59: return;
  63: for (Class<?> clazz : dexFinder.addMsgClasses) {
  64: for (Method method : KavaReflector.declaredMethods(clazz)) {
  65: Class<?>[] parameterTypes = method.getParameterTypes();
  68: final List<Integer> addMsgArgIndexes = new ArrayList<>();
  69: for (int i = 0; i < parameterTypes.length; i++) {
  70: if (RedPacketReflector.isLikelyAddMsgClass(parameterTypes[i])) {
  71: addMsgArgIndexes.add(i);
  74: if (addMsgArgIndexes.isEmpty()) continue;
  76: HookRegistry.get().hook(method,

FILE hooks/items/payment/fake/RedPacketFakePacketHook.java
L76 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookPacketDispatcher@51
L366 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=hookFakePacketResponseFix@351
ORIGIN hookPacketDispatcher L51 private void hookPacketDispatcher()
  53: if (dispatcherHooked) return;
  55: List<Class<?>> sceneBaseClasses = new ArrayList<>();
  60: if (sceneBaseClasses.isEmpty()) sceneBaseClasses.addAll(dexFinder.packetBaseClasses);
  63: if (sceneBaseClasses.isEmpty()) {
  65: return;
  70: for (Class<?> sceneBaseClass : sceneBaseClasses) {
  71: for (Method method : KavaReflector.declaredMethods(sceneBaseClass)) {
  72: if (!"dispatch".equals(method.getName())) continue;
  73: if (method.getParameterTypes().length != 3) continue;
  74: String desc = sceneBaseClass.getName() + "#" + method;
  76: HookRegistry.get().hook(method,
  LOCAL_LOCATOR collectSceneBaseFromQueue L98 private void collectSceneBaseFromQueue(List<Class<?>> out)
  CALLER L48 hookPacketDispatcher();
ORIGIN hookFakePacketResponseFix L351 private void hookFakePacketResponseFix()
  353: if (responseHooked) return;
  355: List<Class<?>> classList = new ArrayList<>(dexFinder.fakePacketClasses);
  357: if (classList.isEmpty()) {
  359: return;
  362: for (Class<?> clazz : classList) {
  363: for (Method method : KavaReflector.declaredMethods(clazz)) {
  364: if (!"onGYNetEnd".equals(method.getName())) continue;
  365: if (method.getParameterTypes().length != 3) continue;
  366: HookRegistry.get().hook(method,
  CALLER L49 hookFakePacketResponseFix();

FILE hooks/items/payment/fakebalance/FakeWalletBalanceFeature.kt
L96 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=hookMoneyLoadingView@90
L134 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=hookTickerView@123
L164 [终端] HookRegistry.get().hook TARGET=textSizeMethod CALLBACK=after,before IN=hookTickerView@123
L241 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=hookMallWalletSectionCell@235
L276 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=hookLqtDetailEntry@270
ORIGIN hookMoneyLoadingView L90 fun hookMoneyLoadingView()
  91: val clazz = KavaReflector.loadClass(WC_PAY_MONEY_LOADING_VIEW, context.hostClassLoader()) ?: return
  92: val methods = KavaReflector.declaredMethods(clazz).filter { isMoneyLoadingMethod(it) }
  93: if (methods.isEmpty()) return
  94: var hooked = 0
  95: methods.forEach { method ->
  96: HookRegistry.get().hook(method,
  LOCAL_LOCATOR isMoneyLoadingMethod L341 fun isMoneyLoadingMethod(method: Method): Boolean
ORIGIN hookTickerView L123 fun hookTickerView()
  124: val methods = locateTickerMethods()
  125: val tickerClass = KavaReflector.loadClass(TICKER_VIEW, context.hostClassLoader())
  126: val textSizeMethod = KavaReflector.findDeclaredMethod(
  131: if (methods.isEmpty() && textSizeMethod == null) return
  132: var hooked = 0
  133: methods.forEach { method ->
  134: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateTickerMethods L297 fun locateTickerMethods(): List<Method>
ORIGIN hookMallWalletSectionCell L235 fun hookMallWalletSectionCell()
  236: val clazz = KavaReflector.loadClass(MALL_WALLET_SECTION_CELL_VIEW, context.hostClassLoader()) ?: return
  237: val methods = KavaReflector.declaredMethods(clazz).filter { isMallWalletSectionCellMethod(it) }
  238: if (methods.isEmpty()) return
  239: var hooked = 0
  240: methods.forEach { method ->
  241: HookRegistry.get().hook(method,
  LOCAL_LOCATOR isMallWalletSectionCellMethod L355 fun isMallWalletSectionCellMethod(method: Method): Boolean
ORIGIN hookLqtDetailEntry L270 fun hookLqtDetailEntry()
  271: val clazz = KavaReflector.loadClass(WX_CROSS_SERVICES, context.hostClassLoader()) ?: return
  272: val methods = KavaReflector.declaredMethods(clazz).filter { isLqtDetailEntryMethod(it) }
  273: if (methods.isEmpty()) return
  274: var hooked = 0
  275: methods.forEach { method ->
  276: HookRegistry.get().hook(method,
  LOCAL_LOCATOR isLqtDetailEntryMethod L365 fun isLqtDetailEntryMethod(method: Method): Boolean

FILE hooks/items/payment/gift/GiftMessageHook.java
L57 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hook@39
ORIGIN hook L39 public List<Object> hook()
  41: if (dexFinder == null || dexFinder.addMsgClasses == null || dexFinder.addMsgClasses.isEmpty()) {
  43: return subscriptions;
  46: for (Class<?> clazz : dexFinder.addMsgClasses) {
  47: for (Method method : KavaReflector.declaredMethods(clazz)) {
  48: Class<?>[] parameterTypes = method.getParameterTypes();
  51: final List<Integer> indexes = new ArrayList<>();
  52: for (int i = 0; i < parameterTypes.length; i++) {
  53: if (RedPacketReflector.isLikelyAddMsgClass(parameterTypes[i])) indexes.add(i);
  55: if (indexes.isEmpty()) continue;
  57: XC_MethodHook.Unhook unhook = HookRegistry.get().hook(method,
  76: return subscriptions;

FILE hooks/items/payment/grab/RedPacketSilentHandler.java
L285 [终端] HookRegistry.get().hook TARGET=onGYNetEnd CALLBACK=before IN=hookOneReceiveCallback@272
L440 [终端] HookRegistry.get().hook TARGET=onGYNetEnd CALLBACK=after IN=hookOneOpenCallback@427
ORIGIN hookOneReceiveCallback L272 private boolean hookOneReceiveCallback(Class<?> receiveClass, String label)
  273: private boolean hookOneReceiveCallback(Class<?> receiveClass, String label) {
  274: if (receiveClass == null) return false;
  276: java.lang.reflect.Method onGYNetEnd = null;
  277: for (java.lang.reflect.Method method : KavaReflector.declaredMethods(receiveClass)) {
  278: if ("onGYNetEnd".equals(method.getName()) && method.getParameterTypes().length == 3) {
  279: onGYNetEnd = method;
  283: if (onGYNetEnd == null) return false;
  413: return true;
  417: return false;
  CALLER L268 hooked = hookOneReceiveCallback(dexFinder.receiveLuckyMoneyClass, "normal") || hooked;
  CALLER L269 hooked = hookOneReceiveCallback(dexFinder.receiveLuckyMoneyUnionClass, "union") || hooked;
ORIGIN hookOneOpenCallback L427 private boolean hookOneOpenCallback(Class<?> openClass, String label)
  428: private boolean hookOneOpenCallback(Class<?> openClass, String label) {
  429: if (openClass == null) return false;
  431: java.lang.reflect.Method onGYNetEnd = null;
  432: for (java.lang.reflect.Method method : KavaReflector.declaredMethods(openClass)) {
  433: if ("onGYNetEnd".equals(method.getName()) && method.getParameterTypes().length == 3) {
  434: onGYNetEnd = method;
  438: if (onGYNetEnd == null) return false;
  516: return true;
  520: return false;
  CALLER L423 hooked = hookOneOpenCallback(dexFinder.openLuckyMoneyClass, "normal") || hooked;
  CALLER L424 hooked = hookOneOpenCallback(dexFinder.openLuckyMoneyUnionClass, "union") || hooked;

FILE hooks/items/payment/grab/RedPacketUiAutomator.java
L115 [包装调用] hookDeclaredAfter TARGET=clazz CALLBACK=after IN=hookReceiveLifecycle@112
L124 [包装调用] hookDeclaredAfter TARGET=clazz CALLBACK=after IN=hookReceiveLifecycle@112
L131 [包装调用] hookDeclaredAfter TARGET=clazz CALLBACK=after IN=hookReceiveLifecycle@112
L142 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=hookReceiveLifecycle@112
L156 [包装调用] hookDeclaredAfter TARGET=clazz CALLBACK=after IN=hookReceiveLifecycle@112
L197 [包装调用] hookDeclaredAfter TARGET=clazz CALLBACK=after IN=hookDetailLifecycle@194
L207 [包装调用] hookDeclaredAfter TARGET=clazz CALLBACK=after IN=hookDetailLifecycle@194
L225 [转发] HookRegistry.get().hook TARGET=method CALLBACK=callback IN=hookDeclaredAfter@218
ORIGIN hookReceiveLifecycle L112 private void hookReceiveLifecycle(Class<?> clazz, String className)
  113: private void hookReceiveLifecycle(Class<?> clazz, String className) {
  114: if (clazz == null) return;
  115: if (hookDeclaredAfter(clazz, "initView", null,
  121: log("Hook领取页: " + className + ".initView");
  124: hookDeclaredAfter(clazz, "onCreate", new Class<?>[]{Bundle.class},
  131: hookDeclaredAfter(clazz, "onResume", null,
  139: for (Method method : KavaReflector.declaredMethods(clazz)) {
  140: if (!"onSceneEnd".equals(method.getName())) continue;
  141: if (method.getParameterTypes().length != 4) continue;
  142: HookRegistry.get().hook(method,
  148: log("Hook领取页: " + className + ".onSceneEnd");
  152: log("Hook领取页onSceneEnd失败: " + className + " | " + e.getMessage());
  156: hookDeclaredAfter(clazz, "onDestroy", null,
  CALLER L106 hookReceiveLifecycle(clazz, className);
ORIGIN hookDetailLifecycle L194 private void hookDetailLifecycle(Class<?> clazz, String className)
  195: private void hookDetailLifecycle(Class<?> clazz, String className) {
  196: if (clazz == null) return;
  197: if (hookDeclaredAfter(clazz, "onCreate", new Class<?>[]{Bundle.class},
  205: log("Hook详情页: " + className + ".onCreate");
  207: if (hookDeclaredAfter(clazz, "onResume", null,
  215: log("Hook详情页: " + className + ".onResume");
  CALLER L188 hookDetailLifecycle(clazz, className);
ORIGIN hookDeclaredAfter L218 private boolean hookDeclaredAfter(Class<?> clazz, String methodName, Class<?>[] parameterTypes, XC_MethodHook callback)
  219: private boolean hookDeclaredAfter(Class<?> clazz, String methodName, Class<?>[] parameterTypes, XC_MethodHook callback) {
  221: Method method = parameterTypes == null
  222: ? KavaReflector.findDeclaredMethod(clazz, methodName)
  223: : KavaReflector.findDeclaredMethod(clazz, methodName, parameterTypes);
  224: if (method == null) return false;
  225: HookRegistry.get().hook(method, callback);
  226: return true;
  228: log("Hook红包页面方法失败: " + clazz.getName() + "." + methodName + " | " + e.getMessage());
  229: return false;
  CALLER L115 if (hookDeclaredAfter(clazz, "initView", null, new XC_MethodHook() {
  CALLER L124 hookDeclaredAfter(clazz, "onCreate", new Class<?>[]{Bundle.class}, new XC_MethodHook() {
  CALLER L131 hookDeclaredAfter(clazz, "onResume", null, new XC_MethodHook() {
  CALLER L156 hookDeclaredAfter(clazz, "onDestroy", null, new XC_MethodHook() {
  CALLER L197 if (hookDeclaredAfter(clazz, "onCreate", new Class<?>[]{Bundle.class}, new XC_MethodHook() {
  CALLER L207 if (hookDeclaredAfter(clazz, "onResume", null, new XC_MethodHook() {

FILE hooks/items/payment/transfer/TransferReceiveAccountStore.kt
L52 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=install@34
ORIGIN install L34 fun install(context: FeatureContext, logger: (String, Throwable?) -> Unit): Boolean
  35: if (installed) return true
  36: val prefs = DexMethodCache.prefs(
  40: val cacheKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
  41: val method = DexMethodCache.load(
  47: ?.takeIf(::isAccountParser)
  48: ?: locateParser(context, logger)?.also {
  49: DexMethodCache.save(prefs, cacheKey, "receive_account_parser", it)
  51: ?: return false
  52: HookRegistry.get().hook(method,
  59: return true
  LOCAL_LOCATOR locateParser L198 fun locateParser( context: FeatureContext, logger: (String, Throwable?) -> Unit ): Method?

FILE hooks/items/profileid/ProfileIdFeature.kt
L106 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookPreferenceTreeClick@102
L139 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=hookAfter@128
ORIGIN hookPreferenceTreeClick L102 fun hookPreferenceTreeClick(clazz: Class<*>, kind: ProfileKind)
  102: fun hookPreferenceTreeClick(clazz: Class<*>, kind: ProfileKind) {
  103: methodsOf(clazz)
  104: .firstOrNull { it.name == "onPreferenceTreeClick" && it.parameterTypes.size >= 2 }
  105: ?.let { method ->
  106: HookRegistry.get().hook(method,
  117: ?: h.Hchat.utils.HLog.e("$TAG 未找到资料页 Preference 点击方法: ${clazz.name}")
  CALLER L82 hookPreferenceTreeClick(clazz, ProfileKind.CONTACT)
  CALLER L94 hookPreferenceTreeClick(clazz, ProfileKind.CHATROOM)
ORIGIN hookAfter L128 fun hookAfter( clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>, block: (XC_MethodHook.MethodHookParam) -> Unit )
  129: clazz: Class<*>,
  131: vararg parameterTypes: Class<*>,
  134: val method = KavaReflector.findMethodRecursive(clazz, methodName, *parameterTypes)
  135: if (method == null) {
  136: h.Hchat.utils.HLog.e("$TAG 未找到 ${clazz.name}#$methodName")
  137: return
  139: HookRegistry.get().hook(method,
  CALLER L79 hookAfter(clazz, "initView") { param ->
  CALLER L91 hookAfter(clazz, "initView") { param ->

FILE hooks/items/protobuf/ProtobufPacketHook.java
L67 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=install@54
L324 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookSceneEndMethod@319
ORIGIN install L54 public boolean install()
  56: if (installed) return true;
  58: List<Class<?>> bases = collectBaseClasses();
  61: for (Class<?> base : bases) {
  62: for (Method method : KavaReflector.declaredMethods(base)) {
  63: if (!"dispatch".equals(method.getName())) continue;
  64: if (method.getParameterTypes().length != 3) continue;
  65: String desc = base.getName() + "#" + method.toString();
  67: HookRegistry.get().hook(method,
  79: return installed;
  82: return false;
  LOCAL_LOCATOR collectBaseClasses L214 private List<Class<?>> collectBaseClasses()
ORIGIN hookSceneEndMethod L319 private int hookSceneEndMethod(Method method, Set<String> hookedDesc)
  320: private int hookSceneEndMethod(Method method, Set<String> hookedDesc) {
  321: if (!isSceneEndMethod(method)) return 0;
  322: String desc = method.getDeclaringClass().getName() + "#" + method.toString();
  323: if (!hookedDesc.add(desc)) return 0;
  324: HookRegistry.get().hook(method,
  333: return 1;
  LOCAL_LOCATOR isSceneEndMethod L367 private boolean isSceneEndMethod(Method method)
  CALLER L300 hooked += hookSceneEndMethod(method, hookedDesc);
  CALLER L310 hooked += hookSceneEndMethod(method, hookedDesc);

FILE hooks/items/purify/ChatPurifyFeature.kt
L121 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installDividerHook@99
L166 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=installSystemTipHook@160
ORIGIN installDividerHook L99 fun installDividerHook(): Boolean
  100: val recyclerClass = KavaReflector.loadClass(
  103: ) ?: return false
  108: val candidates = KavaReflector.declaredMethods(recyclerClass).filter { candidate ->
  109: candidate.returnType == Void.TYPE &&
  110: !Modifier.isStatic(candidate.modifiers) &&
  111: candidate.parameterTypes.size == 1 &&
  112: !candidate.parameterTypes[0].isPrimitive
  114: if (candidates.isEmpty()) {
  116: return false
  118: var hooked = 0
  119: for (method in candidates) {
  121: HookRegistry.get().hook(method,
  148: }.onFailure { logger("界面净化：分割线候选 Hook 失败 ${method.name}", it) }
  151: return hooked > 0
ORIGIN installSystemTipHook L160 fun installSystemTipHook(): Boolean
  161: val method = ChatAdapterBindLocator.locate(context, logger) ?: run {
  163: return false
  165: return runCatching {
  166: HookRegistry.get().hook(method,
  188: logger("界面净化：系统提示 Hook 已挂载 ${method.declaringClass.simpleName}#${method.name}，开关 ${if (enabled(ChatPurifyFeature.KEY_HIDE_SYSTEM_TIP, ChatPurifyFeature.DEFAULT_HIDE_SYSTEM_TIP)) "开" else "关"}", null)

FILE hooks/items/quickcontactedit/QuickContactEditFeature.kt
L142 [包装调用] hook TARGET=create CALLBACK=after IN=installConversationMenuHook@140
L164 [包装调用] hook TARGET=method CALLBACK=after IN=installSnsAvatarMenuHook@161
L171 [包装调用] hook TARGET=method CALLBACK=before IN=installSnsAvatarMenuHook@161
L287 [包装调用] hook TARGET=method CALLBACK=after IN=installSnsAvatarHook@279
L388 [转发] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=callback IN=hook@384
L428 [包装调用] hook TARGET=method CALLBACK=before IN=installMenuClickHook@410
ORIGIN installConversationMenuHook L140 fun installConversationMenuHook(): Boolean
  141: val create = ConversationMenuLocator.menuCreateMethod(context, logger)
  142: val createInstalled = create != null && hook(create,
  148: return createInstalled
ORIGIN installSnsAvatarMenuHook L161 fun installSnsAvatarMenuHook(): Boolean
  162: val methods = SnsAvatarMenuLocator.locate(context, logger)
  163: val createInstalled = methods.createMethods.count { method ->
  164: hook(method,
  170: val clickInstalled = methods.clickMethods.count { method ->
  171: hook(method,
  179: return createInstalled > 0 && clickInstalled > 0
ORIGIN installSnsAvatarHook L279 fun installSnsAvatarHook(): Boolean
  280: val headerClass = KavaReflector.loadClass(SNS_HEADER_CLASS, context.hostClassLoader())
  281: ?: return false
  282: val method = KavaReflector.findMethodRecursive(
  286: ) ?: return false
  287: return hook(method,
ORIGIN hook L384 fun hook(method: Method, callback: XC_MethodHook): Boolean
  384: fun hook(method: Method, callback: XC_MethodHook): Boolean {
  385: if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
  386: if (!hookedMethods.add(method)) return true
  387: return runCatching {
  388: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
  391: hookedMethods.remove(method)
  392: logger("安装快捷设置 Hook 失败: ${method.toGenericString()}", it)
  CALLER L142 val createInstalled = create != null && hook(create, object : XC_MethodHook() {
  CALLER L164 hook(method, object : XC_MethodHook() {
  CALLER L171 hook(method, object : XC_MethodHook() {
  CALLER L287 return hook(method, object : XC_MethodHook() {
  CALLER L428 return hook(method, object : XC_MethodHook() {
ORIGIN installMenuClickHook L410 fun installMenuClickHook(listener: Any?): Boolean
  411: listener ?: return false
  412: val method = KavaReflector.declaredFields(listener.javaClass)
  414: .filterNot { Modifier.isStatic(it.modifiers) }
  417: KavaReflector.findMethod(
  424: .firstOrNull { candidate ->
  425: candidate.returnType == Void.TYPE &&
  426: candidate.declaringClass.name.startsWith("com.tencent.mm.ui.conversation.")
  427: } ?: return false
  428: return hook(method,

FILE hooks/items/quickmoments/QuickMomentsFeature.kt
L144 [包装调用] hook TARGET=create CALLBACK=after IN=install@142
L189 [包装调用] hook TARGET=method CALLBACK=before IN=installMenuClickHook@171
L259 [转发] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=callback IN=hook@255
ORIGIN install L142 fun install(): Boolean
  143: val create = ConversationMenuLocator.menuCreateMethod(context, logger) ?: return false
  144: return hook(create,
ORIGIN installMenuClickHook L171 fun installMenuClickHook(listener: Any?): Boolean
  172: listener ?: return false
  173: val method = KavaReflector.declaredFields(listener.javaClass)
  175: .filterNot { Modifier.isStatic(it.modifiers) }
  178: KavaReflector.findMethod(
  185: .firstOrNull { candidate ->
  186: candidate.returnType == Void.TYPE &&
  187: candidate.declaringClass.name.startsWith("com.tencent.mm.ui.conversation.")
  188: } ?: return false
  189: return hook(method,
ORIGIN hook L255 fun hook(method: Method, callback: XC_MethodHook): Boolean
  255: fun hook(method: Method, callback: XC_MethodHook): Boolean {
  256: if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
  257: if (!hookedMethods.add(method)) return true
  258: return runCatching {
  259: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
  262: hookedMethods.remove(method)
  263: logger("安装快捷查看朋友圈菜单 Hook 失败: ${method.toGenericString()}", it)
  CALLER L144 return hook(create, object : XC_MethodHook() {
  CALLER L189 return hook(method, object : XC_MethodHook() {

FILE hooks/items/quickread/QuickMarkReadFeature.kt
L100 [转发] HookRegistry.get().hook TARGET=method CALLBACK=callback IN=install@83
ORIGIN install L83 fun install(): Boolean
  84: if (installed) return true
  85: val methods = collectDispatchMethods()
  86: if (methods.isEmpty()) return false
  87: val callback =
  96: var hookedCount = 0
  97: var lastError: Throwable? = null
  98: for (method in methods) {
  100: HookRegistry.get().hook(method, callback)
  110: return installed
  LOCAL_LOCATOR collectDispatchMethods L113 fun collectDispatchMethods(): Set<Method>

FILE hooks/items/quoteclear/QuoteDeleteClearFeature.kt
L90 [终端] HookRegistry.get().hook TARGET=onAttached CALLBACK=after IN=installFooterLifecycleHook@84
L99 [终端] HookRegistry.get().hook TARGET=onDetached CALLBACK=after IN=installFooterLifecycleHook@84
L123 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=installInputConnectionHook@115
L149 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installOnKeyHook@142
ORIGIN installFooterLifecycleHook L84 fun installFooterLifecycleHook(): Boolean
  85: if (footerLifecycleHookInstalled) return true
  86: val footerClass = KavaReflector.loadClass(CHAT_FOOTER, context.hostClassLoader()) ?: return false
  87: val onAttached = KavaReflector.findMethodRecursive(footerClass, "onAttachedToWindow") ?: return false
  88: val onDetached = KavaReflector.findMethodRecursive(footerClass, "onDetachedFromWindow")
  89: return runCatching {
ORIGIN installInputConnectionHook L115 fun installInputConnectionHook(): Boolean
  116: if (inputConnectionHookInstalled) return true
  117: val method = KavaReflector.findMethodRecursive(
  121: ) ?: return false
  122: return runCatching {
  123: HookRegistry.get().hook(method,
ORIGIN installOnKeyHook L142 fun installOnKeyHook(): Boolean
  143: if (onKeyHookInstalled) return true
  144: val method = locateSupportAutoCompleteOnKeyMethod() ?: run {
  146: return false
  148: return runCatching {
  149: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateSupportAutoCompleteOnKeyMethod L310 fun locateSupportAutoCompleteOnKeyMethod(): Method?

FILE hooks/items/realtail/BeforeTransferNameQuery.kt
L124 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=setupCallbackHook@122
ORIGIN setupCallbackHook L122 fun setupCallbackHook()
  123: val method = callbackMethod ?: return
  124: HookRegistry.get().hook(method,

FILE hooks/items/realtail/RealNameTailRenderer.kt
L46 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=installUsernameBindHook@42
ORIGIN installUsernameBindHook L42 fun installUsernameBindHook(): Boolean
  43: if (installed) return true
  44: val method = locateUsernameBindMethod() ?: return false
  45: return try {
  46: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateUsernameBindMethod L382 fun locateUsernameBindMethod(): Method?

FILE hooks/items/roundavatar/RoundAvatarFeature.kt
L117 [终端] HookRegistry.get().hook TARGET=member CALLBACK=before IN=hookRadius@114
L144 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookNotificationBitmap@136
L168 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookNotificationIcon@160
L189 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookNotificationPost@186
ORIGIN hookRadius L114 fun hookRadius(member: Member, radiusIndex: Int, maskIndex: Int): Boolean
  114: fun hookRadius(member: Member, radiusIndex: Int, maskIndex: Int): Boolean {
  115: if (hookedMembers.contains(member)) return true
  116: return runCatching {
  CALLER L101 val loadHooked = hookRadius(members.legacyLoad, radiusIndex = 2, maskIndex = -1)
  CALLER L102 val constructorHooked = hookRadius(
  CALLER L108 hookRadius(method, radiusIndex = 3, maskIndex = lastIntParameterIndex(method.parameterTypes))
ORIGIN hookNotificationBitmap L136 fun hookNotificationBitmap()
  137: val method = KavaReflector.findMethod(
  141: ) ?: return
  142: if (!notificationHookedMembers.add(method)) return
  144: HookRegistry.get().hook(method,
  155: notificationHookedMembers.remove(method)
ORIGIN hookNotificationIcon L160 fun hookNotificationIcon()
  161: val method = KavaReflector.findMethod(
  165: ) ?: return
  166: if (!notificationHookedMembers.add(method)) return
  168: HookRegistry.get().hook(method,
  181: notificationHookedMembers.remove(method)
ORIGIN hookNotificationPost L186 fun hookNotificationPost(method: Method?)
  186: fun hookNotificationPost(method: Method?) {
  187: if (method == null || !notificationHookedMembers.add(method)) return
  189: HookRegistry.get().hook(method,
  201: notificationHookedMembers.remove(method)
  CALLER L75 hookNotificationPost(
  CALLER L83 hookNotificationPost(

FILE hooks/items/screenshot/ScreenshotForwardButtonFeature.kt
L146 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installProviderHook@96
L185 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installOverlayHook@166
ORIGIN installProviderHook L96 fun installProviderHook(context: FeatureContext, enabled: () -> Boolean): Boolean
  97: val loader = context.hostClassLoader()
  98: val host = context.hostContext()
  101: val prefs = DexMethodCache.prefs(host, CACHE_PREFS)
  102: val runtimeKey = DexMethodCache.runtimeKey(host, loader)
  103: val cached = DexMethodCache.loadList(prefs, runtimeKey, loader, CACHE_PROVIDER)
  104: .filter(::isForwardProviderMethod)
  105: val methods = if (cached.isNotEmpty()) {
  114: val located = runCatching {
  115: var found = emptyList<Method>()
  116: for (anchor in FORWARD_ACTION_ANCHORS) {
  117: found = context.dexKitBridge().findMethod(
  118: FindMethod().apply {
  128: .filter(::isForwardProviderMethod)
  129: if (found.isNotEmpty()) break
  134: return false
  136: if (located.isNotEmpty()) {
  137: DexMethodCache.saveList(prefs, runtimeKey, CACHE_PROVIDER, located)
  139: located
  141: if (methods.isEmpty()) {
  143: return false
  145: methods.forEach { method ->
  146: HookRegistry.get().hook(method,
  157: return true
ORIGIN installOverlayHook L166 fun installOverlayHook(context: FeatureContext, enabled: () -> Boolean): Boolean
  167: val loader = context.hostClassLoader()
  170: val clazz = OVERLAY_CANDIDATE_CLASSES.asSequence()
  171: .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
  172: .firstOrNull { candidate ->
  173: KavaReflector.declaredMethods(candidate).any(::isActionBindMethod)
  175: if (clazz == null) {
  177: return false
  179: val targets = KavaReflector.declaredMethods(clazz).filter(::isActionBindMethod)
  180: if (targets.isEmpty()) {
  182: return false
  184: targets.forEach { method ->
  185: HookRegistry.get().hook(method,
  208: return true

FILE hooks/items/script/agent/ModuleResourceMenuFallback.kt
L35 [终端] XposedHelpers.findAndHookMethod TARGET=MENU_BUILDER | classLoader | "dispatchMenuItemSelected" | menuBuilderClass | android.view.MenuItem::class.java | object : XC_MethodHook() { override fun beforeHookedMethod(param: MethodHookParam) { val item = param.args?.getOrNull(1) as? android.view.MenuItem ?: return val menu = param.args?.getOrNull(0) ?: param.thisObject if (!isSelectionMenu(menu) && !isSelectionMenu(param.thisObject)) return val terminal = TerminalBridge.current() ?: return when (item.itemId) { 1 -> { val text = terminal.selectedText() if (text.isNullOrEmpty()) return terminal.copyToClipboard(text) terminal.stopSelection() } 2 -> { terminal.stopSelection() terminal.pasteFromClipboard() } 3 -> { terminal.stopSelection() terminal.showMoreMenu() } else -> return } closeActionMode(menu) param.setResult(true) } } CALLBACK=before IN=hookClickDispatch@32
L92 [终端] XposedHelpers.findAndHookMethod TARGET=MENU_BUILDER | classLoader | method | INT | INT | INT | INT | object : XC_MethodHook() { override fun beforeHookedMethod(param: MethodHookParam) { val args = param.args ?: return if (args.size < 4) return val resId = args[3] as? Int ?: return if (resId == 0) return val context = contextOf(param.thisObject) ?: return val entry = ModuleResources.entryName(context, resId) val hostRes = resourcesOf(param.thisObject) val hostIsString = hostRes != null && runCatching { hostRes.getResourceTypeName(resId) }.getOrNull() == "string" val termuxMenu = isTermuxSelectionMenu(param.thisObject) val label: CharSequence? = when { entry != null && LABELS.containsKey(entry) && (!hostIsString || termuxMenu) -> LABELS[entry] entry != null && !hostIsString && termuxMenu -> ModuleResources.text(context, resId) entry == null && !hostIsString && termuxMenu -> LABELS_BY_ITEM[args[1] as? Int ?: -1] else -> null } if (label == null) return if (termuxMenu) markSelectionMenu(param.thisObject) val item = runCatching { XposedHelpers.callMethod( param.thisObject, method, arrayOf(INT, INT, INT, CharSequence::class.java), args[0], args[1], args[2], label, ) }.getOrNull() ?: return param.setResult(item) } } CALLBACK=before IN=hookTitleResourceOverload@90
ORIGIN hookClickDispatch L32 fun hookClickDispatch(classLoader: ClassLoader)
  34: val menuBuilderClass = Class.forName(MENU_BUILDER, false, classLoader)
  35: XposedHelpers.findAndHookMethod(
ORIGIN hookTitleResourceOverload L90 fun hookTitleResourceOverload(classLoader: ClassLoader, method: String)
  90: fun hookTitleResourceOverload(classLoader: ClassLoader, method: String) {
  92: XposedHelpers.findAndHookMethod(
  95: method,
  142: HLog.e("菜单资源兜底安装失败: $method", t)
  CALLER L27 hookTitleResourceOverload(classLoader, "add")
  CALLER L28 hookTitleResourceOverload(classLoader, "addSubMenu")

FILE hooks/items/script/ScriptMenuDispatcher.kt
L241 [包装调用] hookOnce TARGET=method CALLBACK=after IN=installPlusMenuHooks@238
L250 [包装调用] hookOnce TARGET=method CALLBACK=before IN=installPlusMenuHooks@238
L257 [包装调用] hookOnce TARGET=method CALLBACK=after IN=installPlusMenuHooks@238
L264 [包装调用] hookOnce TARGET=method CALLBACK=before IN=installPlusMenuHooks@238
L280 [包装调用] hookOnce TARGET=method CALLBACK=after IN=installMessageMenuHooks@273
L287 [包装调用] hookOnce TARGET=method CALLBACK=before IN=installMessageMenuHooks@273
L300 [转发] HookRegistry.get().hook TARGET=method CALLBACK=callback IN=hookOnce@296
L490 [包装调用] hookOnce TARGET=method CALLBACK=after IN=ensurePlusMenuAdapterViewHook@486
ORIGIN installPlusMenuHooks L238 fun installPlusMenuHooks(finder: SettingsDexFinder): Boolean
  238: fun installPlusMenuHooks(finder: SettingsDexFinder): Boolean {
  239: val helperClass = finder.plusSubMenuHelperClass ?: return false
  240: val adapterInstalled = finder.plusSubMenuAdapterMethod?.let { method ->
  241: hookOnce(method,
  249: val clickInstalled = finder.plusSubMenuOnItemClickMethod?.let { method ->
  250: hookOnce(method,
  256: findPlusMenuPopulateMethods(helperClass).forEach { method ->
  257: hookOnce(method,
  263: findPlusMenuShowMethods(helperClass).forEach { method ->
  264: hookOnce(method,
  270: return adapterInstalled && clickInstalled
  LOCAL_LOCATOR findPlusMenuPopulateMethods L535 fun findPlusMenuPopulateMethods(clazz: Class<*>): Set<Method>
  LOCAL_LOCATOR findPlusMenuShowMethods L543 fun findPlusMenuShowMethods(clazz: Class<*>): Set<Method>
ORIGIN installMessageMenuHooks L273 fun installMessageMenuHooks( context: FeatureContext, log: (String, Throwable?) -> Unit ): Boolean
  277: var createHooked = false
  278: var clickHooked = false
  279: SingleMessageMenuLocator.menuCreateMethods(context, log).forEach { method ->
  280: createHooked = hookOnce(method,
  286: SingleMessageMenuLocator.menuClickMethods(context, log).forEach { method ->
  287: clickHooked = hookOnce(method,
  293: return createHooked && clickHooked
ORIGIN hookOnce L296 fun hookOnce(method: Method, callback: XC_MethodHook): Boolean
  296: fun hookOnce(method: Method, callback: XC_MethodHook): Boolean {
  297: if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
  298: if (!hookedMethods.add(method)) return true
  299: return runCatching {
  300: HookRegistry.get().hook(method, callback)
  303: hookedMethods.remove(method)
  304: logError("脚本菜单Hook安装失败: ${method.toGenericString()}", it)
  CALLER L241 hookOnce(method, object : XC_MethodHook() {
  CALLER L250 hookOnce(method, object : XC_MethodHook() {
  CALLER L257 hookOnce(method, object : XC_MethodHook() {
  CALLER L264 hookOnce(method, object : XC_MethodHook() {
  CALLER L280 createHooked = hookOnce(method, object : XC_MethodHook() {
  CALLER L287 clickHooked = hookOnce(method, object : XC_MethodHook() {
  CALLER L490 val hooked = hookOnce(method, object : XC_MethodHook() {
ORIGIN ensurePlusMenuAdapterViewHook L486 fun ensurePlusMenuAdapterViewHook(adapter: BaseAdapter)
  487: val clazz = adapter.javaClass
  488: if (plusAdapterViewHookedClasses.contains(clazz)) return
  489: val method = findAdapterGetViewMethod(clazz) ?: return
  490: val hooked = hookOnce(method,
  507: if (hooked) plusAdapterViewHookedClasses.add(clazz)
  LOCAL_LOCATOR findAdapterGetViewMethod L510 fun findAdapterGetViewMethod(clazz: Class<*>): Method?

FILE hooks/items/script/ScriptPluginBridge.kt
L534 [终端] HookRegistry.get().hook TARGET=member CALLBACK=before IN=hookBefore@529
L553 [终端] HookRegistry.get().hook TARGET=member CALLBACK=after IN=hookAfter@548
L572 [终端] HookRegistry.get().hook TARGET=member CALLBACK=before IN=hookReplace@567
ORIGIN hookBefore L529 fun hookBefore( pluginId: String?, member: Member, callback: Consumer<XC_MethodHook.MethodHookParam> ): XC_MethodHook.Unhook
  531: member: Member,
  534: val unhook = HookRegistry.get().hook(member,
  540: return unhook
  CALLER L524 fun hookBefore(
  CALLER L527 ): XC_MethodHook.Unhook = hookBefore(null, member, callback)
ORIGIN hookAfter L548 fun hookAfter( pluginId: String?, member: Member, callback: Consumer<XC_MethodHook.MethodHookParam> ): XC_MethodHook.Unhook
  550: member: Member,
  553: val unhook = HookRegistry.get().hook(member,
  559: return unhook
  CALLER L543 fun hookAfter(
  CALLER L546 ): XC_MethodHook.Unhook = hookAfter(null, member, callback)
ORIGIN hookReplace L567 fun hookReplace( pluginId: String?, member: Member, callback: Function<XC_MethodHook.MethodHookParam, Any?> ): XC_MethodHook.Unhook
  569: member: Member,
  572: val unhook = HookRegistry.get().hook(member,
  578: return unhook
  CALLER L562 fun hookReplace(
  CALLER L565 ): XC_MethodHook.Unhook = hookReplace(null, member, callback)

FILE hooks/items/script/ScriptSendButtonHook.kt
L105 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=install@94
L201 [终端] HookRegistry.get().hook TARGET=attachMethod CALLBACK=after IN=installInputTrackingHook@186
L241 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=installLongPressTouchHook@229
ORIGIN install L94 fun install(context: FeatureContext): Boolean
  95: if (installed) return true
  97: val finder = context.dexFinder()
  98: finder.resolveScriptSendHookApi()
  99: val method = finder.chatFooterSendClickMethod
  100: if (method == null) {
  102: return false
  105: HookRegistry.get().hook(method,
  177: longPressTouchHookInstalled = installLongPressTouchHook(method.declaringClass)
  181: return false
  183: return installed
ORIGIN installInputTrackingHook L186 fun installInputTrackingHook(classLoader: ClassLoader): Boolean
  187: if (inputTrackingHookInstalled) return true
  188: val footerClass = KavaReflector.loadClass(CHAT_FOOTER, classLoader) ?: run {
  190: return false
  192: val attachMethod = KavaReflector.declaredMethods(footerClass).singleOrNull { candidate ->
  193: candidate.name == "onAttachedToWindow" &&
  194: candidate.returnType == Void.TYPE &&
  195: candidate.parameterTypes.isEmpty()
  198: return false
  200: return runCatching {
ORIGIN installLongPressTouchHook L229 fun installLongPressTouchHook(sendClickListenerClass: Class<*>): Boolean
  229: fun installLongPressTouchHook(sendClickListenerClass: Class<*>): Boolean {
  230: if (longPressTouchHookInstalled) return true
  231: val method = KavaReflector.declaredMethods(View::class.java).firstOrNull { candidate ->
  232: candidate.name == "dispatchTouchEvent" &&
  233: candidate.returnType == Boolean::class.javaPrimitiveType &&
  234: candidate.parameterTypes.contentEquals(arrayOf(MotionEvent::class.java))
  236: if (method == null) {
  238: return false
  240: return runCatching {
  241: HookRegistry.get().hook(method,
  267: "$TAG 长按发送按钮Hook失败: ${method.toGenericString()} ${it.message}",

FILE hooks/items/selectedmessages/OfficialMassSendSender.kt
L48 [终端] HookRegistry.get().hook TARGET=resolved.sceneEndMethod CALLBACK=after IN=install@43
ORIGIN install L43 fun install(): Boolean
  44: val resolved = locateRuntime() ?: return false
  45: runtime = resolved
  46: if (callbackHookInstalled) return true
  47: return runCatching {
  48: HookRegistry.get().hook(resolved.sceneEndMethod,
  56: logger("微信原生群发助手完成回调Hook失败: ${resolved.sceneEndMethod.toGenericString()}", it)
  LOCAL_LOCATOR locateRuntime L510 fun locateRuntime(): MassSendRuntime?

FILE hooks/items/selectedmessages/SelectedMessagesFeature.kt
L99 [包装调用] hook TARGET=menuCreate CALLBACK=after IN=install@95
L104 [包装调用] hook TARGET=menuClick CALLBACK=before IN=install@95
L111 [包装调用] hook TARGET=method CALLBACK=before IN=install@95
L591 [转发] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=callback IN=hook@588
ORIGIN install L95 fun install(): Boolean
  96: val menuCreate = MultiSelectMessageMenuLocator.menuCreateMethod(context, logger)
  97: val menuClick = MultiSelectMessageMenuLocator.menuClickMethod(context, logger)
  98: val exitMethod = menuClick?.let { MultiSelectMessageMenuLocator.multiSelectExitMethod(context, it, logger) }
  99: val createHooked = menuCreate != null && exitMethod != null && hook(menuCreate,
  104: val clickHooked = menuClick != null && exitMethod != null && hook(menuClick,
  109: val doneMethods = locateRetransmitDoneMethods()
  110: val doneHooked = doneMethods.isNotEmpty() && doneMethods.all { method ->
  111: hook(method,
  118: val officialHooked = officialSender.install()
  123: return createHooked && clickHooked && doneHooked && officialHooked
  LOCAL_LOCATOR locateRetransmitDoneMethods L555 fun locateRetransmitDoneMethods(): List<Method>
ORIGIN hook L588 fun hook(method: Method, callback: XC_MethodHook): Boolean
  588: fun hook(method: Method, callback: XC_MethodHook): Boolean {
  589: if (!hookedMethods.add(method)) return true
  590: return runCatching {
  591: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
  594: hookedMethods.remove(method)
  595: logger("群发助手Hook安装失败: ${method.toGenericString()}", it)
  CALLER L99 val createHooked = menuCreate != null && exitMethod != null && hook(menuCreate, object : XC_MethodHook() {
  CALLER L104 val clickHooked = menuClick != null && exitMethod != null && hook(menuClick, object : XC_MethodHook() {
  CALLER L111 hook(method, object : XC_MethodHook() {

FILE hooks/items/shortvideo/FinderFeedDetailResolver.kt
L237 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=installCallbackHook@234
ORIGIN installCallbackHook L234 fun installCallbackHook(method: Method): Boolean
  234: fun installCallbackHook(method: Method): Boolean {
  235: if (!hookedCallbacks.add(method)) return true
  236: return runCatching {
  237: HookRegistry.get().hook(method,
  267: hookedCallbacks.remove(method)
  CALLER L132 if (!installCallbackHook(resolvedCallback)) return null

FILE hooks/items/shortvideo/FinderMediaDownloadFeature.kt
L95 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookCreateMenu@92
L111 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookClick@108
ORIGIN hookCreateMenu L92 fun hookCreateMenu(method: Method): Boolean
  92: fun hookCreateMenu(method: Method): Boolean {
  93: if (!hookedMethods.add(method)) return true
  94: return runCatching {
  95: HookRegistry.get().hook(method,
  102: hookedMethods.remove(method)
  CALLER L84 locateCreateMenuMethods().forEach { if (hookCreateMenu(it)) createHooked++ }
ORIGIN hookClick L108 fun hookClick(method: Method): Boolean
  108: fun hookClick(method: Method): Boolean {
  109: if (!hookedMethods.add(method)) return true
  110: return runCatching {
  111: HookRegistry.get().hook(method,
  118: hookedMethods.remove(method)
  CALLER L85 locateClickMethods().forEach { if (hookClick(it)) clickHooked++ }

FILE hooks/items/statuslimit/StatusTextLimitFeature.kt
L60 [终端] HookRegistry.get().hook TARGET=constructor CALLBACK=after IN=install@43
ORIGIN install L43 fun install(context: Context?, classLoader: ClassLoader?)
  44: if (context == null || classLoader == null || installed) return
  46: if (installed) return
  47: val activityClass = KavaReflector.loadClass(STATUS_ACTIVITY, classLoader)
  51: return
  53: val constructors = KavaReflector.declaredConstructors(activityClass)
  54: if (constructors.isEmpty()) {
  57: return
  59: constructors.forEach { constructor ->

FILE hooks/items/subscribe/RemoveSubscribeAdsFeature.kt
L383 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookAdPullGate@378
L428 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookCardListGate@417
L524 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookAdFrameTaskGate@499
L577 [终端] HookRegistry.get().hook TARGET=masterSwitch CALLBACK=before IN=hookAdCardServiceGate@566
L638 [终端] HookRegistry.get().hook TARGET=dispatch CALLBACK=before IN=hookAdCardServiceGate@566
L694 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookFrameSubmitGate@683
L783 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookFirstHit@774
L827 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookEventGate@809
L888 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookNativeAdEvents@875
ORIGIN hookAdPullGate L378 fun hookAdPullGate(loader: ClassLoader, enabled: () -> Boolean): Int
  379: val methods = locateJniMethods(loader)
  380: var installed = 0
  381: for (method in methods) {
  382: val ok = runCatching {
  383: HookRegistry.get().hook(method,
  397: HLog.e("$TAG hook ${method.name} 失败: ${it.message}", it)
  401: if (installed == 0 && methods.isEmpty()) {
  404: return installed
  LOCAL_LOCATOR locateJniMethods L911 fun locateJniMethods(loader: ClassLoader): List<Method>
ORIGIN hookCardListGate L417 fun hookCardListGate(loader: ClassLoader, enabled: () -> Boolean): Boolean
  418: val method = exactMethod(CARD_LIST_CLASS, loader, CARD_LIST_METHOD, Integer.TYPE)
  421: return false
  423: if (method.returnType != java.util.List::class.java) {
  425: return false
  427: return runCatching {
  428: HookRegistry.get().hook(method,
ORIGIN hookAdFrameTaskGate L499 fun hookAdFrameTaskGate(loader: ClassLoader, enabled: () -> Boolean): Boolean
  500: val method = exactMethod(resolvedAdFrameTaskClass, loader, "run") ?: run {
  501: HLog.event("$TAG 广告卡帧任务 ${resolvedAdFrameTaskClass}#run 未命中（微信 ${wechatVersion()}）")
  502: return false
  504: if (method.parameterCount != 0 || method.returnType != java.lang.Void.TYPE) {
  505: HLog.event("$TAG 广告卡帧任务 ${resolvedAdFrameTaskClass}#run 签名不匹配")
  506: return false
  509: val serviceField = runCatching {
  510: val serviceType = Class.forName(resolvedAdBaseClass, false, loader)
  511: Class.forName(resolvedAdFrameTaskClass, false, loader).declaredFields
  512: .firstOrNull { it.type == serviceType }?.also { it.isAccessible = true }
  514: val adServiceType = runCatching {
  515: Class.forName(resolvedAdServiceClass, false, loader)
  520: return false
  522: val frameLogged = AtomicBoolean(false)
  523: return runCatching {
  524: HookRegistry.get().hook(method,
  540: HLog.e("$TAG hook ${resolvedAdFrameTaskClass}#run 失败: ${it.message}", it)
ORIGIN hookAdCardServiceGate L566 fun hookAdCardServiceGate(loader: ClassLoader, enabled: () -> Boolean): Int
  567: var installed = 0
  568: val service = resolvedAdServiceClass
  572: val masterSwitch = exactMethod(resolvedAdBaseClass, loader, "Oj")
  574: masterSwitch.returnType == java.lang.Boolean.TYPE
  576: val ok = runCatching {
  589: HLog.e("$TAG hook ${resolvedAdBaseClass}#Oj 失败: ${it.message}", it)
  593: HLog.boot("$TAG 广告卡服务总开关 ${resolvedAdBaseClass}#Oj 未命中（微信 ${wechatVersion()}）")
  601: val toggle = exactMethod(service, loader, "Qj")
  602: if (toggle != null && toggle.parameterCount == 0 && toggle.returnType == java.lang.Boolean.TYPE) {
  606: val notify = exactMethod(
  609: if (notify != null && notify.returnType == java.lang.Void.TYPE) {
  614: val adInfo = exactMethod(service, loader, "Xj", Integer.TYPE, String::class.java)
  615: if (adInfo != null && adInfo.returnType == java.lang.Void.TYPE) {
  619: val oftenRead = exactMethod(service, loader, "Zj", String::class.java, String::class.java)
  620: if (oftenRead != null && oftenRead.returnType == java.lang.Void.TYPE) {
  627: val dispatch = runCatching {
  628: val cls = Class.forName(resolvedAdBaseClass, false, loader)
  629: cls.declaredMethods.firstOrNull { method ->
  630: method.name == "Ej" && Modifier.isStatic(method.modifiers) &&
  631: method.parameterTypes.size == 4 && method.returnType == java.lang.Void.TYPE
  632: }?.also { it.isAccessible = true }
  634: val adServiceClass = runCatching { Class.forName(service, false, loader) }.getOrNull()
  636: val ejLogged = AtomicBoolean(false)
  637: val ok = runCatching {
  651: HLog.e("$TAG hook ${resolvedAdBaseClass}#Ej 失败: ${it.message}", it)
  655: HLog.boot("$TAG 广告帧转发点 ${resolvedAdBaseClass}#Ej 未命中（微信 ${wechatVersion()}）")
  660: for (className in resolvedAdInstanceClasses) {
  661: val send = exactMethod(className, loader, "P9", String::class.java, String::class.java)
  662: if (send != null && send.returnType == java.lang.Void.TYPE) {
  663: if (hookFirstHit(send, enabled, "$className#P9", "丢弃广告事件 P9")) installed++
  670: return installed
ORIGIN hookFrameSubmitGate L683 fun hookFrameSubmitGate(loader: ClassLoader, enabled: () -> Boolean): Boolean
  684: val method = runCatching {
  685: val cls = Class.forName(JNI_CLASS, false, loader)
  687: .also { it.isAccessible = true }
  690: return false
  692: if (method.returnType != java.lang.Void.TYPE) return false
  693: return runCatching {
  694: HookRegistry.get().hook(method,
ORIGIN hookFirstHit L774 fun hookFirstHit( method: Method, enabled: () -> Boolean, label: String, logText: String, result: Any? = null ): Boolean
  775: method: Method,
  781: val logged = AtomicBoolean(false)
  782: return runCatching {
  783: HookRegistry.get().hook(method,
  CALLER L485 return hookFirstHit(
  CALLER L604 if (hookFirstHit(toggle, enabled, "$service#Qj", "关闭广告卡片开关", false)) installed++
  CALLER L610 if (hookFirstHit(notify, enabled, "$service#Rj", "拦下广告帧 Rj")) installed++
  CALLER L616 if (hookFirstHit(adInfo, enabled, "$service#Xj", "拦下广告信息推送")) installed++
  CALLER L621 if (hookFirstHit(oftenRead, enabled, "$service#Zj", "拦下常读广告事件")) installed++
  CALLER L663 if (hookFirstHit(send, enabled, "$className#P9", "丢弃广告事件 P9")) installed++
ORIGIN hookEventGate L809 fun hookEventGate(loader: ClassLoader, enabled: () -> Boolean): String?
  810: for (className in EVENT_GATE_CLASSES) {
  811: val method = lookupSendEvent(className, loader)
  812: if (method == null) {
  813: HLog.event("$TAG 下发出口 $className#H1 不存在（微信 ${wechatVersion()}）")
  816: if (method.returnType != java.lang.Void.TYPE) {
  817: HLog.event("$TAG 下发出口 $className#H1 签名不匹配")
  825: val dropAll = className in AD_CARD_EVENT_CANDIDATES
  826: val ok = runCatching {
  827: HookRegistry.get().hook(method,
  843: HLog.e("$TAG hook $className#H1 失败: ${it.message}", it)
  845: if (ok) return className
  847: return null
ORIGIN hookNativeAdEvents L875 fun hookNativeAdEvents(loader: ClassLoader, enabled: () -> Boolean): Int
  876: var installed = 0
  877: for (className in NATIVE_EVENT_CLASSES) {
  878: for (name in NATIVE_AD_EVENTS) {
  879: val method = exactMethod(
  880: className,
  886: if (method.returnType != java.lang.Void.TYPE) continue
  887: val ok = runCatching {
  888: HookRegistry.get().hook(method,
  902: HLog.e("$TAG hook $className#$name 失败: ${it.message}", it)
  907: return installed

FILE hooks/items/swipequote/SwipeQuoteFeature.kt
L147 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after,before IN=hookRepeatMenuMethod@143
L337 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=installAdapterBindHook@330
L374 [终端] HookRegistry.get().hook TARGET=onAttached CALLBACK=after IN=installFooterLifecycleHook@368
L383 [终端] HookRegistry.get().hook TARGET=onDetached CALLBACK=after IN=installFooterLifecycleHook@368
L406 [终端] HookRegistry.get().hook TARGET=accessible CALLBACK=before IN=installRetransmitDoneHook@399
L475 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookRecyclerTouchMethod@468
ORIGIN hookRepeatMenuMethod L143 fun hookRepeatMenuMethod(method: Method, menuCreate: Boolean): Boolean
  143: fun hookRepeatMenuMethod(method: Method, menuCreate: Boolean): Boolean {
  144: if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
  145: if (!repeatMenuHookedMethods.add(method)) return true
  146: return runCatching {
  147: HookRegistry.get().hook(method,
  158: repeatMenuHookedMethods.remove(method)
  159: logger("长按复读菜单Hook安装失败: ${method.toGenericString()}", it)
  CALLER L132 if (hookRepeatMenuMethod(method, menuCreate = true)) menuHooked++
  CALLER L135 if (hookRepeatMenuMethod(method, menuCreate = false)) clickHooked++
ORIGIN installAdapterBindHook L330 fun installAdapterBindHook(): Boolean
  331: if (adapterBindInstalled) return true
  332: val method = locateAdapterBindMethod() ?: run {
  334: return false
  336: return runCatching {
  337: HookRegistry.get().hook(method,
  LOCAL_LOCATOR locateAdapterBindMethod L978 fun locateAdapterBindMethod(): Method?
ORIGIN installFooterLifecycleHook L368 fun installFooterLifecycleHook(): Boolean
  369: if (footerLifecycleInstalled) return true
  370: val footerClass = KavaReflector.loadClass(CHAT_FOOTER, context.hostClassLoader()) ?: return false
  371: val onAttached = KavaReflector.findMethodRecursive(footerClass, "onAttachedToWindow") ?: return false
  372: val onDetached = KavaReflector.findMethodRecursive(footerClass, "onDetachedFromWindow")
  373: return runCatching {
ORIGIN installRetransmitDoneHook L399 fun installRetransmitDoneHook(): Boolean
  400: if (retransmitDoneHookInstalled) return true
  401: val methods = locateRetransmitDoneMethods()
  402: if (methods.isEmpty()) return false
  403: for (method in methods) {
  404: val accessible = KavaReflector.accessible(method) ?: continue
  427: return false
  431: return true
  LOCAL_LOCATOR locateRetransmitDoneMethods L434 fun locateRetransmitDoneMethods(): List<Method>
ORIGIN hookRecyclerTouchMethod L468 fun hookRecyclerTouchMethod(recyclerViewClass: Class<*>, methodName: String): Boolean
  468: fun hookRecyclerTouchMethod(recyclerViewClass: Class<*>, methodName: String): Boolean {
  469: val method = KavaReflector.findMethodRecursive(
  473: )?.takeIf { it.declaringClass.name == recyclerViewClass.name } ?: return false
  474: return runCatching {
  475: HookRegistry.get().hook(method,

FILE hooks/items/tablet/WeChatTabletFeature.kt
L112 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=hookPadCheck@108
L127 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hookLoginButton@123
ORIGIN hookPadCheck L108 fun hookPadCheck(method: Method, fallbackClassLoader: ClassLoader)
  108: fun hookPadCheck(method: Method, fallbackClassLoader: ClassLoader) {
  109: val key = hookKey(method, fallbackClassLoader)
  111: if (padHookedLoaders.contains(key)) return
  112: HookRegistry.get().hook(method,
  CALLER L67 if (padMethod != null) hookPadCheck(padMethod, classLoader)
  CALLER L95 hookPadCheck(padMethod, classLoader)
ORIGIN hookLoginButton L123 fun hookLoginButton(method: Method, fallbackClassLoader: ClassLoader)
  123: fun hookLoginButton(method: Method, fallbackClassLoader: ClassLoader) {
  124: val key = hookKey(method, fallbackClassLoader)
  126: if (loginButtonHookedLoaders.contains(key)) return
  127: HookRegistry.get().hook(method,
  CALLER L79 hookLoginButton(loginButtonMethod, classLoader)
  CALLER L101 hookLoginButton(it, classLoader)

FILE hooks/items/textspeech/TextSpeechFeature.kt
L54 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=installVolumeKeyHook@43
ORIGIN installVolumeKeyHook L43 fun installVolumeKeyHook()
  44: val method = KavaReflector.findDeclaredMethod(
  49: if (method == null) {
  51: return
  54: HookRegistry.get().hook(method,

FILE hooks/items/textvoice/TextVoiceRuntime.kt
L86 [包装调用] hook TARGET=method CALLBACK=after IN=installMessageMenu@84
L93 [包装调用] hook TARGET=method CALLBACK=before IN=installMessageMenu@84
L253 [转发] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=callback IN=hook@249
ORIGIN installMessageMenu L84 fun installMessageMenu(): Boolean
  85: val createHooked = SingleMessageMenuLocator.menuCreateMethods(context, logger).count { method ->
  86: hook(method,
  92: val clickHooked = SingleMessageMenuLocator.menuClickMethods(context, logger).count { method ->
  93: hook(method,
  101: return createHooked > 0 && clickHooked > 0
ORIGIN hook L249 fun hook(method: Method, callback: XC_MethodHook): Boolean
  249: fun hook(method: Method, callback: XC_MethodHook): Boolean {
  250: if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
  251: if (!hookedMethods.add(method)) return true
  252: return runCatching {
  253: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
  256: hookedMethods.remove(method)
  257: logger("文本转语音菜单 Hook 安装失败: ${method.toGenericString()}", it)
  CALLER L86 hook(method, object : XC_MethodHook() {
  CALLER L93 hook(method, object : XC_MethodHook() {

FILE hooks/items/theme/ActivityResultBridge.kt
L36 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=install@23
ORIGIN install L23 fun install(context: FeatureContext, logger: (String, Throwable?) -> Unit): Boolean
  24: if (installed) return true
  25: val method = KavaReflector.findMethodRecursive(
  33: return false
  35: return runCatching {
  36: HookRegistry.get().hook(method,

FILE hooks/items/theme/ChatImmersionFeature.kt
L94 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=install@79
ORIGIN install L79 fun install(): Boolean
  80: if (installed) return true
  81: val chattingUi = KavaReflector.loadClass(
  86: return false
  88: val method = KavaReflector.findMethodRecursive(chattingUi, "onResume")
  91: return false
  93: return runCatching {
  94: HookRegistry.get().hook(method,

FILE hooks/items/theme/GlobalBackgroundFeature.kt
L120 [终端] HookRegistry.get().hook TARGET=method CALLBACK=after IN=install@104
ORIGIN install L104 fun install(): Boolean
  106: if (installed) return true
  107: val launcher = KavaReflector.loadClass(
  112: return false
  114: val method = KavaReflector.findMethodRecursive(launcher, "onResume")
  117: return false
  119: return runCatching {
  120: HookRegistry.get().hook(method,

FILE hooks/items/theme/GlobalFontFeature.kt
L102 [包装调用] hook TARGET=method CALLBACK=trailing lambda / caller IN=install@83
L111 [包装调用] hook TARGET=method CALLBACK=trailing lambda / caller IN=install@83
L131 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=hook@129
ORIGIN install L83 fun install(): Boolean
  84: if (installed) return true
  85: val plain = KavaReflector.findMethodRecursive(
  90: val styled = KavaReflector.findMethodRecursive(
  98: return false
  100: var hooked = 0
  101: plain?.let { method ->
  102: if (hook(method) { param ->
  103: val original = param.args?.getOrNull(0) as? Typeface
  104: resolve(original, Typeface.NORMAL)?.let { param.args[0] = it }
  110: styled?.let { method ->
  111: if (hook(method) { param ->
  112: val original = param.args?.getOrNull(0) as? Typeface
  113: val style = (param.args?.getOrNull(1) as? Number)?.toInt() ?: Typeface.NORMAL
  114: resolve(original, style)?.let { param.args[0] = it }
  126: return installed
ORIGIN hook L129 fun hook(method: java.lang.reflect.Method, body: (XC_MethodHook.MethodHookParam) -> Unit): Boolean
  129: fun hook(method: java.lang.reflect.Method, body: (XC_MethodHook.MethodHookParam) -> Unit): Boolean {
  130: return runCatching {
  131: HookRegistry.get().hook(method,
  139: logger("全局字体 Hook 安装失败: ${method.toGenericString()}", it)
  CALLER L102 if (hook(method) { param ->
  CALLER L111 if (hook(method) { param ->

FILE hooks/items/typingreport/TypingReportBlockFeature.kt
L73 [终端] HookRegistry.get().hook TARGET=method CALLBACK=before IN=install@69
ORIGIN install L69 fun install(allowDexSearch: Boolean): Boolean
  69: fun install(allowDexSearch: Boolean): Boolean {
  70: if (installed) return true
  71: val method = locateSendTypingMethod(allowDexSearch) ?: return false
  72: return runCatching {
  73: HookRegistry.get().hook(method,
  83: logger("正在输入上报 Hook 安装失败: ${method.toGenericString()}", it)
  LOCAL_LOCATOR locateSendTypingMethod L88 fun locateSendTypingMethod(allowDexSearch: Boolean): Method?

FILE hooks/items/voiceforward/VoiceForwardFeature.kt
L186 [包装调用] hookMethod TARGET=method CALLBACK=after,before IN=hookChatMethod@185
L198 [包装调用] hookMethod TARGET=method CALLBACK=after IN=hookFavoriteMenuCreateMethod@197
L206 [包装调用] hookMethod TARGET=method CALLBACK=before IN=hookFavoriteClickMethod@205
L230 [包装调用] hookMethod TARGET=method CALLBACK=before IN=hookFavoriteSelectClick@213
L293 [包装调用] hookMethod TARGET=method CALLBACK=after,before IN=hookFavoriteSelectorCreate@283
L325 [包装调用] hookMethod TARGET=queryMethod CALLBACK=before IN=hookFavoriteSelectorAdapterQueries@308
L339 [包装调用] hookMethod TARGET=resetMethod CALLBACK=before IN=hookFavoriteSelectorAdapterQueries@308
L427 [包装调用] hookMethod TARGET=method CALLBACK=before IN=registerFavoriteSelectorFilter@407
L528 [包装调用] hookMethod TARGET=method CALLBACK=after IN=hookMultiNativeMenuCreateMethod@527
L536 [包装调用] hookMethod TARGET=method CALLBACK=before IN=hookMultiNativeMenuClickMethod@535
L546 [转发] HookRegistry.get().hook TARGET=method CALLBACK=callback IN=hookMethod@543
ORIGIN hookChatMethod L185 fun hookChatMethod(method: Method, menuCreate: Boolean): Boolean
  185: fun hookChatMethod(method: Method, menuCreate: Boolean): Boolean {
  186: return hookMethod(method,
  CALLER L144 if (hookChatMethod(menuMethod, true)) hooked++
  CALLER L145 if (hookChatMethod(method, false)) {
ORIGIN hookFavoriteMenuCreateMethod L197 fun hookFavoriteMenuCreateMethod(method: Method): Boolean
  197: fun hookFavoriteMenuCreateMethod(method: Method): Boolean {
  198: return hookMethod(method,
  CALLER L161 if (hookFavoriteMenuCreateMethod(method)) hooked++
ORIGIN hookFavoriteClickMethod L205 fun hookFavoriteClickMethod(method: Method): Boolean
  205: fun hookFavoriteClickMethod(method: Method): Boolean {
  206: return hookMethod(method,
  CALLER L164 if (hookFavoriteClickMethod(method)) hooked++
ORIGIN hookFavoriteSelectClick L213 fun hookFavoriteSelectClick(): Int
  214: var hooked = 0
  215: val selectClass = KavaReflector.loadClass(
  219: val selectMethod = selectClass?.let { clazz ->
  220: KavaReflector.findMethod(
  221: clazz, "onItemClick", AdapterView::class.java, View::class.java,
  225: val methods = buildList {
  227: addAll(locateFavoriteSearchItemClickMethods())
  228: }.distinctBy(Method::toGenericString)
  229: methods.forEach { method ->
  230: if (hookMethod(method,
  236: return hooked
  LOCAL_LOCATOR locateFavoriteSearchItemClickMethods L239 fun locateFavoriteSearchItemClickMethods(): List<Method>
ORIGIN hookFavoriteSelectorCreate L283 fun hookFavoriteSelectorCreate(): Int
  284: var hooked = 0
  289: ).forEach { className ->
  290: val clazz = KavaReflector.loadClass(className, context.hostClassLoader()) ?: return@forEach
  291: val method = KavaReflector.findMethod(clazz, "onCreate", Bundle::class.java)
  292: ?: return@forEach
  293: if (hookMethod(method,
  305: return hooked
ORIGIN hookFavoriteSelectorAdapterQueries L308 fun hookFavoriteSelectorAdapterQueries(): Int
  309: val clazz = KavaReflector.loadClass(
  314: return 0
  316: var hooked = 0
  317: KavaReflector.declaredMethods(clazz)
  318: .filter { method ->
  319: method.returnType == Void.TYPE &&
  320: method.parameterTypes.contentEquals(
  324: .forEach { queryMethod ->
  333: arrayOf("b", "c").forEach { name ->
  334: val resetMethod = KavaReflector.declaredMethods(clazz).firstOrNull { method ->
  335: method.name == name &&
  336: method.parameterTypes.isEmpty() &&
  337: method.returnType == Void.TYPE
  350: return hooked
ORIGIN registerFavoriteSelectorFilter L407 fun registerFavoriteSelectorFilter(adapter: Any)
  408: val filter = KavaReflector.findFieldRecursive(adapter.javaClass, "g")?.let {
  410: } ?: return
  411: if (!favoriteSelectorFilters.add(filter)) return
  412: val filterClass = filter.javaClass
  413: if (!favoriteFilterClasses.add(filterClass)) return
  414: var current: Class<*>? = filterClass
  416: KavaReflector.declaredMethods(current)
  417: .filter { method ->
  418: !Modifier.isStatic(method.modifiers) &&
  419: method.returnType == Boolean::class.javaPrimitiveType &&
  420: method.parameterTypes.isNotEmpty() &&
  421: !method.parameterTypes[0].isPrimitive &&
  422: method.parameterTypes.drop(1).all { type ->
  426: .forEach { method ->
  427: hookMethod(method,
ORIGIN hookMultiNativeMenuCreateMethod L527 fun hookMultiNativeMenuCreateMethod(method: Method): Boolean
  527: fun hookMultiNativeMenuCreateMethod(method: Method): Boolean {
  528: return hookMethod(method,
  CALLER L175 if (hookMultiNativeMenuCreateMethod(method)) hooked++
ORIGIN hookMultiNativeMenuClickMethod L535 fun hookMultiNativeMenuClickMethod(method: Method, exitMethod: Method): Boolean
  535: fun hookMultiNativeMenuClickMethod(method: Method, exitMethod: Method): Boolean {
  536: return hookMethod(method,
  CALLER L177 if (hookMultiNativeMenuClickMethod(multiMenuClickMethod, multiExitMethod)) hooked++
ORIGIN hookMethod L543 fun hookMethod(method: Method, callback: XC_MethodHook): Boolean
  543: fun hookMethod(method: Method, callback: XC_MethodHook): Boolean {
  544: if (!hookedMethods.add(method)) return true
  545: return try {
  546: HookRegistry.get().hook(method, callback)
  549: hookedMethods.remove(method)
  550: logger("转发语音Hook安装失败: ${method.name}", e)
  CALLER L186 return hookMethod(method, object : XC_MethodHook() {
  CALLER L198 return hookMethod(method, object : XC_MethodHook() {
  CALLER L206 return hookMethod(method, object : XC_MethodHook() {
  CALLER L230 if (hookMethod(method, object : XC_MethodHook() {
  CALLER L293 if (hookMethod(method, object : XC_MethodHook() {
  CALLER L325 if (hookMethod(queryMethod, object : XC_MethodHook() {
  CALLER L339 if (resetMethod != null && hookMethod(resetMethod, object : XC_MethodHook() {
  CALLER L427 hookMethod(method, object : XC_MethodHook() {
  CALLER L528 return hookMethod(method, object : XC_MethodHook() {
  CALLER L536 return hookMethod(method, object : XC_MethodHook() {

FILE hooks/items/voicepreview/VoicePreviewFeature.kt
L96 [包装调用] hook TARGET=method CALLBACK=after IN=install@94
L103 [包装调用] hook TARGET=method CALLBACK=before IN=install@94
L128 [转发] HookRegistry.get().hook TARGET=KavaReflector.accessible(method) ?: method CALLBACK=callback IN=hook@124
ORIGIN install L94 fun install(): Boolean
  95: val createHooked = SingleMessageMenuLocator.menuCreateMethods(context, logger).count { method ->
  96: hook(method,
  102: val clickHooked = SingleMessageMenuLocator.menuClickMethods(context, logger).count { method ->
  103: hook(method,
  111: return createHooked > 0 && clickHooked > 0
ORIGIN hook L124 fun hook(method: Method, callback: XC_MethodHook): Boolean
  124: fun hook(method: Method, callback: XC_MethodHook): Boolean {
  125: if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
  126: if (!hookedMethods.add(method)) return true
  127: return runCatching {
  128: HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
  131: hookedMethods.remove(method)
  132: logger("语音预览菜单 Hook 安装失败: ${method.toGenericString()}", it)
  CALLER L96 hook(method, object : XC_MethodHook() {
  CALLER L103 hook(method, object : XC_MethodHook() {

FILE hooks/items/zombiecheck/ZombieCheckFeature.kt
L83 [终端] HookRegistry.get().hook TARGET=callback CALLBACK=before IN=install@76
ORIGIN install L76 fun install(): Boolean
  77: if (installed) return true
  78: return runCatching {
  79: val constructor = locateProbeConstructor() ?: return false
  80: val callback = locateCallbackMethod(constructor) ?: return false
  81: locateDeleteMethod()
  82: WeChatApis.network()?.installNetworkHook(context.dexFinder())
  LOCAL_LOCATOR locateProbeConstructor L182 fun locateProbeConstructor(): Constructor<*>?
  LOCAL_LOCATOR locateCallbackMethod L220 fun locateCallbackMethod(constructor: Constructor<*>): Method?
  LOCAL_LOCATOR locateDeleteMethod L235 fun locateDeleteMethod(): Method?
SITES_END
COUNTS {'hook': 259, '包装调用': 98, '转发': 18, '终端': 187, 'hookMethod': 14, 'hookAllMethods': 7, 'hookAllConstructors': 1, 'hookOnce': 11, 'hookAfterOnce': 3, 'hookDeclaredAfter': 6, 'findAndHookMethod': 2} CALLBACK_OBJECTS 271 METHODS {'before': 169, 'after': 139, 'replace': 0}
```

统计口径：`hook` 已排除函数声明和零参数生命周期调用；包装层的调用与其内部终端调用均有记录，不能相加当作唯一宿主目标。`replace: 0` 仅表示没有传统 replace 回调方法；实际以 before 短路返回实现的替换逻辑仍在逐站点清单内。
