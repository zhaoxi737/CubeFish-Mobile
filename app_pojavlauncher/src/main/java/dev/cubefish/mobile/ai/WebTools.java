package dev.cubefish.mobile.ai;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索 / 抓网页（方块鱼手机版）。
 *
 * 和桌面版同一套思路：中文查询优先搜狗（国内网络稳），英文/其它优先 Bing（RSS 直出真实链接），
 * DuckDuckGo 兜底；抓网页时把 HTML 转纯文本，并自动拆「JS 跳转」包装链接。
 */
public final class WebTools {
    private WebTools() {
    }

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 25000;

    public static final class Hit {
        public final String title;
        public final String url;
        public final String snippet;

        Hit(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }

    public static String search(String query) {
        if (query == null || query.trim().isEmpty()) return "query 为空";
        String q = query.trim();
        boolean cjk = hasCjk(q);
        List<String[]> providers = new ArrayList<>();
        if (cjk) {
            providers.add(new String[]{"搜狗", "https://www.sogou.com/web?query=" + enc(q)});
            providers.add(new String[]{"Bing", "https://www.bing.com/search?q=" + enc(q) + "&format=rss"});
            providers.add(new String[]{"DuckDuckGo", "https://duckduckgo.com/html/?q=" + enc(q)});
        } else {
            providers.add(new String[]{"Bing", "https://www.bing.com/search?q=" + enc(q) + "&format=rss"});
            providers.add(new String[]{"搜狗", "https://www.sogou.com/web?query=" + enc(q)});
            providers.add(new String[]{"DuckDuckGo", "https://duckduckgo.com/html/?q=" + enc(q)});
        }
        List<String> failures = new ArrayList<>();
        for (String[] provider : providers) {
            try {
                String body = httpGet(provider[1]);
                List<Hit> hits = provider[0].equals("搜狗") ? parseSogou(body)
                        : provider[0].equals("Bing") ? parseBingRss(body) : parseDuckDuckGo(body);
                if (hits.isEmpty() && provider[0].equals("Bing")) hits = parseBingHtml(body);
                if (hits.isEmpty()) {
                    failures.add(provider[0] + "：没有解析到结果");
                    continue;
                }
                StringBuilder sb = new StringBuilder("搜索「" + q + "」（来源 " + provider[0] + "）：");
                for (int i = 0; i < Math.min(hits.size(), 8); i += 1) {
                    Hit hit = hits.get(i);
                    sb.append("\n· ").append(hit.title).append(" — ").append(hit.url);
                    if (hit.snippet != null && !hit.snippet.isEmpty()) sb.append("\n  ").append(hit.snippet);
                }
                sb.append("\n要看正文用 web_fetch 抓上面的链接。");
                return sb.toString();
            } catch (Throwable error) {
                failures.add(provider[0] + "：" + error.getMessage());
            }
        }
        return "搜索失败：所有引擎都不可用。\n" + joinLines(failures);
    }

    public static String fetch(String url) {
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("https://")) return "只允许 https 链接";
        String current = url.trim();
        String body = null;
        String contentType = "";
        for (int hop = 0; hop < 3; hop += 1) {
            try {
                HttpResponse response = httpGetWithType(current);
                body = response.body;
                contentType = response.contentType;
            } catch (Throwable error) {
                return "抓取失败（" + current + "）：" + error.getMessage();
            }
            String next = extractRedirect(body);
            if (next == null || next.equals(current)) break;
            current = next;
        }
        if (body == null) return "抓取失败：没有返回内容";
        String text = contentType.contains("html") || looksLikeHtml(body) ? htmlToText(body) : body;
        if (text.length() > 12000) text = text.substring(0, 12000) + "\n\n…（已截断，全文 " + text.length() + " 字）";
        return text.isEmpty() ? "（" + current + " 没有文字内容）" : text;
    }

    /* ---------- 解析器 ---------- */

    private static List<Hit> parseBingRss(String xml) {
        List<Hit> hits = new ArrayList<>();
        Matcher matcher = Pattern.compile("<item>([\\s\\S]*?)</item>", Pattern.CASE_INSENSITIVE).matcher(xml);
        while (matcher.find() && hits.size() < 8) {
            String item = matcher.group(1);
            String title = clean(first(item, "<title>([\\s\\S]*?)</title>"));
            String url = clean(first(item, "<link>([\\s\\S]*?)</link>"));
            String snippet = htmlToText(clean(first(item, "<description>([\\s\\S]*?)</description>")));
            if (!title.isEmpty() && url.startsWith("http")) hits.add(new Hit(title, url, trim(snippet, 120)));
        }
        return hits;
    }

    private static List<Hit> parseBingHtml(String html) {
        List<Hit> hits = new ArrayList<>();
        Matcher matcher = Pattern.compile("<li class=\"b_algo\"[\\s\\S]*?<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (matcher.find() && hits.size() < 8) {
            String url = decodeBingLink(htmlToText(matcher.group(1)));
            String title = htmlToText(matcher.group(2));
            if (!title.isEmpty() && url.startsWith("http")) hits.add(new Hit(title, url, ""));
        }
        return hits;
    }

