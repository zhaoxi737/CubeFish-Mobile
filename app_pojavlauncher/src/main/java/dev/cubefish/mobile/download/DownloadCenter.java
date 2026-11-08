package dev.cubefish.mobile.download;

import android.util.Log;

import net.kdt.pojavlaunch.utils.DownloadUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 方块鱼：统一下载中心。
 *
 * 所有下载（模组、光影、整合包…）都走这里，好处：
 *   · 走 DownloadUtils 的多线程分段下载（大文件 4 连接）+ 30 秒超时 + 3 次重试，比原来单连接快
 *   · 每个任务都有进度（已下载/总大小/速度），网页能实时看到
 *   · 下载完/失败都会推到网页，不用一直转圈
 */
public final class DownloadCenter {
    private static final String TAG = "CubeFishDownload";
    private static final int MAX_TASKS = 30;

    private static final List<Task> sTasks = new ArrayList<>();
    private static long sNextId = 1L;
    private static long sLastBroadcast = 0L;

    public interface Listener {
        void onDownloadsChanged(String json);
    }

    private static volatile Listener sListener;

    private DownloadCenter() {
    }

    public static void setListener(Listener listener) {
        sListener = listener;
    }

    /** 一个下载任务 */
    public static final class Task {
        public long id;
        public String label = "";
        public String fileName = "";
        public String state = "running"; // running / done / error
        public long done = 0L;
        public long total = 0L;
        public String error = "";
        public long startedAt = System.currentTimeMillis();
        private long lastBytes = 0L;
        private long lastAt = 0L;
        public double speed = 0d; // bytes / second

        JSONObject toJson() throws Exception {
            JSONObject out = new JSONObject();
            out.put("id", id);
            out.put("label", label);
            out.put("file", fileName);
            out.put("state", state);
            out.put("done", done);
            out.put("total", total);
            out.put("percent", total > 0 ? Math.min(100, Math.round(done * 1000d / total) / 10d) : -1);
            out.put("speed", Math.round(speed));
            out.put("error", error);
            out.put("elapsedMs", System.currentTimeMillis() - startedAt);
            return out;
        }
    }

    public static synchronized String snapshotJson() {
        JSONArray array = new JSONArray();
        try {
            for (int i = sTasks.size() - 1; i >= 0; i--) { // 最新的在最前面
                array.put(sTasks.get(i).toJson());
            }
        } catch (Throwable ignored) {
        }
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("tasks", array);
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    private static synchronized Task newTask(String label, String url, File dest) {
        Task task = new Task();
        task.id = sNextId++;
        task.label = label != null && !label.isEmpty() ? label : "下载";
        task.fileName = dest == null ? "" : dest.getName();
        task.lastAt = System.currentTimeMillis();
        sTasks.add(task);
        while (sTasks.size() > MAX_TASKS) sTasks.remove(0);
        return task;
    }

    private static void broadcast(boolean force) {
        Listener listener = sListener;
        if (listener == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - sLastBroadcast < 350L) return;
        sLastBroadcast = now;
        try {
            listener.onDownloadsChanged(snapshotJson());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 下载一个文件并等它下完（调用线程会阻塞）。
     * 进度会通过 listener 推给网页，所以网页侧请用异步方式触发。
     */
    public static void enqueueAndWait(String label, String url, File dest) throws IOException {
        if (dest == null) throw new IOException("下载目标为空");
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent);
        }
        final Task task = newTask(label, url, dest);
        broadcast(true);
        final Object lock = new Object();
        try {
            DownloadUtils.downloadFileMonitored(url, dest, null, (curr, max) -> {
                synchronized (lock) {
                    long now = System.currentTimeMillis();
                    task.done = curr;
                    if (max > 0) task.total = max;
                    if (now - task.lastAt >= 500L) {
                        long delta = curr - task.lastBytes;
                        task.speed = delta * 1000d / Math.max(1L, now - task.lastAt);
                        task.lastBytes = curr;
                        task.lastAt = now;
                    }
                }
                broadcast(false);
            });
            synchronized (lock) {
                task.state = "done";
                task.done = dest.length();
                if (task.total <= 0) task.total = task.done;
                task.speed = 0d;
            }
            Log.i(TAG, "下载完成：" + task.fileName + " (" + task.done + " bytes)");
        } catch (IOException error) {
            synchronized (lock) {
                task.state = "error";
                task.error = error.getMessage() == null ? "下载失败" : error.getMessage();
                task.done = dest.length();
                task.speed = 0d;
            }
            Log.w(TAG, "下载失败：" + task.fileName, error);
            broadcast(true);
            throw error;
        } finally {
            broadcast(true);
        }
    }
}
