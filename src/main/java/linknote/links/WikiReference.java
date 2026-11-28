package linknote.links;

/** A parsed link's UTF-16 range in the exact Markdown body that was parsed. */
public record WikiReference(String target, int start, int end) {
}
