package com.remorerus.rokidstandalonetest;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_MIC = 4107;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView output;
    private Button micButton;
    private Button asrButton;
    private SpeechRecognizer recognizer;
    private Runnable pendingAfterPermission;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        append("RE:Vision — автономный тест 0.1");
        append("Проверка запускается непосредственно на очках. Hi Rokid не используется этим приложением.");
        append("Android API: " + Build.VERSION.SDK_INT);
        append("Устройство: " + Build.MANUFACTURER + " " + Build.MODEL);
        append("Микрофон по PackageManager: " +
                getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE));
        append("Разрешение RECORD_AUDIO: " +
                (hasMicPermission() ? "уже выдано" : "ещё не выдано"));
        if (Build.VERSION.SDK_INT >= 31) {
            append("Встроенный on-device ASR доступен: " +
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(this));
        } else {
            append("On-device ASR API требует Android 12 / API 31 или новее.");
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("RE:Vision
Автономная проверка Rokid");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, dp(14));
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("Тестирует запуск отдельного APK, разрешение и реальный захват микрофона, а также наличие локального распознавания русского языка. Не меняет настройки системы и не подключается к телефону.");
        note.setTextSize(15);
        note.setPadding(0, 0, 0, dp(16));
        root.addView(note);

        micButton = new Button(this);
        micButton.setText("Проверить микрофон (3 секунды)");
        micButton.setOnClickListener(v -> ensureMicPermission(this::testMicrophone));
        root.addView(micButton);

        asrButton = new Button(this);
        asrButton.setText("Проверить офлайн-распознавание");
        asrButton.setOnClickListener(v -> ensureMicPermission(this::testOfflineRecognition));
        root.addView(asrButton);

        Button clear = new Button(this);
        clear.setText("Очистить журнал");
        clear.setOnClickListener(v -> output.setText(""));
        root.addView(clear);

        output = new TextView(this);
        output.setTextSize(14);
        output.setTextIsSelectable(true);
        output.setPadding(0, dp(16), 0, 0);
        root.addView(output);
        setContentView(scroll);
    }

    private void ensureMicPermission(Runnable action) {
        if (hasMicPermission()) {
            action.run();
            return;
        }
        pendingAfterPermission = action;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            append("RECORD_AUDIO: разрешение выдано.");
            Runnable action = pendingAfterPermission;
            pendingAfterPermission = null;
            if (action != null) action.run();
        } else {
            pendingAfterPermission = null;
            append("RECORD_AUDIO: пользователь не выдал разрешение. Проверка микрофона невозможна.");
        }
    }

    private void testMicrophone() {
        micButton.setEnabled(false);
        append("Начинаю захват AudioRecord на 3 секунды…");
        new Thread(() -> {
            AudioRecord recorder = null;
            try {
                int sampleRate = 16000;
                int min = AudioRecord.getMinBufferSize(sampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IllegalStateException("getMinBufferSize вернул " + min);
                int bufferBytes = Math.max(min, 4096);
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioRecord не инициализирован");
                }
                recorder.startRecording();
                if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("Android не перешёл в режим записи");
                }
                short[] buffer = new short[1024];
                long started = System.currentTimeMillis();
                long sumSquares = 0;
                long samples = 0;
                int peak = 0;
                int readErrors = 0;
                while (System.currentTimeMillis() - started < 3000) {
                    int count = recorder.read(buffer, 0, buffer.length);
                    if (count < 0) {
                        readErrors++;
                        if (readErrors > 5) throw new IllegalStateException("Ошибка чтения AudioRecord: " + count);
                        continue;
                    }
                    for (int i = 0; i < count; i++) {
                        int value = buffer[i];
                        int abs = Math.abs(value);
                        if (abs > peak) peak = abs;
                        sumSquares += (long) value * value;
                    }
                    samples += count;
                }
                final long sampleCount = samples;
                final int peakValue = peak;
                final double rms = samples == 0 ? 0 : Math.sqrt((double) sumSquares / samples);
                main.post(() -> {
                    append("Микрофон: AudioRecord успешно записал " + sampleCount + " отсчётов.");
                    append("Уровень сигнала: RMS=" + String.format(Locale.US, "%.1f", rms) +
                            ", пик=" + peakValue + " из 32767.");
                    if (peakValue == 0) append("ВНИМАНИЕ: сигнал нулевой. Проверь положение очков и доступ к микрофону.");
                    else append("Захват микрофона работает.");
                    micButton.setEnabled(true);
                });
            } catch (Exception e) {
                String message = e.getClass().getSimpleName() + ": " + e.getMessage();
                main.post(() -> {
                    append("Ошибка микрофона: " + message);
                    micButton.setEnabled(true);
                });
            } finally {
                if (recorder != null) {
                    try { if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); } catch (Exception ignored) {}
                    try { recorder.release(); } catch (Exception ignored) {}
                }
            }
        }, "rokid-mic-test").start();
    }

    private void testOfflineRecognition() {
        if (Build.VERSION.SDK_INT < 31) {
            append("Офлайн-распознавание: Android API ниже 31; системный on-device API недоступен.");
            return;
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            append("Офлайн-распознавание: на прошивке не найден установленный on-device Speech Recognition service.");
            append("Это не ошибка микрофона: захват AudioRecord проверяется отдельной кнопкой.");
            return;
        }
        try {
            if (recognizer != null) recognizer.destroy();
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { append("ASR: слушаю. Скажи короткую фразу по-русски."); }
                @Override public void onBeginningOfSpeech() { append("ASR: речь обнаружена."); }
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { append("ASR: конец речи, обрабатываю…"); }
                @Override public void onError(int error) { append("ASR завершился с кодом ошибки: " + error); }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    append("Результат локального распознавания: " +
                            (matches == null || matches.isEmpty() ? "пусто" : matches.get(0)));
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            append("Запускаю именно системный on-device recognizer (ru-RU), не сетевой режим.");
            recognizer.startListening(intent);
        } catch (Exception e) {
            append("Не удалось запустить on-device ASR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void append(String line) {
        if (output == null) return;
        output.append(line + "\n");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
        super.onDestroy();
    }
}
