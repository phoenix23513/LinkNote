package linknote.storage;

import linknote.links.LinkIndex;
import linknote.links.NoteCatalog;
import linknote.links.WikiReference;
import linknote.model.Note;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

public final class NoteRenameService {
    private final MarkdownNoteRepository repository;
    private final Function<String, List<WikiReference>> parseLinks;

    public NoteRenameService(MarkdownNoteRepository repository, Function<String, List<WikiReference>> parseLinks) {
        this.repository = repository;
        this.parseLinks = parseLinks;
    }

    public static final class Plan {
        private final List<Note> snapshot;
        private final List<RenameJournal.Change> changes;
        private final Note renamed;
        private final String notice;

        private Plan(List<Note> snapshot, List<RenameJournal.Change> changes, Note renamed, String notice) {
            this.snapshot = snapshot;
            this.changes = List.copyOf(changes);
            this.renamed = renamed;
            this.notice = notice;
        }

        public Note renamed() {
            return renamed;
        }

        public List<Path> affectedFiles() {
            return changes.stream().map(RenameJournal.Change::source).toList();
        }

        public String notice() {
            return notice;
        }
    }

    public Plan prepare(Note requested, String rawTitle, String rawStem) throws IOException {
        requireRecovered();
        var snapshot = repository.scanFolder().notes();
        Note target = snapshot.stream().filter(note -> note.path().equals(requested.path()) && note.id().equals(requested.id()))
                .findFirst().orElseThrow(() -> new IOException("笔记已移动或身份改变，请重新打开文件。"));
        if (!target.fingerprint().equals(requested.fingerprint())) {
            throw new IOException("目标笔记已被其他程序修改，请重新打开文件。");
        }
        String title = rawTitle.equals(target.title()) ? rawTitle : MarkdownNoteRepository.validTitle(rawTitle);
        Path destination = repository.renameDestination(target, rawStem);
        if (title.equals(target.title()) && destination.equals(target.path())) {
            throw new IOException("请修改笔记标题或文件名。");
        }
        if (!title.equals(target.title()) && snapshot.stream().anyMatch(note -> note.title().equals(title))) {
            throw new IOException("当前目录已有这个笔记标题，请选择其他标题。");
        }

        var catalog = new NoteCatalog(repository.root(), snapshot);
        var index = new LinkIndex(catalog, snapshot, parseLinks);
        var bySource = new LinkedHashMap<Note, List<WikiReference>>();
        for (LinkIndex.Reference reference : index.referencesTo(target)) {
            bySource.computeIfAbsent(reference.source(), ignored -> new ArrayList<>()).add(reference.link());
        }
        String filename = destination.getFileName().toString();
        String replacementTarget = "./" + filename.substring(0, filename.length() - 3);
        String replacement = "[[" + replacementTarget + "]]";
        if (!bySource.isEmpty() && parseLinks.apply(replacement).stream().noneMatch(link -> link.target().equals(replacementTarget))) {
            throw new IOException("当前文件名包含双链不支持的符号。请同时输入可用的新文件名。");
        }

        var changes = new ArrayList<RenameJournal.Change>();
        List<WikiReference> selfLinks = bySource.remove(target);
        for (var entry : bySource.entrySet()) {
            Note source = entry.getKey();
            byte[] before = repository.snapshotBytes(source.path(), source.fingerprint());
            String raw = new String(before, StandardCharsets.UTF_8);
            int start = rawBodyStart(raw, source.body().length());
            String body = rewriteBody(raw.substring(start), entry.getValue(), replacement);
            byte[] content = MarkdownNoteRepository.encodeRaw(raw.substring(0, start) + body);
            if (!Arrays.equals(before, content)) {
                changes.add(new RenameJournal.Change(source.path(), source.path(), source.fingerprint(), content));
            }
        }

        byte[] before = repository.snapshotBytes(target.path(), target.fingerprint());
        String raw = new String(before, StandardCharsets.UTF_8);
        String body = raw.substring(rawBodyStart(raw, target.body().length()));
        if (selfLinks != null) {
            body = rewriteBody(body, selfLinks, replacement);
        }
        byte[] content = repository.renameContents(target, title, body);
        Note renamed = MarkdownNoteRepository.decode(destination, content);
        changes.add(new RenameJournal.Change(target.path(), destination, target.fingerprint(), content));

        String notice = "";
        if (!title.equals(target.title()) && !index.referencesToTitle(target.title()).isEmpty()
                && snapshot.stream().filter(note -> note.title().equals(target.title())).count() == 2) {
            notice = "原先因重名而无法解析的 [[" + target.title() + "]] 将指向另一篇同名笔记，原文会保留。";
        }
        return new Plan(snapshot, changes, renamed, notice);
    }

    public void commit(Plan plan) throws IOException {
        requireRecovered();
        for (RenameJournal.Change change : plan.changes) {
            repository.snapshotBytes(change.source(), change.expected());
            if (!change.source().equals(change.destination())) {
                MarkdownNoteRepository.requireVacant(change.destination());
            }
        }
        if (!repository.scanFolder().notes().equals(plan.snapshot)) {
            throw new IOException("当前目录在确认期间发生变化，请重新生成影响清单。");
        }
        var journal = RenameJournal.begin(repository, plan.changes);
        try {
            for (int i = 0; i < plan.changes.size(); i++) {
                journal.apply(i, plan.changes.get(i).contents());
            }
            journal.complete();
        } catch (IOException failure) {
            try {
                journal.rollback();
            } catch (IOException unresolved) {
                unresolved.addSuppressed(failure);
                throw unresolved;
            }
            throw new IOException("重命名已停止，并恢复了本次写入。\n" + failure.getMessage(), failure);
        }
    }

    public List<Path> pendingOperations() throws IOException {
        return RenameJournal.pending(repository);
    }

    public void recoverPending() throws IOException {
        for (Path directory : pendingOperations()) {
            RenameJournal.load(repository, directory).rollback();
        }
    }

    private void requireRecovered() throws IOException {
        if (!pendingOperations().isEmpty()) {
            throw new IOException("发现未完成的重命名，请重新打开笔记目录并先恢复。");
        }
    }

    private static int rawBodyStart(String raw, int normalizedLength) {
        // The saved snapshot's body uses LF. Keep the original line endings when rewriting it.
        int position = raw.length();
        for (int i = 0; i < normalizedLength; i++) {
            if (raw.charAt(--position) == '\n' && position > 0 && raw.charAt(position - 1) == '\r') {
                position--;
            }
        }
        return position;
    }

    private static String rewriteBody(String raw, List<WikiReference> links, String replacement) throws IOException {
        var result = new StringBuilder(raw.length());
        int normalized = 0;
        int position = 0;
        int copied = 0;
        for (WikiReference link : links) {
            while (normalized < link.start() && position < raw.length()) {
                if (raw.charAt(position++) == '\r' && position < raw.length() && raw.charAt(position) == '\n') {
                    position++;
                }
                normalized++;
            }
            int end = position + link.end() - link.start();
            if (normalized != link.start() || !raw.startsWith("[[", position) || end > raw.length()
                    || !raw.startsWith("]]", end - 2) || !raw.substring(position + 2, end - 2).strip().equals(link.target())) {
                throw new IOException("链接位置与保存的正文不一致，请重新生成重命名清单。");
            }
            result.append(raw, copied, position).append(replacement);
            position = copied = end;
            normalized = link.end();
        }
        return result.append(raw, copied, raw.length()).toString();
    }
}
