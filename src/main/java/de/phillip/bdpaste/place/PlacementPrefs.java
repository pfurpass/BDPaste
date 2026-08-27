package de.phillip.bdpaste.place;

import de.phillip.bdpaste.BDPastePlugin;
import de.phillip.bdpaste.util.Settings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Per player placement settings that outlive a single session, so step sizes, grid snap and
 * the selected value are still there next time instead of falling back to the defaults.
 */
public final class PlacementPrefs {

    /** What is worth remembering about how someone likes to place things. */
    public static final class Entry {
        final EnumMap<PlacementSession.Param, Double> steps = new EnumMap<>(PlacementSession.Param.class);
        Settings.SnapMode snap;
        Boolean surface;
        Boolean repeat;
        PlacementSession.Param param;
    }

    private final BDPastePlugin plugin;
    private final File file;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private boolean dirty;

    public PlacementPrefs(BDPastePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    // ------------------------------------------------------------ persistence

    public void load() {
        entries.clear();
        if (!file.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;
            try {
                UUID id = UUID.fromString(key);
                Entry entry = new Entry();

                if (section.isBoolean("snap")) {
                    // Written before snapping grew a pixel mode.
                    entry.snap = section.getBoolean("snap") ? Settings.SnapMode.BLOCK : Settings.SnapMode.OFF;
                } else if (section.isString("snap")) {
                    entry.snap = parseSnap(section.getString("snap"));
                }
                if (section.isBoolean("surface")) entry.surface = section.getBoolean("surface");
                if (section.isBoolean("repeat")) entry.repeat = section.getBoolean("repeat");
                entry.param = parseParam(section.getString("param"));

                ConfigurationSection steps = section.getConfigurationSection("steps");
                if (steps != null) {
                    for (String name : steps.getKeys(false)) {
                        PlacementSession.Param param = parseParam(name);
                        double value = steps.getDouble(name);
                        if (param != null && value > 0) entry.steps.put(param, value);
                    }
                }
                entries.put(id, entry);
            } catch (IllegalArgumentException ignored) {
                plugin.getSLF4JLogger().warn("Skipping unreadable player preference entry {}", key);
            }
        }
    }

    public void save() {
        if (!dirty) return;

        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Entry> player : entries.entrySet()) {
            String base = "players." + player.getKey();
            Entry entry = player.getValue();

            if (entry.snap != null) yaml.set(base + ".snap", entry.snap.name());
            if (entry.surface != null) yaml.set(base + ".surface", entry.surface);
            if (entry.repeat != null) yaml.set(base + ".repeat", entry.repeat);
            if (entry.param != null) yaml.set(base + ".param", entry.param.name());
            for (Map.Entry<PlacementSession.Param, Double> step : entry.steps.entrySet()) {
                yaml.set(base + ".steps." + step.getKey().name(), step.getValue());
            }
        }
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getSLF4JLogger().error("Could not write players.yml", ex);
        }
    }

    private static Settings.SnapMode parseSnap(String raw) {
        if (raw == null) return null;
        try {
            return Settings.SnapMode.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static PlacementSession.Param parseParam(String raw) {
        if (raw == null) return null;
        try {
            return PlacementSession.Param.valueOf(raw.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ usage

    /** Puts a player's remembered settings onto a fresh session. */
    public void applyTo(UUID player, PlacementSession session) {
        Entry entry = entries.get(player);
        if (entry == null) return;

        if (entry.snap != null) session.setSnap(entry.snap);
        if (entry.surface != null) session.setSurface(entry.surface);
        if (entry.repeat != null) session.setRepeat(entry.repeat);
        if (entry.param != null) session.setParam(entry.param);
        entry.steps.forEach(session::setStep);
    }

    /** Takes the current settings off a session and keeps them for next time. */
    public void remember(UUID player, PlacementSession session) {
        Entry entry = entries.computeIfAbsent(player, id -> new Entry());

        entry.snap = session.snapMode();
        entry.surface = session.surfaceEnabled();
        entry.repeat = session.repeatEnabled();
        entry.param = session.activeParam();
        for (PlacementSession.Param param : PlacementSession.Param.values()) {
            entry.steps.put(param, session.step(param));
        }
        dirty = true;
        save();
    }

    /** Forgets the stored step sizes so the config defaults apply again. */
    public void forgetSteps(UUID player) {
        Entry entry = entries.get(player);
        if (entry == null) return;
        entry.steps.clear();
        dirty = true;
        save();
    }
}
