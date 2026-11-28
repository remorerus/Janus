package com.remorerus.revisionbridgelab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;

public final class MainActivity extends Activity {
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 36, 28, 28);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        status = new TextView(this);
        status.setTextSize(18);
        status.setText("RE:Vision — лаборатория связи\nСлужба пока не запущена.");
        root.addView(status);
        addButton(root, "Запустить Foreground Service", () -> {
            Intent i = new Intent(this, LocalBridgeService.class);
            i.setAction(LocalBridgeService.ACTION_START);
            startForegroundService(i);
            status.setText("Запрос запуска отправлен. Проверяю локальный WebSocket…");
            status.postDelayed(this::testSocket, 700);
        });
        addButton(root, "Проверить WebSocket", this::testSocket);
        addButton(root, "Остановить службу", () -> {
            stopService(new Intent(this, LocalBridgeService.class));
            status.setText("Служба остановлена.");
        });
        TextView note = new TextView(this);
        note.setText("\nАдрес: ws://127.0.0.1:38763/bridge\nДоступ только с этого устройства.\nAccessibility permission не используется.");
        root.addView(note);
        setContentView(root);
    }

    private void addButton(LinearLayout root, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        root.addView(b);
    }

    private void testSocket() {
        status.setText("Подключаюсь к 127.0.0.1:38763…");
        new Thread(() -> {
            WebSocketClient client = null;
            try {
                client = new WebSocketClient(new URI("ws://127.0.0.1:38763/bridge?token=" + LocalBridgeService.LAB_TOKEN)) {
                    @Override public void onOpen(ServerHandshake handshake) {
                        send("LAB_PING");
                    }
                    @Override public void onMessage(String message) {
                        runOnUiThread(() -> status.setText("УСПЕХ: локальный WebSocket ответил\n" + message));
                        close();
                    }
                    @Override public void onClose(int code, String reason, boolean remote) {
                        if (code != 1000) runOnUiThread(() -> status.setText("WebSocket закрыт: " + reason));
                    }
                    @Override public void onError(Exception ex) {
                        runOnUiThread(() -> status.setText("Ошибка WebSocket: " + ex.getMessage()));
                    }
                };
                client.connectBlocking(4, java.util.concurrent.TimeUnit.SECONDS);
                if (!client.isOpen()) runOnUiThread(() -> status.setText("Нет соединения. Служба не отвечает."));
            } catch (Exception e) {
                String message = e.getMessage();
                runOnUiThread(() -> status.setText("Ошибка: " + message));
            }
        }, "bridge-lab-client").start();
    }
}
