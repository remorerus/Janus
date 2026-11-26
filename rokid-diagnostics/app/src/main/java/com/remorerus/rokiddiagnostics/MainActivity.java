package com.remorerus.rokiddiagnostics;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String DEFAULT_PACKAGE = "com.ymid.wakeonlan";
    private EditText packageField;
    private TextView output;
    private LinearLayout root;
    private String lastReport = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF101820);
        getWindow().setNavigationBarColor(0xFF101820);
        buildUi();
        showDeviceInfo();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.setBackgroundColor(0xFF101820);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("ROKID DIAGNOSTICS", 23, 0xFFFFFFFF, true);
        root.addView(title);
        TextView subtitle = text("Диагностика для Rokid Glasses / YodaOS Sprite", 13, 0xFFB8C7D1, false);
        root.addView(subtitle);

        packageField = new EditText(this);
        packageField.setSingleLine(true);
        packageField.setText(DEFAULT_PACKAGE);
        packageField.setHint("Имя пакета, например com.ymid.wakeonlan");
        packageField.setTextColor(0xFFFFFFFF);
        packageField.setHintTextColor(0xFF9EADB8);
        packageField.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(packageField, params(-1, -2, 0, 0, 0, 12));

        addButton("Сведения о RE:Vision", v -> showAppInfo());
        addButton("Activity и Services", v -> showComponents());
        addButton("История сбоев", v -> showCrashes());
        addButton("Сведения об очках", v -> showDeviceInfo());
        addButton("Список установленных приложений", v -> showApps());
        addButton("Открыть системные настройки RE:Vision", v -> openAppSettings());
        addButton("Открыть настройки разработчика", v -> openDeveloperSettings());
        addButton("Попробовать открыть системный отчёт", v -> openBugReport());
        addButton("Поделиться диагностическим отчётом", v -> shareReport());

        output = text("Готово. Выбери раздел выше.", 13, 0xFFE6EDF3, false);
        output.setTextIsSelectable(true);
        output.setPadding(dp(12), dp(14), dp(12), dp(14));
        output.setBackgroundColor(0xFF1B2732);
        root.addView(output, params(-1, -2, 0, 12, 0, 0));
    }

    private void addButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF245B78));
        b.setOnClickListener(listener);
        root.addView(b, params(-1, -2, 0, 6, 0, 0));
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private LinearLayout.LayoutParams params(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String targetPackage() {
        String value = packageField.getText().toString().trim();
        return value.isEmpty() ? DEFAULT_PACKAGE : value;
    }

    private void show(String title, String body) {
        lastReport = title + "\n" + body;
        output.setText(lastReport);
    }

    private void showAppInfo() {
        String pkg = targetPackage();
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
            StringBuilder s = new StringBuilder();
            s.append("ПРИЛОЖЕНИЕ\n");
            s.append("Название: ").append(pm.getApplicationLabel(ai)).append("\n");
            s.append("Пакет: ").append(pkg).append("\n");
            s.append("Версия: ").append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
            s.append("Target SDK: ").append(ai.targetSdkVersion).append("\n");
            s.append("Установлено: ").append(date(pi.firstInstallTime)).append("\n");
            s.append("Обновлено: ").append(date(pi.lastUpdateTime)).append("\n");
            s.append("Системное приложение: ").append((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "да" : "нет").append("\n");
            s.append("Разрешено: ").append(ai.enabled ? "да" : "нет").append("\n");
            s.append("\nНажми «Activity и Services», чтобы увидеть компоненты.");
            show("Сведения о " + pkg, s.toString());
        } catch (Exception e) {
            show("Не удалось прочитать приложение", "Пакет: " + pkg + "\nОшибка: " + e);
        }
    }

    private void showComponents() {
        String pkg = targetPackage();
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
            StringBuilder s = new StringBuilder();
            s.append("ACTIVITY приложения ").append(pkg).append("\n\n");
            ActivityInfo[] activities = pi.activities;
            if (activities == null || activities.length == 0) s.append("Activity не обнаружены.\n");
            else for (ActivityInfo a : activities) {
                s.append(a.name).append("\n");
                s.append("  exported=").append(a.exported).append(", enabled=").append(a.enabled).append("\n");
                if (a.exported && a.enabled) s.append("  [доступна для запуска из диагностической программы]\n");
            }
            s.append("\nSERVICES\n");
            ServiceInfo[] services = pi.services;
            if (services == null || services.length == 0) s.append("Services не обнаружены.\n");
            else for (ServiceInfo v : services) {
                s.append(v.name).append("\n");
                s.append("  exported=").append(v.exported).append(", enabled=").append(v.enabled).append("\n");
            }
            s.append("\nНажми на кнопку ниже, чтобы открыть системную карточку приложения.");
            show("Компоненты " + pkg, s.toString());
        } catch (Exception e) {
            show("Ошибка чтения компонентов", "Пакет: " + pkg + "\n" + e);
        }
    }

    private void showCrashes() {
        String pkg = targetPackage();
        if (Build.VERSION.SDK_INT < 30) {
            show("История сбоев", "Эта функция требует Android 11 или новее. Текущая версия: " + Build.VERSION.RELEASE);
            return;
        }
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(pkg, 0, 20);
            StringBuilder s = new StringBuilder();
            s.append("ИСТОРИЯ ЗАВЕРШЕНИЙ: ").append(pkg).append("\n\n");
            if (exits == null || exits.isEmpty()) {
                s.append("Система не вернула записей. Это не доказывает, что сбоев не было.\n");
            } else {
                for (ApplicationExitInfo info : exits) {
                    s.append(date(info.getTimestamp())).append("\n");
                    s.append("Причина: ").append(reason(info.getReason())).append(" (").append(info.getReason()).append(")\n");
                    s.append("Процесс: ").append(info.getProcessName()).append("\n");
                    s.append("Описание: ").append(info.getDescription()).append("\n");
                    s.append("Статус: ").append(info.getStatus()).append("\n\n");
                }
            }
            show("История сбоев", s.toString());
        } catch (SecurityException e) {
            show("Доступ к истории сбоев ограничен системой",
                    "Rokid не разрешил стороннему приложению читать историю завершений RE:Vision.\n\n" +
                    "Это ограничение Android: для чтения записей другого пакета требуется системное разрешение DUMP.\n" +
                    "Программа не получает root и не обходит защиту.\n\n" + e);
        } catch (Exception e) {
            show("Не удалось прочитать историю сбоев", e.toString());
        }
    }

    private String reason(int code) {
        switch (code) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "приложение завершило себя";
            case ApplicationExitInfo.REASON_SIGNALED: return "завершено сигналом";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "нехватка памяти";
            case ApplicationExitInfo.REASON_CRASH: return "Java crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case ApplicationExitInfo.REASON_ANR: return "ANR / зависание";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "ошибка запуска";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "изменение разрешений";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "чрезмерное потребление ресурсов";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "остановлено пользователем";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "пользователь остановил профиль";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "завершилась зависимость";
            default: return "другая причина";
        }
    }

    private void showDeviceInfo() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        StringBuilder s = new StringBuilder();
        s.append("УСТРОЙСТВО\n");
        s.append("Производитель: ").append(Build.MANUFACTURER).append("\n");
        s.append("Модель: ").append(Build.MODEL).append("\n");
        s.append("Устройство: ").append(Build.DEVICE).append("\n");
        s.append("Продукт: ").append(Build.PRODUCT).append("\n");
        s.append("Android: ").append(Build.VERSION.RELEASE).append("\n");
        s.append("SDK/API: ").append(Build.VERSION.SDK_INT).append("\n");
        s.append("Сборка: ").append(Build.DISPLAY).append("\n");
        s.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n");
        s.append("ABI: ").append(join(Build.SUPPORTED_ABIS)).append("\n");
        s.append("Экран: ").append(dm.widthPixels).append(" × ").append(dm.heightPixels).append("\n");
        s.append("Плотность: ").append(dm.densityDpi).append(" dpi\n");
        s.append("\nДиагностическая программа не требует root. Доступ к полному системному Logcat может быть ограничен прошивкой.");
        show("Сведения об очках", s.toString());
    }

    private void showApps() {
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            Collections.sort(apps, new Comparator<ApplicationInfo>() {
                @Override public int compare(ApplicationInfo a, ApplicationInfo b) {
                    return String.valueOf(pm.getApplicationLabel(a)).compareToIgnoreCase(String.valueOf(pm.getApplicationLabel(b)));
                }
            });
            StringBuilder s = new StringBuilder("УСТАНОВЛЕННЫЕ ПРИЛОЖЕНИЯ (" + apps.size() + ")\n\n");
            for (ApplicationInfo ai : apps) {
                s.append(pm.getApplicationLabel(ai)).append("\n  ").append(ai.packageName).append("\n");
            }
            show("Список приложений", s.toString());
        } catch (Exception e) {
            show("Ошибка списка приложений", e.toString());
        }
    }

    private void openAppSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + targetPackage()));
            startActivity(i);
        } catch (Exception e) {
            show("Не удалось открыть настройки приложения", e.toString());
        }
    }

    private void openDeveloperSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            show("Настройки разработчика недоступны", e.toString());
        }
    }

    private void openBugReport() {
        String[] actions = {"android.settings.BUG_REPORT", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"};
        for (String action : actions) {
            try {
                Intent i = new Intent(action);
                if (i.resolveActivity(getPackageManager()) != null) {
                    startActivity(i);
                    return;
                }
            } catch (Exception ignored) { }
        }
        show("Системный отчёт", "Прошивка не предоставила стороннему приложению экран отчёта об ошибках.");
    }

    private void shareReport() {
        String report = buildReport();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Rokid Diagnostics — " + targetPackage());
        send.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(send, "Отправить диагностический отчёт"));
    }

    private String buildReport() {
        StringBuilder s = new StringBuilder();
        s.append("ROKID DIAGNOSTICS REPORT\n");
        s.append("Время: ").append(date(System.currentTimeMillis())).append("\n\n");
        s.append("Устройство: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        s.append("Android/API: ").append(Build.VERSION.RELEASE).append(" / ").append(Build.VERSION.SDK_INT).append("\n");
        s.append("Сборка: ").append(Build.DISPLAY).append("\n");
        s.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n");
        s.append("ABI: ").append(join(Build.SUPPORTED_ABIS)).append("\n\n");
        String pkg = targetPackage();
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
            s.append("PACKAGE: ").append(pkg).append("\n");
            s.append("VERSION: ").append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
            if (pi.activities != null) for (ActivityInfo a : pi.activities) s.append("ACTIVITY: ").append(a.name).append(" exported=").append(a.exported).append("\n");
            if (pi.services != null) for (ServiceInfo v : pi.services) s.append("SERVICE: ").append(v.name).append(" exported=").append(v.exported).append("\n");
        } catch (Exception e) {
            s.append("PACKAGE ERROR: ").append(e).append("\n");
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(pkg, 0, 20);
                for (ApplicationExitInfo info : exits) {
                    s.append("EXIT: ").append(date(info.getTimestamp())).append(" reason=").append(reason(info.getReason()))
                     .append(" process=").append(info.getProcessName()).append(" description=").append(info.getDescription()).append("\n");
                }
            } catch (Exception e) {
                s.append("EXIT HISTORY UNAVAILABLE: ").append(e).append("\n");
            }
        }
        s.append("\nПримечание: полный Logcat не читается без системного разрешения/ADB/Shizuku.");
        return s.toString();
    }

    private String date(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.getDefault()).format(new Date(millis));
    }

    private String join(String[] values) {
        if (values == null) return "unknown";
        StringBuilder s = new StringBuilder();
        for (String v : values) {
            if (s.length() > 0) s.append(", ");
            s.append(v);
        }
        return s.toString();
    }
}
