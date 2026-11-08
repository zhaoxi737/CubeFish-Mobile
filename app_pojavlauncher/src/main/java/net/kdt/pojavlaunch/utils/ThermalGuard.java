package net.kdt.pojavlaunch.utils;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

/**
 * 方块鱼：手机温度守护。
 *
 * 游戏跑起来以后每 15 秒看一次：
 *   · 电池温度（系统广播里的 EXTRA_TEMPERATURE，不需要权限）
 *   · 系统热状态（PowerManager.getCurrentThermalStatus，Android 10+）
 * 连续两次超阈值、或者系统报「严重过热」，就先提示，然后直接退出游戏，避免把手机烧了。
 *
 * 阈值默认 43℃，可在设置里开/关（pref: thermalGuard）。
 */
public class ThermalGuard {
    private static final String TAG = "CubeFishThermal";
    private static final String CHANNEL_ID = "cubefish_thermal";
    private static final long PERIOD_MS = 15000L;

    private static volatile boolean sRunning = false;
    private static volatile float sLastTemp = 0f;
    private static volatile int sLastThermalStatus = -1;
    private static Thread sThread;

    /** 最近一次读到的电池温度（℃），0 表示读不到 */
    public static float lastTemperature() {
        return sLastTemp;
    }

    /** 最近一次读到的系统热状态：0=NONE 1=LIGHT 2=MODERATE 3=SEVERE 4=CRITICAL 5=EMERGENCY 6=SHUTDOWN，-1 未知 */
    public static int lastThermalStatus() {
        return sLastThermalStatus;
    }

    public static String thermalStatusName(int status) {
        switch (status) {
            case 0: return "正常";
            case 1: return "轻微";
            case 2: return "中等";
            case 3: return "严重";
            case 4: return "危急";
            case 5: return "紧急";
            case 6: return "关机级别";
            default: return "未知";
        }
    }

    /** 立刻读一次当前温度（AI 助手问「手机烫不烫」时用，不用等守护线程） */
    public static void sample(Context context) {
        try {
            sLastTemp = readBatteryTemp(context);
            sLastThermalStatus = readThermalStatus(context);
        } catch (Throwable ignored) {
        }
    }

    private static float readBatteryTemp(Context context) {
        try {
            Intent battery = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return 0f;
            int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (tenths == Integer.MIN_VALUE) return 0f;
            return tenths / 10f;
        } catch (Throwable error) {
            return 0f;
        }
    }

    private static int readThermalStatus(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1;
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) return -1;
            return powerManager.getCurrentThermalStatus();
        } catch (Throwable error) {
            return -1;
        }
    }

    /** 游戏开始时调用（重复调用安全） */
    public static void start(Context context) {
        if (!LauncherPreferences.PREF_THERMAL_GUARD) return;
        if (sRunning) return;
        final Context appContext = context.getApplicationContext();
        sRunning = true;
        sThread = new Thread(() -> {
            int hotStrikes = 0;
            while (sRunning) {
                try {
                    float temp = readBatteryTemp(appContext);
                    int status = readThermalStatus(appContext);
                    sLastTemp = temp;
                    sLastThermalStatus = status;
                    int limit = LauncherPreferences.PREF_THERMAL_LIMIT_C;
                    boolean tooHot = (temp > 0f && temp >= limit)
                            || (status >= PowerManager.THERMAL_STATUS_SEVERE && status > 0);
                    if (tooHot) hotStrikes++;
                    else if (hotStrikes > 0) hotStrikes--; // 慢慢退火，别因为一次抖动就杀
                    Log.i(TAG, "温度 " + temp + "℃ / 系统热状态 " + status + " / 连续过热次数 " + hotStrikes);
                    if (hotStrikes >= 3) {
                        triggerShutdown(appContext, temp, status);
                        return;
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "温度检测失败", error);
                }
                try {
                    Thread.sleep(PERIOD_MS);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }, "CubeFish-ThermalGuard");
        sThread.setDaemon(true);
        sThread.start();
        Log.i(TAG, "温度守护已启动，阈值 " + LauncherPreferences.PREF_THERMAL_LIMIT_C + "℃");
        // 启动时告知用户一句，避免它悄悄起作用让人莫名其妙
        try {
            final String hint = "温度守护已开启：电池超 " + LauncherPreferences.PREF_THERMAL_LIMIT_C
                    + "℃ 或系统过热会自动退出游戏"
                    + (LauncherPreferences.PREF_LOW_HEAT_MODE ? "（省电降温模式也在生效）" : "");
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(appContext, hint, Toast.LENGTH_LONG).show());
        } catch (Throwable ignored) {
        }
    }

    public static void stop() {
        sRunning = false;
        if (sThread != null) {
            sThread.interrupt();
            sThread = null;
        }
    }

    private static void triggerShutdown(Context context, float temp, int status) {
        sRunning = false;
        final String message = "手机太热了（电池 " + (temp > 0 ? String.format("%.1f", temp) : "未知")
                + "℃ / 系统热状态 " + thermalStatusName(status) + "），已自动退出游戏保护手机。"
                + "凉一会儿再玩，或在设置里调低画质/开省电降温模式。";
        Log.w(TAG, message);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            try {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
            try {
                notifyOverheat(context, message);
            } catch (Throwable ignored) {
            }
        });
        // 给提示和通知留点时间，然后退出游戏（游戏和启动器在同一个进程里）
        try {
            Thread.sleep(2200L);
        } catch (InterruptedException ignored) {
        }
        try {
            Tools.fullyExit();
        } catch (Throwable error) {
            Log.w(TAG, "退出失败", error);
        }
    }

    private static void notifyOverheat(Context context, String message) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "方块鱼温度保护", NotificationManager.IMPORTANCE_HIGH);
            manager.createNotificationChannel(channel);
        }
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
                new Intent(), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("方块鱼：温度过高，已退出游戏")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(contentIntent);
        manager.notify(4242, builder.build());
    }
}
