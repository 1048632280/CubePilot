package com.cubepilot.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;

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
        try {
            return sendNormal(systemPrompt, userPrompt)
                    .thenApply(response -> parseReply(response.body()));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    public CompletableFuture<AgentIntent> classify(String question) {
        String system = PromptTemplates.CLASSIFIER;
        String user = "管理员请求：\n" + (question == null ? "" : question);
        return sendNormal(system, user, 512).thenApply(response -> parseIntent(response.body()));
    }

    /**
     * 请求 SSE 流式响应。回调只接收 answer 字段的文字增量，绝不接收命令字段或 JSON 标记。
     */
    public CompletableFuture<AssistantReply> askStreaming(String systemPrompt, String userPrompt,
            Consumer<String> answerConsumer) {
        try {
            return sendStreaming(systemPrompt, userPrompt).thenCompose(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                if (config.streamingFallback()) {
                    return ask(systemPrompt, userPrompt);
                }
                return CompletableFuture.failedFuture(httpError(response.statusCode()));
            }
            return CubePilotTasks.supplyAsync(() -> readStreamingResponse(response.body(), answerConsumer));
            });
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletableFuture<HttpResponse<String>> sendNormal(String systemPrompt, String userPrompt) {
        return sendNormal(systemPrompt, userPrompt, 2048);
    }

    private CompletableFuture<HttpResponse<String>> sendNormal(String systemPrompt, String userPrompt,
            int maxTokens) {
        HttpRequest request;
        try {
            request = buildRequest(systemPrompt, userPrompt, false, maxTokens);
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

    private CompletableFuture<StringResponse> sendStreaming(String systemPrompt, String userPrompt) {
        HttpRequest request;
        try {
            request = buildRequest(systemPrompt, userPrompt, true, 2048);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> new StringResponse(response.statusCode(), response.body()));
    }

    private HttpRequest buildRequest(String systemPrompt, String userPrompt, boolean streaming, int maxTokens) {
        String key = config.apiKey();
        if (key.isBlank()) {
            throw new IllegalStateException(
                    "尚未配置 API Key：请设置 CUBEPILOT_API_KEY，或在 config/cubepilot/cubepilot.toml 中填写 api.key");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", config.model());
        payload.addProperty("temperature", 0.2);
        payload.addProperty("max_tokens", Math.max(16, Math.min(maxTokens, 4096)));
        payload.addProperty("stream", streaming);
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
            if (choices == null || choices.isEmpty()) return AgentIntent.ASK;
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            if (message == null) return AgentIntent.ASK;
            AgentIntent intent = parseIntentText(contentToText(message.get("content")));
            if (intent != null) return intent;
            intent = parseIntentText(contentToText(message.get("reasoning_content")));
            if (intent != null) return intent;
            intent = parseIntentText(contentToText(choice.get("text")));
            return intent == null ? AgentIntent.ASK : intent;
        } catch (RuntimeException exception) {
            return AgentIntent.ASK;
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

    private static AssistantReply readStreamingResponse(InputStream input, Consumer<String> answerConsumer) {
        StringBuilder content = new StringBuilder();
        AnswerExtractor extractor = new AnswerExtractor(answerConsumer);
        try (InputStream stream = input;
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            boolean done = false;
            while (!done && (line = reader.readLine()) != null) {
                String eventLine = line.trim();
                if (!eventLine.startsWith("data:")) {
                    continue;
                }
                String data = eventLine.substring(5).trim();
                if (data.equals("[DONE]")) {
                    done = true;
                    continue;
                }
                JsonObject event = JsonParser.parseString(data).getAsJsonObject();
                JsonArray choices = event.getAsJsonArray("choices");
                if (choices == null || choices.isEmpty()) {
                    continue;
                }
                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject delta = choice.getAsJsonObject("delta");
                JsonElement piece = delta == null ? null : delta.get("content");
                if (piece != null && piece.isJsonPrimitive() && piece.getAsJsonPrimitive().isString()) {
                    String text = piece.getAsString();
                    content.append(text);
                    extractor.accept(text);
                }
            }
            if (content.length() == 0) {
                throw new IOException("模型接口没有返回流式内容");
            }
            return parseStructuredContent(content.toString());
        } catch (java.util.concurrent.CompletionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new java.util.concurrent.CompletionException(new IOException(
                    "模型接口流式响应格式无效，请检查兼容 API 的 SSE 支持", exception));
        }
    }

    private static void closeQuietly(InputStream input) {
        try {
            if (input != null) input.close();
        } catch (IOException ignored) {
            // 关闭响应流失败不应覆盖原始 API 错误。
        }
    }

    private record StringResponse(int statusCode, InputStream body) {
    }

    /** 增量识别 JSON answer 字符串，避免把 JSON 语法和命令字段显示到聊天栏。 */
    static final class AnswerExtractor {
        private final Consumer<String> consumer;
        private final StringBuilder raw = new StringBuilder();
        private final StringBuilder unicode = new StringBuilder();
        private int scanIndex;
        private int openingQuote = -1;
        private boolean inString;
        private boolean escaped;
        private boolean unicodeEscape;
        private boolean finished;

        AnswerExtractor(Consumer<String> consumer) {
            this.consumer = consumer;
        }

        void accept(String piece) {
            if (finished || piece == null || piece.isEmpty()) return;
            raw.append(piece);
            if (openingQuote < 0) {
                int key = findAnswerKey();
                if (key < 0) return;
                int colon = raw.indexOf(":", key + 8);
                if (colon < 0) return;
                int quote = colon + 1;
                while (quote < raw.length() && Character.isWhitespace(raw.charAt(quote))) quote++;
                if (quote >= raw.length() || raw.charAt(quote) != '\"') return;
                openingQuote = quote;
                scanIndex = quote + 1;
                inString = true;
            }
            StringBuilder visible = new StringBuilder();
            while (scanIndex < raw.length() && inString) {
                char current = raw.charAt(scanIndex++);
                if (unicodeEscape) {
                    if (Character.digit(current, 16) < 0) {
                        throw new IllegalArgumentException("answer 包含无效 Unicode 转义");
                    }
                    unicode.append(current);
                    if (unicode.length() == 4) {
                        visible.append((char) Integer.parseInt(unicode.toString(), 16));
                        unicode.setLength(0);
                        unicodeEscape = false;
                    }
                    continue;
                }
                if (escaped) {
                    escaped = false;
                    if (current == 'u') {
                        unicode.setLength(0);
                        unicodeEscape = true;
                    } else {
                        visible.append(switch (current) {
                            case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                            case 'b' -> '\b'; case 'f' -> '\f'; default -> current;
                        });
                    }
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '\"') {
                    inString = false;
                    finished = true;
                } else {
                    visible.append(current);
                }
            }
            if (visible.length() > 0) consumer.accept(visible.toString());
        }

        private int findAnswerKey() {
            int search = 0;
            while (search < raw.length()) {
                int key = raw.indexOf("\"answer\"", search);
                if (key < 0) return -1;
                int before = key - 1;
                while (before >= 0 && Character.isWhitespace(raw.charAt(before))) before--;
                if (before >= 0 && (raw.charAt(before) == '{' || raw.charAt(before) == ',')) {
                    return key;
                }
                search = key + 8;
            }
            return -1;
        }
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
