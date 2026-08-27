package de.phillip.bdpaste.parse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.phillip.bdpaste.BDPastePlugin;
import de.phillip.bdpaste.model.BdModel;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** The {@code plugins/BDPaste/models} folder: lists, caches and downloads model files. */
public final class ModelLibrary {

    private record Cached(BdModel model, long lastModified) {
    }

    private final BDPastePlugin plugin;
    private final Path folder;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ModelLibrary(BDPastePlugin plugin) {
        this.plugin = plugin;
        this.folder = plugin.getDataFolder().toPath().resolve("models");
    }

    public Path folder() {
        return folder;
    }

    public void ensureFolder() {
        try {
            Files.createDirectories(folder);
        } catch (IOException ex) {
            plugin.getSLF4JLogger().error("Could not create the models folder", ex);
        }
    }

    public void clearCache() {
        cache.clear();
    }

    /** Model names (file names without their extension), sorted alphabetically. */
    public List<String> names() {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(ModelLoader::isSupported)
                    .map(ModelLoader::modelNameOf)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException ex) {
            plugin.getSLF4JLogger().error("Could not read the models folder", ex);
            return List.of();
        }
    }

    public Optional<Path> find(String name) {
        if (!Files.isDirectory(folder) || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> matches = new ArrayList<>(files
                    .filter(Files::isRegularFile)
                    .filter(ModelLoader::isSupported)
                    .filter(p -> ModelLoader.modelNameOf(p).equalsIgnoreCase(name))
                    .toList());
            // .bdengine wins over a .txt of the same name
            matches.sort(Comparator.comparingInt(p -> ModelLoader.EXTENSIONS.indexOf(extensionOf(p))));
            return matches.stream().findFirst();
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private static String extensionOf(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    /**
     * Parses a model off the main thread and hands the result back on it.
     * Repeated loads are served from cache until the file changes.
     */
    public void loadAsync(String name, Consumer<BdModel> onSuccess, Consumer<String> onError) {
        Optional<Path> file = find(name);
        if (file.isEmpty()) {
            // Handed to the next tick like every other answer, rather than run before this
            // method has even returned. Otherwise a caller that sets its own state up after
            // the call would have that state overwrite whatever the callback just wrote - and
            // only for a name that does not exist, which is the path nobody tries.
            String message = "No model called '" + name + "' in the models folder.";
            plugin.getServer().getScheduler().runTask(plugin, () -> onError.accept(message));
            return;
        }
        Path path = file.get();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            BdModel model;
            try {
                long modified = Files.getLastModifiedTime(path).toMillis();
                Cached cached = cache.get(name.toLowerCase(Locale.ROOT));
                if (cached != null && cached.lastModified() == modified) {
                    model = cached.model();
                } else {
                    model = ModelLoader.load(path);
                    cache.put(name.toLowerCase(Locale.ROOT), new Cached(model, modified));
                }
            } catch (IOException | RuntimeException ex) {
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                plugin.getServer().getScheduler().runTask(plugin, () -> onError.accept(message));
                return;
            }

            BdModel result = model;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (result.size() == 0) {
                    onError.accept("That file contains no display entities.");
                } else if (result.size() > plugin.settings().maxParts) {
                    onError.accept("Model has " + result.size() + " parts, the limit is "
                            + plugin.settings().maxParts + " (see max-parts in config.yml).");
                } else {
                    onSuccess.accept(result);
                }
            });
        });
    }

    /**
     * Fetches a model into the models folder, off the main thread.
     *
     * @param source a direct file URL, a block-display.com share link, or a bare model id
     * @param name   the name to store it under, or {@code null} to derive one
     */
    public void downloadAsync(String source, String name, Consumer<String> onSuccess, Consumer<String> onError) {
        if (!plugin.settings().downloadsEnabled) {
            onError.accept("Downloads are disabled in config.yml.");
            return;
        }
        if (name != null && !name.matches("[A-Za-z0-9_.-]{1,64}")) {
            onError.accept("Use only letters, digits, '.', '-' and '_' for the model name.");
            return;
        }

        String modelId = BlockDisplayApi.idOf(source);
        if (modelId == null) {
            URI uri;
            try {
                uri = URI.create(withScheme(source));
            } catch (IllegalArgumentException ex) {
                onError.accept("That is neither a valid URL nor a block-display.com model id.");
                return;
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                onError.accept("Only http and https URLs are allowed.");
                return;
            }
            ensureFolder();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                    () -> fetchFile(uri, name, onSuccess, onError));
            return;
        }

        ensureFolder();
        String id = modelId;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> fetchFromBlockDisplay(id, name, onSuccess, onError));
    }

    /** People paste links without the scheme all the time. */
    private static String withScheme(String source) {
        String trimmed = source.strip();
        return trimmed.matches("(?i)^[a-z][a-z0-9+.-]*://.*") ? trimmed : "https://" + trimmed;
    }

    // --------------------------------------------------------------- fetching

    private void fetchFile(URI uri, String name, Consumer<String> onSuccess, Consumer<String> onError) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", userAgent())
                .header("Accept", "*/*")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        try (HttpClient client = http()) {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                fail(onError, "The server answered with HTTP " + response.statusCode() + ".");
                return;
            }
            byte[] data = readCapped(response.body());
            store(data, name, defaultNameFor(uri), onSuccess, onError);
        } catch (IOException | InterruptedException | RuntimeException ex) {
            fail(onError, describe(ex));
        }
    }

    /**
     * block-display.com publishes models in two ways and the page decides which per model:
     * older ones as ready summon commands, newer ones as an editor project file. Try the
     * command endpoint first, then fall back to the project one.
     */
    private void fetchFromBlockDisplay(String id, String name, Consumer<String> onSuccess, Consumer<String> onError) {
        try (HttpClient client = http()) {
            byte[] commands = post(client, BlockDisplayApi.COMMANDS, BlockDisplayApi.modelIdBody(id));
            JsonObject payload = successPayload(commands);

            if (payload != null && payload.has("Passengers")
                    && payload.getAsJsonArray("Passengers").size() > 0) {
                String title = payload.has("name") ? payload.get("name").getAsString() : null;
                store(commands, name, BlockDisplayApi.fileNameOf(title, id) + ".json", onSuccess, onError);
                return;
            }

            byte[] project = post(client, BlockDisplayApi.PROJECT_MODEL, BlockDisplayApi.modelIdBody(id));
            JsonObject info = successPayload(project);
            if (info == null) {
                fail(onError, "block-display.com has no model with id " + id + ".");
                return;
            }

            String fileName = info.has("projectFileName") ? info.get("projectFileName").getAsString() : null;
            String fallbackName = fileName != null && !fileName.isBlank()
                    ? BlockDisplayApi.fileNameOf(stripExtension(fileName), id) + ".bdengine"
                    : BlockDisplayApi.fileNameOf(null, id) + ".bdengine";

            if (info.has("projectFileReady") && info.get("projectFileReady").getAsBoolean()) {
                String kind = info.has("projectFileKind") ? info.get("projectFileKind").getAsString() : "model";
                String fileId = info.has("projectFileId") ? info.get("projectFileId").getAsString() : id;
                byte[] file = post(client, BlockDisplayApi.PROJECT_FILE,
                        BlockDisplayApi.projectFileBody(kind, fileId));
                store(file, name, fallbackName, onSuccess, onError);
                return;
            }

            String inline = info.has("projectBDE") ? info.get("projectBDE").getAsString() : "";
            if (inline.isBlank()) {
                fail(onError, "block-display.com returned no model data for id " + id + ".");
                return;
            }
            store(inline.getBytes(StandardCharsets.UTF_8), name, fallbackName, onSuccess, onError);
        } catch (IOException | InterruptedException | RuntimeException ex) {
            fail(onError, describe(ex));
        }
    }

    private byte[] post(HttpClient client, String url, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json, application/octet-stream, */*")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("block-display.com answered with HTTP " + response.statusCode() + ".");
        }
        return readCapped(response.body());
    }

    /** The {@code data} object of a successful API answer, or {@code null}. */
    private static JsonObject successPayload(byte[] json) {
        try {
            JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) return null;
            return root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Parses first and only then writes, so a broken download never lands in the library. */
    private void store(byte[] data, String name, String fallbackFileName,
                       Consumer<String> onSuccess, Consumer<String> onError) throws IOException {
        String fileName = name == null
                ? fallbackFileName
                : (name.contains(".") ? name : name + defaultExtension(fallbackFileName));

        String modelName = ModelLoader.modelNameOf(Path.of(fileName));
        BdModel model = ModelLoader.load(modelName, fileName, data);
        if (model.size() == 0) {
            throw new IOException("The downloaded model contains no display entities.");
        }

        // A re-import may land under a different extension; drop the older file so
        // /bdpaste list does not show two entries that resolve to the same name.
        for (String extension : ModelLoader.EXTENSIONS) {
            Path stale = folder.resolve(modelName + extension);
            if (!stale.getFileName().toString().equals(fileName)) Files.deleteIfExists(stale);
        }
        Files.write(folder.resolve(fileName), data);
        cache.remove(modelName.toLowerCase(Locale.ROOT));

        int parts = model.size();
        plugin.getServer().getScheduler().runTask(plugin,
                () -> onSuccess.accept(fileName + " (" + parts + " parts)"));
    }

    private HttpClient http() {
        // NORMAL follows 301/302/303/307/308 but never downgrades https to http.
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    private String userAgent() {
        return "BDPaste/" + plugin.getPluginMeta().getVersion() + " (Minecraft plugin)";
    }

    private byte[] readCapped(InputStream body) throws IOException {
        long limit = plugin.settings().downloadMaxMb * 1024L * 1024L;
        byte[] data;
        try (InputStream in = body) {
            data = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE));
        }
        if (data.length > limit) {
            throw new IOException("The download is larger than " + plugin.settings().downloadMaxMb + " MB.");
        }
        return data;
    }

    private static String defaultNameFor(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        int slash = path.lastIndexOf('/');
        String last = slash < 0 ? path : path.substring(slash + 1);
        last = last.replaceAll("[^A-Za-z0-9._-]", "");
        return last.isBlank() ? "downloaded.bdengine" : last;
    }

    private static String defaultExtension(String fallbackFileName) {
        int dot = fallbackFileName.lastIndexOf('.');
        return dot < 0 ? ".bdengine" : fallbackFileName.substring(dot);
    }

    private static String describe(Exception ex) {
        if (ex instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return "The download was interrupted.";
        }
        return ex.getMessage() == null ? ex.toString() : ex.getMessage();
    }

    private void fail(Consumer<String> onError, String message) {
        plugin.getServer().getScheduler().runTask(plugin, () -> onError.accept(message));
    }
}
