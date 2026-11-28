package linknote.storage;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

final class RenameJournal {
    private static final String MANIFEST = "plan.properties";
    private static final String COMPLETE = "complete";
    private static final String ROLLED_BACK = "rolled_back";

    record Change(Path source, Path destination, String expected, byte[] contents) {
        private Step step() {
            return new Step(source, destination, expected, MarkdownNoteRepository.fingerprint(contents));
        }
    }

    private record Step(Path source, Path destination, String before, String after) {
        boolean movesFile() {
            return !source.equals(destination);
        }
    }

    private final MarkdownNoteRepository repository;
    private final Path directory;
    private final List<Step> steps;

    private RenameJournal(MarkdownNoteRepository repository, Path directory, List<Step> steps) {
        this.repository = repository;
        this.directory = directory;
        this.steps = steps;
    }

    static RenameJournal begin(MarkdownNoteRepository repository, List<Change> changes) throws IOException {
        Path directory = Files.createDirectory(operations(repository, true).resolve(UUID.randomUUID().toString()));
        var journal = new RenameJournal(repository, directory, changes.stream().map(Change::step).toList());
        var fields = new Properties();
        fields.setProperty("count", String.valueOf(changes.size()));
        for (int i = 0; i < journal.steps.size(); i++) {
            Step step = journal.steps.get(i);
            Files.write(journal.backup(i), repository.snapshotBytes(step.source(), step.before()));
            fields.setProperty(i + ".source", step.source().getFileName().toString());
            fields.setProperty(i + ".destination", step.destination().getFileName().toString());
            fields.setProperty(i + ".before", step.before());
            fields.setProperty(i + ".after", step.after());
        }
        Path pendingManifest = directory.resolve(".linknote-plan.tmp");
        try (var writer = Files.newBufferedWriter(pendingManifest)) {
            fields.store(writer, "LinkNote rename operation");
        }
        Files.move(pendingManifest, directory.resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE);
        return journal;
    }

    static List<Path> pending(MarkdownNoteRepository repository) throws IOException {
        Path operations = operations(repository, false);
        if (Files.notExists(operations, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var directories = Files.list(operations)) {
            return directories.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().matches("[0-9a-f-]{36}"))
                    .filter(path -> Files.exists(path.resolve(MANIFEST), LinkOption.NOFOLLOW_LINKS)
                            && Files.notExists(path.resolve(COMPLETE), LinkOption.NOFOLLOW_LINKS)
                            && Files.notExists(path.resolve(ROLLED_BACK), LinkOption.NOFOLLOW_LINKS))
                    .sorted().toList();
        }
    }

