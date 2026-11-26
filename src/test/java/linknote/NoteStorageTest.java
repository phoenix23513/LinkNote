package linknote;

import linknote.links.NoteCatalog;
import linknote.storage.MarkdownNoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NoteStorageTest {
    @TempDir
    Path vault;

    @Test
    void savesInUtf8AndReopensWithSameIdentityAndMetadata() throws IOException {
        UUID id = UUID.randomUUID();
        Path file = create("matrix.md", id, "矩阵分块", "初始正文");
        var repository = new MarkdownNoteRepository(vault);
        var original = repository.read(file);
        var saved = repository.save(original, original.title(), "修改后的正文\n[[缓存局部性]]");
        var reopened = new MarkdownNoteRepository(vault).read(file);

        assertEquals(id, reopened.id());
        assertEquals("矩阵分块", reopened.title());
        assertEquals("修改后的正文\n[[缓存局部性]]", reopened.body());
        assertEquals(saved.fingerprint(), reopened.fingerprint());
        assertNotEquals(original.fingerprint(), reopened.fingerprint());
        assertTrue(reopened.yaml().contains("custom_data"));
        assertTrue(reopened.yaml().contains("测试"));
        assertTrue(reopened.yaml().contains("2026-11-26T10:00:00+08:00"));
        try (var entries = Files.list(vault)) {
            assertEquals(1, entries.count(), "保存后不留下临时文件");
        }
    }

    @Test
    void preservesExternalEditsAndDoesNotPretendToSave() throws IOException {
        Path file = create("matrix.md", UUID.randomUUID(), "矩阵分块", "初始正文");
        var repository = new MarkdownNoteRepository(vault);
        var original = repository.read(file);
        Files.writeString(file, Files.readString(file).replace("初始正文", "外部正文"));

        IOException error = assertThrows(IOException.class, () -> repository.save(original, original.title(), "我的草稿"));
        assertTrue(error.getMessage().contains("其他程序修改"));
        assertEquals("外部正文", repository.read(file).body());
    }

    @Test
    void ignoresBackupsAndReportsActualDuplicateIdsAndInvalidNotes() throws IOException {
        UUID id = UUID.randomUUID();
        create("live.md", id, "正常笔记", "正文");
        create(".linknote/rename_operations/op/backup.md", id, "备份", "旧正文");
        Files.writeString(vault.resolve(".linknote-save-demo.tmp"), "写入中");
        Files.writeString(vault.resolve("broken.md"), "---\nschema_version: 1\n---\n# 缺少 LinkNote 数据");
        UUID duplicate = UUID.randomUUID();
        create("duplicate-a.md", duplicate, "重复一", "正文");
        create("duplicate-b.md", duplicate, "重复二", "正文");

        var scan = new MarkdownNoteRepository(vault).scan();
        assertEquals(1, scan.notes().size());
        assertEquals(id, scan.notes().getFirst().id());
        assertEquals(3, scan.problems().size());
        assertTrue(scan.problems().stream().noneMatch(message -> message.contains("backup")));
    }

    @Test
    void resolvesTitlesAndPathsAndReportsAmbiguity() throws IOException {
        UUID target = UUID.randomUUID();
        create("notes/cache.md", target, "Cache", "正文");
        create("papers/cache.md", UUID.randomUUID(), "Cache", "正文");
        create("matrix.md", UUID.randomUUID(), "矩阵分块", "正文");
        var repository = new MarkdownNoteRepository(vault);
        var catalog = new NoteCatalog(repository.root(), repository.scan().notes());

        assertEquals(NoteCatalog.Match.FOUND, catalog.resolve("矩阵分块").match());
        assertEquals(NoteCatalog.Match.AMBIGUOUS, catalog.resolve("Cache").match());
        assertEquals(target, catalog.resolve("notes/cache").note().id());
        assertEquals(NoteCatalog.Match.FOUND, catalog.resolve("./matrix").match());
        assertEquals(NoteCatalog.Match.MISSING, catalog.resolve("../notes/cache").match());
        assertEquals(NoteCatalog.Match.MISSING, catalog.resolve("未创建").match());
    }

    @Test
    void opensPlainMarkdownPersistsTheTitleAndScansOnlyTheSelectedFolder() throws IOException {
        Path plain = vault.resolve("普通笔记.md");
        Files.writeString(plain, "# 无 YAML\n\n正文");
        create("other/child.md", UUID.randomUUID(), "子文件夹", "正文");
        var repository = new MarkdownNoteRepository(vault);

        var opened = repository.read(plain);
        assertEquals("普通笔记", opened.title());
        assertEquals("# 无 YAML\n\n正文", Files.readString(plain));
        assertEquals(1, repository.scanFolder().notes().size());
        var edited = repository.save(opened, opened.title(), "只改正文");
        assertEquals("只改正文", Files.readString(plain));

        repository.save(edited, "新标题", edited.body());
        var reopened = new MarkdownNoteRepository(vault).read(plain);
        assertEquals("新标题", reopened.title());
        assertEquals(opened.id(), reopened.id());
        assertEquals("只改正文", reopened.body());

        var first = repository.create("CON");
        var second = repository.create("CON");
        assertNotEquals(first.path(), second.path());
        assertTrue(first.path().getFileName().toString().startsWith("note_CON"));
        assertEquals("CON", repository.read(first.path()).title());
        assertNotEquals(first.id(), second.id());
    }

    private Path create(String name, UUID id, String title, String body) throws IOException {
        Path file = vault.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                ---
                schema_version: 1
                id: "%s"
                type: note
                title: "%s"
                created_at: "2026-11-26T10:00:00+08:00"
                updated_at: "2026-11-26T10:00:00+08:00"
                custom_data: [测试, 42]
                ---
                %s""".formatted(id, title, body));
        return file;
    }
}
