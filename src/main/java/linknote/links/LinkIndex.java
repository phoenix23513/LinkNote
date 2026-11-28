package linknote.links;

import linknote.model.Note;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** All relationships are derived from saved Markdown in the current folder. */
public final class LinkIndex {
    public record Edge(UUID sourceId, UUID targetId) {
    }

    public record Reference(Note source, WikiReference link) {
    }

    private final Map<UUID, List<Reference>> incoming = new HashMap<>();
    private final Map<String, LinkedHashSet<Note>> titleReferences = new HashMap<>();

    public LinkIndex(NoteCatalog catalog, List<Note> notes, Function<String, List<WikiReference>> parseLinks) {
        var resolutions = new HashMap<String, NoteCatalog.Resolution>();
        for (Note source : notes) {
            for (WikiReference reference : parseLinks.apply(source.body())) {
                String target = reference.target();
                if (!target.contains("/")) {
                    titleReferences.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(source);
                }
                var resolution = resolutions.computeIfAbsent(target, catalog::resolve);
                if (resolution.match() == NoteCatalog.Match.FOUND) {
                    incoming.computeIfAbsent(resolution.note().id(), ignored -> new ArrayList<>())
                            .add(new Reference(source, reference));
                }
            }
        }
    }

    public List<Note> backlinks(Note target) {
        return incoming.getOrDefault(target.id(), List.of()).stream().map(Reference::source).distinct().toList();
    }

    public List<Reference> referencesTo(Note target) {
        return List.copyOf(incoming.getOrDefault(target.id(), List.of()));
    }

    public List<Edge> edges() {
        return incoming.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(reference -> new Edge(reference.source().id(), entry.getKey())))
                .distinct().toList();
    }

    public List<Note> referencesToTitle(String title) {
        var references = titleReferences.get(title);
        return references == null ? List.of() : List.copyOf(references);
    }
}
