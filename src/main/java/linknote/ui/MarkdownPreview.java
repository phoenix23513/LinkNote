package linknote.ui;

import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.ImageRef;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.wikilink.WikiImage;
import com.vladsch.flexmark.ext.wikilink.WikiLink;
import com.vladsch.flexmark.ext.wikilink.WikiLinkExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import linknote.links.WikiReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class MarkdownPreview {
    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownPreview() {
        var options = new MutableDataSet()
                .set(Parser.EXTENSIONS, List.of(WikiLinkExtension.create(), TablesExtension.create()))
                .set(WikiLinkExtension.IMAGE_LINKS, true)
                .set(HtmlRenderer.ESCAPE_HTML, true)
                .set(HtmlRenderer.DO_NOT_RENDER_LINKS, true);
        parser = Parser.builder(options).customInlineParserExtensionFactory(new MathSyntax.Factory()).build();
        renderer = HtmlRenderer.builder(options).nodeRendererFactory(ignored -> new LocalRenderer()).build();
    }

    public List<String> wikiTargets(String markdown) {
        return wikiReferences(markdown).stream().map(WikiReference::target).distinct().toList();
    }

    public List<WikiReference> wikiReferences(String markdown) {
        var references = new ArrayList<WikiReference>();
        for (var node : parser.parse(markdown).getDescendants()) {
            if (node instanceof WikiLink link) {
                String target = wikiTarget(link);
                if (!target.isEmpty()) {
                    references.add(new WikiReference(target, link.getStartOffset(), link.getEndOffset()));
                }
            }
        }
        return List.copyOf(references);
    }

    public String render(String markdown) {
        String body = renderer.render(parser.parse(markdown));
        boolean hasMath = body.contains("class=\"math-inline\"") || body.contains("class=\"math-display\"");
        String policy = hasMath ? KaTeXAssets.policy() : "default-src 'none'; style-src 'unsafe-inline'";
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <meta http-equiv="Content-Security-Policy" content="%s">
                %s
                <style>
                body{font:16px/1.7 'Segoe UI','Microsoft YaHei UI',sans-serif;color:#24352e;
                     background:#fff;margin:0;padding:14px 22px;overflow-wrap:break-word}
                h1,h2,h3{color:#20352b;line-height:1.35;font-weight:650}
                h1{font-size:27px;margin:0 0 .45em} h2{font-size:21px;margin-top:1.2em}
                h3{font-size:17px} p{margin:0 0 .8em}
                code{background:#eff3ee;padding:.13em .3em;border-radius:3px}
                pre{background:#f3f5f1;padding:12px;overflow:auto;border-radius:6px}
                pre code{background:transparent;padding:0}
                blockquote{margin-left:0;padding-left:12px;border-left:3px solid #a1c2b0;color:#456357}
                a[data-linknote-target]{color:#166044;font-weight:600;text-decoration:underline}
                table{border-collapse:collapse;max-width:100%%;margin:1em 0;color:inherit}
                td,th{border:1px solid #dce5dd;padding:5px 10px}
                th{background:#edf4ef;text-align:left} tr:nth-child(even){background:#f7f9f6}
                .math-display{display:block;overflow-x:auto;padding:2px 0;text-align:center}
                .math-error{color:#9d4028}
                .katex-display{margin:.45em 0}
                </style></head><body>
                %s%s</body></html>
                """.formatted(policy, hasMath ? KaTeXAssets.style() : "", body, hasMath ? KaTeXAssets.script() : "");
    }

    private static String wikiTarget(WikiLink link) {
        String source = link.getChars().toString();
        String target = source.substring(2, source.length() - 2).strip();
        if (target.isEmpty() || target.indexOf('|') >= 0 || target.indexOf('#') >= 0
                || target.indexOf('[') >= 0 || target.indexOf(']') >= 0
                || target.indexOf('\\') >= 0 || target.codePoints().anyMatch(Character::isISOControl)) {
            return "";
        }
        return target;
    }

    private static final class LocalRenderer implements NodeRenderer {
        @Override
        public Set<NodeRenderingHandler<?>> getNodeRenderingHandlers() {
            return Set.of(
                    new NodeRenderingHandler<>(WikiLink.class, this::wikiLink),
                    new NodeRenderingHandler<>(MathSyntax.Formula.class, this::formula),
                    new NodeRenderingHandler<>(WikiImage.class, (node, context, html) -> html.text(node.getChars())),
                    new NodeRenderingHandler<>(Image.class, (node, context, html) -> html.text(node.getText())),
                    new NodeRenderingHandler<>(ImageRef.class, (node, context, html) -> html.text(node.getText())));
        }

        private void wikiLink(WikiLink link, NodeRendererContext context, HtmlWriter html) {
            String target = wikiTarget(link);
            if (target.isEmpty()) {
                html.text(link.getChars());
                return;
            }
            html.attr("href", "#").attr("data-linknote-target", target)
                    .withAttr().tag("a").text(target).tag("/a");
        }

        private void formula(MathSyntax.Formula formula, NodeRendererContext context, HtmlWriter html) {
            html.attr("class", formula.display ? "math-display" : "math-inline")
                    .withAttr().tag("span").text(formula.source).tag("/span");
        }
    }
}
