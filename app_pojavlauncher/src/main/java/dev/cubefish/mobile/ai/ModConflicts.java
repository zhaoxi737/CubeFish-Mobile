package dev.cubefish.mobile.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模组冲突检查 / 一键修复（方块鱼手机版）。
 *
 * 桌面版靠扫描 jar 里的依赖声明 + Modrinth 版本数据做修复；手机版做同样的事：
 *  - 读每个 jar 的 fabric.mod.json（或 mods.toml）拿 mod id / 版本 / 依赖
 *  - 检查：同一 mod 装了多份、缺必需前置、声明的 minecraft 版本和当前版本对不上
 *  - 修复：删掉重复的旧 jar、用 Modrinth 把缺的前置装上
 */
public final class ModConflicts {
    private ModConflicts() {
    }

    public static final class Issue {
        public final String severity;
        public final String message;
        public final String suggestion;
        public final String fixKind;
        public final String fixArg;

        Issue(String severity, String message, String suggestion, String fixKind, String fixArg) {
            this.severity = severity;
            this.message = message;
            this.suggestion = suggestion;
            this.fixKind = fixKind;
            this.fixArg = fixArg;
        }
    }

    private static final class ModInfo {
        final String id;
        final String name;
        final String version;
        final File jar;
        final Map<String, String> depends = new HashMap<>();

        ModInfo(String id, String name, String version, File jar) {
            this.id = id;
            this.name = name == null || name.isEmpty() ? id : name;
            this.version = version == null ? "" : version;
            this.jar = jar;
        }
    }

    public static String analyze(ModBrowser.Target target) {
        List<ModInfo> mods = scan(target);
        if (mods.isEmpty()) return "mods 目录里还没有模组（" + target.modsDir.getAbsolutePath() + "）";
        List<Issue> issues = findIssues(target, mods);
        StringBuilder sb = new StringBuilder();
        sb.append("检查了 ").append(mods.size()).append(" 个模组（").append(target.mcVersion).append(" / ").append(target.loader).append("）：");
        if (issues.isEmpty()) {
            sb.append("\n没有发现冲突或缺失前置。");
            return sb.toString();
        }
        sb.append("\n发现 ").append(issues.size()).append(" 个问题：");
        for (Issue issue : issues) {
            sb.append("\n· [").append(issue.severity).append("] ").append(issue.message);
            if (issue.suggestion != null && !issue.suggestion.isEmpty()) sb.append("\n  → ").append(issue.suggestion);
        }
        sb.append("\n可以用 fix_mod_conflicts 一键修（删重复 + 装缺失前置）。");
        return sb.toString();
    }

    public static String fix(ModBrowser.Target target) {
        List<ModInfo> mods = scan(target);
        List<Issue> issues = findIssues(target, mods);
        if (issues.isEmpty()) return "没有需要修复的问题。";
        List<String> done = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Set<String> deletedIds = new HashSet<>();
        for (Issue issue : issues) {
            if (!"duplicate".equals(issue.fixKind) || issue.fixArg == null) continue;
            if (deletedIds.contains(issue.fixArg)) continue;
            deletedIds.add(issue.fixArg);
            done.add("删掉重复的旧 jar：" + new File(issue.fixArg).getName());
        }
        // 先删重复（fixArg 是文件绝对路径），再装缺失前置
        for (Issue issue : issues) {
            if ("duplicate".equals(issue.fixKind) && issue.fixArg != null) {
                File file = new File(issue.fixArg);
                if (file.exists() && file.delete()) done.add("已删除重复文件 " + file.getName());
            }
        }
        for (Issue issue : issues) {
            if (!"missing-dep".equals(issue.fixKind) || issue.fixArg == null) continue;
            try {
                JSONObject search = ModBrowser.search(issue.fixArg, target);
                JSONArray hits = search.optJSONArray("hits");
                if (hits == null || hits.length() == 0) {
                    failed.add(issue.fixArg + "（Modrinth 没搜到）");
                    continue;
                }
                String projectId = hits.getJSONObject(0).optString("id");
                ModBrowser.install(target, projectId);
                done.add("装上前置 " + issue.fixArg);
            } catch (Exception error) {
                failed.add(issue.fixArg + "（" + error.getMessage() + "）");
            }
        }
        StringBuilder sb = new StringBuilder("修复结果：");
        if (done.isEmpty()) sb.append("\n没有能自动修的项目。");
        for (String line : done) sb.append("\n✓ ").append(line);
        for (String line : failed) sb.append("\n✕ ").append(line);
        sb.append("\n再检查一次：").append(analyze(target).replace("\n", "\n  "));
        return sb.toString();
    }

