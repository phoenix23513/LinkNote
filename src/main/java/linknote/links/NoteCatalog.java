package linknote.links;

import linknote.model.Note;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/** Resolve links using saved, valid notes from the current vault. */
public final class NoteCatalog {
    private final Path root;
    private final List<Note> notes;

    public NoteCatalog(Path root, List<Note> notes) {
        this.root = root;
        this.notes = List.copyOf(notes);
    }

    public enum Match { FOUND, AMBIGUOUS, MISSING }

    public record Resolution(Match match, Note note) {
    }

    public Resolution resolve(String target) {
        List<Note> matches = target.contains("/") ? matchPath(target) :
                notes.stream().filter(note -> note.title().equals(target)).toList();
        return switch (matches.size()) {
            case 0 -> new Resolution(Match.MISSING, null);
            case 1 -> new Resolution(Match.FOUND, matches.getFirst());
            default -> new Resolution(Match.AMBIGUOUS, null);
        };
    }

    private List<Note> matchPath(String target) {
        try {
            Path relative = Path.of(target + ".md");
            if (relative.getRoot() != null) {
                return List.of();
            }
            Path path = root.resolve(relative).normalize();
            if (!path.startsWith(root)) {
                return List.of();
            }
            return notes.stream().filter(note -> note.path().equals(path)).toList();
        } catch (InvalidPathException exception) {
            return List.of();
        }
    }
}
