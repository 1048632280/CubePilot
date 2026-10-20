package com.cubepilot.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommandPolicy {
    private static final int MAX_COMMAND_LENGTH = 2048;
    private static final Set<String> PERMANENT_AUTO_DENY = Set.of(
            "stop", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "whitelist",
            "execute", "function", "schedule", "reload", "debug", "jfr", "perf", "publish",
            "transfer", "fill", "clone", "setblock", "kill", "summon", "data", "forceload",
            "place", "spreadplayers", "cp", "cpilot", "cpa");
    private static final Set<String> DANGEROUS_ITEM_IDS = Set.of(
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:structure_block", "minecraft:jigsaw", "command_block", "chain_command_block",
            "repeating_command_block", "structure_block", "jigsaw");
    private static final Set<String> DANGEROUS_NBT_KEYS = Set.of(
            "command", "commandblockoutput", "command_block_output", "entitytag", "passengers",
            "entity_data", "block_entity_data", "minecraft:entity_data", "minecraft:block_entity_data");
    private static final Pattern ALL_PLAYERS = Pattern.compile("(?i)@a(?=\\b|\\[)");
    private static final Pattern ALL_ENTITIES = Pattern.compile("(?i)@e(?=\\b|\\[)");
    private static final Pattern OTHER_SELECTOR = Pattern.compile("(?i)@[pra](?=\\b|\\[)");
    private static final Pattern MULTI_SELECTOR = Pattern.compile("(?i)@[ae](?:\\[([^]]*)])?");

    private CommandPolicy() {
    }

    /**
     * 同时判定自动执行、手动建议和加入白名单按钮。永久禁令只影响自动执行，
     * 不会拦截玩家自行输入命令，也不会单独阻止手动建议。
     */
    public static Decision evaluate(String rawCommand, CubePilotConfig config,
            String actorName, List<String> onlinePlayerNames) {
        String command = normalizeForRule(rawCommand);
        if (command == null || !hasBalancedPayload(command)) {
            return Decision.rejected("命令包含链式分隔符、换行、无效字符或不完整的数据结构");
        }

        List<String> words = topLevelWords(command);
        if (words.isEmpty()) {
            return Decision.rejected("命令为空");
        }
        String root = words.get(0).toLowerCase(Locale.ROOT);
        if (!root.matches("[a-z0-9_.-]+(?::[a-z0-9_./-]+)?")) {
            return Decision.rejected("一级命令名称无效");
        }
        String rootBase = root.substring(root.lastIndexOf(':') + 1);

        boolean permanentDenied = PERMANENT_AUTO_DENY.contains(rootBase)
                || isDangerousAutomaticPayload(command, rootBase);
        boolean configurableDenied = matches(config.autoDeny(), command, words);
        boolean allowlisted = matches(config.autoAllow(), command, words);
        List<CubePilotConfig.SafetyRule> matchedSafety = matchedSafetyRules(
                command, actorName, onlinePlayerNames);
        boolean autoSafe = permits(matchedSafety, config, Channel.AUTO);
        boolean manualSafe = permits(matchedSafety, config, Channel.MANUAL);

        boolean automatic = allowlisted && !permanentDenied && !configurableDenied && autoSafe;
        boolean offerAdd = !allowlisted && !permanentDenied && !configurableDenied && autoSafe
                && config.offerAddToAutoAllow();
        String reason;
        if (automatic) {
            reason = "命中自动执行白名单";
        } else if (permanentDenied) {
            reason = "命中内置永久自动执行禁令";
        } else if (configurableDenied) {
            reason = "命中可调整自动执行禁止名单";
        } else if (!allowlisted) {
            reason = "未命中自动执行白名单";
        } else {
            reason = "命中自动执行安全限制";
        }
        return new Decision("/" + command, automatic, manualSafe, offerAdd, reason,
                List.copyOf(matchedSafety));
    }

    /** 标准化配置规则和模型命令；不会改变引号内部的空格。 */
    public static String normalizeForRule(String rawCommand) {
        if (rawCommand == null) {
            return null;
        }
        String command = rawCommand.trim();
        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty() || command.length() > MAX_COMMAND_LENGTH || command.contains("\n")
                || command.contains("\r") || command.contains(";") || command.indexOf('\0') >= 0) {
            return null;
        }

        StringBuilder normalized = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        boolean escaped = false;
        boolean whitespace = false;
        for (int index = 0; index < command.length(); index++) {
            char current = command.charAt(index);
            if (quoted) {
                normalized.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quoted = false;
                }
                continue;
            }
            if (current == '\"' || current == '\'') {
                if (whitespace && normalized.length() > 0) {
                    normalized.append(' ');
                }
                whitespace = false;
                quoted = true;
                quote = current;
                normalized.append(current);
            } else if (Character.isWhitespace(current)) {
                whitespace = true;
            } else {
                if (whitespace && normalized.length() > 0) {
                    normalized.append(' ');
                }
                whitespace = false;
                normalized.append(current);
            }
        }
        return quoted ? null : normalized.toString();
    }

    /** 按命令顶层空格拆分，不拆开引号、NBT 或组件中的内容。 */
    public static List<String> topLevelWords(String command) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        boolean escaped = false;
        int braces = 0;
        int brackets = 0;
        for (int index = 0; index < command.length(); index++) {
            char character = command.charAt(index);
            if (quoted) {
                current.append(character);
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == quote) {
                    quoted = false;
                }
                continue;
            }
            if (character == '\"' || character == '\'') {
                quoted = true;
                quote = character;
                current.append(character);
            } else if (character == '{') {
                braces++;
                current.append(character);
            } else if (character == '}') {
                braces--;
                current.append(character);
            } else if (character == '[') {
                brackets++;
                current.append(character);
            } else if (character == ']') {
                brackets--;
                current.append(character);
            } else if (Character.isWhitespace(character) && braces == 0 && brackets == 0) {
                if (current.length() > 0) {
                    words.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (current.length() > 0) {
            words.add(current.toString());
        }
        return List.copyOf(words);
    }

    private static boolean matches(CubePilotConfig.CommandRules rules,
            String command, List<String> words) {
        if (rules.exact().contains(command)) {
            return true;
        }
        String root = words.get(0).toLowerCase(Locale.ROOT);
        if (rules.roots().contains(root)) {
            return true;
        }
        return words.size() >= 2
                && rules.subcommands().contains(root + " " + words.get(1).toLowerCase(Locale.ROOT));
    }

    private static List<CubePilotConfig.SafetyRule> matchedSafetyRules(String command,
            String actorName, List<String> onlinePlayerNames) {
        List<CubePilotConfig.SafetyRule> matched = new ArrayList<>();
        if (ALL_PLAYERS.matcher(command).find()) {
            matched.add(CubePilotConfig.SafetyRule.ALL_PLAYERS_SELECTOR);
        }
        if (ALL_ENTITIES.matcher(command).find()) {
            matched.add(CubePilotConfig.SafetyRule.ALL_ENTITIES_SELECTOR);
        }
        if (hasComplexData(command)) {
            matched.add(CubePilotConfig.SafetyRule.COMPLEX_NBT);
        }
        if (hasMultipleTargets(command)) {
            matched.add(CubePilotConfig.SafetyRule.MULTIPLE_TARGETS);
        }
        if (targetsOtherPlayers(command, actorName, onlinePlayerNames)) {
            matched.add(CubePilotConfig.SafetyRule.OTHER_PLAYERS);
        }
        return matched;
    }

    private static boolean permits(List<CubePilotConfig.SafetyRule> matched,
            CubePilotConfig config, Channel channel) {
        for (CubePilotConfig.SafetyRule rule : matched) {
            int mode = config.safetyMode(rule);
            if ((channel == Channel.AUTO && (mode == 1 || mode == 3))
                    || (channel == Channel.MANUAL && (mode == 2 || mode == 3))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasComplexData(String command) {
        if (command.indexOf('{') >= 0 || command.indexOf('}') >= 0) {
            return true;
        }
        String withoutSelectors = command.replaceAll("@[pares](?:\\[[^]]*])?", "");
        return withoutSelectors.indexOf('[') >= 0 || withoutSelectors.indexOf(']') >= 0;
    }

    private static boolean hasMultipleTargets(String command) {
        Matcher matcher = MULTI_SELECTOR.matcher(command);
        while (matcher.find()) {
            String options = matcher.group(1);
            if (options == null || !hasSingleTargetLimit(options)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSingleTargetLimit(String options) {
        for (String option : options.replace(" ", "").split(",")) {
            if (option.equalsIgnoreCase("limit=1")) {
                return true;
            }
        }
        return false;
    }

    private static boolean targetsOtherPlayers(String command, String actorName,
            List<String> onlinePlayerNames) {
        if (OTHER_SELECTOR.matcher(command).find()) {
            return true;
        }
        Set<String> otherNames = new HashSet<>();
        for (String playerName : onlinePlayerNames == null ? List.<String>of() : onlinePlayerNames) {
            if (playerName != null && (actorName == null || !playerName.equalsIgnoreCase(actorName))) {
                otherNames.add(playerName.toLowerCase(Locale.ROOT));
            }
        }
        for (String word : topLevelWords(command)) {
            if (otherNames.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDangerousAutomaticPayload(String command, String root) {
        if (!root.equals("give")) {
            return false;
        }
        String lower = command.toLowerCase(Locale.ROOT);
        for (String itemId : DANGEROUS_ITEM_IDS) {
            if (lower.contains(itemId)) {
                return true;
            }
        }
        for (String key : DANGEROUS_NBT_KEYS) {
            if (lower.matches(".*(?:[\\{\\[,\\s])" + Pattern.quote(key) + "\\s*[:=].*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBalancedPayload(String command) {
        int braces = 0;
        int brackets = 0;
        int depth = 0;
        boolean quoted = false;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < command.length(); index++) {
            char current = command.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quoted = false;
                }
                continue;
            }
            if (current == '\'' || current == '\"') {
                quoted = true;
                quote = current;
            } else if (current == '{') {
                braces++;
                depth++;
            } else if (current == '}') {
                if (--braces < 0) return false;
                depth--;
            } else if (current == '[') {
                brackets++;
                depth++;
            } else if (current == ']') {
                if (--brackets < 0) return false;
                depth--;
            }
            if (depth > 16) return false;
        }
        return !quoted && braces == 0 && brackets == 0;
    }

    private enum Channel { AUTO, MANUAL }

    public record Decision(String command, boolean automatic, boolean manualAllowed,
            boolean offerAddToAutoAllow, String reason, List<CubePilotConfig.SafetyRule> matchedSafetyRules) {
        private static Decision rejected(String reason) {
            return new Decision(null, false, false, false, reason, List.of());
        }

        public boolean valid() {
            return command != null;
        }
    }
}
