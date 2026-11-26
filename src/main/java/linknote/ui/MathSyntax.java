package linknote.ui;

import com.vladsch.flexmark.parser.InlineParser;
import com.vladsch.flexmark.parser.InlineParserExtension;
import com.vladsch.flexmark.parser.InlineParserExtensionFactory;
import com.vladsch.flexmark.parser.LightInlineParser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.BasedSequence;

import java.util.Set;

/** Parses formulas with the Markdown parser, so code spans and fenced blocks stay untouched. */
final class MathSyntax implements InlineParserExtension {
    @Override
    public boolean parse(LightInlineParser parser) {
        var input = parser.getInput();
        int start = parser.getIndex();
        if (start > 0 && input.charAt(start - 1) == '$') {
            return false;
        }
        boolean display = parser.peek(1) == '$';
        int width = display ? 2 : 1;
        int contentStart = start + width;
        if (contentStart >= input.length() || input.charAt(contentStart) == '$'
                || (!display && Character.isWhitespace(input.charAt(contentStart)))) {
            return false;
        }
        int limit = Math.min(input.length() - width, contentStart + 4096);
        for (int end = contentStart; end <= limit; end++) {
            char current = input.charAt(end);
            if (current == '\\') {
                end++;
                continue;
            }
            if (!display && current == '\n') {
                break;
            }
            if (current != '$') {
                continue;
            }
            int after = end + width;
            if (display && input.charAt(end + 1) != '$') {
                continue;
            }
            if (after < input.length() && (input.charAt(after) == '$'
                    || (!display && Character.isDigit(input.charAt(after))))) {
                break;
            }
            if (!display && Character.isWhitespace(input.charAt(end - 1))) {
                break;
            }
            var source = input.subSequence(contentStart, end);
            if (source.toString().isBlank()) {
                return false;
            }
            parser.flushTextNode();
            parser.appendNode(new Formula(input.subSequence(start, after), source, display));
            parser.setIndex(after);
            return true;
        }
        return false;
    }

    @Override public void finalizeDocument(InlineParser parser) { }
    @Override public void finalizeBlock(InlineParser parser) { }

    static final class Factory implements InlineParserExtensionFactory {
        @Override public CharSequence getCharacters() { return "$"; }
        @Override public Set<Class<?>> getAfterDependents() { return Set.of(); }
        @Override public Set<Class<?>> getBeforeDependents() { return Set.of(); }
        @Override public boolean affectsGlobalScope() { return false; }
        @Override public InlineParserExtension apply(LightInlineParser parser) { return new MathSyntax(); }
    }

    static final class Formula extends Node {
        final BasedSequence source;
        final boolean display;

        Formula(BasedSequence chars, BasedSequence source, boolean display) {
            super(chars);
            this.source = source;
            this.display = display;
        }

        @Override
        public BasedSequence[] getSegments() {
            return new BasedSequence[]{source};
        }
    }
}
