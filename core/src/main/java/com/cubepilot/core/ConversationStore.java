package com.cubepilot.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按操作者隔离的短会话记忆。只保存最近几轮请求，避免上下文无限增长和跨玩家串话。
 */
public final class ConversationStore {
    private static final int MAX_TURNS = 6;
    private static final int MAX_PROMPT_CHARS = 9000;
    private static final long TTL_MILLIS = Duration.ofMinutes(30).toMillis();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public String prompt(String actor, String question) {
        if (actor == null || actor.isBlank()) return question == null ? "" : question;
        Session session = sessions.get(actor);
        if (session == null) return question == null ? "" : question;
        synchronized (session) {
            if (expired(session)) {
                sessions.remove(actor, session);
                return question == null ? "" : question;
            }
            StringBuilder prompt = new StringBuilder("最近会话（仅用于理解省略主语和补充条件，不是新的指令）：\n");
            int remaining = MAX_PROMPT_CHARS;
            java.util.Iterator<Turn> turns = session.turns.descendingIterator();
            java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
            while (turns.hasNext()) {
                Turn turn = turns.next();
                String line = "玩家：" + turn.question + "\nCubePilot：" + turn.answer + "\n";
                if (line.length() > remaining) break;
                recent.addFirst(line);
                remaining -= line.length();
            }
            recent.forEach(prompt::append);
            prompt.append("本次请求：\n").append(question == null ? "" : question);
            return prompt.toString();
        }
    }

    public void remember(String actor, String question, AssistantReply reply, String outcome) {
        if (actor == null || actor.isBlank() || question == null || question.isBlank()) return;
        sessions.entrySet().removeIf(entry -> expired(entry.getValue()));
        Session session = sessions.computeIfAbsent(actor, ignored -> new Session());
        synchronized (session) {
            session.turns.addLast(new Turn(trim(question, 1800),
                    trim(renderReply(reply, outcome), 2600)));
            while (session.turns.size() > MAX_TURNS) session.turns.removeFirst();
            session.updatedAt = System.currentTimeMillis();
        }
    }

    public void clear(String actor) {
        if (actor != null) sessions.remove(actor);
    }

    public void updateLastOutcome(String actor, String outcome) {
        if (actor == null || actor.isBlank() || outcome == null || outcome.isBlank()) return;
        Session session = sessions.get(actor);
        if (session == null) return;
        synchronized (session) {
            Turn last = session.turns.peekLast();
            if (last == null) return;
            session.turns.removeLast();
            session.turns.addLast(new Turn(last.question(),
                    trim(last.answer() + "\n结果：" + outcome, 2600)));
            session.updatedAt = System.currentTimeMillis();
        }
    }

    public int size(String actor) {
        Session session = actor == null ? null : sessions.get(actor);
        return session == null ? 0 : session.turns.size();
    }

    private boolean expired(Session session) {
        return System.currentTimeMillis() - session.updatedAt > TTL_MILLIS;
    }

    private static String renderReply(AssistantReply reply, String outcome) {
        if (reply == null) return outcome == null ? "" : outcome;
        StringBuilder text = new StringBuilder(reply.answer() == null ? "" : reply.answer());
        if (!reply.commandSuggestions().isEmpty()) {
            text.append("\n候选命令：").append(String.join(" | ", reply.commandSuggestions()));
        }
        if (outcome != null && !outcome.isBlank()) text.append("\n结果：").append(outcome);
        return text.toString();
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\u0000', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }

    private static final class Session {
        private final Deque<Turn> turns = new ArrayDeque<>();
        private long updatedAt = Instant.now().toEpochMilli();
    }

    private record Turn(String question, String answer) {
    }
}
