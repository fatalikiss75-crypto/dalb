package com.example.birjaHW;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AdminCommands implements CommandExecutor {

    private final BirjaHW plugin;

    public AdminCommands(BirjaHW plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (command.getName().equalsIgnoreCase("praims")) {
            return handlePraimsCommand(sender, args);
        }

        return false;
    }

    private boolean handlePraimsCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("birja.admin")) {
            sender.sendMessage("§cУ вас нет прав!");
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                double balance = plugin.getPraims(player);
                sender.sendMessage("§6Ваш баланс праймов: §e" + formatNumber(balance));
            } else {
                sender.sendMessage("§cИспользование: /praims <give|set|take> <игрок> <количество>");
            }
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cИспользование: /praims <give|set|take> <игрок> <количество>");
            return true;
        }

        String action = args[0].toLowerCase();
        Player target = Bukkit.getPlayer(args[1]);

        if (target == null) {
            sender.sendMessage("§cИгрок не найден!");
            return true;
        }

        double amount;
        try {
            amount = parseAmount(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cНеверное число!");
            return true;
        }

        double currentBalance = plugin.getPraims(target);

        switch (action) {
            case "give":
                plugin.givePraims(target, amount);
                sender.sendMessage("§a✓ Выдано §e" + formatNumber(amount) + " праймов §aигроку §e" + target.getName());
                target.sendMessage("§a✓ Вы получили §e" + formatNumber(amount) + " праймов");
                break;

            case "set":
                plugin.getConfig().set("praims." + target.getUniqueId(), amount);
                plugin.saveConfig();
                sender.sendMessage("§a✓ Баланс праймов игрока §e" + target.getName() + " §aустановлен на §e" + formatNumber(amount));
                target.sendMessage("§a✓ Ваш баланс праймов установлен на §e" + formatNumber(amount));
                break;

            case "take":
                plugin.takePraims(target, amount);
                sender.sendMessage("§a✓ Забрано §e" + formatNumber(amount) + " праймов §aу игрока §e" + target.getName());
                target.sendMessage("§c✗ У вас забрали §e" + formatNumber(amount) + " праймов");
                break;

            default:
                sender.sendMessage("§cИспользование: /praims <give|set|take> <игрок> <количество>");
                return true;
        }

        return true;
    }

    private double parseAmount(String str) throws NumberFormatException {
        str = str.toUpperCase().trim();
        double multiplier = 1;

        if (str.endsWith("B")) {
            multiplier = 1000000000;
            str = str.substring(0, str.length() - 1);
        } else if (str.endsWith("M")) {
            multiplier = 1000000;
            str = str.substring(0, str.length() - 1);
        } else if (str.endsWith("K")) {
            multiplier = 1000;
            str = str.substring(0, str.length() - 1);
        }

        return Double.parseDouble(str) * multiplier;
    }

    private String formatNumber(double number) {
        if (number >= 1000000000) {
            return String.format("%.1fB", number / 1000000000);
        } else if (number >= 1000000) {
            return String.format("%.1fM", number / 1000000);
        } else if (number >= 1000) {
            return String.format("%.1fK", number / 1000);
        } else {
            return String.format("%.0f", number);
        }
    }
}