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
                    "尚未配置 API Key：请设置 CUBEPILOT_API_KEY，或在 config/cubepilot/cubepilot.properties 中填写 api.key"));
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
        String content = contentElement.isJsonPrimitive() ? contentElement.getAsString() : contentElement.toString();
        return parseStructuredContent(content);
    }

    private static AssistantReply parseStructuredContent(String content) {
        String candidate = content.trim();
        if (candidate.startsWith("```")) {
            int firstLine = candidate.indexOf('\n');
            int closing = candidate.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                candidate = candidate.substring(firstLine + 1, closing).trim();
            }
        }
        try {
            JsonObject json = JsonParser.parseString(candidate).getAsJsonObject();
            String answer = json.has("answer") ? json.get("answer").getAsString() : "（AI 未提供文字说明）";
            JsonArray suggestions = json.has("command_suggestions") && json.get("command_suggestions").isJsonArray()
                    ? json.getAsJsonArray("command_suggestions") : new JsonArray();
            java.util.List<String> commands = suggestions.asList().stream()
                    .filter(JsonElement::isJsonPrimitive)
                    .map(JsonElement::getAsString)
                    .limit(3)
                    .toList();
            return new AssistantReply(answer, commands);
        } catch (RuntimeException ignored) {
            return new AssistantReply(content, java.util.List.of());
        }
    }
}
