package linknote;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.events.DocumentEvent;
import org.w3c.dom.events.EventTarget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "linknote.desktopSmoke", matches = "true")
class DesktopSmokeTest {
    @TempDir
    Path vault;

    @Test
    void demonstratesNewNoteMathNavigationRenameAndReopen() throws Exception {
        Path sample = Path.of("sample_vault", "notes");
        Path matrix = Files.copy(sample.resolve("matrix_blocking.md"), vault.resolve("matrix_blocking.md"));
        Path cache = Files.copy(sample.resolve("cache_locality.md"), vault.resolve("cache_locality.md"));
        var startup = new CompletableFuture<Void>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            startup.complete(null);
        });
        startup.get(15, TimeUnit.SECONDS);
        try {
            var app = new LinkNoteApp();
            Stage stage = onFx(() -> launch(app));
            onFx(() -> {
                assertNotNull(stage.getScene().lookup("#welcome"));
                assertTrue(button(stage, "#save-button").isDisabled());
                button(stage, "#example-button").fire();
                assertEquals("矩阵分块", title(stage).getText());
                return null;
            });
            onFx(() -> {
                app.openFile(matrix);
                assertTrue(title(stage).getText().equals("矩阵分块"));
                return null;
            });
            onFx(() -> {
                var dialogAnswer = answerDialog("确定", dialog -> {
                    dialog.lookup(".text-field").requestFocus();
                    ((TextField) dialog.lookup(".text-field")).setText("演示笔记");
                });
                button(stage, "#new-button").fire();
                dialogAnswer.join();
                assertEquals("演示笔记", title(stage).getText());
                assertTrue(((TitledPane) stage.getScene().lookup("#backlinks")).isExpanded());
                editor(stage).setText("""
                        # 演示笔记

                        **加粗**，行内公式 $x^2$，错误公式 $\\notAnActualCommand$。

                        | 项目 | 值 |
                        | --- | --- |
                        | 行数 | 2 |

                        $$
                        \\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}
                        $$

                        [[缓存局部性]]

                        `[[代码不跳转]] $x$`
                        """);
                assertTrue(((Label) stage.getScene().lookup("#save-status")).getText().contains("未保存"));
                return null;
            });

            Path demo = vault.resolve("演示笔记.md");
            assertFalse(Files.readString(demo).contains("加粗"));
            await(() -> onFx(() -> Boolean.TRUE.equals(preview(stage).getEngine().executeScript("""
                    !!document.querySelector("table td")
                    && !!document.querySelector(".math-inline .katex")
                    && !!document.querySelector(".math-display .katex-display")
                    && document.querySelector(".math-error")?.textContent.includes("notAnActualCommand")
                    && !!document.querySelector("code")
                    && document.fonts.check("16px KaTeX_Main")
                    """))));

            onFx(() -> {
                button(stage, "#save-button").fire();
                assertTrue(button(stage, "#save-button").isDisabled());
                clickPreview(stage, "缓存局部性");
                return null;
            });
            assertTrue(Files.readString(demo).contains("加粗"));

            await(() -> onFx(() -> title(stage).getText().equals("缓存局部性")));
            onFx(() -> {
                fireBacklink(stage, "演示笔记");
                assertEquals("演示笔记", title(stage).getText());
                editor(stage).appendText("\n这段先保留在编辑区。");
                answerDialog("继续编辑", dialog -> {});
                stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
                assertTrue(stage.isShowing());
                assertTrue(editor(stage).getText().contains("先保留"));
                answerDialog("放弃修改", dialog -> {});
                app.openFile(cache);
                assertEquals("缓存局部性", title(stage).getText());

                title(stage).setText("局部性");
                var warning = answerDialog("确定", dialog -> {
                    assertEquals("旧引用需要手动更新", dialog.getHeaderText());
                    assertTrue(dialog.getContentText().contains("演示笔记"));
                });
                button(stage, "#save-button").fire();
                assertTrue(warning.isDone());
                warning.join();
                return null;
            });
            assertTrue(Files.readString(demo).contains("[[缓存局部性]]"));
            assertFalse(Files.readString(demo).contains("先保留"));
            assertTrue(Files.readString(cache).contains("title: 局部性"));

            onFx(() -> {
                app.openFile(demo);
                return null;
            });
            await(() -> onFx(() -> previewLink(stage, "缓存局部性") != null));
            CompletableFuture<Void> invalidLink = onFx(() -> {

                clickPreview(stage, "缓存局部性");
                return answerDialog("确定", dialog -> assertEquals("无法打开链接", dialog.getHeaderText()));
            });
            invalidLink.get(10, TimeUnit.SECONDS);

            onFx(() -> {
                title(stage).setText("中期演示");
                editor(stage).setText(editor(stage).getText().replace("[[缓存局部性]]", "[[局部性]]"));
                answerDialog("保存", dialog -> {});
                stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
                assertFalse(stage.isShowing());
                return null;
            });

            var reopened = new LinkNoteApp();
            Stage restarted = onFx(() -> launch(reopened));
            onFx(() -> {
                assertNotNull(restarted.getScene().lookup("#welcome"));
                reopened.openFile(demo);
                restarted.getScene().getRoot().applyCss();
                restarted.getScene().getRoot().layout();
                assertEquals("中期演示", title(restarted).getText());
                assertTrue(editor(restarted).getText().contains("[[局部性]]"));
                return null;
            });
            await(() -> onFx(() -> previewLink(restarted, "局部性") != null));
            onFx(() -> {
                clickPreview(restarted, "局部性");
                return null;
            });
            await(() -> onFx(() -> title(restarted).getText().equals("局部性")));
            onFx(() -> {
                fireBacklink(restarted, "中期演示");
                assertEquals("中期演示", title(restarted).getText());
                restarted.close();
                return null;
            });
        } finally {
            Platform.exit();
        }
    }

    private static Stage launch(LinkNoteApp app) {
        Stage stage = new Stage();
        app.start(stage);
        return stage;
    }

    private static Button button(Stage stage, String selector) {
        return (Button) stage.getScene().lookup(selector);
    }

    private static void fireBacklink(Stage stage, String title) {
        stage.getScene().getRoot().applyCss();
        var link = stage.getScene().getRoot().lookupAll(".backlink-button").stream()
                .map(Button.class::cast).filter(button -> button.getText().equals(title)).findFirst();
        link.orElseThrow().fire();
    }

    private static CompletableFuture<Void> answerDialog(String caption, java.util.function.Consumer<DialogPane> inspect) {
        var result = new CompletableFuture<Void>();
        // The queued callback runs inside the dialog's showAndWait event loop.
        Platform.runLater(() -> {
            var active = Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene)
                    .filter(java.util.Objects::nonNull).map(scene -> scene.getRoot())
                    .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst();
            if (active.isEmpty()) {
                result.completeExceptionally(new AssertionError("未打开对话框 " + caption));
                return;
            }
            DialogPane pane = active.get();
            try {
                inspect.accept(pane);
                var type = pane.getButtonTypes().stream()
                        .filter(candidate -> caption.equals(candidate.getText())).findFirst().orElse(ButtonType.OK);
                ((Button) pane.lookupButton(type)).fire();
                result.complete(null);
            } catch (Throwable failure) {
                pane.getScene().getWindow().hide();
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static void clickPreview(Stage stage, String target) {
        Element anchor = previewLink(stage, target);
        assertNotNull(anchor, "预览中需要有 " + target);
        var event = ((DocumentEvent) anchor.getOwnerDocument()).createEvent("Event");
        event.initEvent("click", true, true);
        ((EventTarget) anchor).dispatchEvent(event);
    }

    private static Element previewLink(Stage stage, String target) {
        var document = preview(stage).getEngine().getDocument();
        if (document != null) {
            var anchors = document.getElementsByTagName("a");
            for (int index = 0; index < anchors.getLength(); index++) {
                var element = (Element) anchors.item(index);
                if (target.equals(element.getAttribute("data-linknote-target"))) {
                    return element;
                }
            }
        }
        return null;
    }

    private static WebView preview(Stage stage) {
        return (WebView) stage.getScene().lookup("#note-preview");
    }

    private static TextField title(Stage stage) {
        return (TextField) stage.getScene().lookup("#note-title");
    }

    private static TextArea editor(Stage stage) {
        return (TextArea) stage.getScene().lookup("#note-editor");
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("界面没有在限定时间内到达预期状态");
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result.get(15, TimeUnit.SECONDS);
    }
}
