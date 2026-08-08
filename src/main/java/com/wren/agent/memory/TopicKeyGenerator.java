package com.wren.agent.memory;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class TopicKeyGenerator {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9\\s]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final String[] STOP_WORDS = {
            "the", "a", "an", "and", "or", "of", "in", "on", "at", "to",
            "for", "with", "by", "from", "is", "are", "was", "be", "that",
            "this", "it", "its", "as", "via", "using", "new", "large", "language"
    };

    /**
     * Produces a stable, lowercased, alphanumeric slug from the candidate title.
     * Near-duplicate titles produce the same or very similar keys for dedup purposes.
     */
    public String generate(String title) {
        if (title == null || title.isBlank()) {
            return "unknown-topic";
        }

        // Normalize unicode
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        // Lowercase
        String lower = normalized.toLowerCase(Locale.ROOT);

        // Remove non-alphanumeric (keep spaces)
        String clean = NON_ALPHANUMERIC.matcher(lower).replaceAll(" ");

        // Remove stop words
        String[] words = WHITESPACE.split(clean.trim());
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!isStopWord(word) && word.length() > 2) {
                if (sb.length() > 0) sb.append("-");
                sb.append(word);
            }
        }

        // Cap length and return
        String key = sb.length() > 80 ? sb.substring(0, 80) : sb.toString();
        return key.isEmpty() ? "unknown-topic" : key;
    }

    private boolean isStopWord(String word) {
        for (String stop : STOP_WORDS) {
            if (stop.equals(word)) return true;
        }
        return false;
    }
}
