package linknote;

import linknote.model.Note;
import linknote.search.NoteSearch;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteSearchTest {
    @Test
    void matchesAllKeywordsAcrossTitleAndBodyAndRanksTitleHitsFirst() {
        Note completeTitle = note("z.md", "缓存 Cache", "学习摘要");
        Note mixed = note("b.md", "Cache", "查看缓存上下文");
        Note bothFields = note("c.md", "缓存", "缓存 CACHE 的正文");
        Note bodyOnly = note("a.md", "矩阵分块", "Cache 和缓存");
        Note partial = note("partial.md", "缓存", "只匹配其中一个词");
        var notes = List.of(bodyOnly, mixed, partial, bothFields, completeTitle);

        var results = NoteSearch.find(notes, "\u3000CaChE\t缓存\u00a0cache ");

        assertEquals(List.of(completeTitle, bothFields, mixed, bodyOnly), results.stream().map(NoteSearch.Result::note).toList());
        assertEquals("学习摘要", results.getFirst().snippet());
        assertEquals("Cache 和缓存", results.getLast().snippet());
    }

    @Test
    void searchesPunctuationLiterallyAndUsesPathToBreakTies() {
        Note first = note("a.md", "一", "# 源码\n\n" + "🌿 ".repeat(45) + "<tag> arr[0] " + "上下文".repeat(45));
        Note second = note("z.md", "二", "<tag> arr[0]");
        Note similar = note("b.md", "三", "<tag> arr0");

        var results = NoteSearch.find(List.of(second, similar, first), "ARR[0]");

        assertEquals(List.of(first, second), results.stream().map(NoteSearch.Result::note).toList());
        String snippet = results.getFirst().snippet();
        assertTrue(snippet.startsWith("…"));
        assertTrue(snippet.endsWith("…"));
        assertTrue(snippet.contains("<tag> arr[0]"));
        assertTrue(snippet.length() < 80);
        assertFalse(snippet.contains("\n"));
        assertFalse(snippet.codePoints().anyMatch(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF));
    }

    @Test
    void blankQueryPreservesTheOriginalListAndUnmatchedQueryIsEmpty() {
        var notes = List.of(note("z.md", "先显示", ""), note("a.md", "后显示", "正文"));
        var results = NoteSearch.find(notes, "\t\u3000\n");
        assertEquals(notes, results.stream().map(NoteSearch.Result::note).toList());
        assertTrue(results.stream().allMatch(result -> result.snippet().isEmpty()));
        assertTrue(NoteSearch.find(notes, "丢失关键词").isEmpty());
    }

    private Note note(String filename, String title, String body) {
        return new Note(UUID.randomUUID(), title, Path.of(filename), null, body, "");
    }
}