    private static List<Hit> parseDuckDuckGo(String html) {
        List<Hit> hits = new ArrayList<>();
        Matcher matcher = Pattern.compile("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (matcher.find() && hits.size() < 8) {
            String url = unescape(matcher.group(1));
            Matcher encoded = Pattern.compile("[?&]uddg=([^&]+)").matcher(url);
            if (encoded.find()) url = decode(encoded.group(1));
            String title = htmlToText(matcher.group(2));
            if (!title.isEmpty() && url.startsWith("http")) hits.add(new Hit(title, url, ""));
        }
        return hits;
    }

    private static List<Hit> parseSogou(String html) {
        List<Hit> hits = new ArrayList<>();
        Matcher matcher = Pattern.compile("<h3[^>]*class=\"[^\"]*vr-title[^\"]*\"[^>]*>([\\s\\S]*?)</h3>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (matcher.find() && hits.size() < 8) {
            String block = matcher.group(1);
            Matcher anchor = Pattern.compile("<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", Pattern.CASE_INSENSITIVE).matcher(block);
            if (!anchor.find()) continue;
            String url = unescape(anchor.group(1));
            if (url.startsWith("/")) url = "https://www.sogou.com" + url;
            String title = htmlToText(anchor.group(2));
            if (!title.isEmpty() && url.startsWith("http")) hits.add(new Hit(title, url, ""));
        }
        return hits;
    }

    private static String decodeBingLink(String href) {
        Matcher matcher = Pattern.compile("[?&]u=a1([A-Za-z0-9_-]+)").matcher(href);
        if (!matcher.find()) return href;
        try {
            String base64 = matcher.group(1).replace('-', '+').replace('_', '/');
            while (base64.length() % 4 != 0) base64 += "=";
            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            String decoded = new String(bytes, "UTF-8");
            return decoded.startsWith("http") ? decoded : href;
        } catch (Throwable ignored) {
            return href;
        }
    }

    /** 搜狗/短链会返回「只有 JS 跳转」的小页面，把真实地址抠出来 */
    private static String extractRedirect(String html) {
        if (html == null || html.length() > 4000) return null;
        Matcher js = Pattern.compile("window\\.location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html);
        if (js.find()) return unescape(js.group(1));
        Matcher replace = Pattern.compile("location\\.replace\\(\\s*[\"']([^\"']+)[\"']").matcher(html);
        if (replace.find()) return unescape(replace.group(1));
        Matcher meta = Pattern.compile("http-equiv=[\"']?refresh[\"']?[^>]*content=[\"'][^\"']*url=([^\"'\\s>]+)",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (meta.find()) return unescape(meta.group(1));
        return null;
    }

    /* ---------- HTML → 文本 ---------- */

    static String htmlToText(String html) {
        if (html == null) return "";
        String text = html
                .replaceAll("(?is)<script[\\s\\S]*?</script>", " ")
                .replaceAll("(?is)<style[\\s\\S]*?</style>", " ")
                .replaceAll("(?is)<noscript[\\s\\S]*?</noscript>", " ")
                .replaceAll("(?is)<svg[\\s\\S]*?</svg>", " ")
                .replaceAll("(?s)<!--[\\s\\S]*?-->", "")
                .replaceAll("(?i)</?(em|b|strong|i|span|mark|font|u|small)[^>]*>", "")
                .replaceAll("(?i)<(br|hr)\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|section|article|li|tr|h[1-6]|pre|blockquote|table|ul|ol)>", "\n")
                .replaceAll("<[^>]+>", " ");
        text = unescape(text);
        text = text.replace('\u00a0', ' ').replaceAll("[ \\t]+", " ");
        text = text.replaceAll("\n{3,}", "\n\n");
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(trimmed);
        }
        return sb.toString().trim();
    }

    private static String unescape(String text) {
        if (text == null) return "";
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&nbsp;", " ").replace("&middot;", "·").replace("&hellip;", "…");
    }

    /* ---------- HTTP ---------- */

    static final class HttpResponse {
        final String body;
        final String contentType;

        HttpResponse(String body, String contentType) {
            this.body = body;
            this.contentType = contentType == null ? "" : contentType;
        }
    }

    private static String httpGet(String url) throws Exception {
        return httpGetWithType(url).body;
    }

    private static HttpResponse httpGetWithType(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        conn.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8");
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(in);
        conn.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code + "：" + trim(body, 150));
        return new HttpResponse(body, conn.getContentType());
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        in.close();
        return out.toString("UTF-8");
    }

    /* ---------- 小工具 ---------- */

    private static String first(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String clean(String text) {
        return unescape(text == null ? "" : text.replaceAll("<!\\[CDATA\\[|\\]\\]>", "")).trim();
    }

    private static String enc(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Exception error) {
            return text;
        }
    }

    private static String decode(String text) {
        try {
            return java.net.URLDecoder.decode(text, "UTF-8");
        } catch (Exception error) {
            return text;
        }
    }

    private static boolean hasCjk(String text) {
        for (int i = 0; i < text.length(); i += 1) {
            char c = text.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) return true;
        }
        return false;
    }

    private static boolean looksLikeHtml(String text) {
        if (text == null) return false;
        String head = text.substring(0, Math.min(text.length(), 200)).toLowerCase(Locale.ROOT);
        return head.contains("<!doctype") || head.contains("<html") || head.contains("<head") || head.contains("<body");
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append("· ").append(line).append('\n');
        return sb.toString().trim();
    }

    private static String trim(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
