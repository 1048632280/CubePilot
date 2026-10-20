package com.cubepilot.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class OpenAiClient {
    private final CubePilotConfig config;
    private final HttpClient httpClient;

    public OpenAiClient(CubePilotConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
    }

    public CompletableFuture<AssistantReply> ask(String systemPrompt, String userPrompt) {
        String key = config.apiKey();
        if (key.isBlank()) {
            return CompletableFuture.failedFuture(new IOException(
                    "尚未配置 API Key：请设置 CUBEPILOT_API_KEY，或在 config/cubepilot/cubepilot.toml 中填写 api.key"));
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", config.model());
        payload.addProperty("temperature", 0.2);
        payload.addProperty("max_tokens", 900);
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        payload.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.endpoint()))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new java.util.concurrent.CompletionException(new IOException(
                                "模型接口返回 HTTP " + response.statusCode() + "；请检查地址、模型名和服务端日志"));
                    }
                    return parseReply(response.body());
                });
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static AssistantReply parseReply(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new java.util.concurrent.CompletionException(new IOException("模型接口没有返回内容"));
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            JsonElement contentElement = message == null ? null : message.get("content");
            if (contentElement == null || contentElement.isJsonNull()) {
                throw new java.util.concurrent.CompletionException(new IOException("模型接口返回了空内容"));
            }
            String content = contentElement.isJsonPrimitive()
                    ? contentElement.getAsString() : contentElement.toString();
            return parseStructuredContent(content);
        } catch (java.util.concurrent.CompletionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new java.util.concurrent.CompletionException(new IOException(
                    "模型接口响应格式无效，请检查兼容 API 返回格式", exception));
        }
    }

    /**
     * 解析模型的结构化回复。模型偶尔会包裹 Markdown 或附加一句说明，
     * 因此先提取完整 JSON 对象；解析失败必须让调用方显示明确错误，不能把原始 JSON 当答案。
     */
    static AssistantReply parseStructuredContent(String content) {
        try {
            String candidate = extractJsonObject(content);
            JsonElement parsed = JsonParser.parseString(candidate);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("顶层不是 JSON 对象");
            }
            JsonObject json = parsed.getAsJsonObject();
            JsonElement answerElement = json.get("answer");
            if (answerElement == null || !answerElement.isJsonPrimitive()
                    || !answerElement.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("answer 不是字符串");
            }
            String answer = answerElement.getAsString().trim();
            if (answer.isEmpty()) {
                answer = "（AI 未提供文字说明）";
            }

            JsonArray suggestions = json.has("command_suggestions")
                    && json.get("command_suggestions").isJsonArray()
                    ? json.getAsJsonArray("command_suggestions") : new JsonArray();
            java.util.ArrayList<String> commands = new java.util.ArrayList<>();
            for (JsonElement suggestion : suggestions) {
                if (!suggestion.isJsonPrimitive()
                        || !suggestion.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("command_suggestions 中包含非字符串");
                }
                String command = suggestion.getAsString().trim();
                if (!command.isEmpty()) {
                    commands.add(command);
                }
                if (commands.size() == 3) {
                    break;
                }
            }
            return new AssistantReply(answer, commands);
        } catch (RuntimeException exception) {
            throw new java.util.concurrent.CompletionException(new IOException(
                    "AI 返回格式无效：未能解析 answer 或 command_suggestions，请重试", exception));
        }
    }

    private static String extractJsonObject(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("模型返回为空");
        }
        String candidate = content.trim();
        if (candidate.startsWith("\ufeff")) {
            candidate = candidate.substring(1).trim();
        }
        int start = candidate.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("未找到 JSON 对象");
        }
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = start; index < candidate.length(); index++) {
            char current = candidate.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
                continue;
            }
            if (current == '"') {
                quoted = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return candidate.substring(start, index + 1);
            }
        }
        throw new IllegalArgumentException("JSON 对象未闭合");
    }
}
