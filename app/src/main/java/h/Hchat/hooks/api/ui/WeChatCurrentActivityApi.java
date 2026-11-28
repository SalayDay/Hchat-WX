package h.Hchat.hooks.api.ui;

import android.app.Activity;

import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.utils.KavaReflector;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 当前微信 Activity 跟踪 API。
 */
public final class WeChatCurrentActivityApi {
    public interface Logger {
        void log(String message);
    }

    private final Logger logger;
    private volatile WeakReference<Activity> currentActivity =
            new WeakReference<>(null);
    private volatile boolean installed;

    public WeChatCurrentActivityApi(Logger logger) {
        this.logger = logger;
    }

    public boolean isAvailable() {
        return installed;
    }

    public synchronized void install() {
        if (installed) return;
        try {
            Method onResume = KavaReflector.findDeclaredMethod(Activity.class, "onResume");
            Method onPause = KavaReflector.findDeclaredMethod(Activity.class, "onPause");
            Method onDestroy = KavaReflector.findDeclaredMethod(Activity.class, "onDestroy");
            HookRegistry.get().hook(onResume, new CurrentActivityResumeHooker(this));
            HookRegistry.get().hook(onPause, new CurrentActivityClearHooker(this));
            HookRegistry.get().hook(onDestroy, new CurrentActivityClearHooker(this));
            installed = true;
            log("当前Activity Hook已安装");
        } catch (Throwable e) {
            log("当前Activity Hook失败: " + e.getMessage());
        }
    }

    public Activity currentActivity() {
        WeakReference<Activity> ref = currentActivity;
        return ref != null ? ref.get() : null;
    }

    public void updateCurrentActivity(Activity activity) {
        if (activity != null && !activity.isFinishing()) {
            currentActivity = new WeakReference<>(activity);
        }
    }

    public String currentActivityName() {
        Activity activity = currentActivity();
        return activity != null ? activity.getClass().getName() : "";
    }

    public boolean isCurrentActivity(String className) {
        String currentName = currentActivityName();
        return className != null && className.equals(currentName);
    }

    public boolean isCurrentActivityContains(String classNamePart) {
        String currentName = currentActivityName();
        return classNamePart != null && currentName.contains(classNamePart);
    }

    public boolean isInChatting() {
        return isCurrentActivityContains(".ui.chatting.");
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatCurrentActivityApi] " + message);
    }

    private static final class CurrentActivityResumeHooker implements XposedInterface.Hooker {
        private final WeChatCurrentActivityApi owner;
        private CurrentActivityResumeHooker(WeChatCurrentActivityApi owner) { this.owner = owner; }
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            if (chain.getThisObject() instanceof Activity) {
                owner.currentActivity = new WeakReference<>((Activity) chain.getThisObject());
            }
            return result;
        }
    }

    private static final class CurrentActivityClearHooker implements XposedInterface.Hooker {
        private final WeChatCurrentActivityApi owner;
        private CurrentActivityClearHooker(WeChatCurrentActivityApi owner) { this.owner = owner; }
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            Activity current = owner.currentActivity();
            if (current != null && current == chain.getThisObject()) {
                owner.currentActivity = new WeakReference<>(null);
            }
            return result;
        }
    }

}
