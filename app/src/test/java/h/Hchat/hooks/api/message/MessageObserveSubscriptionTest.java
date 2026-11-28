package h.Hchat.hooks.api.message;

import org.junit.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** 只测试回调交付契约，不创建微信对象，也不执行 Android stub。 */
public class MessageObserveSubscriptionTest {
    private void dispatch(WeChatMessageObserveApi api) throws Exception {
        Method dispatch = WeChatMessageObserveApi.class.getDeclaredMethod(
                "dispatch", WeChatMessageObserveApi.ObservedMessage.class);
        dispatch.setAccessible(true);
        dispatch.invoke(api, (Object) null);
    }

    @Test public void labeledAndOriginalSubscriptionsRemainSynchronousAndOrdered() throws Exception {
        WeChatMessageObserveApi api = new WeChatMessageObserveApi(null, null, null, null, null);
        Thread caller = Thread.currentThread();
        List<String> calls = new ArrayList<>();
        api.subscribe("first", message -> { assertSame(caller, Thread.currentThread()); calls.add("a"); });
        api.subscribe(message -> { assertSame(caller, Thread.currentThread()); calls.add("b"); });
        api.subscribe("third", message -> calls.add("c"));
        dispatch(api);
        assertEquals(Arrays.asList("a", "b", "c"), calls);
    }

    @Test public void labelsDoNotDuplicateTheSameListener() throws Exception {
        WeChatMessageObserveApi api = new WeChatMessageObserveApi(null, null, null, null, null);
        AtomicInteger count = new AtomicInteger();
        WeChatMessageObserveApi.Listener listener = message -> count.incrementAndGet();
        api.subscribe(listener);
        api.subscribe("another-label", listener);
        dispatch(api);
        assertEquals(1, count.get());
        api.unsubscribe(listener);
        dispatch(api);
        assertEquals(1, count.get());
    }

    @Test public void subscriptionTokenStillCancelsExactlyItsListener() throws Exception {
        WeChatMessageObserveApi api = new WeChatMessageObserveApi(null, null, null, null, null);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        WeChatMessageObserveApi.Subscription token = api.subscribe("first", message -> first.incrementAndGet());
        api.subscribe("second", message -> second.incrementAndGet());
        token.unsubscribe();
        token.unsubscribe();
        assertFalse(token.isActive());
        dispatch(api);
        assertEquals(0, first.get());
        assertEquals(1, second.get());
    }

    @Test public void listenerFailureDoesNotStopLaterListeners() throws Exception {
        List<String> errors = new ArrayList<>();
        WeChatMessageObserveApi api = new WeChatMessageObserveApi(null, null, null, null, errors::add);
        AtomicInteger delivered = new AtomicInteger();
        api.subscribe("broken", message -> { throw new IllegalStateException("test failure"); });
        api.subscribe("healthy", message -> delivered.incrementAndGet());
        dispatch(api);
        assertEquals(1, delivered.get());
        assertEquals(1, errors.size());
    }

    @Test public void loggerFailureCannotAbortRemainingListeners() throws Exception {
        WeChatMessageObserveApi api = new WeChatMessageObserveApi(null, null, null, null,
                text -> { throw new IllegalStateException("logging failed"); });
        AtomicInteger delivered = new AtomicInteger();
        api.subscribe("broken", message -> { throw new IllegalArgumentException("listener failed"); });
        api.subscribe("healthy", message -> delivered.incrementAndGet());
        dispatch(api);
        assertEquals(1, delivered.get());
    }

    @Test public void modificationsDuringDispatchApplyToTheNextSnapshot() throws Exception {
        WeChatMessageObserveApi api = new WeChatMessageObserveApi(null, null, null, null, null);
        List<String> calls = new ArrayList<>();
        WeChatMessageObserveApi.Listener second = message -> calls.add("b");
        WeChatMessageObserveApi.Listener later = message -> calls.add("c");
        api.subscribe("first", message -> {
            calls.add("a");
            api.unsubscribe(second);
            api.subscribe("later", later);
        });
        api.subscribe("second", second);
        dispatch(api);
        assertEquals(Arrays.asList("a", "b"), calls);
        calls.clear();
        dispatch(api);
        assertEquals(Arrays.asList("a", "c"), calls);
    }
}
