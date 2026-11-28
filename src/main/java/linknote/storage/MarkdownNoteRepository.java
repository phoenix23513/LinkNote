package linknote.storage;

import linknote.model.Note;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class MarkdownNoteRepository {
    private static final int MAX_NOTE_BYTES = 2 * 1024 * 1024;
    private static final Pattern FRONT_MATTER =
            Pattern.compile("\\A---[ \\t]*\\n(.*?)^---[ \\t]*(?:\\n|\\z)",
                    Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern SCHEMA_MARKER = Pattern.compile("(?m)^schema_version\\s*:");
    private static final Pattern RENAME_STEM = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}");
    private static final Pattern UNSAFE_FILENAME = Pattern.compile("[\\\\/:*?\"<>|]");
    private static final Pattern WINDOWS_DEVICE =
            Pattern.compile("(?i)^(CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³])(?:\\..*)?$");
    private static final LoadSettings YAML_SETTINGS = LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(0)
            .setCodePointLimit(65_536)
            .build();
    public static final Comparator<Note> NOTE_ORDER =
            Comparator.comparing(Note::title).thenComparing(note -> note.path().toString());
    private final Path root;

    public MarkdownNoteRepository(Path directory) throws IOException {
        root = directory.toRealPath();
        if (!Files.isDirectory(root) || internalPath(root)) {
            throw new IOException("请选择一个笔记目录。");
        }
    }

    public Path root() {
        return root;
    }

    public record ScanResult(List<Note> notes, List<String> problems) {
    }

    public ScanResult scanFolder() throws IOException {
        return scan(1);
    }

    public ScanResult scan() throws IOException {
        return scan(Integer.MAX_VALUE);
    }

    private ScanResult scan(int depth) throws IOException {
        List<Note> notes = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Files.walkFileTree(root, Set.of(), depth, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return directory.equals(root) || !internalPath(directory)
                        ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && isNotePath(file)) {
                    try {
                        notes.add(read(file));
                    } catch (IOException exception) {
                        problems.add(root.relativize(file) + " · " + exception.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                problems.add(root.relativize(file) + " · 无法读取");
                return FileVisitResult.CONTINUE;
            }
        });

        Map<UUID, Integer> counts = new HashMap<>();
        notes.forEach(note -> counts.merge(note.id(), 1, Integer::sum));
        List<Note> validNotes = new ArrayList<>();
        for (Note note : notes) {
            if (counts.get(note.id()) == 1) {
                validNotes.add(note);
            } else {
                problems.add(root.relativize(note.path()) + " · UUID 重复");
            }
        }
        validNotes.sort(NOTE_ORDER);
        return new ScanResult(List.copyOf(validNotes), List.copyOf(problems));
    }

    public Note read(Path file) throws IOException {
        Path path = checkedNotePath(file);
        return decode(path, readBytes(path));
    }

    public Note create(String rawTitle) throws IOException {
        String title = validTitle(rawTitle);
        String stem = UNSAFE_FILENAME.matcher(title).replaceAll("_");
        // Leave room for both a numeric suffix and the extension.
        stem = stem.substring(0, stem.offsetByCodePoints(0, Math.min(65, stem.codePointCount(0, stem.length()))))
                .replaceFirst("[. ]+$", "");
        if (stem.isEmpty() || WINDOWS_DEVICE.matcher(stem).matches()) {
            stem = "note_" + stem;
        }
        byte[] bytes = encode(newNoteFields(UUID.randomUUID(), title), "");
        Path temporaryFile = Files.createTempFile(root, ".linknote-save-", ".tmp");
        try {
            Files.write(temporaryFile, bytes);
            for (int suffix = 1; suffix <= 1000; suffix++) {
                Path path = root.resolve(stem + (suffix == 1 ? "" : "-" + suffix) + ".md");
                try {
                    Files.move(temporaryFile, path);
                    return decode(path, bytes);
                } catch (FileAlreadyExistsException ignored) {
                    // Leave existing files intact, including those created by another process.
                }
            }
            throw new IOException("找不到可用的文件名，请换一个标题。");
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    public Note save(Note original, String rawTitle, String newBody) throws IOException {
        String title = rawTitle.equals(original.title()) ? rawTitle : validTitle(rawTitle);
        Path path = checkedNotePath(original.path());
        assertUnchanged(path, original.fingerprint());
        byte[] bytes;
        if (original.yaml() == null && original.title().equals(title)) {
            bytes = encode(null, newBody);
        } else {
            var fields = original.yaml() == null ? newNoteFields(original.id(), title) : metadata(original.yaml());
            fields.put("title", title);
            if (fields.containsKey("schema_version")) {
                fields.put("updated_at", OffsetDateTime.now().toString());
            }
            bytes = encode(fields, newBody);
        }
        Note saved = decode(path, bytes);
        replaceContents(path, original.fingerprint(), bytes);
        return saved;
    }

    Path renameDestination(Note original, String rawStem) throws IOException {
        String oldName = original.path().getFileName().toString();
        String oldStem = oldName.substring(0, oldName.length() - 3);
        String stem = rawStem.strip();
        if (rawStem.equals(oldStem) || stem.equals(oldStem)) {
            return original.path();
        }
        if (!RENAME_STEM.matcher(stem).matches() || WINDOWS_DEVICE.matcher(stem).matches()) {
            throw new IOException("新文件名须以英文字母或数字开头，最多 80 个字符，可使用字母、数字、短横线和下划线。");
        }
        String newName = stem + ".md";
        if (oldName.equalsIgnoreCase(newName)) {
            throw new IOException("暂不支持只改变文件名大小写。请使用不同的文件名。");
        }
        Path destination = root.resolve(newName);
        requireVacant(destination);
        return destination;
    }

    byte[] renameContents(Note note, String title, String body) throws IOException {
        var fields = note.yaml() == null ? newNoteFields(note.id(), title) : metadata(note.yaml());
        if (!fields.containsKey("schema_version")) {
            newNoteFields(note.id(), title).forEach(fields::putIfAbsent);
            fields.put("id", note.id().toString());
            if (!"paper".equals(fields.get("type")) && !"note".equals(fields.get("type"))) {
                fields.put("type", "note");
            }
        }
        fields.put("title", title);
        fields.put("updated_at", OffsetDateTime.now().toString());
        return encode(fields, body);
    }

    byte[] snapshotBytes(Path path, String expected) throws IOException {
        byte[] bytes = readBytes(checkedNotePath(path));
        if (!fingerprint(bytes).equals(expected)) {
            throw new ModifiedNoteException(path);
        }
        return bytes;
    }

    void replaceContents(Path path, String expected, byte[] bytes) throws IOException {
        Path temporaryFile = Files.createTempFile(path.getParent(), ".linknote-save-", ".tmp");
        try {
            Files.write(temporaryFile, bytes);
            assertUnchanged(checkedNotePath(path), expected);
            Files.move(temporaryFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("该目录不支持安全替换。草稿仍在编辑区，请改用支持的本地目录。", exception);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    void publishContents(Path path, byte[] bytes) throws IOException {
        Path temporaryFile = Files.createTempFile(path.getParent(), ".linknote-save-", ".tmp");
        try {
            Files.write(temporaryFile, bytes);
            Files.move(temporaryFile, path);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    void deleteUnchanged(Path path, String expected) throws IOException {
        Path checked = checkedNotePath(path);
        assertUnchanged(checked, expected);
        Files.delete(checked);
    }

    static void requireVacant(Path path) throws IOException {
        if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException("目标文件名已占用或无法确认：" + path.getFileName());
        }
    }

    private Path checkedNotePath(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || !isNotePath(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("该文件已失效或不属于当前笔记目录。");
        }
        Path real = normalized.toRealPath();
        if (!real.startsWith(root) || !isNotePath(real)) {
            throw new IOException("该文件已失效或不属于当前笔记目录。");
        }
        return real;
    }

    private boolean internalPath(Path file) {
        for (Path part : file) {
            if (part.toString().equalsIgnoreCase(".linknote")) {
                return true;
            }
        }
        return false;
    }

    private boolean isNotePath(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") && !name.startsWith(".linknote-save-") && !internalPath(file);
    }

    static String validTitle(String rawTitle) throws IOException {
        String title = rawTitle.strip();
        if (title.isEmpty() || title.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("请输入有效的笔记标题。");
        }
        if (title.codePointCount(0, title.length()) > 120) {
            throw new IOException("标题最多支持 120 个字符。");
        }
        return title;
    }

    private static Map<String, Object> newNoteFields(UUID id, String title) {
        var fields = new LinkedHashMap<String, Object>();
        String now = OffsetDateTime.now().toString();
        fields.put("schema_version", 1);
        fields.put("id", id.toString());
        fields.put("type", "note");
        fields.put("title", title);
        fields.put("created_at", now);
        fields.put("updated_at", now);
        return fields;
    }

    private static byte[] encode(Map<String, Object> fields, String body) throws IOException {
        String content = body;
        if (fields != null) {
            String yaml = new Dump(DumpSettings.builder().build()).dumpToString(fields);
            content = "---\n" + yaml + "---\n" + body;
        }
        return encodeRaw(content);
    }

    static byte[] encodeRaw(String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_NOTE_BYTES) {
            throw new IOException("笔记超过 2 MiB，未保存。");
        }
        return bytes;
    }

    static byte[] readBytes(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_NOTE_BYTES + 1);
            if (bytes.length > MAX_NOTE_BYTES) {
                throw new IOException("笔记超过 2 MiB。");
            }
            return bytes;
        }
    }

    private static void assertUnchanged(Path file, String expected) throws IOException {
        if (!fingerprint(readBytes(file)).equals(expected)) {
            throw new ModifiedNoteException(file);
        }
    }

    static final class ModifiedNoteException extends IOException {
        ModifiedNoteException(Path path) {
            super("磁盘文件已被其他程序修改：" + path.getFileName() + "。请重新打开文件后重试。");
        }
    }

    static Note decode(Path path, byte[] bytes) throws IOException {
        String contents = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                .replace("\r\n", "\n").replace('\r', '\n');
        if (contents.startsWith("\uFEFF")) {
            contents = contents.substring(1);
        }
        String filename = path.getFileName().toString();
        String title = filename.substring(0, filename.length() - 3);
        UUID id = UUID.nameUUIDFromBytes(path.toString().getBytes(StandardCharsets.UTF_8));
        String fingerprint = fingerprint(bytes);
        var header = FRONT_MATTER.matcher(contents);
        if (!header.find()) {
            if (contents.startsWith("---\nschema_version:")) {
                throw new IOException("LinkNote 元数据缺少结束分隔线。");
            }
            return new Note(id, title, path, null, contents, fingerprint);
        }

        String yaml = header.group(1);
        Map<String, Object> fields;
        try {
            fields = metadata(yaml);
        } catch (IOException exception) {
            if (SCHEMA_MARKER.matcher(yaml).find()) {
                throw exception;
            }
            return new Note(id, title, path, null, contents, fingerprint);
        }
        Object fieldTitle = fields.get("title");
        if (fieldTitle instanceof String named && !named.isBlank()) {
            title = named.strip();
        }
        if (fields.containsKey("schema_version")) {
            if (!Integer.valueOf(1).equals(fields.get("schema_version"))) {
                throw new IOException("只支持 schema_version 为 1 的笔记。");
            }
            Object type = fields.get("type");
            if (!"note".equals(type) && !"paper".equals(type)) {
                throw new IOException("笔记 type 必须是 note 或 paper。");
            }
            if (!(fieldTitle instanceof String named) || named.isBlank()) {
                throw new IOException("缺少有效标题。");
            }
            try {
                String rawId = String.valueOf(fields.get("id"));
                id = UUID.fromString(rawId);
                if (!id.toString().equalsIgnoreCase(rawId)) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("缺少有效 UUID。", exception);
            }
        }
        return new Note(id, title, path, yaml, contents.substring(header.end()), fingerprint);
    }

    private static Map<String, Object> metadata(String yaml) throws IOException {
        try {
            if (new Load(YAML_SETTINGS).loadFromString(yaml) instanceof Map<?, ?> fields) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (var field : fields.entrySet()) {
                    if (!(field.getKey() instanceof String name)) {
                        throw new IOException("YAML 字段名必须是字符串。");
                    }
                    result.put(name, field.getValue());
                }
                return result;
            }
            throw new IOException("YAML front matter 必须是字段映射。");
        } catch (YamlEngineException exception) {
            throw new IOException("YAML front matter 格式错误。", exception);
        }
    }

    static String fingerprint(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256。", exception);
        }
    }
}
