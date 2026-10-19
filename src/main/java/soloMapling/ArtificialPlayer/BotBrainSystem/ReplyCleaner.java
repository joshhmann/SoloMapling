package soloMapling.ArtificialPlayer.BotBrainSystem;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns a model's raw text into one chat line, or nothing. Thinking models leak their reasoning when the server
 * is not told to switch it off (a llama.cpp Qwen3 run spent its whole token budget on a visible
 * "Thinking Process:" block), and that must never reach the chat box.
 */
final class ReplyCleaner {

    /** MapleStory's general chat input caps a line at 70 characters. */
    static final int MAX_CHAT_LENGTH = 70;

    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern UNCLOSED_THINK = Pattern.compile("(?is)<think>.*");
    private static final Pattern THINKING_HEADER = Pattern.compile("(?i)^\\s*(thinking process|thinking|reasoning)\\s*:");
    private static final Pattern SPEAKER_PREFIX = Pattern.compile("^[A-Za-z0-9]{3,12}\\s*:\\s+");

    private ReplyCleaner() {
    }

    static Optional<String> clean(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = THINK_BLOCK.matcher(raw).replaceAll("");
        text = UNCLOSED_THINK.matcher(text).replaceAll("");
        if (THINKING_HEADER.matcher(text).find()) {
            // Reasoning with no closing tag: nothing in it is a safe chat line.
            return Optional.empty();
        }
        String line = firstNonBlankLine(text).trim();
        line = SPEAKER_PREFIX.matcher(line).replaceFirst("");
        line = stripQuotes(line).trim();
        if (line.isEmpty()) {
            return Optional.empty();
        }
        if (line.length() > MAX_CHAT_LENGTH) {
            line = clampAtWord(line);
        }
        return Optional.of(line);
    }

    /** A memory note's limit: longer than a chat line, still one thought. */
    static final int MAX_NOTE_LENGTH = 140;
    static final int MAX_NOTES = 2;
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*\u2022]|\\d+[.)])\\s*");

    /**
     * Turns a note-writing reply into at most {@link #MAX_NOTES} lines of at most {@link #MAX_NOTE_LENGTH}
     * characters, joined by newlines; empty when the model says there is nothing to keep ("NONE") or leaked only
     * reasoning.
     */
    static Optional<String> notes(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = UNCLOSED_THINK.matcher(THINK_BLOCK.matcher(raw).replaceAll("")).replaceAll("");
        if (THINKING_HEADER.matcher(text).find()) {
            return Optional.empty();
        }
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String line : text.split("\\R")) {
            String note = stripQuotes(BULLET.matcher(line).replaceFirst("").trim()).trim();
            if (note.isEmpty() || note.toUpperCase().startsWith("NONE")) {
                continue;
            }
            if (note.length() > MAX_NOTE_LENGTH) {
                int cut = note.lastIndexOf(' ', MAX_NOTE_LENGTH);
                note = note.substring(0, cut > MAX_NOTE_LENGTH / 2 ? cut : MAX_NOTE_LENGTH).trim();
            }
            kept.add(note);
            if (kept.size() == MAX_NOTES) {
                break;
            }
        }
        return kept.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", kept));
    }

    private static String firstNonBlankLine(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
    }

    private static String stripQuotes(String line) {
        if (line.length() >= 2) {
            char first = line.charAt(0);
            char last = line.charAt(line.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')
                    || (first == '“' && last == '”')) {
                return line.substring(1, line.length() - 1);
            }
        }
        return line;
    }

    /** Cuts an over-long line at the last sentence break that keeps most of it, else at the last word. */
    private static String clampAtWord(String line) {
        for (int i = MAX_CHAT_LENGTH - 1; i > MAX_CHAT_LENGTH / 2; i--) {
            char c = line.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == ',') {
                return line.substring(0, c == ',' ? i : i + 1).trim();
            }
        }
        int cut = line.lastIndexOf(' ', MAX_CHAT_LENGTH);
        return (cut > MAX_CHAT_LENGTH / 2 ? line.substring(0, cut) : line.substring(0, MAX_CHAT_LENGTH)).trim();
    }
}
