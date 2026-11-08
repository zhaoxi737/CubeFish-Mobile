package dev.cubefish.mobile.ai;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 网页（assets/ai/index.html）与 Android / 启动器引擎之间的桥。
 *
 * 约定：JS 侧只通过 window.CubeFish.* 调用；主进程通过 window.__cubeFishEvent(json) 回推事件。
 */
public class AiBridge {
    private static final String PREFS = "cubefish_ai";

    private final Activity activity;
    private final WebView webView;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile LlmStream current;
    private volatile boolean destroyed;

    public AiBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /* ---------- 配置 ---------- */

    @JavascriptInterface
    public String getConfig() {
        try {
            JSONObject out = new JSONObject();
            out.put("baseUrl", prefs.getString("baseUrl", "https://api.deepseek.com/v1"));
            out.put("model", prefs.getString("model", "deepseek-chat"));
            out.put("hasKey", !prefs.getString("apiKey", "").isEmpty());
            out.put("device", getStatusJson());
            return out.toString();
        } catch (Exception error) {
            return "{\"hasKey\":false,\"device\":{}}";
        }
    }

    @JavascriptInterface
    public String getApiKey() {
        return prefs.getString("apiKey", "");
    }

    @JavascriptInterface
    public void saveConfig(String baseUrl, String model, String apiKey) {
        SharedPreferences.Editor editor = prefs.edit();
        if (baseUrl != null && !baseUrl.trim().isEmpty()) editor.putString("baseUrl", baseUrl.trim());
        if (model != null && !model.trim().isEmpty()) editor.putString("model", model.trim());
        if (apiKey != null && !apiKey.trim().isEmpty()) editor.putString("apiKey", apiKey.trim());
        editor.apply();
        push("{\"type\":\"configSaved\"}");
    }

    /* ---------- 设备 / 引擎状态 ---------- */

    @JavascriptInterface
    public String getStatus() {
        return getStatusJson();
    }