    static RenameJournal load(MarkdownNoteRepository repository, Path directory) throws IOException {
        Path manifest = directory.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("无法读取重命名记录：" + manifest);
        }
        var fields = new Properties();
        try (var reader = Files.newBufferedReader(manifest)) {
            fields.load(reader);
            int count = Integer.parseInt(fields.getProperty("count"));
            if (count < 1 || count > fields.size() / 4) {
                throw new IllegalArgumentException("invalid step count");
            }
            var steps = new ArrayList<Step>();
            for (int i = 0; i < count; i++) {
                steps.add(new Step(notePath(repository, fields.getProperty(i + ".source")),
                        notePath(repository, fields.getProperty(i + ".destination")),
                        hash(fields.getProperty(i + ".before")), hash(fields.getProperty(i + ".after"))));
            }
            return new RenameJournal(repository, directory, List.copyOf(steps));
        } catch (IllegalArgumentException exception) {
            throw new IOException("重命名记录格式错误：" + manifest, exception);
        }
    }

    void apply(int index, byte[] contents) throws IOException {
        Step step = steps.get(index);
        if (!MarkdownNoteRepository.fingerprint(contents).equals(step.after())) {
            throw new IOException("重命名内容与操作记录不一致：" + step.source().getFileName());
        }
        repository.snapshotBytes(step.source(), step.before());
        if (step.movesFile()) {
            MarkdownNoteRepository.requireVacant(step.destination());
        }
        // Persist intent before the first write so recovery can handle an interrupted step.
        Files.createFile(started(index));
        if (step.movesFile()) {
            try {
                repository.publishContents(step.destination(), contents);
            } catch (FileAlreadyExistsException occupied) {
                Files.delete(started(index));
                throw occupied;
            }
            repository.deleteUnchanged(step.source(), step.before());
        } else {
            try {
                repository.replaceContents(step.source(), step.before(), contents);
            } catch (MarkdownNoteRepository.ModifiedNoteException changedBeforeWrite) {
                Files.delete(started(index));
                throw changedBeforeWrite;
            }
        }
        Files.createFile(directory.resolve(index + ".applied"));
    }

    void complete() throws IOException {
        Files.createFile(directory.resolve(COMPLETE));
    }

    void rollback() throws IOException {
        var conflicts = new ArrayList<String>();
        for (int i = steps.size() - 1; i >= 0; i--) {
            Path restored = directory.resolve(i + ".restored");
            if (Files.notExists(started(i), LinkOption.NOFOLLOW_LINKS) || Files.exists(restored, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Step step = steps.get(i);
            try {
                restore(step, backup(i));
                Files.createFile(restored);
            } catch (IOException failure) {
                conflicts.add(step.source().getFileName() + " · " + failure.getMessage());
            }
        }
        if (!conflicts.isEmpty()) {
            throw new IOException("以下文件需要人工核对：\n" + String.join("\n", conflicts)
                    + "\n\n操作记录和备份：\n" + directory);
        }
        Files.createFile(directory.resolve(ROLLED_BACK));
    }

    private void restore(Step step, Path backup) throws IOException {
        String original = fileHash(step.source());
        if (!step.movesFile()) {
            if (!step.before().equals(original)) {
                requireState(step.after().equals(original));
                repository.replaceContents(step.source(), step.after(), backupBytes(step, backup));
            }
            return;
        }

        String destination = fileHash(step.destination());
        if (step.before().equals(original) && destination == null) {
            return;
        }
        requireState(step.after().equals(destination) && (original == null || step.before().equals(original)));
        if (original == null) {
            byte[] bytes = backupBytes(step, backup);
            repository.snapshotBytes(step.destination(), step.after());
            repository.publishContents(step.source(), bytes);
        } else {
            repository.snapshotBytes(step.source(), step.before());
        }
        repository.deleteUnchanged(step.destination(), step.after());
    }

    private static byte[] backupBytes(Step step, Path backup) throws IOException {
        if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("备份不可读：" + backup.getFileName());
        }
        byte[] bytes = MarkdownNoteRepository.readBytes(backup);
        if (!MarkdownNoteRepository.fingerprint(bytes).equals(step.before())) {
            throw new IOException("备份指纹不符：" + backup.getFileName());
        }
        return bytes;
    }

    private String fileHash(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return repository.read(path).fingerprint();
    }

    private static void requireState(boolean matches) throws IOException {
        if (!matches) {
            throw new IOException("路径被占用或文件已在外部更改，保留了现有文件。");
        }
    }

    private Path backup(int index) {
        return directory.resolve(index + ".md");
    }

    private Path started(int index) {
        return directory.resolve(index + ".started");
    }

    private static Path operations(MarkdownNoteRepository repository, boolean create) throws IOException {
        Path internal = repository.root().resolve(".linknote");
        Path operations = internal.resolve("rename_operations");
        for (Path directory : List.of(internal, operations)) {
            if (create && Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(directory);
            }
            if (!Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("重命名记录目录不可用：" + directory);
            }
        }
        return operations;
    }

    private static Path notePath(MarkdownNoteRepository repository, String name) {
        if (name == null || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || !name.toLowerCase(Locale.ROOT).endsWith(".md") || name.startsWith(".linknote-save-")) {
            throw new IllegalArgumentException("invalid note path in journal");
        }
        return repository.root().resolve(name);
    }

    private static String hash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid fingerprint in journal");
        }
        return value;
    }
}
