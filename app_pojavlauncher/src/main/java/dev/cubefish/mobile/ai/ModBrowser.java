package dev.cubefish.mobile.ai;

import dev.cubefish.mobile.download.DownloadCenter;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 模组下载（Modrinth）：给「下载模组」页面用。
 *
 * 引擎自带的内容搜索只会搜整合包（SearchModFragment 里 isModpack 写死为 true），
 * 所以方块鱼在这里自己实现「搜单个模组 + 装进当前版本的 mods 目录」。
 */
public final class ModBrowser {
    private static final String API = "https://api.modrinth.com/v2";
    private static final String USER_AGENT =
            "CubeFish-Mobile/0.6 (+https://github.com/zhaoxi737/CubeFish-Mobile)";

    private ModBrowser() {
    }

    /** 当前版本上下文：游戏版本 / 加载器 / mods 目录 */
    public static final class Target {
        public final String mcVersion;
        public final String loader;
        public final File gameDir;
        public final File modsDir;

        Target(String mcVersion, String loader, File gameDir) {
            this.mcVersion = mcVersion;
            this.loader = loader;
            this.gameDir = gameDir;
            this.modsDir = new File(gameDir, "mods");
        }
    }

    public static Target target() {
        LauncherProfiles.load();
        MinecraftProfile profile = LauncherProfiles.getCurrentProfile();
        if (profile == null) throw new IllegalStateException("还没有选中版本：先在主界面底部版本栏创建/选择一个版本");
        String versionId = profile.lastVersionId == null ? "" : profile.lastVersionId.toLowerCase(Locale.ROOT);
        String loader = "vanilla";
        if (versionId.contains("neoforge")) loader = "neoforge";
        else if (versionId.contains("forge")) loader = "forge";
        else if (versionId.contains("fabric") || versionId.contains("quilt")) loader = "fabric";
        String mcVersion = "";
        try {
            mcVersion = Tools.getSelectedVanillaMcVer();
        } catch (Throwable ignored) {
        }
        if (mcVersion == null || mcVersion.isEmpty()) mcVersion = versionId;
        return new Target(mcVersion, loader, Tools.getGameDirPath(profile));
    }

