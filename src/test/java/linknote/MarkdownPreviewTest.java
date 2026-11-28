package linknote;

import linknote.ui.MarkdownPreview;
import linknote.links.LinkIndex;
import linknote.links.NoteCatalog;
import linknote.model.Note;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownPreviewTest {
    private final MarkdownPreview preview = new MarkdownPreview();

    @Test
    void rendersMarkdownAndCreatesALocalLinkOnlyForPlainWikiLinks() {
        String markdown = "# 标题\n\n- 了解 [[缓存局部性]]\n\n"
                + "```text\n[[code block]]\n```\n\n"
                + "`[[inline code]]`\n\n[[缓存局部性|别名]]\n\n[[notes/cache#章节]]";
        String html = preview.render(markdown);

        assertTrue(html.contains("<h1>标题</h1>"));
        assertTrue(html.contains("<li>了解 "));
        assertTrue(html.contains("href=\"#\" data-linknote-target=\"缓存局部性\""));
        assertTrue(html.contains("<code>[[inline code]]</code>"));
        assertTrue(html.contains("[[code block]]"));
        assertTrue(html.contains("[[缓存局部性|别名]]"));
        assertEquals(1, html.split("data-linknote-target=\"", -1).length - 1);
    }

    @Test
    void indexesOnlySavedWikiLinksAndDeduplicatesPaths() {
        Path root = Path.of("vault").toAbsolutePath();
        var source = new Note(UUID.randomUUID(), "A", root.resolve("a.md"), null,
                "[[B]] [[B]] [[./b]] `[[C]]`\n\n```\n[[C]]\n```\n\n[[缺失]]", "");
        var target = new Note(UUID.randomUUID(), "B", root.resolve("b.md"), null, "", "");
        var codeOnly = new Note(UUID.randomUUID(), "C", root.resolve("c.md"), null, "", "");
        var notes = List.of(source, target, codeOnly);
        var index = new LinkIndex(new NoteCatalog(root, notes), notes, preview::wikiReferences);

        assertEquals(List.of(source), index.backlinks(target));
        assertEquals(List.of(source), index.referencesToTitle("B"));
        assertTrue(index.backlinks(codeOnly).isEmpty());
        assertEquals(List.of("B", "./b", "缺失"), preview.wikiTargets(source.body()));

        var updated = new Note(source.id(), source.title(), source.path(), null, "", "");
        var afterSave = List.of(updated, target, codeOnly);
        assertTrue(new LinkIndex(new NoteCatalog(root, afterSave), afterSave, preview::wikiReferences)
                .backlinks(target).isEmpty());
    }

    @Test
    void keepsRawHtmlAndImageRequestsOutOfPreview() {
        String html = preview.render("""
                <script>alert('unsafe')</script>

                ![示例](https://example.invalid/example.png)

                ![[图片]]
                """);

        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("https://example.invalid/example.png"));
        assertTrue(html.contains("示例"));
    }

    @Test
    void treatsWikiLinksInsideHtmlAsLiteralInBothPreviewAndIndex() {
        String markdown = """
                正常 [[A]] <CoDe>**[[B]]**</cOdE> <span><em>[[C]]</em></span> <br> [[D]] <tag/> [[E]]。

                <pre>
                [[F]]
                </pre>

                正常 [[G]]。
                """;

        assertEquals(List.of("A", "D", "E", "G"), preview.wikiTargets(markdown));
        String html = preview.render(markdown);
        assertTrue(html.contains("[[B]]"));
        assertTrue(html.contains("[[C]]"));
        assertTrue(html.contains("[[F]]"));
        assertEquals(4, html.split("data-linknote-target=\"", -1).length - 1);
    }

    @Test
    void keepsLaterMarkdownBlocksAndTableCellsOutsideUnclosedInlineHtml() {
        String markdown = """
                # 标题 <code>[[标题代码]]

                正文 [[正文链接]]。

                | 左边 | 右边 |
                | --- | --- |
                | <code>[[表格代码]] | [[表格链接]] |
                """;

        assertEquals(List.of("正文链接", "表格链接"), preview.wikiTargets(markdown));
    }
}
