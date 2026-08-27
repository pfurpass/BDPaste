package de.phillip.bdpaste.parse;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Model references pointing at <a href="https://block-display.com">block-display.com</a>.
 *
 * <p>A share link such as {@code bde.gg/b/298796} is a web page, not a file. The editor embedded
 * on that page asks {@code /api/v1/bde/commands} for the model, and so do we - the answer is the
 * merged entity NBT as JSON, which {@link ModelLoader} can read directly.</p>
 */
public final class BlockDisplayApi {

    /** Serves models published as ready-made summon commands. */
    public static final String COMMANDS = "https://block-display.com/api/v1/bde/commands";
    /** Serves models published as an editor project; tells us where the project file lives. */
    public static final String PROJECT_MODEL = "https://block-display.com/api/v1/bde/project-model";
    /** Serves the project file itself: gzipped, usually a PRJ2 container. */
    public static final String PROJECT_FILE = "https://block-display.com/api/v1/bde/project-file";

    /** {@code bde.gg/b/123}, {@code block-display.com/bd/123}, {@code bdengine.app/?...id=123} or a bare id. */
    private static final Pattern LINK = Pattern.compile(
            "(?:^|//|\\.)(?:bde\\.gg/b/|(?:www\\.)?block-display\\.com/bd/|bdengine\\.app/\\S*?[?&]id=)(\\d{1,12})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_ID = Pattern.compile("\\d{1,12}");

    private BlockDisplayApi() {
    }

    /** The numeric model id in {@code reference}, or {@code null} if it is not a block-display link. */
    public static String idOf(String reference) {
        if (reference == null) return null;
        String trimmed = reference.strip();

        if (BARE_ID.matcher(trimmed).matches()) return trimmed;

        Matcher matcher = LINK.matcher(trimmed);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static boolean isModelReference(String reference) {
        return idOf(reference) != null;
    }

    public static String modelIdBody(String id) {
        return "modelID=" + id;
    }

    public static String projectFileBody(String kind, String id) {
        return "kind=" + kind + "&id=" + id;
    }

    /** Turns a model title into something usable as a file name. */
    public static String fileNameOf(String title, String id) {
        if (title == null || title.isBlank()) return "bd-" + id;
        String cleaned = title.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        return cleaned.isBlank() ? "bd-" + id : cleaned;
    }
}
