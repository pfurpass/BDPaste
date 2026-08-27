package de.phillip.bdpaste.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** Small MiniMessage helpers so the rest of the plugin stays readable. */
public final class Msg {

    public static final String PREFIX = "<gradient:#ffb300:#ff7043><bold>BDPaste</bold></gradient> <dark_gray>»</dark_gray> ";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Msg() {
    }

    public static Component of(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(miniMessage, resolvers);
    }

    public static Component prefixed(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(PREFIX + miniMessage, resolvers);
    }

    public static void send(CommandSender to, String miniMessage, TagResolver... resolvers) {
        to.sendMessage(prefixed(miniMessage, resolvers));
    }

    public static void plain(CommandSender to, String miniMessage, TagResolver... resolvers) {
        to.sendMessage(of(miniMessage, resolvers));
    }

    public static void error(CommandSender to, String miniMessage, TagResolver... resolvers) {
        to.sendMessage(prefixed("<red>" + miniMessage + "</red>", resolvers));
    }

    public static TagResolver arg(String key, String value) {
        return Placeholder.unparsed(key, value == null ? "" : value);
    }

    /** MiniMessage treats {@code <} as a tag opener, so anything user supplied gets escaped. */
    public static String escape(String raw) {
        return raw == null ? "" : MM.escapeTags(raw);
    }
}
