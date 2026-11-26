package linknote.links;

import linknote.model.Note;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Function;

/** All relationships are derived from saved Markdown in the current folder. */
public final class LinkIndex {
    private final Map<UUID, List<Note>> incoming = new HashMap<>();
    private final Map<String, List<Note>> titleReferences = new HashMap<>();

    public LinkIndex(NoteCatalog catalog, List<Note> notes, Function<String, List<String>> wikiTargets) {
        for (Note source : notes) {
            for (String target : wikiTargets.apply(source.body())) {
                if (!target.contains("/")) {
                    titleReferences.computeIfAbsent(target, ignored -> new ArrayList<>()).add(source);
                }
                var resolution = catalog.resolve(target);
                if (resolution.match() == NoteCatalog.Match.FOUND) {
                    var sources = incoming.computeIfAbsent(resolution.note().id(), ignored -> new ArrayList<>());
                    if (sources.isEmpty() || !sources.getLast().id().equals(source.id())) {
                        sources.add(source);
                    }
                }
            }
        }
    }

    public List<Note> backlinks(Note target) {
        return List.copyOf(incoming.getOrDefault(target.id(), List.of()));
    }

    public List<Note> referencesToTitle(String title) {
        return List.copyOf(titleReferences.getOrDefault(title, List.of()));
    }
}
