package de.phillip.bdpaste;

import de.phillip.bdpaste.api.BdPasteApi;
import de.phillip.bdpaste.api.BdPasteApiImpl;
import de.phillip.bdpaste.cmd.BdCommand;
import de.phillip.bdpaste.interact.ClickListener;
import de.phillip.bdpaste.interact.Hitboxes;
import de.phillip.bdpaste.label.Labels;
import de.phillip.bdpaste.parse.ModelLibrary;
import de.phillip.bdpaste.place.PlacementManager;
import de.phillip.bdpaste.place.PlacementPrefs;
import de.phillip.bdpaste.registry.PlacedModels;
import de.phillip.bdpaste.spawn.ModelSpawner;
import de.phillip.bdpaste.util.Settings;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BDPaste - imports models built on <a href="https://bdengine.app">bdengine.app</a> and
 * lets players drop them into the world with their crosshair instead of pasting command blocks.
 */
public final class BDPastePlugin extends JavaPlugin {

    /** Scoreboard tag on every entity this plugin spawns. */
    public static final String TAG = "bdpaste";
    /** Additional tag while an entity is still only a preview. */
    public static final String TAG_PREVIEW = "bdpaste_preview";

    private final Settings settings = new Settings();
    private final Set<String> warnedUnknown = ConcurrentHashMap.newKeySet();

    private NamespacedKey keyModelId;
    private NamespacedKey keyModelName;
    private NamespacedKey keyTool;
    private NamespacedKey keyPartIndex;
    private ModelLibrary library;
    private ModelSpawner spawner;
    private PlacedModels placed;
    private PlacementManager placement;
    private PlacementPrefs prefs;
    private Hitboxes hitboxes;
    private Labels labels;
    private BdPasteApi api;
    private de.phillip.bdpaste.anim.AnimationPlayer animations;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings.load(getConfig());

        keyModelId = new NamespacedKey(this, "model_id");
        keyModelName = new NamespacedKey(this, "model_name");
        keyTool = new NamespacedKey(this, "placement_tool");
        keyPartIndex = new NamespacedKey(this, "part_index");

        library = new ModelLibrary(this);
        library.ensureFolder();

        spawner = new ModelSpawner(this);

        // Built before the registry, because adding a model asks them for a hitbox and a label.
        hitboxes = new Hitboxes(this);
        labels = new Labels(this);

        placed = new PlacedModels(this);
        placed.load();
        placed.start();

        prefs = new PlacementPrefs(this);
        prefs.load();

        placement = new PlacementManager(this);
        placement.start();

        animations = new de.phillip.bdpaste.anim.AnimationPlayer(this);
        animations.start();

        labels.start();
        hitboxes.start();

        api = new BdPasteApiImpl(this);
        getServer().getServicesManager().register(BdPasteApi.class, api, this, ServicePriority.Normal);

        getServer().getPluginManager().registerEvents(placement, this);
        getServer().getPluginManager().registerEvents(new ClickListener(this), this);

        PluginCommand command = getCommand("bdpaste");
        if (command == null) {
            getSLF4JLogger().error("The bdpaste command is missing from plugin.yml - disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(new BdCommand(this));

        int stale = 0;
        for (World world : getServer().getWorlds()) {
            stale += sweepPreviews(world.getEntities());
        }
        if (stale > 0) getSLF4JLogger().info("Cleaned up {} leftover preview entities", stale);

        // Every model is measured against its file again as its chunk comes in: that is what
        // gives models placed before hitboxes existed theirs, and what corrects boxes left over
        // from a version that sized them differently.
        labels.refreshAll();

        getSLF4JLogger().info("BDPaste ready - {} model(s) in {}",
                library.names().size(), library.folder());
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (animations != null) animations.stop();
        if (labels != null) labels.stop();
        if (hitboxes != null) hitboxes.stop();
        if (placement != null) placement.stop();
        if (placed != null) placed.stop();
        if (prefs != null) prefs.save();
    }

    /** Previews are spawned non-persistent, but a hard crash can still leave some behind. */
    private int sweepPreviews(Iterable<Entity> entities) {
        int removed = 0;
        for (Entity entity : entities) {
            if (entity.getScoreboardTags().contains(TAG_PREVIEW)) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    public void reloadSettings() {
        reloadConfig();
        settings.load(getConfig());
        library.clearCache();
        warnedUnknown.clear();
        // Height, scale and colours all come from here, so every label has to be redone.
        if (labels != null) labels.refreshAll();
        // Padding and max-boxes come from here too, so every clickable shell is re-laid.
        if (hitboxes != null) hitboxes.forget();
    }

    /** Logs an unknown block or item once, so a broken model does not flood the console. */
    public void warnUnknown(String type, String name) {
        if (warnedUnknown.add(type + ":" + name)) {
            getSLF4JLogger().warn("Unknown {} '{}' in a model - substituting stone", type, name);
        }
    }

    /**
     * Logs a block whose state Minecraft would not take, so the plain block was used instead.
     *
     * <p>Worth saying out loud rather than quietly doing: a bell with its default state stands
     * on the floor and shows its gold body, while the same bell hung between two walls shows a
     * wooden bar. Same block, completely different shape, and until now nothing in the log said
     * the model had not got what it asked for.</p>
     */
    public void warnBlockState(String name, String bare) {
        if (warnedUnknown.add("state:" + name)) {
            getSLF4JLogger().warn("Block state of '{}' was not accepted - falling back to plain "
                    + "'{}' with its default state, which may look quite different", name, bare);
        }
    }

    /** Says something to the console once per server run, however often it comes up. */
    public void warnOnce(String key, String message) {
        if (warnedUnknown.add("once:" + key)) getSLF4JLogger().info(message);
    }

    /** Logs a part that could not be spawned at all. Skipped, not substituted. */
    public void warnPartFailed(String name, Throwable cause) {
        if (warnedUnknown.add("failed:" + name)) {
            getSLF4JLogger().warn("Skipping part '{}' - it could not be spawned", name, cause);
        }
    }

    public Settings settings() {
        return settings;
    }

    public ModelLibrary library() {
        return library;
    }

    public ModelSpawner spawner() {
        return spawner;
    }

    public PlacedModels placed() {
        return placed;
    }

    public PlacementManager placement() {
        return placement;
    }

    public PlacementPrefs prefs() {
        return prefs;
    }

    public de.phillip.bdpaste.anim.AnimationPlayer animations() {
        return animations;
    }

    public Hitboxes hitboxes() {
        return hitboxes;
    }

    public Labels labels() {
        return labels;
    }

    /** What other plugins talk to. Also handed out through the services manager. */
    public BdPasteApi api() {
        return api;
    }

    public NamespacedKey keyModelId() {
        return keyModelId;
    }

    public NamespacedKey keyModelName() {
        return keyModelName;
    }

    public NamespacedKey keyTool() {
        return keyTool;
    }

    public NamespacedKey keyPartIndex() {
        return keyPartIndex;
    }
}
