package linknote;

import linknote.links.LinkIndex;
import linknote.links.NoteCatalog;
import linknote.model.Note;
import linknote.ui.MarkdownPreview;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinkIndexTest {
    @Test
    void derivesDirectedEdgesAndBacklinksFromTheSameResolvedReferences() {
        Path root = Path.of("graph-vault").toAbsolutePath();
        var source = new Note(UUID.randomUUID(), "来源", root.resolve("source.md"), "", """
                [[目标]] [[目标]] [[来源]] [[不存在]] [[同名]]
                `[[仅在代码里]]`

                ```java
                [[仅在代码里]]
                ```
                """, "");
        var target = new Note(UUID.randomUUID(), "目标", root.resolve("target.md"), "", "[[来源]]", "");
        var codeTarget = new Note(UUID.randomUUID(), "仅在代码里", root.resolve("code.md"), "", "", "");
        var duplicateA = new Note(UUID.randomUUID(), "同名", root.resolve("same-a.md"), "", "", "");
        var duplicateB = new Note(UUID.randomUUID(), "同名", root.resolve("same-b.md"), "", "", "");
        var notes = List.of(source, target, codeTarget, duplicateA, duplicateB);
        var index = new LinkIndex(new NoteCatalog(root, notes), notes, new MarkdownPreview()::wikiReferences);

        var expected = Set.of(new LinkIndex.Edge(source.id(), target.id()), new LinkIndex.Edge(target.id(), source.id()),
                new LinkIndex.Edge(source.id(), source.id()));
        assertEquals(expected.size(), index.edges().size());
        assertEquals(expected, Set.copyOf(index.edges()));
        assertEquals(List.of(source), index.backlinks(target));
        assertEquals(Set.of(source, target), Set.copyOf(index.backlinks(source)));
    }
}
