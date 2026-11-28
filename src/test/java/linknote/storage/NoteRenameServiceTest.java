package linknote.storage;

import linknote.links.LinkIndex;
import linknote.links.NoteCatalog;
import linknote.ui.MarkdownPreview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NoteRenameServiceTest {
    @TempDir
    Path vault;

    private final MarkdownPreview markdown = new MarkdownPreview();

    @Test
    void renamesPlainMarkdownPreservesIdentityAndUpdatesOnlyResolvedReferences() throws IOException {
        Path target = write("旧标题.md", "# 正文标题\r\n\r\n自链 [[旧标题]]\r\n");
        String before = "\uFEFF---\r\ncustom: 留下 [[旧标题]]\r\n---\r\n\r\n"
                + "🌿 正文 [[旧标题]] 和 [[./旧标题]]\r\n\r\n"
                + "`[[旧标题]]`、\\[[旧标题]]、[[旧标题|别名]]\r\n\r\n"
                + "```md\r\n[[旧标题]]\r\n```\r\n\r\n    [[旧标题]]\r\n";
        Path source = write("source.md", before);
        var repository = new MarkdownNoteRepository(vault);
        var original = repository.read(target);
        var service = service(repository);
        var plan = service.prepare(original, "新标题", "renamed-note");
        assertEquals(Set.of(source.getFileName(), target.getFileName()),
                Set.copyOf(plan.affectedFiles().stream().map(Path::getFileName).toList()));
        assertEquals(before, Files.readString(source));

        service.commit(plan);

        assertFalse(Files.exists(target));
        var restarted = new MarkdownNoteRepository(vault);
        var renamed = restarted.read(vault.resolve("renamed-note.md"));
        assertEquals(original.id(), renamed.id());
        assertEquals("新标题", renamed.title());
        assertEquals("# 正文标题\n\n自链 [[./renamed-note]]\n", renamed.body());
        assertEquals(before.replace("正文 [[旧标题]] 和 [[./旧标题]]", "正文 [[./renamed-note]] 和 [[./renamed-note]]"),
                Files.readString(source));
        var notes = restarted.scan().notes();
        assertEquals(2, notes.size(), "隐藏的重命名备份不参与笔记扫描");
        var index = new LinkIndex(new NoteCatalog(restarted.root(), notes), notes, markdown::wikiReferences);
        assertEquals(Set.of(renamed.id(), restarted.read(source).id()),
                Set.copyOf(index.backlinks(renamed).stream().map(note -> note.id()).toList()));
        assertEquals(3, index.referencesTo(renamed).size());
        assertTrue(service.pendingOperations().isEmpty());
        try (var operations = Files.list(vault.resolve(".linknote/rename_operations"))) {
            Path operation = operations.findFirst().orElseThrow();
            assertTrue(Files.isRegularFile(operation.resolve("complete")));
            assertEquals(before, Files.readString(operation.resolve("0.md")));
        }
    }

    @Test
    void rejectsCollisionsAndChangesMadeAfterConfirmationWithoutTouchingFiles() throws IOException {
        Path target = write("target.md", "正文");
        Path source = write("source.md", "前言\n[[target]]\n");
        var repository = new MarkdownNoteRepository(vault);
        var original = repository.read(target);
        var service = service(repository);
        assertThrows(IOException.class, () -> service.prepare(original, "新笔记", "source"));
        assertThrows(IOException.class, () -> service.prepare(original, "source", "new-name"));
        assertThrows(IOException.class, () -> service.prepare(original, "新笔记", "TARGET"));
        assertThrows(IOException.class, () -> service.prepare(original, "新笔记", "../new"));
        assertThrows(IOException.class, () -> service.prepare(original, "新笔记", "CON"));

        var plan = service.prepare(original, "新笔记", "new-name");
        String external = "外部插入\n" + Files.readString(source);
        Files.writeString(source, external);
        IOException conflict = assertThrows(IOException.class, () -> service.commit(plan));
        assertTrue(conflict.getMessage().contains("source.md"));
        assertFalse(Files.exists(vault.resolve("new-name.md")));
        assertEquals("正文", Files.readString(target));
        assertEquals(external, Files.readString(source));
    }

    @Test
    void updatesExplicitPathsWithoutClaimingAmbiguousTitleLinks() throws IOException {
        Path target = write("a.md", "---\ntitle: 同名\ncustom: 保留\n---\n原正文\n");
        write("b.md", "---\ntitle: 同名\n---\n另一个笔记\n");
        Path source = write("source.md", "[[同名]] 和 [[./a]]\n");
        var repository = new MarkdownNoteRepository(vault);
        var service = service(repository);
        var plan = service.prepare(repository.read(target), "精确标题", "new-a");
        assertTrue(plan.notice().contains("同名"));
        service.commit(plan);
        assertEquals("[[同名]] 和 [[./new-a]]\n", Files.readString(source));
        assertTrue(repository.read(vault.resolve("new-a.md")).yaml().contains("custom: 保留"));
    }

    @Test
    void changesOnlyTheTitleAndRetainsPaperMetadataAndFilename() throws IOException {
        var id = UUID.randomUUID();
        Path paper = write("paper.md", """
                ---
                schema_version: 1
                type: paper
                id: '%s'
                title: Paper
                authors: [Example Author]
                doi: 10.0000/example
                ---
                # 阅读笔记
                """.formatted(id));
        Path source = write("source.md", "[[Paper]] 和 [[./paper]]");
        var repository = new MarkdownNoteRepository(vault);
        var service = service(repository);
        service.commit(service.prepare(repository.read(paper), "论文阅读", "paper"));
        var renamed = repository.read(paper);
        assertEquals(id, renamed.id());
        assertEquals("论文阅读", renamed.title());
        assertTrue(renamed.yaml().contains("type: paper"));
        assertTrue(renamed.yaml().contains("Example Author"));
        assertTrue(renamed.yaml().contains("doi: 10.0000/example"));
        assertEquals("[[./paper]] 和 [[./paper]]", Files.readString(source));
    }

    @Test
    void verifiesEachWriteAndRollsBackEarlierStepsAfterAnExternalEdit() throws IOException {
        Path first = write("first.md", "[[old]]");
        Path second = write("second.md", "[[old]]\n第二篇");
        var repository = new MarkdownNoteRepository(vault);
        var firstChange = change(repository, first, first, "[[./new]]");
        var secondChange = change(repository, second, second, "[[./new]]\n第二篇");
        var journal = RenameJournal.begin(repository, List.of(firstChange, secondChange));
        journal.apply(0, firstChange.contents());
        Files.writeString(second, "外部加入一行\n[[old]]\n第二篇");
        assertThrows(IOException.class, () -> journal.apply(1, secondChange.contents()));

        var restarted = service(new MarkdownNoteRepository(vault));
        assertEquals(1, restarted.pendingOperations().size());
        restarted.recoverPending();
        assertEquals("[[old]]", Files.readString(first));
        assertEquals("外部加入一行\n[[old]]\n第二篇", Files.readString(second));
        assertTrue(restarted.pendingOperations().isEmpty());
    }

    @Test
    void recoversOnlyItsOwnVersionAndRetainsBackupsWhenTheOldPathIsOccupied() throws IOException {
        Path source = write("source.md", "[[old]]");
        Path original = write("old.md", "原始正文");
        Path destination = vault.resolve("new.md");
        var repository = new MarkdownNoteRepository(vault);
        var sourceChange = change(repository, source, source, "[[./new]]");
        var movedChange = change(repository, original, destination, "重命名内容");
        var journal = RenameJournal.begin(repository, List.of(sourceChange, movedChange));
        journal.apply(0, sourceChange.contents());
        journal.apply(1, movedChange.contents());
        assertFalse(Files.exists(original));
        Files.writeString(original, "外部文件占用原路径");

        var restarted = service(new MarkdownNoteRepository(vault));
        var pending = restarted.pendingOperations().getFirst();
        IOException conflict = assertThrows(IOException.class, restarted::recoverPending);
        assertTrue(conflict.getMessage().contains(pending.toString()));
        assertEquals("[[old]]", Files.readString(source));
        assertEquals("外部文件占用原路径", Files.readString(original));
        assertEquals("重命名内容", Files.readString(destination));
        assertEquals("原始正文", Files.readString(pending.resolve("1.md")));

        Files.move(original, vault.resolve("external.md"));
        restarted.recoverPending();
        assertEquals("原始正文", Files.readString(original));
        assertFalse(Files.exists(destination));
        assertEquals("外部文件占用原路径", Files.readString(vault.resolve("external.md")));
        assertTrue(restarted.pendingOperations().isEmpty());
    }

    @Test
    void leavesAnExternallyEditedReferenceAndItsBackupIntactDuringRecovery() throws IOException {
        Path source = write("source.md", "[[old]]");
        var repository = new MarkdownNoteRepository(vault);
        var change = change(repository, source, source, "[[./new]]");
        var journal = RenameJournal.begin(repository, List.of(change));
        journal.apply(0, change.contents());
        Files.writeString(source, "保留外部改动\n[[./new]]");

        var restarted = service(new MarkdownNoteRepository(vault));
        Path pending = restarted.pendingOperations().getFirst();
        assertThrows(IOException.class, restarted::recoverPending);
        assertEquals("保留外部改动\n[[./new]]", Files.readString(source));
        assertEquals("[[old]]", Files.readString(pending.resolve("0.md")));
        assertEquals(List.of(pending), restarted.pendingOperations());
    }

    private NoteRenameService service(MarkdownNoteRepository repository) {
        return new NoteRenameService(repository, markdown::wikiReferences);
    }

    private Path write(String filename, String text) throws IOException {
        return Files.writeString(vault.resolve(filename), text);
    }

    private RenameJournal.Change change(MarkdownNoteRepository repository, Path source, Path destination, String body) throws IOException {
        return new RenameJournal.Change(source, destination, repository.read(source).fingerprint(), body.getBytes(StandardCharsets.UTF_8));
    }
}
