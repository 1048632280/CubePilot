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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class OpenAiClient {
    private static final Pattern INTENT_JSON = Pattern.compile(
            "(?i)\"intent\"\\s*:\\s*\"(ASK|CONFIG|ANALYZE|COMMAND)\"");
    private static final Pattern TRAILING_INTENT = Pattern.compile(
            "(?i)(?:^|\\s)(ASK|CONFIG|ANALYZE|COMMAND)[.!]?\\s*$");
    private static final Pattern COMMAND_LINE = Pattern.compile(
            "(?i)^/?(?:give|item|summon|tp|teleport|effect|enchant|experience|xp|time|weather|gamemode|setblock|fill|playsound|particle|function|execute)\\b.*");
    private final CubePilotConfig config;
    private final HttpClient httpClient;

    public OpenAiClient(CubePilotConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .executor(CubePilotTasks.executor())
                .build();
    }

    public CompletableFuture<AssistantReply> ask(String systemPrompt, String userPrompt) {
        return ask(systemPrompt, userPrompt, ignored -> { });
    }

    public CompletableFuture<AssistantReply> ask(String systemPrompt, String userPrompt,
            Consumer<String> rawResponseConsumer) {
        try {
            return sendNormal(systemPrompt, userPrompt)
                    .thenApply(response -> {
                        notifyRawResponse(rawResponseConsumer, response.body());
                        return parseReply(response.body());
                    });
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public CompletableFuture<AgentIntent> classify(String question) {
        return classify(question, ignored -> { });
    }

    public CompletableFuture<AgentIntent> classify(String question, Consumer<String> rawResponseConsumer) {
        String system = PromptTemplates.CLASSIFIER;
        String user = "管理员请求：\n" + (question == null ? "" : question);
        return sendNormal(system, user, 512).thenApply(response -> {
            notifyRawResponse(rawResponseConsumer, response.body());
            return parseIntent(response.body());
        });
    }

    /** 由独立模型请求逐项核验候选命令是否保留原请求约束。 */
    public CompletableFuture<java.util.List<CommandVerification>> verifyCommand(String question,
            java.util.List<String> commands,
            String attachedContext, String minecraftVersion) {
        return verifyCommand(question, commands, attachedContext, minecraftVersion, ignored -> { });
    }

    public CompletableFuture<java.util.List<CommandVerification>> verifyCommand(String question,
            java.util.List<String> commands, String attachedContext, String minecraftVersion,
            Consumer<String> rawResponseConsumer) {
        JsonObject request = new JsonObject();
        request.addProperty("request", question == null ? "" : question);
        JsonArray candidates = new JsonArray();
        commands.forEach(candidates::add);
        request.add("candidate_commands", candidates);
        request.addProperty("server_context", attachedContext == null ? "" : attachedContext);
        return sendNormal(PromptTemplates.commandVerifierFor(minecraftVersion), request.toString(), 768)
                .thenApply(response -> {
                    notifyRawResponse(rawResponseConsumer, response.body());
                    java.util.List<CommandVerification> results = parseCommandVerification(response.body());
                    if (results.size() != commands.size()) {
                        throw new java.util.concurrent.CompletionException(
                                new IOException("命令完整性核验未覆盖全部候选"));
                    }
                    for (int index = 0; index < commands.size(); index++) {
                        if (!commands.get(index).equals(results.get(index).command())) {
                            throw new java.util.concurrent.CompletionException(
                                    new IOException("命令完整性核验候选不匹配"));
                        }
                    }
                    return results;
                });
    }

    private static void notifyRawResponse(Consumer<String> consumer, String body) {
        if (consumer == null) return;
        try {
            consumer.accept(visibleResponse(body));
        } catch (RuntimeException ignored) {
            // 审计回调不能影响模型响应解析和主请求结果。
        }
    }

    /** 只保留模型可见内容，避免把兼容接口返回的隐藏推理字段写入请求链日志。 */
    private static String visibleResponse(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonElement root = JsonParser.parseString(body);
            stripHiddenReasoning(root);
            return root.toString();
        } catch (RuntimeException ignored) {
            return body;
        }
    }

    private static void stripHiddenReasoning(JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            object.remove("reasoning_content");
            object.remove("reasoning");
            object.remove("thinking");
            for (JsonElement child : object.values()) stripHiddenReasoning(child);
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) stripHiddenReasoning(child);
        }
    }

    static java.util.List<CommandVerification> parseCommandVerification(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) throw new IllegalArgumentException("无核验结果");
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            String content = contentToText(message == null ? null : message.get("content"));
            JsonObject result = JsonParser.parseString(extractJsonObject(content)).getAsJsonObject();
            JsonArray results = result.getAsJsonArray("results");
            if (results == null || results.isEmpty()) throw new IllegalArgumentException("缺少逐条核验结果");
            java.util.ArrayList<CommandVerification> verifications = new java.util.ArrayList<>();
            for (JsonElement element : results) {
                if (!element.isJsonObject()) throw new IllegalArgumentException("核验项格式无效");
                JsonObject item = element.getAsJsonObject();
                JsonElement commandValue = item.get("command");
                JsonElement completeValue = item.get("complete");
                if (commandValue == null || !commandValue.isJsonPrimitive()
                        || !commandValue.getAsJsonPrimitive().isString()
                        || completeValue == null || !completeValue.isJsonPrimitive()
                        || !completeValue.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("核验项缺少 command 或 complete");
                }
                java.util.ArrayList<String> missing = new java.util.ArrayList<>();
                JsonElement missingValue = item.get("missing_requirements");
                if (missingValue != null && missingValue.isJsonArray()) {
                    for (JsonElement missingItem : missingValue.getAsJsonArray()) {
                        if (missingItem.isJsonPrimitive() && missingItem.getAsJsonPrimitive().isString()) {
                            missing.add(missingItem.getAsString());
                        }
                    }
                }
                JsonElement reasonValue = item.get("reason");
                String reason = reasonValue != null && reasonValue.isJsonPrimitive()
                        ? reasonValue.getAsString() : "";
                verifications.add(new CommandVerification(commandValue.getAsString(),
                        completeValue.getAsBoolean(), missing, reason));
            }
            return java.util.List.copyOf(verifications);
        } catch (RuntimeException exception) {
            throw new java.util.concurrent.CompletionException(
                    new IOException("命令完整性核验失败，候选不会执行", exception));
        }
    }

    private CompletableFuture<HttpResponse<String>> sendNormal(String systemPrompt, String userPrompt) {
        return sendNormal(systemPrompt, userPrompt, 2048);
    }

    private CompletableFuture<HttpResponse<String>> sendNormal(String systemPrompt, String userPrompt,
            int maxTokens) {
        HttpRequest request;
        try {
            request = buildRequest(systemPrompt, userPrompt, maxTokens);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new java.util.concurrent.CompletionException(httpError(response.statusCode()));
                    }
                    return response;
                });
    }

    private HttpRequest buildRequest(String systemPrompt, String userPrompt, int maxTokens) {
        String key = config.apiKey();
        if (key.isBlank()) {
            throw new IllegalStateException(
                    "尚未配置 API Key：请设置 CUBEPILOT_API_KEY，或在 config/cubepilot/cubepilot.toml 中填写 api.key");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", config.model());
        payload.addProperty("temperature", 0.2);
        payload.addProperty("max_tokens", Math.max(16, Math.min(maxTokens, 4096)));
        payload.addProperty("stream", false);
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

        return request;
    }

    private static IOException httpError(int statusCode) {
        return new IOException("模型接口返回 HTTP " + statusCode + "；请检查地址、模型名和服务端日志");
    }

    static AgentIntent parseIntent(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) return AgentIntent.UNKNOWN;
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            if (message == null) return AgentIntent.UNKNOWN;
            AgentIntent intent = parseIntentText(contentToText(message.get("content")));
            if (intent != null) return intent;
            intent = parseIntentText(contentToText(message.get("reasoning_content")));
            if (intent != null) return intent;
            intent = parseIntentText(contentToText(choice.get("text")));
            return intent == null ? AgentIntent.UNKNOWN : intent;
        } catch (RuntimeException exception) {
            return AgentIntent.UNKNOWN;
        }
    }

    private static AgentIntent parseIntentText(String text) {
        if (text == null || text.isBlank()) return null;
        String cleaned = text.replaceAll("(?s)<think>.*?</think>", "").replace("```", "").trim();
        try {
            JsonElement value = JsonParser.parseString(extractJsonObject(cleaned)).getAsJsonObject().get("intent");
            if (value != null && value.isJsonPrimitive()) {
                AgentIntent parsed = intentFrom(value.getAsString());
                if (parsed != null) return parsed;
            }
        } catch (RuntimeException ignored) {
            // 兼容直接输出标签的分类器。
        }
        Matcher matcher = INTENT_JSON.matcher(cleaned);
        if (matcher.find()) return intentFrom(matcher.group(1));
        String[] lines = cleaned.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].trim().replaceAll("^[^A-Za-z]+|[^A-Za-z]+$", "");
            AgentIntent parsed = intentFrom(line);
            if (parsed != null) return parsed;
            Matcher trailing = TRAILING_INTENT.matcher(line);
            if (trailing.find()) return intentFrom(trailing.group(1));
            int colon = line.lastIndexOf(':');
            if (colon >= 0) {
                parsed = intentFrom(line.substring(colon + 1));
                if (parsed != null) return parsed;
            }
        }
        return null;
    }

    private static AgentIntent intentFrom(String value) {
        if (value == null) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ASK" -> AgentIntent.ASK;
            case "CONFIG" -> AgentIntent.CONFIG;
            case "ANALYZE" -> AgentIntent.ANALYZE;
            case "COMMAND" -> AgentIntent.COMMAND;
            default -> null;
        };
    }

    private static String contentToText(JsonElement content) {
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        if (content.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (!part.isJsonObject()) continue;
                JsonElement value = part.getAsJsonObject().get("text");
                if (value != null && value.isJsonPrimitive()) text.append(value.getAsString());
            }
            if (!text.isEmpty()) return text.toString();
        }
        return content.toString();
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
                JsonElement reasoning = message == null ? null : message.get("reasoning_content");
                if (reasoning != null && !reasoning.isJsonNull() && !contentToText(reasoning).isBlank()) {
                    return new AssistantReply("模型未返回可用答案，请重试。", java.util.List.of());
                }
                throw new java.util.concurrent.CompletionException(new IOException("模型接口返回了空内容"));
            }
            String content = contentToText(contentElement);
            return parseStructuredContent(content);
        } catch (java.util.concurrent.CompletionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new java.util.concurrent.CompletionException(new IOException(
                    "模型接口响应格式无效，请检查兼容 API 返回格式", exception));
        }
    }

    /** 兼容 JSON、Markdown 包裹及纯文本回复；候选命令仍交给宿主安全策略校验。 */
    static AssistantReply parseStructuredContent(String content) {
        try {
            if (content == null || content.isBlank()) throw new IllegalArgumentException("模型返回为空");
            String candidate = extractJsonObject(content);
            JsonElement parsed = JsonParser.parseString(candidate);
            if (!parsed.isJsonObject()) return parsePlainReply(content);
            JsonObject json = parsed.getAsJsonObject();
            String answer = firstString(json, "answer", "response", "text");
            JsonArray suggestions = firstArray(json, "command_suggestions", "commands", "suggestions", "command");
            java.util.ArrayList<String> commands = new java.util.ArrayList<>();
            for (JsonElement suggestion : suggestions == null ? new JsonArray() : suggestions) {
                if (!suggestion.isJsonPrimitive() || !suggestion.getAsJsonPrimitive().isString()) continue;
                String command = suggestion.getAsString().trim();
                if (!command.isEmpty()) {
                    commands.add(command);
                }
                if (commands.size() == 3) {
                    break;
                }
            }
            boolean clarificationRequired = json.has("clarification_required")
                    && json.get("clarification_required").isJsonPrimitive()
                    && json.getAsJsonPrimitive("clarification_required").isBoolean()
                    && json.get("clarification_required").getAsBoolean();
            if (clarificationRequired && !commands.isEmpty()) {
                throw new IllegalArgumentException("澄清回复不能包含命令候选");
            }
            if (commands.isEmpty() && !clarificationRequired && !answer.isEmpty()) {
                AssistantReply answerWithCommand = parsePlainReply(answer);
                if (!answerWithCommand.commandSuggestions().isEmpty()) {
                    answer = answerWithCommand.answer();
                    commands.addAll(answerWithCommand.commandSuggestions());
                }
            }
            if (answer.isEmpty() && commands.isEmpty()) return parsePlainReply(content);
            return new AssistantReply(answer, commands, clarificationRequired);
        } catch (RuntimeException exception) {
            if (content != null && content.stripLeading().startsWith("{")) {
                throw new java.util.concurrent.CompletionException(new IOException(
                        "模型回复不是完整 JSON，请重试", exception));
            }
            return parsePlainReply(content);
        }
    }

    private static String firstString(JsonObject json, String... keys) {
        for (String key : keys) {
            JsonElement value = json.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                return value.getAsString().trim();
            }
        }
        return "";
    }

    private static JsonArray firstArray(JsonObject json, String... keys) {
        for (String key : keys) {
            JsonElement value = json.get(key);
            if (value != null && value.isJsonArray()) return value.getAsJsonArray();
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                JsonArray array = new JsonArray();
                array.add(value.getAsString());
                return array;
            }
        }
        return null;
    }

    private static AssistantReply parsePlainReply(String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) return new AssistantReply("模型未返回内容。", java.util.List.of());
        if (text.startsWith("{") || text.startsWith("[")) {
            return new AssistantReply("模型回复格式异常，请重试。", java.util.List.of());
        }
        java.util.ArrayList<String> commands = new java.util.ArrayList<>();
        StringBuilder answer = new StringBuilder();
        boolean inCodeBlock = false;
        StringBuilder code = new StringBuilder();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("```")) {
                if (inCodeBlock) {
                    addPlainCandidate(code.toString(), commands, answer);
                    code.setLength(0);
                }
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if (inCodeBlock) {
                if (!code.isEmpty()) code.append('\n');
                code.append(line);
                continue;
            }
            addPlainCandidate(line, commands, answer);
            if (commands.size() >= 3) break;
        }
        if (inCodeBlock && !code.isEmpty()) addPlainCandidate(code.toString(), commands, answer);
        return new AssistantReply(answer.toString().trim(), commands);
    }

    private static void addPlainCandidate(String line, java.util.List<String> commands, StringBuilder answer) {
        String value = line.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("json")) return;
        if (value.startsWith("{") || value.startsWith("[")) return;
        if (value.regionMatches(true, 0, "命令：", 0, 3)
                || value.regionMatches(true, 0, "命令:", 0, 3)
                || value.regionMatches(true, 0, "command:", 0, 8)) {
            int separator = value.indexOf(':');
            if (separator < 0) separator = value.indexOf('：');
            value = value.substring(separator + 1).trim();
        }
        value = value.replaceAll("^`+|`+$", "").trim();
        String command = value.replaceAll("\\s*\\R\\s*", " ");
        if (COMMAND_LINE.matcher(command).matches()) {
            commands.add(command.startsWith("/") ? command.substring(1) : command);
        } else {
            if (!answer.isEmpty()) answer.append('\n');
            answer.append(value);
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
