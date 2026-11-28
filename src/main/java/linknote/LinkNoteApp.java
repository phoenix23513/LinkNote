package linknote;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import linknote.links.LinkIndex;
import linknote.links.NoteCatalog;
import linknote.model.Note;
import linknote.storage.MarkdownNoteRepository;
import linknote.ui.MarkdownPreview;
import linknote.ui.NoteGraphView;
import org.w3c.dom.Element;
import org.w3c.dom.events.EventTarget;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class LinkNoteApp extends Application {
    private static final Path SAMPLE_NOTE = Path.of("sample_vault", "notes", "matrix_blocking.md");
    private final MarkdownPreview markdown = new MarkdownPreview();

    private Stage stage;
    private BorderPane view;
    private Node welcome;
    private Node workspace;
    private ListView<Note> notes;
    private TextField title;
    private TextArea editor;
    private WebView preview;
    private Label folderLabel;
    private Label status;
    private Button graphButton;
    private Button saveButton;
    private NoteGraphView graph;
    private TitledPane backlinks;
    private VBox backlinkEntries;
    private PauseTransition previewDelay;
    private MarkdownNoteRepository repository;
    private NoteCatalog catalog;
    private LinkIndex linkIndex;
    private Note currentNote;
    private boolean populating;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage window) {
        stage = window;
        view = buildView();
        previewDelay = new PauseTransition(Duration.millis(200));
        previewDelay.setOnFinished(event -> refreshPreview());
        preview.getEngine().getLoadWorker().stateProperty().addListener((property, oldState, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                connectPreviewLinks();
            }
        });
        title.textProperty().addListener((property, oldValue, value) -> refreshDraftStatus());
        editor.textProperty().addListener((property, oldValue, value) -> {
            if (!populating && currentNote != null) {
                refreshDraftStatus();
                previewDelay.playFromStart();
            }
        });
        notes.getSelectionModel().selectedItemProperty().addListener((property, oldNote, note) -> {
            if (!populating && note != null) {
                openNote(note);
            }
        });

        Scene scene = new Scene(view, 1200, 740);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/linknote.css")).toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN), this::saveCurrentNote);
        stage.setTitle("LinkNote");
        stage.setMinWidth(860);
        stage.setMinHeight(560);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            if (!confirmLeave()) {
                event.consume();
            }
        });
        stage.setOnHidden(event -> previewDelay.stop());
        stage.show();
    }

    private BorderPane buildView() {
        var brand = new Label("LinkNote");
        brand.getStyleClass().add("brand");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var open = button("打开文件", "open-button", this::chooseFile);
        var example = button("打开示例", "example-button", () -> openFile(SAMPLE_NOTE));
        var create = button("新建笔记", "new-button", this::chooseNewNote);
        graphButton = button("图谱", "graph-button", this::toggleGraph);
        graphButton.setDisable(true);
        saveButton = button("保存", "save-button", this::saveCurrentNote);
        saveButton.getStyleClass().add("primary");
        saveButton.setDisable(true);
        var toolbar = new HBox(9, brand, spacer, open, example, create, graphButton, saveButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("toolbar");

        var welcomeTitle = new Label("打开一篇 Markdown 笔记");
        welcomeTitle.getStyleClass().add("welcome-title");
        var hint = new Label("打开本地 .md 文件，或用上方的「打开示例」体验双链。");
        hint.getStyleClass().add("secondary");
        var welcomeOpen = button("打开文件", "welcome-open-button", this::chooseFile);
        welcomeOpen.getStyleClass().add("primary");
        var welcomeContent = new VBox(15, welcomeTitle, hint, welcomeOpen);
        welcomeContent.setAlignment(Pos.CENTER);
        welcome = new StackPane(welcomeContent);
        welcome.setId("welcome");
        welcome.getStyleClass().add("welcome");

        status = new Label("未打开笔记");
        status.setId("save-status");
        status.getStyleClass().add("status");
        workspace = buildWorkspace();
        graph = new NoteGraphView(this::openNote);
        return new BorderPane(welcome, toolbar, null, status, null);
    }

    private Node buildWorkspace() {
        folderLabel = new Label();
        folderLabel.getStyleClass().add("secondary");
        notes = new ListView<>();
        notes.setId("notes-list");
        notes.setCellFactory(list -> new ListCell<>() {
            private final Label name = new Label();
            private final Label filename = new Label();
            private final VBox content = new VBox(3, name, filename);

            {
                name.getStyleClass().add("list-title");
                filename.getStyleClass().add("list-filename");
            }

            @Override
            protected void updateItem(Note note, boolean empty) {
                super.updateItem(note, empty);
                if (empty || note == null) {
                    setGraphic(null);
                    setTooltip(null);
                } else {
                    name.setText(note.title());
                    filename.setText(note.path().getFileName().toString());
                    setGraphic(content);
                    setTooltip(new Tooltip(note.title() + "\n" + note.path().getFileName()));
                }
            }
        });
        var library = section("笔记", folderLabel, notes);
        VBox.setVgrow(notes, Priority.ALWAYS);
        library.setPrefWidth(207);
        library.setMinWidth(150);

        title = new TextField();
        title.setId("note-title");
        title.setPromptText("笔记标题");
        title.getStyleClass().add("note-title");
        editor = new TextArea();
        editor.setId("note-editor");
        editor.setWrapText(true);
        editor.setPromptText("在这里编写 Markdown");
        var editorPane = section("Markdown", editor);
        editorPane.setMinWidth(245);
        VBox.setVgrow(editor, Priority.ALWAYS);

        preview = new WebView();
        preview.setId("note-preview");
        preview.setMinWidth(0);
        preview.setMinHeight(100);
        preview.setContextMenuEnabled(false);
        preview.getEngine().setCreatePopupHandler(features -> null);
        backlinkEntries = new VBox(3);
        var scroll = new ScrollPane(backlinkEntries);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(82);
        backlinks = new TitledPane("引用此笔记", scroll);
        backlinks.setId("backlinks");
        backlinks.setAnimated(false);
        backlinks.setMaxHeight(Region.USE_PREF_SIZE);
        var previewContent = new SplitPane(preview, backlinks);
        previewContent.setOrientation(Orientation.VERTICAL);
        previewContent.setDividerPositions(0.81);
        // The drawer keeps its own height when the window is resized.
        SplitPane.setResizableWithParent(backlinks, false);
        backlinks.expandedProperty().addListener((property, oldValue, expanded) ->
                previewContent.setDividerPositions(expanded ? 0.81 : 1));
        var previewPane = section("预览", previewContent);
        previewPane.setMinWidth(245);
        VBox.setVgrow(previewContent, Priority.ALWAYS);
        var split = new SplitPane(editorPane, previewPane);
        split.setDividerPositions(0.49);

        var document = new VBox(10, title, split);
        VBox.setVgrow(split, Priority.ALWAYS);
        HBox.setHgrow(document, Priority.ALWAYS);
        var body = new HBox(12, library, document);
        body.getStyleClass().add("workspace");
        return body;
    }

    private VBox section(String title, Node... children) {
        var label = new Label(title);
        label.getStyleClass().add("section-title");
        var box = new VBox(8, label);
        box.getChildren().addAll(children);
        box.getStyleClass().add("pane");
        return box;
    }

    private Button button(String caption, String id, Runnable action) {
        var button = new Button(caption);
        button.setId(id);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void chooseFile() {
        var chooser = new FileChooser();
        chooser.setTitle("打开 Markdown 文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown (*.md)", "*.md", "*.MD"));
        if (repository != null && Files.isDirectory(repository.root())) {
            chooser.setInitialDirectory(repository.root().toFile());
        }
        var file = chooser.showOpenDialog(stage);
        if (file != null) {
            openFile(file.toPath());
        }
    }

    // Also used by the window test after it supplies a file choice.
    void openFile(Path file) {
        if (!confirmLeave()) {
            return;
        }
        try {
            Path path = file.toAbsolutePath().normalize();
            var next = new MarkdownNoteRepository(path.getParent());
            var scan = next.scanFolder();
            Path selected = path.toRealPath();
            var note = scan.notes().stream().filter(item -> item.path().equals(selected)).findFirst();
            if (note.isEmpty()) {
                next.read(path);
                throw new IOException("文件无法在当前目录载入。请检查文件 UUID 是否重复。");
            }
            showFolder(next, scan.notes(), note.get(), scan.problems());
        } catch (IOException exception) {
            showMessage("无法打开 Markdown 文件", exception.getMessage());
        }
    }

    private void chooseNewNote() {
        var dialog = new TextInputDialog();
        dialog.initOwner(stage);
        dialog.setTitle("新建笔记");
        dialog.setHeaderText("新建 Markdown 笔记");
        dialog.setContentText("笔记标题");
        var result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        Path directory = repository == null ? chooseNewNoteFolder() : repository.root();
        if (directory != null) {
            createNote(directory, result.get());
        }
    }

    private Path chooseNewNoteFolder() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("选择新笔记的保存目录");
        var folder = chooser.showDialog(stage);
        return folder == null ? null : folder.toPath();
    }

    void createNote(Path directory, String newTitle) {
        if (!confirmLeave()) {
            return;
        }
        try {
            var next = new MarkdownNoteRepository(directory);
            var scan = next.scanFolder();
            Note created = next.create(newTitle);
            var updated = new ArrayList<>(scan.notes());
            updated.add(created);
            updated.sort(MarkdownNoteRepository.NOTE_ORDER);
            showFolder(next, updated, created, scan.problems());
            status.setText("已新建 · " + created.path().getFileName());
            editor.requestFocus();
        } catch (IOException exception) {
            showMessage("无法新建笔记", exception.getMessage());
        }
    }

    private void showFolder(MarkdownNoteRepository next, List<Note> items, Note selected, List<String> problems) {
        repository = next;
        Path directory = next.root();
        String folder = directory.getFileName() == null ? directory.toString() : directory.getFileName().toString();
        folderLabel.setText(problems.isEmpty() ? folder : folder + " · " + problems.size() + " 篇未载入");
        folderLabel.setTooltip(new Tooltip(directory + (problems.isEmpty() ? "" : "\n" + String.join("\n", problems))));
        populating = true;
        notes.getItems().setAll(items);
        populating = false;
        showWorkspace();
        rebuildLinks();
        graphButton.setDisable(false);
        displayNote(selected);
    }

    private void openNote(Note listed) {
        if (listed.path().equals(currentNote.path())) {
            selectCurrent();
            showWorkspace();
            return;
        }
        if (!confirmLeave()) {
            selectCurrent();
            return;
        }
        try {
            Note read = repository.read(listed.path());
            if (!read.id().equals(listed.id()) || !read.title().equals(listed.title())) {
                throw new IOException("标题或 UUID 已在外部更改，请用「打开文件」重新载入。");
            }
            if (!read.fingerprint().equals(listed.fingerprint())) {
                replaceListedNote(read);
            }
            showWorkspace();
            displayNote(read);
        } catch (IOException exception) {
            selectCurrent();
            showMessage("无法打开笔记", exception.getMessage());
        }
    }

    private void displayNote(Note note) {
        currentNote = note;
        populating = true;
        editor.setText(note.body());
        title.setText(note.title());
        populating = false;
        selectCurrent();
        refreshDraftStatus();
        refreshPreview();
        showBacklinks();
    }

    private void selectCurrent() {
        populating = true;
        notes.getItems().stream().filter(note -> note.path().equals(currentNote.path())).findFirst()
                .ifPresent(notes.getSelectionModel()::select);
        populating = false;
    }

    private boolean isDirty() {
        return currentNote != null && (!currentNote.body().equals(editor.getText())
                || !currentNote.title().equals(title.getText()));
    }

    private void refreshDraftStatus() {
        if (!populating && currentNote != null) {
            boolean dirty = isDirty();
            saveButton.setDisable(!dirty);
            status.setText((dirty ? "未保存修改 · " : "已保存 · ") + currentNote.path().getFileName());
        }
    }

    private boolean saveCurrentNote() {
        if (!isDirty()) {
            return true;
        }
        try {
            String oldTitle = currentNote.title();
            currentNote = repository.save(currentNote, title.getText(), editor.getText());
            replaceListedNote(currentNote);
            populating = true;
            title.setText(currentNote.title());
            populating = false;
            selectCurrent();
            refreshDraftStatus();
            showBacklinks();
            if (!oldTitle.equals(currentNote.title())) {
                remindOldReferences(oldTitle);
            }
            return true;
        } catch (IOException exception) {
            status.setText("保存失败，草稿仍在编辑区");
            showMessage("保存失败", exception.getMessage());
            return false;
        }
    }

    private void remindOldReferences(String oldTitle) {
        var sources = linkIndex.referencesToTitle(oldTitle).stream()
                .map(source -> source.title() + "（" + source.path().getFileName() + "）")
                .toList();
        if (!sources.isEmpty()) {
            showMessage("旧引用需要手动更新", "以下笔记仍包含 [[" + oldTitle + "]]：\n"
                    + String.join("\n", sources) + "\n\n请将旧引用改为 [[" + currentNote.title() + "]] 并保存。");
        }
    }

    private void replaceListedNote(Note saved) {
        populating = true;
        notes.getItems().removeIf(note -> note.path().equals(saved.path()));
        notes.getItems().add(saved);
        notes.getItems().sort(MarkdownNoteRepository.NOTE_ORDER);
        populating = false;
        rebuildLinks();
    }

    private void rebuildLinks() {
        var items = List.copyOf(notes.getItems());
        catalog = new NoteCatalog(repository.root(), items);
        linkIndex = new LinkIndex(catalog, items, markdown::wikiTargets);
        if (view.getCenter() == graph) {
            graph.showGraph(items, linkIndex);
        }
    }

    private void toggleGraph() {
        if (view.getCenter() == graph) {
            showWorkspace();
        } else {
            graph.showGraph(notes.getItems(), linkIndex);
            view.setCenter(graph);
            graphButton.setText("返回编辑");
        }
    }

    private void showWorkspace() {
        view.setCenter(workspace);
        graphButton.setText("图谱");
    }

    private void showBacklinks() {
        var sources = linkIndex.backlinks(currentNote);
        backlinks.setText("引用此笔记" + (sources.isEmpty() ? "" : " · " + sources.size()));
        backlinkEntries.getChildren().clear();
        if (sources.isEmpty()) {
            var empty = new Label("暂无反链");
            empty.getStyleClass().add("secondary");
            backlinkEntries.getChildren().add(empty);
        }
        for (Note source : sources) {
            var link = new Button(source.title());
            link.getStyleClass().add("backlink-button");
            link.setTooltip(new Tooltip(source.path().getFileName().toString()));
            link.setOnAction(event -> openNote(source));
            backlinkEntries.getChildren().add(link);
        }
    }

    private boolean confirmLeave() {
        if (!isDirty()) {
            return true;
        }
        var save = new ButtonType("保存", ButtonBar.ButtonData.YES);
        var discard = new ButtonType("放弃修改", ButtonBar.ButtonData.NO);
        var cancel = new ButtonType("继续编辑", ButtonBar.ButtonData.CANCEL_CLOSE);
        var prompt = new Alert(Alert.AlertType.CONFIRMATION, "当前笔记还有未保存的修改。", save, discard, cancel);
        prompt.initOwner(stage);
        prompt.setHeaderText(currentNote.title());
        var choice = prompt.showAndWait().orElse(cancel);
        return choice == discard || (choice == save && saveCurrentNote());
    }

    private void refreshPreview() {
        previewDelay.stop();
        preview.getEngine().loadContent(markdown.render(editor.getText()), "text/html");
    }

    private void connectPreviewLinks() {
        var document = preview.getEngine().getDocument();
        if (document == null) {
            return;
        }
        var anchors = document.getElementsByTagName("a");
        for (int index = 0; index < anchors.getLength(); index++) {
            var anchor = (Element) anchors.item(index);
            String target = anchor.getAttribute("data-linknote-target");
            ((EventTarget) anchor).addEventListener("click", event -> {
                event.preventDefault();
                if (!target.isBlank()) {
                    Platform.runLater(() -> {
                        if (document.equals(preview.getEngine().getDocument())) {
                            followLink(target);
                        }
                    });
                }
            }, false);
        }
    }

    private void followLink(String target) {
        var resolution = catalog.resolve(target);
        if (resolution.match() == NoteCatalog.Match.FOUND) {
            openNote(resolution.note());
        } else {
            showMessage("无法打开链接", "请检查目标笔记标题或链接写法。\n\n[[" + target + "]]");
        }
    }

    private void showMessage(String heading, String details) {
        var dialog = new Alert(Alert.AlertType.WARNING, details, ButtonType.OK);
        dialog.initOwner(stage);
        dialog.setTitle("LinkNote");
        dialog.setHeaderText(heading);
        dialog.setResizable(true);
        dialog.showAndWait();
    }
}
