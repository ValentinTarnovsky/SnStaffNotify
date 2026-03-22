package com.sn.staffnotify.command;

import com.sn.staffnotify.SnStaffNotify;
import com.sn.staffnotify.config.ConfigManager;
import com.sn.staffnotify.config.MessagesManager;
import com.velocitypowered.api.command.SimpleCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Command handler for /staffnotify.
 * Subcommands: reload, help.
 */
public final class StaffNotifyCommand implements SimpleCommand {

    private static final List<String> SUBCOMMANDS = List.of("reload", "help");

    private final SnStaffNotify plugin;
    private final ConfigManager config;
    private final MessagesManager messages;

    public StaffNotifyCommand(SnStaffNotify plugin, ConfigManager config, MessagesManager messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(config.getCommandPermission());
    }

    @Override
    public void execute(Invocation invocation) {
        var source = invocation.source();
        String[] args = invocation.arguments();
        String sub = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "help";

        switch (sub) {
            case "reload" -> {
                plugin.reload();
                source.sendMessage(messages.getMessage("reload-success"));
            }

            case "help" -> {
                source.sendMessage(messages.getMessage("help.header"));
                source.sendMessage(messages.getMessage("help.reload"));
                source.sendMessage(messages.getMessage("help.help-line"));
                source.sendMessage(messages.getMessage("help.footer"));
            }

            default -> source.sendMessage(messages.getMessage("no-permission"));
        }
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String prefix = invocation.arguments().length >= 1
                ? invocation.arguments()[0].toLowerCase(Locale.ROOT) : "";

        List<String> suggestions = new ArrayList<>(2);
        for (String sub : SUBCOMMANDS) {
            if (sub.startsWith(prefix)) {
                suggestions.add(sub);
            }
        }

        return CompletableFuture.completedFuture(suggestions);
    }
}
