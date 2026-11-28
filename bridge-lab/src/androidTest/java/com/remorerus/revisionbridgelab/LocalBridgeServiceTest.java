package com.remorerus.revisionbridgelab;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class LocalBridgeServiceTest {
    @Test public void foregroundServiceAcceptsLoopbackWebSocketAndEchoesMessage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(launch);
        Intent service = new Intent(context, LocalBridgeService.class).setAction(LocalBridgeService.ACTION_START);
        context.startForegroundService(service);

        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicReference<String> response = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        WebSocketClient client = null;
        boolean connected = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !connected) {
            client = new WebSocketClient(new URI(
                    "ws://127.0.0.1:38763/bridge?token=" + LocalBridgeService.LAB_TOKEN)) {
                @Override public void onOpen(ServerHandshake handshake) { send("instrumentation-ping"); }
                @Override public void onMessage(String message) {
                    response.set(message);
                    responseLatch.countDown();
                    close();
                }
                @Override public void onClose(int code, String reason, boolean remote) {}
                @Override public void onError(Exception ex) { error.set(ex.toString()); }
            };
            try { connected = client.connectBlocking(2, TimeUnit.SECONDS); }
            catch (Exception ex) { error.set(ex.toString()); }
            if (!connected) {
                try { client.closeBlocking(); } catch (Exception ignored) {}
                Thread.sleep(250);
            }
        }
        try {
            assertTrue("loopback WebSocket did not connect: " + error.get(), connected);
            assertTrue("server did not answer; error=" + error.get(),
                    responseLatch.await(5, TimeUnit.SECONDS));
            assertNotNull(response.get());
            assertTrue(response.get(), response.get().contains("\"ok\":true"));
            assertTrue(response.get(), response.get().contains("instrumentation-ping"));
        } finally {
            if (client != null) client.close();
            context.stopService(service);
        }
    }
}