    /** 搜模组，返回 {ok, mcVersion, loader, hits:[{id,slug,title,description,downloads,icon}]} */
    public static JSONObject search(String query, Target target) throws Exception {
        JSONArray facets = new JSONArray();
        facets.put(new JSONArray().put("project_type:mod"));
        if (target.mcVersion != null && !target.mcVersion.isEmpty()) {
            facets.put(new JSONArray().put("versions:" + target.mcVersion));
        }
        if ("fabric".equals(target.loader) || "forge".equals(target.loader) || "neoforge".equals(target.loader)) {
            facets.put(new JSONArray().put("categories:" + target.loader));
        }
        String url = API + "/search?query=" + URLEncoder.encode(query, "UTF-8")
                + "&limit=20&index=relevance&facets=" + URLEncoder.encode(facets.toString(), "UTF-8");
        JSONObject data = new JSONObject(get(url));
        JSONArray hits = data.optJSONArray("hits");
        JSONArray out = new JSONArray();
        if (hits != null) {
            for (int i = 0; i < hits.length(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                JSONObject item = new JSONObject();
                item.put("id", hit.optString("project_id"));
                item.put("slug", hit.optString("slug"));
                item.put("title", hit.optString("title"));
                item.put("description", hit.optString("description"));
                item.put("downloads", hit.optLong("downloads"));
                item.put("icon", hit.optString("icon_url"));
                out.put(item);
            }
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("mcVersion", target.mcVersion);
        result.put("loader", target.loader);
        result.put("hits", out);
        return result;
    }

    /** 搜光影（Modrinth 的 project_type=shader），返回 {ok, hits:[...]} */
    public static JSONObject searchShaders(String query) throws Exception {
        JSONArray facets = new JSONArray();
        facets.put(new JSONArray().put("project_type:shader"));
        String url = API + "/search?query=" + URLEncoder.encode(query, "UTF-8")
                + "&limit=20&index=relevance&facets=" + URLEncoder.encode(facets.toString(), "UTF-8");
        JSONObject data = new JSONObject(get(url));
        JSONArray hits = data.optJSONArray("hits");
        JSONArray out = new JSONArray();
        if (hits != null) {
            for (int i = 0; i < hits.length(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                JSONObject item = new JSONObject();
                item.put("id", hit.optString("project_id"));
                item.put("slug", hit.optString("slug"));
                item.put("title", hit.optString("title"));
                item.put("description", hit.optString("description"));
                item.put("downloads", hit.optLong("downloads"));
                item.put("icon", hit.optString("icon_url"));
                out.put(item);
            }
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("hits", out);
        return result;
    }

    /** 装光影：取最新版本里的第一个 .zip，放进 shaderpacks/ */
    public static String installShader(String projectId) throws Exception {
        Target target = target();
        File shaderDir = new File(target.gameDir, "shaderpacks");
        if (!shaderDir.exists() && !shaderDir.mkdirs()) throw new IOException("无法创建 shaderpacks 目录");
        JSONArray versions = new JSONArray(get(API + "/project/"
                + URLEncoder.encode(projectId, "UTF-8") + "/version"));
        for (int i = 0; i < versions.length(); i++) {
            JSONObject version = versions.optJSONObject(i);
            if (version == null) continue;
            JSONArray files = version.optJSONArray("files");
            if (files == null) continue;
            for (int j = 0; j < files.length(); j++) {
                JSONObject file = files.optJSONObject(j);
                if (file == null) continue;
                String filename = file.optString("filename");
                String url = file.optString("url");
                if (!filename.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
                download(url, new File(shaderDir, filename));
                return "已把光影装到 shaderpacks/" + filename
                        + "\n（要真的生效还需要 Iris（Fabric）或 OptiFine；游戏里 设置 → 光影 里选它）";
            }
        }
        return "这个光影没有 .zip 文件可下";
    }

    /** 装一个模组（含它的 required 前置，最多两层），返回人话总结 */
    public static String install(Target target, String projectId) throws Exception {
        Set<String> visited = new HashSet<>();
        List<String> installed = new ArrayList<>();
        installRecursive(target, projectId, visited, installed, 0);
        if (installed.isEmpty()) return "没有装任何文件（可能没有匹配 " + target.mcVersion + "/" + target.loader + " 的版本）";
        StringBuilder summary = new StringBuilder("已安装 " + installed.size() + " 个文件：");
        for (String name : installed) summary.append("\n· ").append(name);
        return summary.toString();
    }

    private static void installRecursive(Target target, String projectId, Set<String> visited,
                                         List<String> installed, int depth) throws Exception {
        if (projectId == null || projectId.isEmpty() || depth > 2 || visited.contains(projectId)) return;
        visited.add(projectId);

        JSONObject version = pickVersion(target, projectId);
        if (version == null) return;

        String projectTitle = version.optJSONObject("__project") == null ? projectId : version.optString("name", projectId);
        JSONArray files = version.optJSONArray("files");
        if (files == null) return;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            if (!file.optBoolean("primary", i == 0)) continue;
            String filename = file.optString("filename");
            String url = file.optString("url");
            if (filename.isEmpty() || url.isEmpty()) continue;
            if (!target.modsDir.exists() && !target.modsDir.mkdirs()) {
                throw new IOException("无法创建 mods 目录：" + target.modsDir);
            }
            File dest = new File(target.modsDir, filename);
            download(url, dest);
            installed.add(filename);
            break;
        }

        JSONArray dependencies = version.optJSONArray("dependencies");
        if (dependencies != null && depth < 2) {
            for (int i = 0; i < dependencies.length(); i++) {
                JSONObject dep = dependencies.getJSONObject(i);
                if (!"required".equals(dep.optString("dependency_type"))) continue;
                String depProject = dep.optString("project_id", "");
                if (depProject.isEmpty()) continue;
                installRecursive(target, depProject, visited, installed, depth + 1);
            }
        }
    }

    private static JSONObject pickVersion(Target target, String projectId) throws Exception {
        StringBuilder url = new StringBuilder(API)
                .append("/project/").append(URLEncoder.encode(projectId, "UTF-8")).append("/version");
        List<String> params = new ArrayList<>();
        if (!target.mcVersion.isEmpty()) {
            params.add("game_versions=" + URLEncoder.encode("[\"" + target.mcVersion + "\"]", "UTF-8"));
        }
        if ("fabric".equals(target.loader) || "forge".equals(target.loader) || "neoforge".equals(target.loader)) {
            params.add("loaders=" + URLEncoder.encode("[\"" + target.loader + "\"]", "UTF-8"));
        }
        if (!params.isEmpty()) url.append('?').append(String.join("&", params));

        JSONArray list;
        try {
            list = new JSONArray(get(url.toString()));
        } catch (Exception noMatch) {
            // 回退：不带筛选，取最新的（装错版本的概率交给页面提示版本不匹配）
            list = new JSONArray(get(API + "/project/" + URLEncoder.encode(projectId, "UTF-8") + "/version"));
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject version = list.getJSONObject(i);
            JSONArray files = version.optJSONArray("files");
            if (files == null || files.length() == 0) continue;
            version.put("name", projectId);
            return version;
        }
        return null;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(in);
        if (code >= 400) {
            throw new IOException("Modrinth 请求失败（HTTP " + code + "）" + trim(body, 200));
        }
        return body;
    }

    static void download(String url, File dest) throws IOException {
        // 方块鱼：走统一下载中心（多线程分段 + 重试 + 进度可见），比原来单连接快
        String label = dest.getName();
        int dot = label.lastIndexOf('.');
        if (dot > 0) label = label.substring(0, dot);
        DownloadCenter.enqueueAndWait(label, url, dest);
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        in.close();
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String trim(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
