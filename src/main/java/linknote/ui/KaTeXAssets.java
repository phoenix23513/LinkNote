package linknote.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/** Formula assets bundled in the Maven dependency and used directly in WebView. */
final class KaTeXAssets {
    private static final String RESOURCE_ROOT = "/META-INF/resources/webjars/katex/0.18.11/dist/";
    private static final Pattern FONT_FACE = Pattern.compile(
            "@font-face\\{[^}]*?font-family:([^;]+);font-style:([^;]+);font-weight:([^;]+);"
                    + "[^}]*?url\\((fonts/[^()]+\\.ttf)\\)[^}]*}");
    private static final String RAW_CSS = readText("katex.min.css");
    private static final String STYLE = FONT_FACE.matcher(RAW_CSS).replaceAll("");
    private static final String SCRIPT = readText("katex.min.js").replaceFirst("(?m)^//# sourceMappingURL=.*$", "")
            + "\n" + fontRegistration() + """
            for (const formula of document.querySelectorAll(".math-inline, .math-display")) {
                const source = formula.textContent;
                const display = formula.classList.contains("math-display");
                try {
                    katex.render(source, formula, {
                        displayMode: display, trust: false, maxExpand: 500, maxSize: 12, minRuleThickness: 0.075
                    });
                } catch (error) {
                    const marker = display ? "$$" : "$";
                    formula.textContent = marker + source + marker;
                    formula.classList.add("math-error");
                    formula.title = "无法渲染此公式";
                }
            }
            """;
    private static final String SCRIPT_HASH = hash(SCRIPT);

    private KaTeXAssets() {
    }

    static String policy() {
        return "default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-" + SCRIPT_HASH + "'";
    }

    static String style() {
        return "<style>" + STYLE + "</style>";
    }

    static String script() {
        return "<script>" + SCRIPT + "</script>";
    }

    private static String fontRegistration() {
        // WebView's loadContent has no base URL. Register the font bytes without a URL fetch.
        var script = new StringBuilder("""
                function addMathFont(family, style, weight, base64) {
                    try {
                        const bytes = Uint8Array.from(atob(base64), character => character.charCodeAt(0));
                        const font = new FontFace(family, bytes, {style, weight});
                        document.fonts.add(font);
                        font.loaded.catch(() => {});
                    } catch (error) {
                        // WebView can still render with its fallback math fonts.
                    }
                }
                """);
        var fonts = FONT_FACE.matcher(RAW_CSS);
        while (fonts.find()) {
            String encoded = Base64.getEncoder().encodeToString(readResource(fonts.group(4)));
            script.append("addMathFont('%s','%s','%s','%s');\n"
                    .formatted(fonts.group(1), fonts.group(2), fonts.group(3), encoded));
        }
        return script.toString();
    }

    private static String readText(String name) {
        return new String(readResource(name), StandardCharsets.UTF_8);
    }

    private static byte[] readResource(String name) {
        try (var input = KaTeXAssets.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (input == null) {
                throw new IllegalStateException("缺少 KaTeX 资源 " + name);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String hash(String script) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