    private static List<Issue> findIssues(ModBrowser.Target target, List<ModInfo> mods) {
        List<Issue> issues = new ArrayList<>();
        Map<String, List<ModInfo>> byId = new HashMap<>();
        for (ModInfo mod : mods) byId.computeIfAbsent(mod.id, key -> new ArrayList<>()).add(mod);

        // 1) 同一个 mod 装多份
        for (Map.Entry<String, List<ModInfo>> entry : byId.entrySet()) {
            List<ModInfo> list = entry.getValue();
            if (list.size() <= 1) continue;
            ModInfo keep = list.get(0);
            for (ModInfo mod : list) {
                if (cmpVersion(mod.version, keep.version) > 0) keep = mod;
            }
            for (ModInfo mod : list) {
                if (mod == keep) continue;
                issues.add(new Issue("error",
                        "「" + entry.getKey() + "」装了多份：" + list.get(0).jar.getName() + " 等，可能直接崩",
                        "保留新版 " + keep.jar.getName() + "，删掉 " + mod.jar.getName(),
                        "duplicate", mod.jar.getAbsolutePath()));
            }
        }

        // 2) 缺必需前置 + 3) minecraft 版本不匹配
        boolean hasFabricApi = false;
        for (ModInfo mod : mods) {
            String id = mod.id.toLowerCase(Locale.ROOT);
            if (id.equals("fabric-api") || id.startsWith("fabric-")) hasFabricApi = true;
        }
        for (ModInfo mod : mods) {
            for (Map.Entry<String, String> dep : mod.depends.entrySet()) {
                String depId = dep.getKey().toLowerCase(Locale.ROOT);
                String range = dep.getValue();
                if (depId.equals("minecraft")) {
                    if (!target.mcVersion.isEmpty() && !range.isEmpty() && !matchesMc(range, target.mcVersion)) {
                        issues.add(new Issue("error",
                                "「" + mod.name + "」要求 Minecraft " + range + "，当前是 " + target.mcVersion,
                                "换一个支持 " + target.mcVersion + " 的版本，或换游戏版本",
                                null, null));
                    }
                    continue;
                }
                if (depId.equals("java") || depId.equals("fabricloader") || depId.equals("forge")
                        || depId.equals("neoforge") || depId.equals("fabric")) {
                    continue;
                }
                boolean satisfied = byId.containsKey(depId);
                if (!satisfied && (depId.equals("fabric-api") || depId.startsWith("fabric-"))) {
                    satisfied = hasFabricApi;
                }
                if (!satisfied) {
                    issues.add(new Issue("error",
                            "「" + mod.name + "」需要前置「" + dep.getKey() + "」" + (range.isEmpty() ? "" : " " + range) + "，但没装",
                            "装上前置 " + dep.getKey() + "（fix_mod_conflicts 会自动装）",
                            "missing-dep", dep.getKey()));
                }
            }
        }
        return issues;
    }

    private static List<ModInfo> scan(ModBrowser.Target target) {
        List<ModInfo> result = new ArrayList<>();
        File[] files = target.modsDir.listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            ModInfo info = readJar(file);
            if (info != null) result.add(info);
        }
        return result;
    }

    private static ModInfo readJar(File jar) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(jar);
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry != null) {
                String text = readEntry(zip, entry);
                JSONObject json = new JSONObject(text);
                String id = json.optString("id", "");
                if (id.isEmpty()) return null;
                ModInfo info = new ModInfo(id, json.optString("name", id), json.optString("version", ""), jar);
                collectDepends(info.depends, json.optJSONObject("depends"));
                return info;
            }
            // Forge / NeoForge
            ZipEntry modsToml = zip.getEntry("META-INF/mods.toml");
            if (modsToml == null) modsToml = zip.getEntry("META-INF/neoforge.mods.toml");
            if (modsToml != null) {
                String text = readEntry(zip, modsToml);
                String id = firstMatch(text, "(?m)^\\s*modId\\s*=\\s*[\"']([^\"']+)[\"']");
                if (id == null) return null;
                String version = firstMatch(text, "(?m)^\\s*version\\s*=\\s*[\"']([^\"']+)[\"']");
                return new ModInfo(id, id, version == null ? "" : version, jar);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void collectDepends(Map<String, String> out, JSONObject depends) {
        if (depends == null) return;
        for (java.util.Iterator<String> it = depends.keys(); it.hasNext(); ) {
            String key = it.next();
            String value = depends.optString(key, "");
            if ("required".equals(value)) {
                out.put(key, "");
            } else {
                out.put(key, value);
            }
        }
    }

    private static String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String firstMatch(String text, String pattern) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 粗匹配 minecraft 版本范围（支持 ~、>=、<=、x、逗号/空格组合） */
    static boolean matchesMc(String range, String mcVersion) {
        String want = mcVersion.toLowerCase(Locale.ROOT);
        String lower = range.toLowerCase(Locale.ROOT).replace(" ", "");
        // 直接把 1.20.1 这类具体版本写进范围的，包含即算匹配
        if (lower.contains(want)) return true;
        if (lower.contains("x")) return true; // 1.20.x 之类，粗放处理
        // 处理 >=1.20 / <=1.20 / ~1.20
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(>=|<=|>|<|~|=)?(\\d+(?:\\.\\d+)*)").matcher(lower);
        while (matcher.find()) {
            String op = matcher.group(1) == null ? "~" : matcher.group(1);
            String ver = matcher.group(2);
            int cmp = cmpVersion(want, ver);
            if (">=".equals(op) && cmp < 0) return false;
            if ("<=".equals(op) && cmp > 0) return false;
            if (">".equals(op) && cmp <= 0) return false;
            if ("<".equals(op) && cmp >= 0) return false;
            if ("~".equals(op) || "=".equals(op)) {
                if (!want.startsWith(ver)) return false;
            }
        }
        return true;
    }

    /** 版本号比较：1.2.10 > 1.2.9；带 mc 前缀（mc1.20.1-0.5.13）也能比 */
    static int cmpVersion(String a, String b) {
        int[] av = nums(a);
        int[] bv = nums(b);
        int len = Math.max(av.length, bv.length);
        for (int i = 0; i < len; i += 1) {
            int x = i < av.length ? av[i] : 0;
            int y = i < bv.length ? bv[i] : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static int[] nums(String text) {
        if (text == null) return new int[0];
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(text);
        List<Integer> list = new ArrayList<>();
        while (matcher.find()) {
            try {
                list.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
            }
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i += 1) out[i] = list.get(i);
        return out;
    }
}