    private String getStatusJson() {
        try {
            LauncherProfiles.load();
            MinecraftProfile profile = LauncherProfiles.getCurrentProfile();
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) manager.getMemoryInfo(memoryInfo);

            JSONObject out = new JSONObject();
            out.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            out.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            out.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?");
            out.put("ramTotalMb", memoryInfo.totalMem / (1024L * 1024L));
            out.put("ramFreeMb", memoryInfo.availMem / (1024L * 1024L));
            out.put("allocatedMb", LauncherPreferences.PREF_RAM_ALLOCATION);
            if (profile != null) {
                out.put("profile", profile.name == null ? "" : profile.name);
                out.put("versionId", profile.lastVersionId == null ? "" : profile.lastVersionId);
                out.put("renderer", profile.pojavRendererName == null ? "" : profile.pojavRendererName);
                out.put("useAngle", profile.useANGLE);
                File gameDir = Tools.getGameDirPath(profile);
                out.put("gameDir", gameDir.getAbsolutePath());
                out.put("mods", countMods(gameDir));
            }
            return out.toString();
        } catch (Exception error) {
            return "{\"error\":" + JSONObject.quote(String.valueOf(error.getMessage())) + "}";
        }
    }

    private static int countMods(File gameDir) {
        File[] files = new File(gameDir, "mods").listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) count++;
        }
        return count;
    }

    /* ---------- 日志 ---------- */

    @JavascriptInterface
    public String readLog(int lines) {
        int maxLines = Math.max(20, Math.min(lines <= 0 ? 120 : lines, 400));
        List<String> candidates = new ArrayList<>();
        try {
            LauncherProfiles.load();
            MinecraftProfile profile = LauncherProfiles.getCurrentProfile();
            if (profile != null) {
                candidates.add(new File(Tools.getGameDirPath(profile), "logs/latest.log").getAbsolutePath());
            }
        } catch (Exception ignored) {
        }
        candidates.add(new File(Tools.DIR_GAME_NEW, "logs/latest.log").getAbsolutePath());
        candidates.add(new File(Tools.DIR_GAME_HOME, "latestlog.txt").getAbsolutePath());

        for (String path : candidates) {
            File file = new File(path);
            if (!file.isFile()) continue;
            try {
                List<String> all = new ArrayList<>();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)
                );
                String line;
                while ((line = reader.readLine()) != null) {
                    all.add(redact(line));
                    if (all.size() > 4000) all.remove(0);
                }
                reader.close();
                int from = Math.max(0, all.size() - maxLines);
                StringBuilder text = new StringBuilder();
                for (int i = from; i < all.size(); i++) {
                    if (text.length() > 0) text.append('\n');
                    text.append(all.get(i));
                }
                JSONObject out = new JSONObject();
                out.put("path", path);
                out.put("lines", all.size());
                out.put("text", text.toString());
                return out.toString();
            } catch (Exception ignored) {
            }
        }
        try {
            JSONObject out = new JSONObject();
            out.put("path", candidates.isEmpty() ? "" : candidates.get(0));
            out.put("lines", 0);
            out.put("text", "还没有日志：先启动一次游戏，或日志文件尚未生成。");
            return out.toString();
        } catch (Exception error) {
            return "{\"text\":\"读取日志失败\"}";
        }
    }

    /** 日志里可能带 UUID / 路径 / 邮箱 / IP / 用户目录 —— 发给模型前先打码（M2 硬性要求） */
    private static String redact(String line) {
        String out = line;
        out = out.replaceAll("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>");
        out = out.replaceAll("(?i)sk-[A-Za-z0-9_-]{8,}", "sk-***");
        out = out.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "<email>");
        out = out.replaceAll("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b", "<ip>");
        out = out.replaceAll("/storage/[^\\s\"']+", "<path>");
        out = out.replaceAll("/data/[^\\s\"']+", "<path>");
        return out;
    }

    /* ---------- 工具 ---------- */

    @JavascriptInterface
    public String runTool(String name, String argsJson) {
        try {
            JSONObject args = new JSONObject(argsJson == null || argsJson.trim().isEmpty() ? "{}" : argsJson);
            switch (name) {
                case "get_status":
                    return ok("设备/引擎状态：" + getStatusJson());
                case "read_log": {
                    JSONObject log = new JSONObject(readLog(args.optInt("lines", 120)));
                    String text = log.optString("text", "");
                    return ok("日志（" + log.optString("path") + "，共 " + log.optInt("lines") + " 行，已脱敏）：\n" + text);
                }
                case "set_memory": {
                    int mb = args.optInt("mb", 0);
                    return setMemory(mb);
                }
                case "list_mods":
                    return listMods();
                case "search_mods":
                    return searchModsTool(args);
                case "install_mod":
                    return installModTool(args);
                case "list_versions":
                    return listVersionsTool();
                case "select_version":
                    return selectVersionTool(args);
                case "read_crash":
                    return readCrashTool();
                case "open_game_folder":
                    return openGameFolderTool();
                case "plan":
                    return planTool(args);
                case "launch_game":
                    return launchGame();
                default:
                    return err("没有这个工具：" + name);
            }
        } catch (Exception error) {
            return err("工具执行失败：" + error.getMessage());
        }
    }

    private String setMemory(int mb) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) manager.getMemoryInfo(info);
        long ramMb = info.totalMem / (1024L * 1024L);
        int upper = (int) Math.max(512, Math.min(8192, ramMb - 1024));
        int target = Math.max(512, Math.min(upper, mb <= 0 ? LauncherPreferences.PREF_RAM_ALLOCATION : mb));
        try {
            android.preference.PreferenceManager.getDefaultSharedPreferences(activity)
                    .edit().putInt("allocation", target).apply();
            LauncherPreferences.loadPreferences(activity);
            return ok("已把内存分配设为 " + target + " MB（下次启动生效；本机共 " + ramMb + " MB）");
        } catch (Exception error) {
            return err("改内存失败：" + error.getMessage());
        }
    }

    private String listMods() throws Exception {
        LauncherProfiles.load();
        MinecraftProfile profile = LauncherProfiles.getCurrentProfile();
        if (profile == null) return err("还没有选中的版本");
        File modsDir = new File(Tools.getGameDirPath(profile), "mods");
        File[] files = modsDir.listFiles();
        JSONArray items = new JSONArray();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) items.put(file.getName());
            }
        }
        return ok("已安装 " + items.length() + " 个模组：\n" + items.join("\n"));
    }

    private String launchGame() {
        activity.runOnUiThread(() -> {
            try {
                ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                activity.finish();
            } catch (Throwable ignored) {
            }
        });
        return ok("已请求启动游戏（如果没反应，回主界面点一下「开始游戏」）。");
    }

    /* ---------- 和桌面版对齐的额外工具 ---------- */

    private String searchModsTool(JSONObject args) {
        String query = args.optString("query", "").trim();
        if (query.isEmpty()) return err("请给个关键词，例如 sodium / jei / 小地图");
        try {
            JSONObject result = ModBrowser.search(query, ModBrowser.target());
            JSONArray hits = result.optJSONArray("hits");
            if (hits == null || hits.length() == 0) {
                return ok("没搜到「" + query + "」（当前版本 " + result.optString("mcVersion") + " / " + result.optString("loader") + "）");
            }
            StringBuilder sb = new StringBuilder("搜索「" + query + "」（" + result.optString("mcVersion") + " / " + result.optString("loader") + "）：");
            for (int i = 0; i < Math.min(hits.length(), 8); i++) {
                JSONObject hit = hits.getJSONObject(i);
                sb.append("\n· ").append(hit.optString("title"))
                        .append("（projectId=").append(hit.optString("id")).append("）")
                        .append(hit.optString("description"));
            }
            sb.append("\n要装的话调 install_mod（可以直接给 query）。");
            return ok(sb.toString());
        } catch (Exception error) {
            return err("搜索模组失败：" + error.getMessage());
        }
    }

    private String installModTool(JSONObject args) {
        String projectId = args.optString("projectId", "").trim();
        String query = args.optString("query", "").trim();
        if (projectId.isEmpty() && query.isEmpty()) return err("需要 query（模组名）或 projectId");
        try {
            ModBrowser.Target target = ModBrowser.target();
            if (projectId.isEmpty()) {
                JSONObject result = ModBrowser.search(query, target);
                JSONArray hits = result.optJSONArray("hits");
                if (hits == null || hits.length() == 0) return err("没搜到「" + query + "」，换个关键词");
                projectId = hits.getJSONObject(0).optString("id");
            }
            String summary = ModBrowser.install(target, projectId);
            return ok(summary + "\n（装完回主界面启动游戏生效；目标版本 " + target.mcVersion + " / " + target.loader + "）");
        } catch (Exception error) {
            return err("安装失败：" + error.getMessage());
        }
    }

    private String listVersionsTool() {
        try {
            File dir = new File(Tools.DIR_HOME_VERSION);
            File[] children = dir.listFiles();
            java.util.ArrayList<String> ready = new java.util.ArrayList<>();
            java.util.ArrayList<String> partial = new java.util.ArrayList<>();
            if (children != null) {
                for (File child : children) {
                    if (!child.isDirectory()) continue;
                    String name = child.getName();
                    if (new File(child, name + ".json").exists()) ready.add(name);
                    else partial.add(name);
                }
            }
            java.util.Collections.sort(ready);
            java.util.Collections.sort(partial);
            String current = "";
            try {
                current = LauncherProfiles.getCurrentProfile().lastVersionId;
            } catch (Throwable ignored) {
            }
            StringBuilder sb = new StringBuilder("本机版本（当前选中：" + (current.isEmpty() ? "未知" : current) + "）：");
            if (ready.isEmpty() && partial.isEmpty()) sb.append("\n还没有下载任何版本：先在主界面版本栏创建并下载一个");
            for (String name : ready) sb.append("\n✓ ").append(name).append(name.equals(current) ? "（当前）" : "");
            for (String name : partial) sb.append("\n… ").append(name).append("（未下载完）");
            return ok(sb.toString());
        } catch (Exception error) {
            return err("读取版本失败：" + error.getMessage());
        }
    }

    private String selectVersionTool(JSONObject args) {
        String versionId = args.optString("versionId", "").trim();
        if (versionId.isEmpty()) return err("需要 versionId，例如 1.20.1");
        try {
            File json = new File(Tools.DIR_HOME_VERSION + "/" + versionId + "/" + versionId + ".json");
            if (!json.exists()) {
                return err("本机没有 " + versionId + " 的版本文件：先用主界面版本栏下载它，或用 list_versions 看已有哪些");
            }
            LauncherProfiles.load();
            MinecraftProfile profile = MinecraftProfile.createTemplate();
            profile.name = versionId;
            profile.lastVersionId = versionId;
            String key = LauncherProfiles.getFreeProfileKey();
            LauncherProfiles.mainProfileJson.profiles.put(key, profile);
            LauncherProfiles.write();
            android.preference.PreferenceManager.getDefaultSharedPreferences(activity)
                    .edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).apply();
            return ok("已切换到版本 " + versionId + "（回主界面点「启动游戏」即可）");
        } catch (Exception error) {
            return err("切换版本失败：" + error.getMessage());
        }
    }

    private String readCrashTool() {
        try {
            ModBrowser.Target target = ModBrowser.target();
            File folder = new File(target.gameDir, "crash-reports");
            File[] files = folder.listFiles();
            if (files == null || files.length == 0) return ok("没有崩溃报告（crash-reports 目录是空的）");
            File newest = null;
            for (File file : files) {
                if (!file.isFile()) continue;
                if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
            }
            if (newest == null) return ok("没有崩溃报告");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(newest), java.nio.charset.StandardCharsets.UTF_8));
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(redact(line));
                if (lines.size() > 300) lines.remove(0);
            }
            reader.close();
            StringBuilder sb = new StringBuilder("最新崩溃报告（" + newest.getName() + "，已脱敏）：");
            int from = Math.max(0, lines.size() - 80);
            for (int i = from; i < lines.size(); i++) sb.append("\n").append(lines.get(i));
            return ok(sb.toString());
        } catch (Exception error) {
            return err("读取崩溃报告失败：" + error.getMessage());
        }
    }

    private String openGameFolderTool() {
        try {
            ModBrowser.Target target = ModBrowser.target();
            final File dir = target.gameDir;
            activity.runOnUiThread(() -> {
                try {
                    Tools.openPath(activity, dir, false);
                } catch (Throwable ignored) {
                }
            });
            return ok("正在打开游戏目录：" + dir.getAbsolutePath());
        } catch (Exception error) {
            return err("打开目录失败：" + error.getMessage());
        }
    }

    private String planTool(JSONObject args) {
        JSONArray steps = args.optJSONArray("steps");
        if (steps == null || steps.length() == 0) return ok("计划已记录（空计划）");
        StringBuilder sb = new StringBuilder("计划（" + steps.length() + " 步）：");
        for (int i = 0; i < steps.length(); i++) sb.append("\n").append(i + 1).append(". ").append(steps.optString(i));
        return ok(sb.toString());
    }

    /* ---------- 模型对话 ---------- */

    @JavascriptInterface
    public void startChat(String requestJson) {
        if (destroyed) return;
        stopChat();
        final String apiKey = prefs.getString("apiKey", "").trim();
        if (apiKey.isEmpty()) {
            push("{\"type\":\"error\",\"message\":\"还没有配置模型 API Key：点右上角设置填一个（只存在本机）。\"}");
            return;
        }
        final String baseUrl = prefs.getString("baseUrl", "https://api.deepseek.com/v1");
        final String model = prefs.getString("model", "deepseek-chat");
        executor.execute(() -> {
            try {
                JSONObject request = new JSONObject(requestJson);
                JSONArray messages = request.getJSONArray("messages");
                JSONArray tools = request.optJSONArray("tools");
                LlmStream stream = new LlmStream();
                current = stream;
                stream.chat(baseUrl, apiKey, model, messages, tools, new LlmStream.Callback() {
                    @Override
                    public void onDelta(String text) {
                        push("{\"type\":\"delta\",\"text\":" + JSONObject.quote(text) + "}");
                    }

                    @Override
                    public void onDone(String content, List<LlmStream.ToolCall> toolCalls) {
                        try {
                            JSONObject out = new JSONObject();
                            out.put("type", "done");
                            out.put("content", content == null ? "" : content);
                            JSONArray calls = new JSONArray();
                            for (LlmStream.ToolCall call : toolCalls) {
                                JSONObject item = new JSONObject();
                                item.put("id", call.id);
                                item.put("name", call.name);
                                item.put("arguments", call.arguments);
                                calls.put(item);
                            }
                            out.put("toolCalls", calls);
                            push(out.toString());
                        } catch (Exception error) {
                            push("{\"type\":\"error\",\"message\":\"解析模型结果失败\"}");
                        }
                    }

                    @Override
                    public void onError(String message) {
                        push("{\"type\":\"error\",\"message\":" + JSONObject.quote(message) + "}");
                    }
                });
            } catch (Exception error) {
                push("{\"type\":\"error\",\"message\":" + JSONObject.quote("请求失败：" + error.getMessage()) + "}");
            } finally {
                current = null;
            }
        });
    }

    /* ---------- 模组浏览 / 安装（Modrinth） ---------- */

    @JavascriptInterface
    public String getModContext() {
        try {
            ModBrowser.Target target = ModBrowser.target();
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("mcVersion", target.mcVersion);
            out.put("loader", target.loader);
            out.put("gameDir", target.gameDir.getAbsolutePath());
            out.put("modsDir", target.modsDir.getAbsolutePath());
            return out.toString();
        } catch (Exception error) {
            return err(String.valueOf(error.getMessage()));
        }
    }

    @JavascriptInterface
    public String searchMods(String query) {
        try {
            return ModBrowser.search(query == null ? "" : query, ModBrowser.target()).toString();
        } catch (Exception error) {
            return err("搜索失败：" + error.getMessage());
        }
    }

    /** 异步安装：结果通过 __cubeFishEvent 回推（type=mod） */
    @JavascriptInterface
    public void installMod(String projectId) {
        final String id = projectId == null ? "" : projectId;
        executor.execute(() -> {
            push("{\"type\":\"mod\",\"stage\":\"start\",\"projectId\":" + JSONObject.quote(id) + "}");
            try {
                ModBrowser.Target target = ModBrowser.target();
                String summary = ModBrowser.install(target, id);
                push("{\"type\":\"mod\",\"stage\":\"done\",\"summary\":" + JSONObject.quote(summary) + "}");
            } catch (Exception error) {
                push("{\"type\":\"mod\",\"stage\":\"error\",\"message\":" + JSONObject.quote(String.valueOf(error.getMessage())) + "}");
            }
        });
    }

    /** 关闭当前页面（网页里的「返回」按钮用） */
    @JavascriptInterface
    public void closePage() {
        try {
            activity.runOnUiThread(() -> activity.finish());
        } catch (Throwable ignored) {
        }
    }

    @JavascriptInterface
    public void stopChat() {
        LlmStream stream = current;
        if (stream != null) stream.abort();
    }

    public void shutdown() {
        destroyed = true;
        stopChat();
        executor.shutdownNow();
    }

    private void push(String json) {
        if (destroyed || webView == null) return;
        final String script = "window.__cubeFishEvent(" + JSONObject.quote(json) + ");";
        webView.post(() -> {
            try {
                webView.evaluateJavascript(script, null);
            } catch (Throwable ignored) {
            }
        });
    }

    private static String ok(String summary) {
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("summary", summary);
            return out.toString();
        } catch (Exception error) {
            return "{\"ok\":true,\"summary\":\"ok\"}";
        }
    }

    private static String err(String summary) {
        try {
            JSONObject out = new JSONObject();
            out.put("ok", false);
            out.put("summary", summary);
            return out.toString();
        } catch (Exception error) {
            return "{\"ok\":false,\"summary\":\"error\"}";
        }
    }
}
