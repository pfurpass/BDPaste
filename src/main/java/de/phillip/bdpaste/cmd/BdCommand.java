package de.phillip.bdpaste.cmd;

import de.phillip.bdpaste.BDPastePlugin;
import de.phillip.bdpaste.interact.ClickListener;
import de.phillip.bdpaste.model.BdPart;
import de.phillip.bdpaste.model.DisplayKind;
import de.phillip.bdpaste.place.PlacementSession;
import de.phillip.bdpaste.registry.Placement;
import de.phillip.bdpaste.util.Msg;
import de.phillip.bdpaste.util.Settings;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Everything the plugin exposes lives under {@code /bdpaste}. */
public final class BdCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of(
            "list", "inspect", "place", "duplicate", "move", "replace", "repeat", "freeze", "pause", "resume", "step", "snap", "confirm", "cancel", "set", "undo", "remove", "delete",
            "animate", "label", "command", "info", "near", "tp", "import", "hitbox", "cleanup", "reload", "help");

    /** A command line the server would refuse anyway is not worth storing. */
    private static final int MAX_COMMAND = 256;

    private final BDPastePlugin plugin;

    public BdCommand(BDPastePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("bdpaste.use")) {
            Msg.error(sender, "You are not allowed to use BDPaste.");
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "list" -> list(sender);
            case "inspect" -> inspect(sender, rest);
            case "place" -> place(sender, rest);
            case "duplicate", "copy", "clone" -> duplicate(sender);
            case "repeat" -> repeat(sender, rest);
            case "move" -> move(sender);
            case "replace" -> replace(sender, rest);
            case "freeze" -> freeze(sender);
            case "pause" -> pause(sender);
            case "resume" -> resume(sender);
            case "step" -> step(sender, rest);
            case "snap" -> snap(sender, rest);
            case "confirm" -> confirm(sender);
            case "cancel" -> cancel(sender);
            case "set" -> set(sender, rest);
            case "undo" -> undo(sender);
            case "remove" -> remove(sender);
            case "delete" -> delete(sender, rest);
            case "animate" -> animate(sender, rest);
            case "info" -> info(sender);
            case "near" -> near(sender, rest);
            case "tp" -> teleport(sender, rest);
            case "import" -> importUrl(sender, rest);
            case "label" -> label(sender, rest);
            case "command" -> clickCommand(sender, rest);
            case "hitbox" -> hitbox(sender, rest);
            case "cleanup" -> cleanup(sender, rest);
            case "reload" -> reload(sender);
            default -> help(sender, label);
        }
        return true;
    }

    // -------------------------------------------------------------- handlers

    private void help(CommandSender sender, String label) {
        Msg.send(sender, "<white>BDEngine models, placed with your crosshair.</white>");
        Msg.plain(sender, "<yellow>/<l> list</yellow> <dark_gray>-</dark_gray> <gray>show importable models", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> inspect <model></yellow> <dark_gray>-</dark_gray> <gray>parse it without spawning", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> place <model></yellow> <dark_gray>-</dark_gray> <gray>start placing", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> duplicate</yellow> <dark_gray>-</dark_gray> <gray>copy the model you are looking at", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> repeat [on|off]</yellow> <dark_gray>-</dark_gray> <gray>keep placing without retyping the command", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> move</yellow> <dark_gray>-</dark_gray> <gray>pick the model you are looking at back up", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> replace <model></yellow> <dark_gray>-</dark_gray> <gray>swap it for another model, same spot", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> set <value> <number></yellow> <dark_gray>-</dark_gray> <gray>exact yaw/scale/offset while placing", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> freeze</yellow> <dark_gray>-</dark_gray> <gray>park the preview so you can walk around it", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> pause</yellow> <dark_gray>-</dark_gray> <gray>step out, build something, then resume", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> resume</yellow> <dark_gray>-</dark_gray> <gray>step back into placement mode", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> snap [off|pixel|block]</yellow> <dark_gray>-</dark_gray> <gray>how far positions get rounded", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> step [number|reset] [value]</yellow> <dark_gray>-</dark_gray> <gray>show, set or reset step sizes", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> confirm</yellow> <dark_gray>-</dark_gray> <gray>place it (same as right click)", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> cancel</yellow> <dark_gray>-</dark_gray> <gray>abort placing", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> undo</yellow> <dark_gray>-</dark_gray> <gray>remove what you placed last", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> remove</yellow> <dark_gray>-</dark_gray> <gray>remove the model you are looking at", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> animate [name] [speed] [once|loop|off]</yellow> <dark_gray>-</dark_gray> <gray>play an animation", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> info</yellow> <dark_gray>-</dark_gray> <gray>details about that model", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> near [radius]</yellow> <dark_gray>-</dark_gray> <gray>list nearby models", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> tp <id></yellow> <dark_gray>-</dark_gray> <gray>teleport to a model", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> import <url-or-id> [name]</yellow> <dark_gray>-</dark_gray> <gray>fetch from a link or model id", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> label <text|raise <n>|off></yellow> <dark_gray>-</dark_gray> <gray>floating name over a model, MiniMessage", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> command <cmd></yellow> <dark_gray>-</dark_gray> <gray>run a command when the model is clicked", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> hitbox [on|off|sync]</yellow> <dark_gray>-</dark_gray> <gray>make a model clickable", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> cleanup <radius></yellow> <dark_gray>-</dark_gray> <gray>delete every BDPaste display nearby", Msg.arg("l", label));
        Msg.plain(sender, "<yellow>/<l> reload</yellow> <dark_gray>-</dark_gray> <gray>reload config and rescan models", Msg.arg("l", label));
    }

    /**
     * Reads a number a player typed, and refuses anything that is not a real one.
     *
     * <p>{@link Double#parseDouble} is perfectly happy to hand back {@code NaN} and
     * {@code Infinity}, and every range check written the obvious way waves them through -
     * {@code NaN < -64} is false, and so is {@code NaN > 64}. One of them in a scale poisons
     * that model's transform: the matrix goes to NaN, so does its bounding box, and a box of
     * NaN can never be hit by a ray again - the model becomes impossible to look at, move or
     * remove. And it is written to placements.yml, so it survives a restart.</p>
     *
     * @return the number, or null when it was not one - the player has been told why
     */
    private Double number(CommandSender to, String text, String whatFor) {
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            Msg.error(to, "'" + Msg.escape(text) + "' is not a number.");
            return null;
        }
        if (!Double.isFinite(value)) {
            Msg.error(to, "'" + Msg.escape(text) + "' is not a real number" + whatFor + ".");
            return null;
        }
        return value;
    }

    private void list(CommandSender sender) {
        List<String> names = plugin.library().names();
        if (names.isEmpty()) {
            Msg.send(sender, "<yellow>No models yet.</yellow> <gray>Drop .bdengine, .json, .txt or datapack .zip files into</gray>");
            Msg.plain(sender, "<gray>  <path></gray>", Msg.arg("path", plugin.library().folder().toString()));
            return;
        }
        Msg.send(sender, "<white><count> model(s):</white> <gray><names></gray>",
                Msg.arg("count", String.valueOf(names.size())),
                Msg.arg("names", String.join(", ", names)));
    }

    /** Parses a model without spawning anything - handy to check an export before placing it. */
    private void inspect(CommandSender sender, String[] args) {
        if (args.length < 1) {
            Msg.error(sender, "Usage: /bdpaste inspect <model>");
            return;
        }
        plugin.library().loadAsync(args[0],
                model -> {
                    Map<DisplayKind, Long> byKind = model.parts().stream()
                            .collect(Collectors.groupingBy(BdPart::kind, Collectors.counting()));
                    Vector3f size = model.dimensions();

                    Msg.send(sender, "<white><model></white> <gray>- <n> display entities</gray>",
                            Msg.arg("model", model.name()), Msg.arg("n", String.valueOf(model.size())));
                    Msg.plain(sender, "<gray>  blocks <white><nblocks></white>, items <white><nitems></white>, text <white><ntexts></white>",
                            Msg.arg("nblocks", String.valueOf(byKind.getOrDefault(DisplayKind.BLOCK, 0L))),
                            Msg.arg("nitems", String.valueOf(byKind.getOrDefault(DisplayKind.ITEM, 0L))),
                            Msg.arg("ntexts", String.valueOf(byKind.getOrDefault(DisplayKind.TEXT, 0L))));
                    Msg.plain(sender, "<gray>  size <white><sx> x <sy> x <sz></white> blocks",
                            Msg.arg("sx", String.format(Locale.ROOT, "%.2f", size.x)),
                            Msg.arg("sy", String.format(Locale.ROOT, "%.2f", size.y)),
                            Msg.arg("sz", String.format(Locale.ROOT, "%.2f", size.z)));

                    String top = model.parts().stream()
                            .collect(Collectors.groupingBy(p -> p.name().split("\\[")[0], Collectors.counting()))
                            .entrySet().stream()
                            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                            .limit(6)
                            .map(e -> e.getKey() + " x" + e.getValue())
                            .collect(Collectors.joining(", "));
                    Msg.plain(sender, "<gray>  most used: <white><top></white>", Msg.arg("top", top));
                    if (model.hasBakedAnimation()) {
                        String names = model.baked().stream()
                                .map(x -> x.name() + " (" + x.length() + "t)")
                                .collect(Collectors.joining(", "));
                        Msg.plain(sender, "<gray>  baked animations: <white><names></white>",
                                Msg.arg("names", names));
                    } else if (model.hasAnimation()) {
                        String names = model.tracks().stream()
                                .filter(t -> t.length() > 0 || t.sound() != null)
                                .map(t -> t.name() + (t.length() > 0
                                        ? " (" + trim(t.length()) + "t)"
                                        : " (sound only)"))
                                .collect(Collectors.joining(", "));
                        Msg.plain(sender, "<gray>  animations: <white><names></white> on "
                                        + "<white><n> parts</white> <gray>(/bdpaste animate)</gray>",
                                Msg.arg("names", names),
                                Msg.arg("n", String.valueOf(model.animatedParts())));
                        if (model.hasSound()) {
                            String withSound = model.tracks().stream()
                                    .filter(t -> t.sound() != null)
                                    .map(t -> t.name() + " (" + t.sound().notes().size() + " notes, "
                                            + trim(t.sound().lengthInKeyframes()) + "t)")
                                    .collect(Collectors.joining(", "));
                            Msg.plain(sender, "<gray>  with sound: <white><s></white>",
                                    Msg.arg("s", withSound));
                        }
                    }
                },
                error -> Msg.error(sender, Msg.escape(error)));
    }

    private void place(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !require(player, "bdpaste.place")) return;
        if (args.length < 1) {
            Msg.error(player, "Usage: /bdpaste place <model>");
            return;
        }

        String name = args[0];
        Msg.send(player, "<gray>Loading <white><model></white>...</gray>", Msg.arg("model", name));
        plugin.library().loadAsync(name,
                model -> {
                    if (!player.isOnline()) return;
                    plugin.placement().begin(player, model, name);
                },
                error -> Msg.error(player, Msg.escape(error)));
    }

    private void pause(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (!plugin.placement().isPlacing(player)) {
            Msg.error(player, "You are not placing anything.");
            return;
        }
        plugin.placement().suspend(player);
    }

    private void resume(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (!plugin.placement().isPlacing(player)) {
            Msg.error(player, "You are not placing anything.");
            return;
        }
        plugin.placement().resume(player);
    }

    /** Picks how far the model origin gets rounded while it follows the crosshair. */
    private void snap(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        Optional<PlacementSession> found = plugin.placement().session(player);
        if (found.isEmpty()) {
            Msg.error(player, "You are not placing anything.");
            return;
        }
        PlacementSession session = found.get();

        if (args.length == 0) {
            plugin.placement().announceSnap(player, session.cycleSnap());
        } else {
            try {
                session.setSnap(Settings.SnapMode.valueOf(args[0].toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                Msg.error(player, "Pick one of: off, pixel, block");
                return;
            }
            plugin.placement().announceSnap(player, session.snapMode());
        }
        plugin.placement().remember(player, session);
    }

    /** Shows, sets or resets how much one scroll notch changes a value. */
    private void step(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        Optional<PlacementSession> found = plugin.placement().session(player);
        if (found.isEmpty()) {
            Msg.error(player, "You are not placing anything.");
            return;
        }
        PlacementSession session = found.get();

        if (args.length == 0) {
            showSteps(player, session);
            return;
        }
        if (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("default")) {
            session.resetSteps();
            plugin.prefs().forgetSteps(player.getUniqueId());
            plugin.placement().remember(player, session);
            Msg.send(player, "<green>Step sizes are back to the defaults.</green>");
            showSteps(player, session);
            return;
        }

        Double parsed = number(player, args[0], " - use a number, or 'reset'");
        if (parsed == null) return;
        double size = parsed;
        if (size <= 0) {
            Msg.error(player, "The step has to be greater than zero.");
            return;
        }

        PlacementSession.Param target = session.activeParam();
        if (args.length >= 2) {
            try {
                target = PlacementSession.Param.valueOf(args[1].toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException ex) {
                Msg.error(player, "Unknown value. Pick one of: " + String.join(", ", paramNames()));
                return;
            }
        }
        if (target == PlacementSession.Param.SCALE && size <= 1) {
            Msg.error(player, "Scale multiplies, so its step must be greater than 1 (try 1.05).");
            return;
        }

        session.setStep(target, size);
        plugin.placement().remember(player, session);
        Msg.send(player, "<green>One notch now changes <white><param></white> by <white><size></white>.</green>",
                Msg.arg("param", target.label()), Msg.arg("size", trim(size)));
    }

    private void showSteps(Player player, PlacementSession session) {
        Msg.send(player, "<white>Step sizes</white> <gray>(left click cycles the selected one)</gray>");
        for (PlacementSession.Param param : PlacementSession.Param.values()) {
            boolean active = param == session.activeParam();
            Msg.plain(player, "<gray>  <marker> <name></gray> <white><size></white>",
                    Msg.arg("marker", active ? ">" : " "),
                    Msg.arg("name", param.label()),
                    Msg.arg("size", (param == PlacementSession.Param.SCALE ? "x" : "")
                            + trim(session.step(param))));
        }
        Msg.plain(player, "<gray>Reset them all with <white>/bdpaste step reset</white>.</gray>");
    }

    /** 0.25 instead of 0.250000, 15 instead of 15.0. */
    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.5f", value).replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    private void freeze(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (!plugin.placement().isPlacing(player)) {
            Msg.error(player, "You are not placing anything.");
            return;
        }
        plugin.placement().toggleFreeze(player);
    }

    private void confirm(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (!plugin.placement().isPlacing(player)) {
            Msg.error(player, "You are not placing anything.");
            return;
        }
        plugin.placement().confirm(player);
    }

    /** Hands you a fresh copy of the model you are looking at, original untouched. */
    private void duplicate(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null || !require(player, "bdpaste.place")) return;

        // Copying does not change anyone else's build, so ownership does not matter here.
        Optional<Placement> found = plugin.placed().lookingAt(player, plugin.settings().maxDistance);
        if (found.isEmpty()) {
            Msg.error(player, "You are not looking at a placed model.");
            return;
        }
        Placement original = found.get();
        PlacementSession.Pose pose = new PlacementSession.Pose(
                original.yaw(), original.pitch(), original.roll(), original.scale(),
                original.offsetX(), original.offsetY(), original.offsetZ());

        plugin.library().loadAsync(original.source(),
                model -> {
                    if (!player.isOnline()) return;
                    PlacementSession session = plugin.placement().begin(player, model, original.source(),
                            fresh -> fresh.seedPose(pose));
                    session.completionNote("Duplicated");
                },
                error -> Msg.error(player, "Cannot copy that: " + Msg.escape(error)));
    }

    /** Turns placing-in-a-row on or off. */
    private void repeat(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        Optional<PlacementSession> found = plugin.placement().session(player);
        if (found.isEmpty()) {
            Msg.error(player, "You are not placing anything.");
            return;
        }
        PlacementSession session = found.get();

        boolean on;
        if (args.length == 0) {
            on = !session.repeatEnabled();
        } else if (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("true")) {
            on = true;
        } else if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("false")) {
            on = false;
        } else {
            Msg.error(player, "Usage: /bdpaste repeat [on|off]");
            return;
        }

        session.setRepeat(on);
        plugin.placement().remember(player, session);

        if (on && !session.isRepeatable()) {
            Msg.send(player, "<yellow>Repeat is on, but a move never repeats - "
                    + "it would copy instead of moving.</yellow>");
        } else if (on) {
            Msg.send(player, "<green>Repeat on.</green> <gray>After each placement you get the next copy.</gray>");
        } else {
            Msg.send(player, "<gray>Repeat off.</gray>");
        }
    }

    /** Picks a placed model back up and hands it to the crosshair again. */
    private void move(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null || !require(player, "bdpaste.place")) return;

        Placement old = targeted(player);
        if (old == null) return;

        Location origin = old.location();
        if (origin == null) {
            Msg.error(player, "That model's world is not loaded.");
            return;
        }
        PlacementSession.Pose pose = new PlacementSession.Pose(
                old.yaw(), old.pitch(), old.roll(), old.scale(),
                old.offsetX(), old.offsetY(), old.offsetZ());

        // The stored position already has the offsets baked in; freezing works off the bare
        // anchor, because targetLocation() adds the offsets back on top.
        Location anchor = origin.clone().subtract(old.offsetX(), old.offsetY(), old.offsetZ());

        plugin.library().loadAsync(old.source(),
                model -> {
                    if (!player.isOnline()) return;
                    if (plugin.placed().delete(old) < 0) {
                        Msg.error(player, "Another plugin is holding on to that model.");
                        return;
                    }
                    PlacementSession session = plugin.placement().begin(player, model, old.source(),
                            fresh -> {
                                fresh.seedPose(pose);
                                fresh.seedLabel(old.label());
                                fresh.seedLabelOffset(old.labelOffset());
                                // Stay put, so tweaking one value does not mean aiming all over again.
                                fresh.freezeAt(anchor);
                                fresh.setRepeatable(false);
                            });
                    session.completionNote("Moved");
                    // Cancelling must not make the model disappear for good.
                    session.restoreOnCancel(new PlacementSession.Restore(
                            model, old.source(), origin, pose));
                },
                error -> Msg.error(player, "Cannot pick that up: " + Msg.escape(error)));
    }

    /** Swaps a placed model for a different one, keeping position, rotation and scale. */
    private void replace(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !require(player, "bdpaste.place")) return;
        if (args.length < 1) {
            Msg.error(player, "Usage: /bdpaste replace <model>");
            return;
        }

        Placement old = targeted(player);
        if (old == null) return;

        Location origin = old.location();
        if (origin == null) {
            Msg.error(player, "That model's world is not loaded.");
            return;
        }
        PlacementSession.Pose pose = new PlacementSession.Pose(
                old.yaw(), old.pitch(), old.roll(), old.scale(),
                old.offsetX(), old.offsetY(), old.offsetZ());
        String source = args[0];

        // Load first, delete second: a typo must not cost you the model that is already there.
        plugin.library().loadAsync(source,
                model -> {
                    if (!player.isOnline()) return;
                    if (plugin.placed().delete(old) < 0) {
                        Msg.error(player, "Another plugin is holding on to that model.");
                        return;
                    }
                    plugin.placement().beginFixed(player, model, source, origin, pose,
                            "Replaced with", old.label(), old.labelOffset());
                },
                error -> Msg.error(player, Msg.escape(error)));
    }

    /**
     * Removes a model and says what happened.
     *
     * @return false when another plugin cancelled {@code BdModelRemoveEvent}
     */
    private boolean deleteAndReport(CommandSender sender, Placement placement) {
        int removed = plugin.placed().delete(placement);
        if (removed < 0) {
            Msg.error(sender, "Another plugin is holding on to that model.");
            return false;
        }
        Msg.send(sender, "<green>Removed <white><model></white> <gray>(<n> entities)</gray>",
                Msg.arg("model", placement.model()), Msg.arg("n", String.valueOf(removed)));
        return true;
    }

    /** The placed model the player is aiming at, or {@code null} after explaining why not. */
    private Placement targeted(Player player) {
        Optional<Placement> found = plugin.placed().lookingAt(player, plugin.settings().maxDistance);
        if (found.isEmpty()) {
            Msg.error(player, "You are not looking at a placed model.");
            return null;
        }
        if (!canEdit(player, found.get())) {
            Msg.error(player, "That one belongs to " + Msg.escape(found.get().ownerName()) + ".");
            return null;
        }
        return found.get();
    }

    private void cancel(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (!plugin.placement().isPlacing(player)) {
            Msg.error(player, "You are not placing anything.");
            return;
        }
        plugin.placement().cancel(player, true);
    }

    private void set(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        Optional<PlacementSession> session = plugin.placement().session(player);
        if (session.isEmpty()) {
            Msg.error(player, "You are not placing anything.");
            return;
        }
        if (args.length < 2) {
            Msg.error(player, "Usage: /bdpaste set <" + String.join("|", paramNames()) + "> <number>");
            return;
        }

        PlacementSession.Param param;
        try {
            param = PlacementSession.Param.valueOf(args[0].toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            Msg.error(player, "Unknown value. Pick one of: " + String.join(", ", paramNames()));
            return;
        }

        Double parsed = number(player, args[1], "");
        if (parsed == null) return;

        session.get().setParam(param);
        session.get().setValue(param, parsed);
        Msg.send(player, "<green><param> set to <white><value></white>.</green>",
                Msg.arg("param", param.label()), Msg.arg("value", String.valueOf(parsed)));
    }

    private void undo(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null || !require(player, "bdpaste.remove")) return;

        Optional<Placement> placement = plugin.placed().popUndo(player.getUniqueId())
                .flatMap(id -> plugin.placed().get(id));
        if (placement.isEmpty()) {
            Msg.error(player, "Nothing left to undo.");
            return;
        }
        deleteAndReport(player, placement.get());
    }

    private void remove(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null || !require(player, "bdpaste.remove")) return;

        Optional<Placement> placement = plugin.placed().lookingAt(player, plugin.settings().maxDistance);
        if (placement.isEmpty()) {
            Msg.error(player, "You are not looking at a placed model.");
            return;
        }
        if (!canEdit(player, placement.get())) {
            Msg.error(player, "That one belongs to " + Msg.escape(placement.get().ownerName()) + ".");
            return;
        }
        deleteAndReport(player, placement.get());
    }

    private void delete(CommandSender sender, String[] args) {
        if (!require(sender, "bdpaste.remove")) return;
        if (args.length < 1) {
            Msg.error(sender, "Usage: /bdpaste delete <id>");
            return;
        }
        Optional<Placement> placement = plugin.placed().resolve(args[0]);
        if (placement.isEmpty()) {
            Msg.error(sender, "No placed model with that id.");
            return;
        }
        if (sender instanceof Player player && !canEdit(player, placement.get())) {
            Msg.error(sender, "That one belongs to " + Msg.escape(placement.get().ownerName()) + ".");
            return;
        }
        deleteAndReport(sender, placement.get());
    }

    /** Starts, stops or re-speeds the keyframe animation of the model you are looking at. */
    private void animate(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !require(player, "bdpaste.place")) return;

        Placement target = targeted(player);
        if (target == null) return;

        boolean running = plugin.animations().isPlaying(target.id());

        if (args.length >= 1 && (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("stop"))) {
            plugin.animations().reset(target.id());
            plugin.placed().setAnimating(target.id(), false, target.animationSpeed());
            Msg.send(player, "<gray>Animation stopped.</gray>");
            return;
        }

        // Arguments in any order: a number is the speed, "once" or "loop" says whether it
        // repeats, and anything else names an animation.
        double speed = target.animationSpeed();
        String animation = target.animationName();
        boolean loop = true;
        for (String arg : args) {
            if (arg.equalsIgnoreCase("on")) continue;
            if (arg.equalsIgnoreCase("once") || arg.equalsIgnoreCase("norepeat")
                    || arg.equalsIgnoreCase("noloop")) {
                loop = false;
                continue;
            }
            if (arg.equalsIgnoreCase("loop") || arg.equalsIgnoreCase("repeat")) {
                loop = true;
                continue;
            }
            // Anything that is not a number at all is taken as the animation's name, so a
            // bad number here cannot be told apart from a name - but NaN and Infinity parse,
            // and a speed of NaN stops the timeline dead.
            try {
                double asNumber = Double.parseDouble(arg);
                if (!Double.isFinite(asNumber) || asNumber <= 0 || asNumber > 20) {
                    Msg.error(player, "Speed has to be between 0 and 20.");
                    return;
                }
                speed = asNumber;
            } catch (NumberFormatException ex) {
                animation = arg;
            }
        }

        // Already running and no new speed asked for means this was meant as a toggle.
        if (running && args.length == 0) {
            plugin.animations().reset(target.id());
            plugin.placed().setAnimating(target.id(), false, target.animationSpeed());
            Msg.send(player, "<gray>Animation stopped.</gray>");
            return;
        }
        // Only a plain speed change can be applied to a running model. Anything else - a
        // different track, or switching between looping and one-shot - has to start over.
        if (running && loop && animation.equalsIgnoreCase(target.animationName())) {
            plugin.animations().setSpeed(target.id(), speed);
            plugin.placed().setAnimating(target.id(), true, speed, animation);
            Msg.send(player, "<green>Speed set to <white><speed>x</white>.</green>",
                    Msg.arg("speed", trim(speed)));
            return;
        }

        // Switching track means the running one has to go first.
        if (running) plugin.animations().stop(target.id());

        double chosen = speed;
        String chosenName = animation;
        boolean chosenLoop = loop;
        Msg.send(player, "<gray>Starting animation...</gray>");
        plugin.animations().startAsync(target, chosen, chosenName, chosenLoop, result -> {
            switch (result) {
                case STARTED -> {
                    // A one-shot is not written down: that flag is what makes a model start
                    // itself again after a restart, which is a loop thing.
                    plugin.placed().setAnimating(target.id(), chosenLoop, chosen, chosenName);
                    Msg.send(player, "<green>Animating <white><model></white> at <white><speed>x</white>.</green>",
                            Msg.arg("model", target.model()), Msg.arg("speed", trim(chosen)));
                    Msg.plain(player, "<gray>Stop it again with <white>/bdpaste animate off</white>.</gray>");
                }
                case NO_ANIMATION -> Msg.error(player, "That model has no keyframes in it.");
                case NO_SUCH_ANIMATION -> Msg.error(player,
                        "That model has no animation called '" + Msg.escape(chosenName) + "'. "
                                + "Use /bdpaste inspect to see the names.");
                case NO_ENTITIES -> {
                    Msg.error(player, "Could not match the model to its entities.");
                    Msg.plain(player, "<gray>Models placed before animation support have no part "
                            + "index - place it again and it will work.</gray>");
                }
                case TOO_MANY -> Msg.error(player, "Too many models are animating already "
                        + "(see animation.max-playing).");
                case DISABLED -> Msg.error(player, "Animations are disabled in config.yml.");
            }
        });
    }

    private void info(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        Optional<Placement> found = plugin.placed().lookingAt(player, plugin.settings().maxDistance);
        if (found.isEmpty()) {
            Msg.error(player, "You are not looking at a placed model.");
            return;
        }
        Placement p = found.get();
        Msg.send(player, "<white><model></white> <gray>#<id></gray>",
                Msg.arg("model", p.model()), Msg.arg("id", p.shortId()));
        Msg.plain(player, "<gray>  parts <white><n></white>, scale <white><sc></white>, yaw <white><yw>°</white>",
                Msg.arg("n", String.valueOf(p.parts())),
                Msg.arg("sc", String.format(Locale.ROOT, "%.2f", p.scale())),
                Msg.arg("yw", String.format(Locale.ROOT, "%.0f", p.yaw())));
        if (p.animating()) {
            Msg.plain(player, "<gray>  playing <white><anim></white> at <white><speed>x</white>",
                    Msg.arg("anim", p.animationName().isBlank() ? "its first animation" : p.animationName()),
                    Msg.arg("speed", trim(p.animationSpeed())));
        }

        // The full id is what the API takes, and every copy of a model has its own. A UUID
        // cannot contain MiniMessage syntax, so putting it straight into the tag is safe.
        String full = p.id().toString();
        Msg.plain(player, "<gray>  id <white><click:copy_to_clipboard:'" + full + "'>"
                + "<hover:show_text:'<gray>Click to copy'>" + full + "</hover></click></white>");
        Msg.plain(player, "<gray>  at <white><x> <yy> <z></white> by <white><owner></white>, <age> ago",
                Msg.arg("x", String.format(Locale.ROOT, "%.1f", p.x())),
                Msg.arg("yy", String.format(Locale.ROOT, "%.1f", p.y())),
                Msg.arg("z", String.format(Locale.ROOT, "%.1f", p.z())),
                Msg.arg("owner", p.ownerName()),
                Msg.arg("age", age(p.placedAt())));
    }

    private void near(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        double radius = 64;
        if (args.length >= 1) {
            Double parsed = number(player, args[0], "");
            if (parsed == null) return;
            radius = Math.min(512, Math.max(1, parsed));
        }
        List<Placement> found = plugin.placed().near(player.getLocation(), radius);
        if (found.isEmpty()) {
            Msg.send(player, "<yellow>No placed models within <r> blocks.</yellow>",
                    Msg.arg("r", String.format(Locale.ROOT, "%.0f", radius)));
            return;
        }
        Msg.send(player, "<white><n> model(s) nearby:</white>", Msg.arg("n", String.valueOf(found.size())));
        for (Placement p : found.stream().limit(15).toList()) {
            Msg.plain(player, "<gray>  #<id> <white><model></white> <gray>at <x> <yy> <z> (<parts> parts)",
                    Msg.arg("id", p.shortId()), Msg.arg("model", p.model()),
                    Msg.arg("x", String.format(Locale.ROOT, "%.0f", p.x())),
                    Msg.arg("yy", String.format(Locale.ROOT, "%.0f", p.y())),
                    Msg.arg("z", String.format(Locale.ROOT, "%.0f", p.z())),
                    Msg.arg("parts", String.valueOf(p.parts())));
        }
    }

    private void teleport(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !require(player, "bdpaste.admin")) return;
        if (args.length < 1) {
            Msg.error(player, "Usage: /bdpaste tp <id>");
            return;
        }
        Optional<Placement> placement = plugin.placed().resolve(args[0]);
        Location target = placement.map(Placement::location).orElse(null);
        if (target == null) {
            Msg.error(player, "No placed model with that id (or its world is not loaded).");
            return;
        }
        player.teleport(target);
        Msg.send(player, "<green>Teleported to <white><model></white>.</green>",
                Msg.arg("model", placement.get().model()));
    }

    private void importUrl(CommandSender sender, String[] args) {
        if (!require(sender, "bdpaste.import")) return;
        if (args.length < 1) {
            Msg.error(sender, "Usage: /bdpaste import <url-or-id> [name]");
            return;
        }

        // Accept both orders: the argument that looks like a link or an id is the source.
        String source = args[0];
        String name = args.length > 1 ? args[1] : null;
        if (args.length > 1 && !looksLikeSource(args[0]) && looksLikeSource(args[1])) {
            source = args[1];
            name = args[0];
        }

        Msg.send(sender, "<gray>Fetching <white><src></white>...</gray>", Msg.arg("src", source));
        plugin.library().downloadAsync(source, name,
                result -> {
                    String stored = result.contains(" ") ? result.substring(0, result.indexOf(' ')) : result;
                    String model = stored.contains(".") ? stored.substring(0, stored.lastIndexOf('.')) : stored;
                    Msg.send(sender, "<green>Imported <white><r></white>.</green>", Msg.arg("r", result));
                    Msg.plain(sender, "<gray>Place it with <white>/bdpaste place <model></white>",
                            Msg.arg("model", model));
                },
                error -> Msg.error(sender, "Import failed: " + Msg.escape(error)));
    }

    private static boolean looksLikeSource(String arg) {
        return arg.contains("/") || arg.contains(":") || arg.matches("\\d{1,12}");
    }

    /** Long enough for anything worth reading over a model, short enough not to be a weapon. */
    private static final int MAX_LABEL = 256;

    /**
     * Hangs a floating name over the model you are aiming at.
     *
     * <p>The text is MiniMessage, so colours are written as {@code <#ff8800>} and the usual
     * tags all work. Everything after the subcommand is taken as the text, spaces and all.</p>
     */
    private void label(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !require(player, "bdpaste.place")) return;

        Placement target = targeted(player);
        if (target == null) return;
        if (!canEdit(player, target)) {
            Msg.error(player, "That one belongs to " + Msg.escape(target.ownerName()) + ".");
            return;
        }

        if (args.length == 0) {
            if (target.label().isBlank()) {
                Msg.send(player, "<gray>No label on <white><model></white>.</gray>",
                        Msg.arg("model", target.model()));
            } else {
                Msg.send(player, "<gray>Label:</gray> <label>", Msg.arg("label", target.label()));
                Msg.plain(player, "<gray>  shown as:</gray> " + target.label());
                Msg.plain(player, "<gray>  raised by <white><n></white> blocks</gray>",
                        Msg.arg("n", trim(target.labelOffset())));
            }
            Msg.plain(player, "<gray>Set one with <white>/bdpaste label <#ff8800>Shop</white>, "
                    + "nudge it with <white>/bdpaste label raise -0.8</white>, "
                    + "clear it with <white>/bdpaste label off</white>.</gray>");
            return;
        }

        if (args.length == 1 && (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("clear"))) {
            plugin.placed().setLabel(target.id(), "");
            Msg.send(player, "<green>Label removed.</green>");
            return;
        }

        if (args[0].equalsIgnoreCase("raise") || args[0].equalsIgnoreCase("height")) {
            if (args.length < 2) {
                Msg.error(player, "Usage: /bdpaste label raise <blocks>");
                Msg.plain(player, "<gray>Negative moves it down. Currently <white><n></white>.</gray>",
                        Msg.arg("n", trim(target.labelOffset())));
                return;
            }
            Double parsed = number(player, args[1], "");
            if (parsed == null) return;
            double offset = parsed;
            if (offset < -64 || offset > 64) {
                Msg.error(player, "Keep it between -64 and 64.");
                return;
            }
            plugin.placed().setLabelOffset(target.id(), offset);
            Msg.send(player, "<green>Label raised by <white><n></white> blocks.</green>",
                    Msg.arg("n", trim(offset)));
            return;
        }

        String text = String.join(" ", args);
        if (text.length() > MAX_LABEL) {
            Msg.error(player, "That label is " + text.length() + " characters; the limit is "
                    + MAX_LABEL + ".");
            return;
        }

        plugin.placed().setLabel(target.id(), text);
        // Shown rendered, which is the only honest check there is - MiniMessage never rejects
        // anything, it just leaves a tag it does not know sitting there as text.
        Msg.send(player, "<green>Label set to</green> " + text);
    }

    /**
     * Manages the invisible boxes that make models clickable.
     *
     * <p>Without an argument it works on the model you are aiming at; {@code sync} brings every
     * model on record in line with the config, which is what you want after switching
     * {@code interaction.enabled} or loading chunks that were away at startup.</p>
     */
    /**
     * The command a model runs when somebody right clicks it.
     *
     * <p>The arguments are taken as typed, so quoting is not needed and a command can hold
     * spaces - {@code /bdpaste command warp lobby} is one command, not two arguments.</p>
     */
    private void clickCommand(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !require(player, "bdpaste.place")) return;

        Placement target = targeted(player);
        if (target == null) return;
        if (!canEdit(player, target)) {
            Msg.error(player, "That one belongs to " + Msg.escape(target.ownerName()) + ".");
            return;
        }

        if (args.length == 0) {
            if (target.clickCommand().isBlank()) {
                Msg.send(player, "<gray><white><model></white> runs no command when clicked.</gray>",
                        Msg.arg("model", target.model()));
            } else {
                Msg.send(player, "<gray>On click:</gray> <white>/<cmd></white>",
                        Msg.arg("cmd", target.clickCommand()));
            }
            Msg.plain(player, "<gray>Set one with <white>/bdpaste command sit</white>, "
                    + "clear it with <white>/bdpaste command off</white>.</gray>");
            Msg.plain(player, "<gray>Placeholders: <white>%player% %uuid% %model% %source% "
                    + "%id% %world% %x% %y% %z%</white></gray>");
            Msg.plain(player, "<gray>Prefix with <white>console:</white> to run it from the "
                    + "console instead of as the player.</gray>");
            return;
        }

        if (args.length == 1 && (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("clear"))) {
            if (target.clickCommand().isBlank()) {
                Msg.send(player, "<gray>There was none.</gray>");
                return;
            }
            plugin.placed().setClickCommand(target.id(), "");
            Msg.send(player, "<green>Click command removed.</green> "
                    + "<gray>Clicking does whatever the config says again.</gray>");
            return;
        }

        // Typed with a slash out of habit - drop it, since that is how it is stored.
        String command = String.join(" ", args).trim();
        if (command.startsWith("/")) command = command.substring(1).trim();

        if (command.regionMatches(true, 0, ClickListener.CONSOLE_PREFIX, 0,
                ClickListener.CONSOLE_PREFIX.length())) {
            // Running from the console hands the player something they have no permission for,
            // so putting one on a model is an admin's business, not a builder's.
            if (!require(player, "bdpaste.admin")) return;
            String rest = command.substring(ClickListener.CONSOLE_PREFIX.length()).trim();
            if (rest.startsWith("/")) rest = rest.substring(1).trim();
            if (rest.isEmpty()) {
                Msg.error(player, "There is no command after 'console:'.");
                return;
            }
            command = ClickListener.CONSOLE_PREFIX + " " + rest;
        }

        if (command.length() > MAX_COMMAND) {
            Msg.error(player, "Keep it under " + MAX_COMMAND + " characters.");
            return;
        }

        plugin.placed().setClickCommand(target.id(), command);
        Msg.send(player, "<green><white><model></white> now runs <white>/<cmd></white> "
                        + "when right clicked.</green>",
                Msg.arg("model", target.model()), Msg.arg("cmd", command));

        if (!plugin.hitboxes().has(target)) {
            Msg.plain(player, "<yellow>It has no hitbox yet, so nobody can click it.</yellow> "
                    + "<gray>Turn one on with <white>/bdpaste hitbox on</white>.</gray>");
        }
    }

    private void hitbox(CommandSender sender, String[] args) {
        if (!require(sender, "bdpaste.admin")) return;

        if (args.length >= 1 && args[0].equalsIgnoreCase("sync")) {
            int queued = plugin.hitboxes().syncAll();
            Msg.send(sender, "<green>Re-measuring <white><n></white> model(s).</green> "
                            + "<gray>Each needs its model file, so they follow over the next "
                            + "few seconds; ones in unloaded chunks wait for their chunk.</gray>",
                    Msg.arg("n", String.valueOf(queued)));
            return;
        }

        Player player = requirePlayer(sender);
        if (player == null) return;
        Placement target = targeted(player);
        if (target == null) return;

        // Read off the record, not off whether the entities happen to be up: they are put
        // up again a moment after the model's chunk loads, and asking too early would say no.
        boolean has = target.clickable();
        if (args.length == 0) {
            Msg.send(player, has
                            ? "<white><model></white> <gray>is</gray> <green>clickable</green>"
                            : "<white><model></white> <gray>is</gray> <yellow>not clickable</yellow>",
                    Msg.arg("model", target.model()));
            Msg.plain(player, "<gray>Change it with <white>/bdpaste hitbox on|off</white>.</gray>");
            return;
        }

        boolean wanted = args[0].equalsIgnoreCase("on");
        if (!wanted && !args[0].equalsIgnoreCase("off")) {
            Msg.error(sender, "Usage: /bdpaste hitbox [on|off|sync]");
            return;
        }
        if (wanted == has) {
            Msg.send(player, has ? "<gray>Already clickable.</gray>" : "<gray>Already not clickable.</gray>");
            return;
        }
        plugin.placed().setClickable(target.id(), wanted);
        Msg.send(player, wanted
                        ? "<green><white><model></white> is clickable now.</green>"
                        : "<green><white><model></white> is no longer clickable.</green>",
                Msg.arg("model", target.model()));
    }

    private void cleanup(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !require(player, "bdpaste.admin")) return;
        if (args.length < 1) {
            Msg.error(player, "Usage: /bdpaste cleanup <radius>");
            return;
        }
        Double parsed = number(player, args[0], "");
        if (parsed == null) return;
        double radius = Math.min(256, Math.max(1, parsed));
        int removed = plugin.placed().cleanup(player.getLocation(), radius);
        Msg.send(player, "<green>Deleted <n> BDPaste display entities.</green>",
                Msg.arg("n", String.valueOf(removed)));
    }

    private void reload(CommandSender sender) {
        if (!require(sender, "bdpaste.admin")) return;
        plugin.reloadSettings();
        Msg.send(sender, "<green>Config reloaded, model cache cleared.</green> <gray><n> model(s) available.</gray>",
                Msg.arg("n", String.valueOf(plugin.library().names().size())));
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> paramNames() {
        return java.util.Arrays.stream(PlacementSession.Param.values())
                .map(p -> p.name().toLowerCase(Locale.ROOT))
                .toList();
    }

    private boolean canEdit(Player player, Placement placement) {
        return player.hasPermission("bdpaste.admin")
                || player.getUniqueId().equals(placement.owner());
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) return player;
        Msg.error(sender, "Only a player can do that.");
        return null;
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        Msg.error(sender, "You are missing the permission " + permission + ".");
        return false;
    }

    private static String age(long epochMillis) {
        Duration d = Duration.between(Instant.ofEpochMilli(epochMillis), Instant.now());
        if (d.toDays() > 0) return d.toDays() + "d";
        if (d.toHours() > 0) return d.toHours() + "h";
        if (d.toMinutes() > 0) return d.toMinutes() + "m";
        return Math.max(0, d.toSeconds()) + "s";
    }

    // ------------------------------------------------------------ tab complete

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("bdpaste.use")) return List.of();

        if (args.length == 1) {
            return prefixed(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        if (args.length == 2) {
            return switch (sub) {
                case "place", "inspect", "replace" -> prefixed(plugin.library().names(), args[1]);
                case "set" -> prefixed(paramNames(), args[1]);
                case "delete", "tp" -> prefixed(
                        plugin.placed().all().stream().map(Placement::shortId).toList(), args[1]);
                case "near", "cleanup" -> prefixed(List.of("16", "32", "64", "128"), args[1]);
                case "hitbox" -> prefixed(List.of("on", "off", "sync"), args[1]);
                case "label" -> prefixed(List.of("off", "raise"), args[1]);
                case "command" -> prefixed(List.of("off", "console:"), args[1]);
                case "snap" -> prefixed(List.of("off", "pixel", "block"), args[1]);
                case "repeat" -> prefixed(List.of("on", "off"), args[1]);
                case "animate" -> prefixed(List.of("off", "once", "loop", "0.5", "1", "2", "4"), args[1]);
                case "step" -> prefixed(List.of("reset", "0.0625", "0.125", "0.25", "0.5", "1", "5", "15", "45", "90"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && sub.equals("step")) {
            return prefixed(paramNames(), args[2]);
        }
        if (args.length == 3 && sub.equals("set")) {
            return prefixed(List.of("0", "45", "90", "180", "1.0", "0.5", "2.0"), args[2]);
        }
        return List.of();
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String needle = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(needle)) out.add(option);
        }
        return out;
    }
}
