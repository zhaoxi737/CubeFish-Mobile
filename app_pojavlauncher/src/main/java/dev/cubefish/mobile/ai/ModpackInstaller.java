package dev.cubefish.mobile.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Modrinth 整合包（.mrpack）安装（方块鱼手机版）。
 *
 * 流程：读 modrinth.index.json → 按 files[].path 下载到游戏目录 → 解压 overrides/。
 * 只支持 Modrinth 的 .mrpack；CurseForge 的 .zip（manifest.json）暂不支持。
 */
public final class ModpackInstaller {
    private ModpackInstaller() {
    }

    public static String install(File mrpack, ModBrowser.Target target) throws Exception {
        ZipFile zip = new ZipFile(mrpack);
        try {
            ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
            if (indexEntry == null) {
                throw new IOException("不是 Modrinth 整合包：缺少 modrinth.index.json（CurseForge 的 zip 暂不支持）");
            }
            JSONObject index = new JSONObject(readEntry(zip, indexEntry));
            String name = index.optString("name", mrpack.getName());
            JSONObject dependencies = index.optJSONObject("dependencies");
            String mcVersion = dependencies == null ? "" : dependencies.optString("minecraft", "");
            String loader = dependencies == null ? "" : loaderOf(dependencies);

            JSONArray files = index.optJSONArray("files");
            int downloaded = 0;
            int skipped = 0;
            List<String> failed = new ArrayList<>();
            if (files != null) {
                for (int i = 0; i < files.length(); i += 1) {
                    JSONObject file = files.getJSONObject(i);
                    String path = file.optString("path", "");
                    if (path.isEmpty() || path.contains("..")) continue;
                    File dest = new File(target.gameDir, path);
                    if (dest.exists()) {
                        skipped += 1;
                        continue;
                    }
                    JSONArray downloads = file.optJSONArray("downloads");
                    if (downloads == null || downloads.length() == 0) continue;
                    String url = downloads.optString(0);
                    try {
                        ModBrowser.download(url, dest);
                        downloaded += 1;
                    } catch (Exception error) {
                        failed.add(path + "（" + error.getMessage() + "）");
                    }
                }
            }

            int overrides = 0;
            for (java.util.Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                String prefix = null;
                if (entryName.startsWith("overrides/")) prefix = "overrides/";
                else if (entryName.startsWith("client-overrides/")) prefix = "client-overrides/";
                if (prefix == null || entry.isDirectory()) continue;
                String relative = entryName.substring(prefix.length());
                if (relative.isEmpty() || relative.contains("..")) continue;
                File dest = new File(target.gameDir, relative);
                File parent = dest.getParentFile();
                if (parent != null) parent.mkdirs();
                try {
                    copyEntry(zip, entry, dest);
                    overrides += 1;
                } catch (Exception ignored) {
                }
            }

            StringBuilder summary = new StringBuilder();
            summary.append("整合包「").append(name).append("」安装完成");
            if (!mcVersion.isEmpty()) summary.append("（适用于 ").append(mcVersion).append(loader.isEmpty() ? "" : " / " + loader).append("）");
            summary.append("\n· 下载 ").append(downloaded).append(" 个文件，跳过已存在 ").append(skipped).append(" 个");
            summary.append("\n· 覆盖配置/资源 ").append(overrides).append(" 个");
            if (!failed.isEmpty()) {
                summary.append("\n· 失败 ").append(failed.size()).append(" 个：");
                for (int i = 0; i < Math.min(failed.size(), 5); i += 1) summary.append("\n  ✕ ").append(failed.get(i));
            }
            summary.append("\n到主界面启动游戏即可（整合包一般自带版本配置）。");
            return summary.toString();
        } finally {
            try {
                zip.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String loaderOf(JSONObject dependencies) {
        if (dependencies.has("fabric-loader")) return "fabric";
        if (dependencies.has("forge")) return "forge";
        if (dependencies.has("neoforge")) return "neoforge";
        if (dependencies.has("quilt-loader")) return "quilt";
        return "";
    }

    private static String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }

    private static void copyEntry(ZipFile zip, ZipEntry entry, File dest) throws IOException {
        try (InputStream in = zip.getInputStream(entry); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }
}
