package linknote.search;

import linknote.model.Note;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

public final class NoteSearch {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Comparator<RankedMatch> ORDER = Comparator
            .comparingInt(RankedMatch::titleMatches).reversed()
            .thenComparing(Comparator.comparingInt(RankedMatch::bodyMatches).reversed())
            .thenComparing(match -> match.result().note().path().toString());

    public record Result(Note note, String snippet) {
    }

    private record RankedMatch(Result result, int titleMatches, int bodyMatches) {
    }

    private NoteSearch() {
    }

    public static List<Result> find(List<Note> savedNotes, String query) {
        var keywords = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        WHITESPACE.splitAsStream(query).filter(word -> !word.isEmpty()).forEach(keywords::add);
        if (keywords.isEmpty()) {
            return savedNotes.stream().map(note -> new Result(note, "")).toList();
        }
        var patterns = keywords.stream()
                .map(word -> Pattern.compile(word, Pattern.LITERAL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                .toList();
        var matches = new ArrayList<RankedMatch>();
        for (Note note : savedNotes) {
            RankedMatch match = match(note, patterns);
            if (match != null) {
                matches.add(match);
            }
        }
        return matches.stream().sorted(ORDER).map(RankedMatch::result).toList();
    }

    private static RankedMatch match(Note note, List<Pattern> keywords) {
        int titleMatches = 0;
        int bodyMatches = 0;
        int firstHit = note.body().length();
        for (Pattern keyword : keywords) {
            boolean inTitle = keyword.matcher(note.title()).find();
            var body = keyword.matcher(note.body());
            boolean inBody = body.find();
            if (!inTitle && !inBody) {
                return null;
            }
            if (inTitle) {
                titleMatches++;
            }
            if (inBody) {
                bodyMatches++;
                firstHit = Math.min(firstHit, body.start());
            }
        }
        int anchor = bodyMatches > 0 ? firstHit : 0;
        return new RankedMatch(new Result(note, snippet(note.body(), anchor)), titleMatches, bodyMatches);
    }

    private static String snippet(String body, int anchor) {
        while (anchor < body.length() && Character.isWhitespace(body.charAt(anchor))) {
            anchor++;
        }
        if (anchor == body.length()) {
            return "";
        }
        int lineStart = body.lastIndexOf('\n', anchor - 1) + 1;
        int nextLine = body.indexOf('\n', anchor);
        int lineEnd = nextLine < 0 ? body.length() : nextLine;
        int start = Math.max(lineStart, anchor - 18);
        int end = Math.min(lineEnd, start + 72);
        if (start > lineStart && Character.isLowSurrogate(body.charAt(start))) {
            start--;
        }
        if (end < lineEnd && Character.isLowSurrogate(body.charAt(end))) {
            end++;
        }
        return (start > lineStart ? "…" : "") + body.substring(start, end).strip() + (end < lineEnd ? "…" : "");
    }
}
