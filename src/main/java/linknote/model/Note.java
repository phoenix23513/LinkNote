package linknote.model;

import java.nio.file.Path;
import java.util.UUID;

/** A saved version of one note. The editor keeps its draft separately. */
public record Note(UUID id, String title, Path path, String yaml, String body, String fingerprint) {
}
