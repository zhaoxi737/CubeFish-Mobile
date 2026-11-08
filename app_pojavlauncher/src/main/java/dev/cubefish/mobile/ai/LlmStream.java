package dev.cubefish.mobile.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 极简 OpenAI 兼容流式客户端（只为一个 POST /chat/completions 服务）。 */
public final class LlmStream {
    public interface Callback {
        void onDelta(String text);

        void onDone(String content, List<ToolCall> toolCalls);

        void onError(String message);
    }

    public static final class ToolCall {
        public final String id;
        public final String name;
        public final String arguments;

        ToolCall(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }

    private HttpURLConnection connection;
    private volatile boolean aborted;

    public void abort() {
        aborted = true;
        HttpURLConnection c = connection;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    public void chat(String baseUrl, String apiKey, String model, JSONArray messages, JSONArray tools, Callback callback) {
        StringBuilder content = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        StringBuilder[] callArgs = new StringBuilder[8];
        String[] callIds = new String[8];
        String[] callNames = new String[8];

        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.2);
            body.put("stream", true);
            if (tools != null && tools.length() > 0) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }

            String endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(180000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                callback.onError("HTTP " + status + "：没有返回内容");
                return;
            }
            if (status >= 400) {
                String detail = readAll(stream);
                callback.onError("HTTP " + status + "：" + trim(detail, 300));
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (aborted) {
                    callback.onError("已停止");
                    return;
                }
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                JSONObject chunk;
                try {
                    chunk = new JSONObject(payload);
                } catch (Exception ignored) {
                    continue;
                }
                raw.append(payload);
                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                if (delta == null) continue;
                String piece = delta.optString("content", "");
                if (!piece.isEmpty()) {
                    content.append(piece);
                    callback.onDelta(piece);
                }
                JSONArray toolDeltas = delta.optJSONArray("tool_calls");
                if (toolDeltas != null) {
                    for (int i = 0; i < toolDeltas.length(); i++) {
                        JSONObject call = toolDeltas.getJSONObject(i);
                        int index = call.optInt("index", 0);
                        if (index < 0 || index >= callArgs.length) continue;
                        if (callArgs[index] == null) callArgs[index] = new StringBuilder();
                        if (call.has("id")) callIds[index] = call.optString("id");
                        JSONObject fn = call.optJSONObject("function");
                        if (fn != null) {
                            if (fn.has("name")) callNames[index] = fn.optString("name");
                            if (fn.has("arguments")) callArgs[index].append(fn.optString("arguments"));
                        }
                    }
                }
            }

            for (int i = 0; i < callArgs.length; i++) {
                if (callNames[i] == null || callNames[i].isEmpty()) continue;
                String args = callArgs[i] == null ? "{}" : callArgs[i].toString();
                String id = callIds[i] == null || callIds[i].isEmpty() ? "call_" + i : callIds[i];
                calls.add(new ToolCall(id, callNames[i], args.isEmpty() ? "{}" : args));
            }
            callback.onDone(content.toString(), calls);
        } catch (Exception error) {
            if (aborted) {
                callback.onError("已停止");
            } else {
                callback.onError(describe(error));
            }
        } finally {
            connection = null;
        }
    }

    private static String readAll(InputStream stream) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String lower = message.toLowerCase();
        if (lower.contains("timed out") || lower.contains("timeout")) return "连接模型超时（" + message + "）";
        if (lower.contains("unable to resolve host") || lower.contains("dnserror")) return "域名解析失败：" + message;
        if (lower.contains("ssl") || lower.contains("certificate")) return "HTTPS 证书错误：" + message;
        return message;
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
