package com.example.birjaHW;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.ChatColor;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public final class BirjaHW extends JavaPlugin implements CommandExecutor, Listener {
    private Economy economy = null;
    private File ordersFile;
    private FileConfiguration ordersConfig;
    private Map<UUID, Order> activeOrders = new HashMap<>();
    private Map<UUID, String> menuType = new HashMap<>();

    // Для меню покупки/продажи - выбранное количество
    private Map<UUID, Integer> selectedAmount = new HashMap<>();
    // Для хранения выбранной заявки
    private Map<UUID, UUID> selectedOrder = new HashMap<>();
    
    // Константы для меню
    private static final String TITLE_MAIN = "§0§lБИРЖА ПРАЙМОВ";
    private static final String TITLE_BUY_AMOUNT = "§b§lВыбор количества для покупки";
    private static final String TITLE_SELL_AMOUNT = "§a§lВыбор количества для продажи";
    private static final String TITLE_CREATE_BUY = "§b§lСоздать заявку на покупку";
    private static final String TITLE_CREATE_SELL = "§a§lСоздать заявку на продажу";
    private static final String TITLE_MY_ORDERS = "§0§lМОИ ЗАЯВКИ";

    // Кастомная головка для отображения заявок
    private ItemStack customOrderHead = null;
    // Ключи для PersistentDataContainer
    private NamespacedKey amountKey;
    private NamespacedKey orderIdKey;

    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().severe("Vault не найден! Плагин отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        setupOrdersFile();
        loadOrders();
        loadCustomHead();

        amountKey = new NamespacedKey(this, "praims_amount");
        orderIdKey = new NamespacedKey(this, "order_id");

        getCommand("birja").setExecutor(this);
        getCommand("praims").setExecutor(new AdminCommands(this));
        getCommand("exchange").setExecutor(this);

        getServer().getPluginManager().registerEvents(this, this);
        startAutoRefresh();

        getLogger().info("Биржа Праймов успешно загружена с Vault!");
        getLogger().info("Загружено заявок: " + activeOrders.size());
    }

    @Override
    public void onDisable() {
        saveOrders();
        getLogger().info("Биржа Праймов выключена!");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    private void setupOrdersFile() {
        ordersFile = new File(getDataFolder(), "orders.yml");
        if (!ordersFile.exists()) {
            try {
                ordersFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        ordersConfig = YamlConfiguration.loadConfiguration(ordersFile);
    }

    private void loadOrders() {
        activeOrders.clear();
        if (ordersConfig.contains("orders")) {
            for (String key : ordersConfig.getConfigurationSection("orders").getKeys(false)) {
                try {
                    String path = "orders." + key;
                    UUID owner = UUID.fromString(ordersConfig.getString(path + ".owner"));
                    OrderType type = OrderType.valueOf(ordersConfig.getString(path + ".type"));
                    double amount = ordersConfig.getDouble(path + ".amount");
                    double rate = ordersConfig.getDouble(path + ".rate");
                    long timestamp = ordersConfig.getLong(path + ".timestamp", System.currentTimeMillis());

                    Order order = new Order(owner, type, amount, rate, timestamp);
                    activeOrders.put(UUID.fromString(key), order);
                } catch (Exception e) {
                    getLogger().warning("Ошибка загрузки заявки: " + key);
                }
            }
        }
    }

    private void saveOrders() {
        ordersConfig.set("orders", null);
        for (Map.Entry<UUID, Order> entry : activeOrders.entrySet()) {
            String path = "orders." + entry.getKey().toString();
            Order order = entry.getValue();
            ordersConfig.set(path + ".owner", order.owner.toString());
            ordersConfig.set(path + ".type", order.type.name());
            ordersConfig.set(path + ".amount", order.amount);
            ordersConfig.set(path + ".rate", order.rate);
            ordersConfig.set(path + ".timestamp", order.timestamp);
        }
        try {
            ordersConfig.save(ordersFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadCustomHead() {
        if (getConfig().contains("order-head")) {
            try {
                customOrderHead = getConfig().getItemStack("order-head");
                if (customOrderHead != null) {
                    getLogger().info("Кастомная головка загружена успешно!");
                }
            } catch (Exception e) {
                getLogger().warning("Ошибка загрузки кастомной головки: " + e.getMessage());
                customOrderHead = null;
            }
        }
    }

    private void saveCustomHead(ItemStack head) {
        try {
            getConfig().set("order-head", head);
            saveConfig();
            customOrderHead = head.clone();
            getLogger().info("Кастомная головка сохранена успешно!");
        } catch (Exception e) {
            getLogger().severe("Ошибка сохранения кастомной головки: " + e.getMessage());
        }
    }

    private void startAutoRefresh() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (menuType.containsKey(player.getUniqueId())) {
                        String menu = menuType.get(player.getUniqueId());
                        if ("MAIN".equals(menu)) {
                            refreshMainMenu(player);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 40L, 40L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("exchange")) {
            return handleExchangeCommand(sender, args);
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("§cЭта команда только для игроков!");
            return true;
        }

        Player player = (Player) sender;

        if (args.length > 0 && args[0].equalsIgnoreCase("reload") && player.hasPermission("birja.admin")) {
            loadOrders();
            player.sendMessage("§a✓ Биржа перезагружена! Заявок: " + activeOrders.size());
            return true;
        }

        openMainMenu(player);
        return true;
    }

    private boolean handleExchangeCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cЭта команда только для игроков!");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("birja.admin")) {
            openMainMenu(player);
            return true;
        }

        if (args.length == 0) {
            openMainMenu(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "sethead":
                ItemStack itemInHand = player.getInventory().getItemInMainHand();
                if (itemInHand == null || itemInHand.getType() == Material.AIR) {
                    player.sendMessage("§c§l✗ Держите предмет в руке!");
                    return true;
                }
                ItemStack headToSave = itemInHand.clone();
                headToSave.setAmount(1);
                saveCustomHead(headToSave);
                player.sendMessage("§a§l✓ Головка для заявок установлена!");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                break;

            case "resethead":
                customOrderHead = null;
                getConfig().set("order-head", null);
                saveConfig();
                player.sendMessage("§a§l✓ Головка сброшена на стандартную!");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                break;

            default:
                openMainMenu(player);
                break;
        }

        return true;
    }

    private void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_MAIN);
        fillMainMenu(inv, player);
        menuType.put(player.getUniqueId(), "MAIN");
        player.openInventory(inv);
    }

    private void refreshMainMenu(Player player) {
        if (player.getOpenInventory() != null && player.getOpenInventory().getTopInventory().getSize() == 54) {
            Inventory inv = player.getOpenInventory().getTopInventory();
            String title = ChatColor.stripColor(player.getOpenInventory().getTitle());
            if (title.equalsIgnoreCase(ChatColor.stripColor(TITLE_MAIN))) {
                fillMainMenu(inv, player);
            }
        }
    }

    private void fillMainMenu(Inventory inv, Player player) {
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, createItem(Material.BLACK_STAINED_GLASS_PANE, " "));
        }

        fillOrdersDisplay(inv, player);

        for (int i = 36; i < 45; i++) {
            inv.setItem(i, createItem(Material.BROWN_STAINED_GLASS_PANE, " "));
        }

        double praims = getPraims(player.getUniqueId());
        double money = economy.getBalance(player);
        double rate = calculateRate();

        inv.setItem(45, createItem(Material.ENDER_CHEST, "§e§lМои заявки",
                "§7Активных заявок: §e" + getPlayerOrdersCount(player),
                "",
                "§eНажмите для управления"));

        inv.setItem(46, createItem(Material.BLUE_STAINED_GLASS_PANE, " "));
        inv.setItem(47, createItem(Material.BLUE_STAINED_GLASS_PANE, " "));

        inv.setItem(48, createItem(Material.EMERALD_BLOCK, "§a§lПродать праймы",
                "§7Создать заявку на продажу",
                "§7Обменять праймы на монетки",
                "",
                "§7Ваш баланс праймов: §e" + formatNumber(praims),
                "",
                "§a§l→ Нажмите для создания"));

        inv.setItem(49, createItem(Material.NETHER_STAR, "§6§lТекущий курс",
                "§e" + formatNumber(rate) + " монеток §7= §a1 прайм",
                "",
                "§7Базовый курс: §f" + formatNumber(getConfig().getDouble("base-rate")) + " монеток",
                "§7Заявок на продажу: §a" + getOrdersByType(OrderType.SELL),
                "§7Заявок на покупку: §b" + getOrdersByType(OrderType.BUY)));

        inv.setItem(50, createItem(Material.DIAMOND_BLOCK, "§b§lКупить праймы",
                "§7Создать заявку на покупку",
                "§7Обменять монетки на праймы",
                "",
                "§7Ваш баланс монеток: §b" + formatNumber(money),
                "",
                "§b§l→ Нажмите для создания"));

        inv.setItem(51, createItem(Material.BLUE_STAINED_GLASS_PANE, " "));
        inv.setItem(52, createItem(Material.BLUE_STAINED_GLASS_PANE, " "));

        inv.setItem(53, createItem(Material.BARRIER, "§c§lЗакрыть", "§7Выйти из биржи"));
    }

    private ItemStack getOrderDisplayItem(boolean isMyOrder, OrderType type) {
        if (customOrderHead != null) {
            ItemStack clone = customOrderHead.clone();
            clone.setAmount(1);
            return clone;
        }
        if (isMyOrder) {
            return new ItemStack(Material.ENCHANTED_GOLDEN_APPLE);
        }
        return new ItemStack(type == OrderType.SELL ? Material.WRITABLE_BOOK : Material.ENCHANTED_BOOK);
    }

    private List<Map.Entry<UUID, Order>> getSortedOrders() {
        return activeOrders.entrySet().stream()
                .sorted((a, b) -> {
                    Order orderA = a.getValue();
                    Order orderB = b.getValue();
                    if (orderA.type == OrderType.SELL && orderB.type == OrderType.SELL) {
                        return Double.compare(orderA.rate, orderB.rate);
                    }
                    if (orderA.type == OrderType.BUY && orderB.type == OrderType.BUY) {
                        return Double.compare(orderB.rate, orderA.rate);
                    }
                    if (orderA.type == OrderType.SELL) return -1;
                    return 1;
                })
                .limit(36)
                .collect(Collectors.toList());
    }

    private void fillOrdersDisplay(Inventory inv, Player player) {
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, null);
        }

        List<Map.Entry<UUID, Order>> orders = getSortedOrders();

        int slot = 0;
        for (Map.Entry<UUID, Order> entry : orders) {
            if (slot >= 36) break;

            Order order = entry.getValue();
            Player owner = Bukkit.getPlayer(order.owner);
            String ownerName = owner != null ? owner.getName() : "§7Неизвестно";
            boolean isMyOrder = order.owner.equals(player.getUniqueId());

            ItemStack displayItem = getOrderDisplayItem(isMyOrder, order.type);
            double exchangeAmount = order.amount * order.rate;

            List<String> lore = new ArrayList<>();
            lore.add("§7Игрок: " + (isMyOrder ? "§6" : "§f") + ownerName);
            lore.add("§7Тип: " + (order.type == OrderType.SELL ? "§aПродажа" : "§bПокупка"));
            lore.add("");

            if (order.type == OrderType.SELL) {
                lore.add("§7Продаёт: §a" + formatNumber(order.amount) + " праймов");
                lore.add("§7Цена: §b" + formatNumber(exchangeAmount) + " монеток");
            } else {
                lore.add("§7Покупает: §a" + formatNumber(order.amount) + " праймов");
                lore.add("§7Платит: §b" + formatNumber(exchangeAmount) + " монеток");
            }

            lore.add("");
            lore.add("§7Курс: §e" + formatNumber(order.rate) + " монеток §7= §a1 прайм");

            if (isMyOrder) {
                lore.add("");
                lore.add("§e§l✦ Это ваша заявка!");
                lore.add("§7Нажмите для управления");
            } else {
                lore.add("");
                if (order.type == OrderType.SELL) {
                    lore.add("§b§l✦ Нажмите чтобы купить");
                    lore.add("§7(можно купить частично)");
                } else {
                    lore.add("§b§l✦ Нажмите чтобы продать");
                    lore.add("§7(можно продать частично)");
                }
            }

            ItemMeta meta = displayItem.getItemMeta();
            meta.setDisplayName((order.type == OrderType.SELL ? "§a§l" : "§b§l") + "Заявка #" + (slot + 1));
            meta.setLore(lore);

            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(orderIdKey, PersistentDataType.STRING, entry.getKey().toString());

            displayItem.setItemMeta(meta);

            int amount = 1;
            if (order.amount >= 1000000000) {
                amount = Math.min(64, (int)(order.amount / 1000000000));
            } else if (order.amount >= 10000000) {
                amount = Math.min(64, (int)(order.amount / 10000000));
            } else if (order.amount >= 1000000) {
                amount = Math.min(64, (int)(order.amount / 1000000));
            } else if (order.amount >= 1000) {
                amount = Math.min(64, (int)(order.amount / 1000));
            }
            displayItem.setAmount(Math.max(1, Math.min(64, amount)));

            inv.setItem(slot++, displayItem);
        }

        for (int i = slot; i < 36; i++) {
            inv.setItem(i, createItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
    }

    private void openPurchaseAmountMenu(Player player, UUID orderId, Order order) {
        selectedAmount.put(player.getUniqueId(), 1);
        selectedOrder.put(player.getUniqueId(), orderId);

        Inventory inv = Bukkit.createInventory(null, 54, TITLE_BUY_AMOUNT);
        fillPurchaseAmountMenu(inv, player, order);

        menuType.put(player.getUniqueId(), "BUY_AMOUNT");
        player.openInventory(inv);
    }

    private void fillPurchaseAmountMenu(Inventory inv, Player player, Order order) {
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, createItem(Material.BLACK_STAINED_GLASS_PANE, " "));
        }

        int selectedAmt = selectedAmount.getOrDefault(player.getUniqueId(), 1);
        double rate = order.rate;
        double totalCost = selectedAmt * rate;
        double playerMoney = economy.getBalance(player);
        boolean canAfford = playerMoney >= totalCost;
        double maxCanBuy = Math.min(order.amount, Math.floor(playerMoney / rate));

        Player seller = Bukkit.getPlayer(order.owner);
        String sellerName = seller != null ? seller.getName() : "§7Неизвестно";

        inv.setItem(4, createItem(Material.PLAYER_HEAD, "§e§lИнформация о заявке",
                "§7Продавец: §f" + sellerName,
                "§7Доступно: §a" + formatNumber(order.amount) + " праймов",
                "§7Курс: §e" + formatNumber(rate) + " монеток/прайм",
                "",
                "§7Вы можете купить макс: §b" + formatNumber(maxCanBuy) + " праймов"));

        int[] plusSlots = {20, 21, 22, 23, 24};
        int[] plusValues = {1, 5, 10, 50, 100};
        Material[] plusMaterials = {Material.LIME_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS, Material.LIME_TERRACOTTA, Material.LIME_CONCRETE, Material.LIME_WOOL};

        for (int i = 0; i < plusValues.length; i++) {
            inv.setItem(plusSlots[i], createItem(plusMaterials[i], "§a§l+" + plusValues[i], "§7Добавить §a" + plusValues[i] + " §7праймов", "", "§eКлик чтобы добавить"));
        }

        inv.setItem(31, createItem(Material.ENCHANTED_BOOK, "§6§lВыбрано: §e" + selectedAmt + " праймов",
                "§7Стоимость: §b" + formatNumber(totalCost) + " монеток",
                "§7Ваш баланс: " + (canAfford ? "§a" : "§c") + formatNumber(playerMoney) + " монеток",
                "",
                canAfford ? "§a§l✓ Достаточно средств" : "§c§l✗ Недостаточно средств!"));

        int[] minusSlots = {38, 39, 40, 41, 42};
        int[] minusValues = {1, 5, 10, 50, 100};
        Material[] minusMaterials = {Material.RED_STAINED_GLASS_PANE, Material.RED_STAINED_GLASS, Material.RED_TERRACOTTA, Material.RED_CONCRETE, Material.RED_WOOL};

        for (int i = 0; i < minusValues.length; i++) {
            inv.setItem(minusSlots[i], createItem(minusMaterials[i], "§c§l-" + minusValues[i], "§7Убрать §c" + minusValues[i] + " §7праймов", "", "§eКлик чтобы убрать"));
        }

        inv.setItem(48, createItem(canAfford ? Material.EMERALD_BLOCK : Material.BARRIER,
                canAfford ? "§a§l✓ Купить праймы" : "§c§l✗ Недостаточно средств",
                "§7Количество: §e" + selectedAmt + " праймов",
                "§7Стоимость: §b" + formatNumber(totalCost) + " монеток",
                "",
                canAfford ? "§a§lКлик чтобы купить!" : "§cНужно больше монеток!"));

        if (maxCanBuy > 0) {
            inv.setItem(49, createItem(Material.GOLD_BLOCK, "§6§l⚡ Купить максимум",
                    "§7Купить: §e" + formatNumber(maxCanBuy) + " праймов",
                    "§7Стоимость: §b" + formatNumber(maxCanBuy * rate) + " монеток",
                    "",
                    "§eКлик для максимальной покупки"));
        }

        inv.setItem(50, createItem(Material.ARROW, "§e§lНазад", "§7Вернуться к бирже"));
    }

    private void handlePurchaseAmountMenuClick(Player player, ItemStack item, Inventory inv) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return;
        String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        Material mat = item.getType();

        UUID orderId = selectedOrder.get(player.getUniqueId());
        Order order = orderId != null ? activeOrders.get(orderId) : null;
        if (order == null) {
            player.sendMessage("§cЗаявка больше не существует!");
            player.closeInventory();
            openMainMenu(player);
            return;
        }

        if (mat == Material.ARROW || name.toLowerCase().contains("назад")) {
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(this, () -> openMainMenu(player), 1L);
            return;
        }

        if (name.contains("Купить максимум") || (mat == Material.GOLD_BLOCK && name.contains("максимум"))) {
            double rate = order.rate;
            int maxCanBuy = (int)Math.min(order.amount, Math.floor(economy.getBalance(player) / rate));
            if (maxCanBuy > 0) {
                executePurchase(player, orderId, order, maxCanBuy);
            } else {
                player.sendMessage("§c§l✗ Недостаточно монеток!");
            }
            return;
        }

        if (name.contains("Купить праймы") || (mat == Material.EMERALD_BLOCK && name.contains("Купить"))) {
            int amount = selectedAmount.getOrDefault(player.getUniqueId(), 1);
            if (economy.getBalance(player) >= amount * order.rate) {
                executePurchase(player, orderId, order, amount);
            } else {
                player.sendMessage("§c§l✗ Недостаточно монеток!");
            }
            return;
        }

        if (name.startsWith("+")) {
            try {
                int val = Integer.parseInt(name.substring(1));
                int current = selectedAmount.getOrDefault(player.getUniqueId(), 1);
                selectedAmount.put(player.getUniqueId(), Math.min(current + val, (int)order.amount));
                fillPurchaseAmountMenu(inv, player, order);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.2f);
            } catch (Exception ignored) {}
        } else if (name.startsWith("-")) {
            try {
                int val = Integer.parseInt(name.substring(1));
                int current = selectedAmount.getOrDefault(player.getUniqueId(), 1);
                selectedAmount.put(player.getUniqueId(), Math.max(1, current - val));
                fillPurchaseAmountMenu(inv, player, order);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 0.8f);
            } catch (Exception ignored) {}
        }
    }

    private void executePurchase(Player player, UUID orderId, Order order, int amount) {
        double totalCost = amount * order.rate;
        if (!economy.has(player, totalCost)) {
            player.sendMessage("§c§l✗ Недостаточно монеток!");
            return;
        }

        economy.withdrawPlayer(player, totalCost);
        givePraims(player.getUniqueId(), amount);
        economy.depositPlayer(Bukkit.getOfflinePlayer(order.owner), totalCost);

        if (amount >= order.amount) {
            activeOrders.remove(orderId);
            notifyOwner(order.owner, "§a§l✓ Ваша заявка на продажу выполнена!", amount, totalCost);
        } else {
            order.amount -= amount;
            notifyOwner(order.owner, "§e§l⚡ Частичная продажа!", amount, totalCost);
        }

        saveOrders();
        player.sendMessage("§a§l✓ ПОКУПКА УСПЕШНА! §7Куплено: §e" + amount + " §7за §b" + formatNumber(totalCost));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 2.0f);
        player.closeInventory();
        Bukkit.getScheduler().runTaskLater(this, () -> openMainMenu(player), 1L);
    }

    private void notifyOwner(UUID uuid, String msg, double amount, double money) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) {
            p.sendMessage("");
            p.sendMessage(msg);
            p.sendMessage("§7Количество: §e" + formatNumber(amount));
            p.sendMessage("§7Сумма: §b" + formatNumber(money));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
        }
    }

    private void openSellToOrderMenu(Player player, UUID orderId, Order order) {
        selectedAmount.put(player.getUniqueId(), 1);
        selectedOrder.put(player.getUniqueId(), orderId);
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_SELL_AMOUNT);
        fillSellToOrderMenu(inv, player, order);
        menuType.put(player.getUniqueId(), "SELL_TO_ORDER");
        player.openInventory(inv);
    }

    private void fillSellToOrderMenu(Inventory inv, Player player, Order order) {
        for (int i = 0; i < 54; i++) inv.setItem(i, createItem(Material.BLACK_STAINED_GLASS_PANE, " "));
        int selectedAmt = selectedAmount.getOrDefault(player.getUniqueId(), 1);
        double rate = order.rate;
        double playerPraims = getPraims(player.getUniqueId());
        boolean canSell = playerPraims >= selectedAmt;
        double maxCanSell = Math.min(order.amount, playerPraims);

        inv.setItem(4, createItem(Material.PLAYER_HEAD, "§e§lИнформация о заявке", "§7Покупатель: §f" + (Bukkit.getOfflinePlayer(order.owner).getName()), "§7Нужно: §a" + formatNumber(order.amount), "§7Курс: §e" + formatNumber(rate), "", "§7Вы можете продать макс: §b" + formatNumber(maxCanSell)));

        int[] plusSlots = {20, 21, 22, 23, 24};
        int[] plusValues = {1, 5, 10, 50, 100};
        Material[] plusMaterials = {Material.LIME_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS, Material.LIME_TERRACOTTA, Material.LIME_CONCRETE, Material.LIME_WOOL};
        for (int i = 0; i < plusValues.length; i++) inv.setItem(plusSlots[i], createItem(plusMaterials[i], "§a§l+" + plusValues[i], "§7Добавить §a" + plusValues[i], "", "§eКлик чтобы добавить"));

        inv.setItem(31, createItem(Material.WRITABLE_BOOK, "§6§lВыбрано: §e" + selectedAmt, "§7Вы получите: §b" + formatNumber(selectedAmt * rate), "§7Баланс: " + (canSell ? "§a" : "§c") + formatNumber(playerPraims), "", canSell ? "§a§l✓ Достаточно" : "§c§l✗ Мало"));

        int[] minusSlots = {38, 39, 40, 41, 42};
        int[] minusValues = {1, 5, 10, 50, 100};
        Material[] minusMaterials = {Material.RED_STAINED_GLASS_PANE, Material.RED_STAINED_GLASS, Material.RED_TERRACOTTA, Material.RED_CONCRETE, Material.RED_WOOL};
        for (int i = 0; i < minusValues.length; i++) inv.setItem(minusSlots[i], createItem(minusMaterials[i], "§c§l-" + minusValues[i], "§7Убрать §c" + minusValues[i], "", "§eКлик чтобы убрать"));

        inv.setItem(48, createItem(canSell ? Material.DIAMOND_BLOCK : Material.BARRIER, canSell ? "§a§l✓ Продать" : "§c§l✗ Мало праймов", "§7Кол-во: §e" + selectedAmt, "§7Сумма: §b" + formatNumber(selectedAmt * rate)));
        if (maxCanSell > 0) inv.setItem(49, createItem(Material.GOLD_BLOCK, "§6§l⚡ Продать максимум", "§7Продать: §e" + formatNumber(maxCanSell)));
        inv.setItem(50, createItem(Material.ARROW, "§e§lНазад", "§7К бирже"));
    }

    private void handleSellToOrderMenuClick(Player player, ItemStack item, Inventory inv) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return;
        String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        Material mat = item.getType();
        UUID orderId = selectedOrder.get(player.getUniqueId());
        Order order = orderId != null ? activeOrders.get(orderId) : null;
        if (order == null) { player.closeInventory(); return; }

        if (mat == Material.ARROW || name.contains("Назад")) { player.closeInventory(); Bukkit.getScheduler().runTaskLater(this, () -> openMainMenu(player), 1L); return; }
        if (name.contains("Продать максимум")) {
            int maxCanSell = (int)Math.min(order.amount, getPraims(player.getUniqueId()));
            if (maxCanSell > 0) executeSellToOrder(player, orderId, order, maxCanSell);
            return;
        }
        if (name.contains("Продать") && mat == Material.DIAMOND_BLOCK) {
            int amt = selectedAmount.getOrDefault(player.getUniqueId(), 1);
            if (getPraims(player.getUniqueId()) >= amt) executeSellToOrder(player, orderId, order, amt);
            return;
        }
        if (name.startsWith("+")) {
            try {
                int val = Integer.parseInt(name.substring(1));
                selectedAmount.put(player.getUniqueId(), Math.min(selectedAmount.getOrDefault(player.getUniqueId(), 1) + val, (int)order.amount));
                fillSellToOrderMenu(inv, player, order);
            } catch (Exception ignored) {}
        } else if (name.startsWith("-")) {
            try {
                int val = Integer.parseInt(name.substring(1));
                selectedAmount.put(player.getUniqueId(), Math.max(1, selectedAmount.getOrDefault(player.getUniqueId(), 1) - val));
                fillSellToOrderMenu(inv, player, order);
            } catch (Exception ignored) {}
        }
    }

    private void executeSellToOrder(Player player, UUID orderId, Order order, int amount) {
        double totalReward = amount * order.rate;
        if (!takePraims(player.getUniqueId(), amount)) return;
        economy.depositPlayer(player, totalReward);
        givePraims(order.owner, amount);

        if (amount >= order.amount) {
            activeOrders.remove(orderId);
            notifyOwner(order.owner, "§a§l✓ Ваша заявка на покупку выполнена!", amount, totalReward);
        } else {
            order.amount -= amount;
            notifyOwner(order.owner, "§e§l⚡ Частичная покупка!", amount, totalReward);
        }
        saveOrders();
        player.sendMessage("§a§l✓ ПРОДАЖА УСПЕШНА!");
        player.closeInventory();
        Bukkit.getScheduler().runTaskLater(this, () -> openMainMenu(player), 1L);
    }

    private void openPurchaseMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_CREATE_BUY);
        double balance = economy.getBalance(player);
        double rate = calculateRate();
        for (int i = 0; i < 54; i++) inv.setItem(i, createItem(Material.CYAN_STAINED_GLASS_PANE, " "));
        inv.setItem(4, createItem(Material.GOLD_INGOT, "§6§lВаш баланс", "§7Монетки: §b" + formatNumber(balance), "", "§7Курс: §e" + formatNumber(rate)));
        double[] amounts = {1, 5, 10, 50, 100, 500, 1000, 5000};
        int[] slots = {20, 21, 22, 23, 24, 29, 30, 31};
        for (int i = 0; i < amounts.length; i++) inv.setItem(slots[i], createBuyAmountItem(amounts[i], rate, balance));
        inv.setItem(49, createItem(Material.ARROW, "§e§lНазад"));
        menuType.put(player.getUniqueId(), "BUY");
        player.openInventory(inv);
    }

    private void openSellMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_CREATE_SELL);
        double balance = getPraims(player.getUniqueId());
        double rate = calculateRate();
        for (int i = 0; i < 54; i++) inv.setItem(i, createItem(Material.GREEN_STAINED_GLASS_PANE, " "));
        inv.setItem(4, createItem(Material.DIAMOND, "§6§lВаш баланс", "§7Праймы: §a" + formatNumber(balance), "", "§7Курс: §e" + formatNumber(rate)));
        double[] amounts = {1, 5, 10, 50, 100, 500, 1000, 5000};
        int[] slots = {20, 21, 22, 23, 24, 29, 30, 31};
        for (int i = 0; i < amounts.length; i++) inv.setItem(slots[i], createSellAmountItem(amounts[i], rate, balance));
        inv.setItem(49, createItem(Material.ARROW, "§e§lНазад"));
        menuType.put(player.getUniqueId(), "SELL");
        player.openInventory(inv);
    }

    private void openMyOrdersMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_MY_ORDERS);
        for (int i = 0; i < 54; i++) inv.setItem(i, createItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        List<Map.Entry<UUID, Order>> playerOrders = activeOrders.entrySet().stream().filter(entry -> entry.getValue().owner.equals(player.getUniqueId())).collect(Collectors.toList());
        if (playerOrders.isEmpty()) {
            inv.setItem(22, createItem(Material.BARRIER, "§c§lНет активных заявок"));
        } else {
            int slot = 20;
            for (Map.Entry<UUID, Order> entry : playerOrders) {
                if (slot > 34) break;
                Order order = entry.getValue();
                ItemStack item = createItem(order.type == OrderType.SELL ? Material.EMERALD_BLOCK : Material.DIAMOND_BLOCK, (order.type == OrderType.SELL ? "§a§lПродажа" : "§b§lПокупка"), "§7Кол-во: §e" + formatNumber(order.amount), "§7Курс: §e" + formatNumber(order.rate), "", "§c§l✖ ЛКМ §7- Отменить");
                ItemMeta meta = item.getItemMeta();
                meta.getPersistentDataContainer().set(orderIdKey, PersistentDataType.STRING, entry.getKey().toString());
                item.setItemMeta(meta);
                inv.setItem(slot, item);
                slot++; if (slot % 9 == 7) slot += 4;
            }
        }
        inv.setItem(49, createItem(Material.ARROW, "§e§lНазад"));
        menuType.put(player.getUniqueId(), "MY_ORDERS");
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player player = (Player) e.getWhoClicked();
        if (e.getView() == null || e.getView().getTitle() == null) return;
        String title = ChatColor.stripColor(e.getView().getTitle());

        boolean our = false;
        if (title.equalsIgnoreCase(ChatColor.stripColor(TITLE_MAIN)) || title.equalsIgnoreCase(ChatColor.stripColor(TITLE_BUY_AMOUNT)) ||
            title.equalsIgnoreCase(ChatColor.stripColor(TITLE_SELL_AMOUNT)) || title.equalsIgnoreCase(ChatColor.stripColor(TITLE_CREATE_BUY)) ||
            title.equalsIgnoreCase(ChatColor.stripColor(TITLE_CREATE_SELL)) || title.equalsIgnoreCase(ChatColor.stripColor(TITLE_MY_ORDERS))) {
            our = true;
        }
        if (!our) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory().equals(player.getInventory())) return;
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;

        if (title.equalsIgnoreCase(ChatColor.stripColor(TITLE_BUY_AMOUNT))) handlePurchaseAmountMenuClick(player, item, e.getClickedInventory());
        else if (title.equalsIgnoreCase(ChatColor.stripColor(TITLE_SELL_AMOUNT))) handleSellToOrderMenuClick(player, item, e.getClickedInventory());
        else if (title.equalsIgnoreCase(ChatColor.stripColor(TITLE_CREATE_BUY))) handleBuyMenuClick(player, item);
        else if (title.equalsIgnoreCase(ChatColor.stripColor(TITLE_CREATE_SELL))) handleSellMenuClick(player, item);
        else if (title.equalsIgnoreCase(ChatColor.stripColor(TITLE_MAIN))) handleMainMenuClick(player, item);
        else if (title.equalsIgnoreCase(ChatColor.stripColor(TITLE_MY_ORDERS))) handleMyOrdersClick(player, item);
    }

    private void handleMainMenuClick(Player player, ItemStack item) {
        if (item.getType() == Material.BARRIER) player.closeInventory();
        else if (item.getType() == Material.EMERALD_BLOCK) openSellMenu(player);
        else if (item.getType() == Material.DIAMOND_BLOCK) openPurchaseMenu(player);
        else if (item.getType() == Material.ENDER_CHEST || item.getType() == Material.ENCHANTED_GOLDEN_APPLE) openMyOrdersMenu(player);
        else handleOrderClick(player, item);
    }

    private void handleOrderClick(Player player, ItemStack item) {
        if (!item.hasItemMeta()) return;
        String orderIdStr = item.getItemMeta().getPersistentDataContainer().get(orderIdKey, PersistentDataType.STRING);
        if (orderIdStr == null) return;
        UUID orderId = UUID.fromString(orderIdStr);
        Order order = activeOrders.get(orderId);
        if (order == null) { player.sendMessage("§cЗаявка не существует!"); return; }
        if (order.owner.equals(player.getUniqueId())) { openMyOrdersMenu(player); return; }
        if (order.type == OrderType.SELL) openPurchaseAmountMenu(player, orderId, order);
        else openSellToOrderMenu(player, orderId, order);
    }

    private void handleBuyMenuClick(Player player, ItemStack item) {
        if (item.getType() == Material.ARROW) { openMainMenu(player); return; }
        Double amount = item.getItemMeta().getPersistentDataContainer().get(amountKey, PersistentDataType.DOUBLE);
        if (amount == null) return;
        createBuyOrder(player, amount.intValue(), calculateRate());
        openMainMenu(player);
    }

    private void handleSellMenuClick(Player player, ItemStack item) {
        if (item.getType() == Material.ARROW) { openMainMenu(player); return; }
        Double amount = item.getItemMeta().getPersistentDataContainer().get(amountKey, PersistentDataType.DOUBLE);
        if (amount == null) return;
        createSellOrder(player, amount);
        openMainMenu(player);
    }

    private void handleMyOrdersClick(Player player, ItemStack item) {
        if (item.getType() == Material.ARROW) { openMainMenu(player); return; }
        String idStr = item.getItemMeta().getPersistentDataContainer().get(orderIdKey, PersistentDataType.STRING);
        if (idStr != null) { cancelSpecificOrder(player, UUID.fromString(idStr)); openMyOrdersMenu(player); }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        menuType.remove(e.getPlayer().getUniqueId());
        selectedAmount.remove(e.getPlayer().getUniqueId());
        selectedOrder.remove(e.getPlayer().getUniqueId());
    }

    private ItemStack createBuyAmountItem(double praims, double rate, double balance) {
        double cost = praims * rate;
        boolean can = balance >= cost;
        ItemStack item = new ItemStack(can ? Material.PAPER : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((can ? "§e" : "§c") + formatNumber(praims));
        meta.setLore(Arrays.asList("§7Купить §a" + formatNumber(praims), "§7Стоимость: §b" + formatNumber(cost), "", can ? "§aКлик для создания" : "§cМало монет"));
        meta.getPersistentDataContainer().set(amountKey, PersistentDataType.DOUBLE, praims);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createSellAmountItem(double praims, double rate, double balance) {
        boolean can = balance >= praims;
        ItemStack item = new ItemStack(can ? Material.WRITABLE_BOOK : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((can ? "§e" : "§c") + formatNumber(praims));
        meta.setLore(Arrays.asList("§7Продать §a" + formatNumber(praims), "§7Получите: §b" + formatNumber(praims * rate), "", can ? "§aКлик для создания" : "§cМало праймов"));
        meta.getPersistentDataContainer().set(amountKey, PersistentDataType.DOUBLE, praims);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createItem(Material m, String name, String... lore) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); meta.setLore(Arrays.asList(lore)); item.setItemMeta(meta); }
        return item;
    }

    private void createSellOrder(Player player, double amount) {
        if (getPraims(player.getUniqueId()) < amount) return;
        if (!takePraims(player.getUniqueId(), amount)) return;
        activeOrders.put(UUID.randomUUID(), new Order(player.getUniqueId(), OrderType.SELL, amount, calculateRate(), System.currentTimeMillis()));
        saveOrders();
        player.sendMessage("§a§l✓ Заявка на продажу создана!");
    }

    private void createBuyOrder(Player player, int amount, double price) {
        double total = amount * price;
        if (!economy.has(player, total)) return;
        economy.withdrawPlayer(player, total);
        activeOrders.put(UUID.randomUUID(), new Order(player.getUniqueId(), OrderType.BUY, amount, price, System.currentTimeMillis()));
        saveOrders();
        player.sendMessage("§a§l✓ Заявка на покупку создана!");
    }

    private void cancelSpecificOrder(Player player, UUID id) {
        Order o = activeOrders.remove(id);
        if (o == null) return;
        if (o.type == OrderType.SELL) givePraims(player.getUniqueId(), o.amount);
        else economy.depositPlayer(player, o.amount * o.rate);
        saveOrders();
        player.sendMessage("§a§l✓ Отменено!");
    }

    private int getPlayerOrdersCount(Player p) { return (int)activeOrders.values().stream().filter(o -> o.owner.equals(p.getUniqueId())).count(); }
    private int getOrdersByType(OrderType t) { return (int)activeOrders.values().stream().filter(o -> o.type == t).count(); }

    private double calculateRate() {
        double base = getConfig().getDouble("base-rate", 400000.0);
        double vol = getConfig().getDouble("volatility", 0.05);
        int diff = getOrdersByType(OrderType.BUY) - getOrdersByType(OrderType.SELL);
        return Math.max(getConfig().getDouble("min-rate", 100000.0), base + diff * vol * base);
    }

    public double getPraims(UUID id) { return getConfig().getDouble("praims." + id, 0.0); }
    public double getPraims(Player p) { return getPraims(p.getUniqueId()); }
    
    public boolean takePraims(UUID id, double amt) {
        double cur = getPraims(id); if (cur < amt) return false;
        getConfig().set("praims." + id, cur - amt); saveConfig(); return true;
    }
    public boolean takePraims(Player p, double amt) { return takePraims(p.getUniqueId(), amt); }

    public boolean givePraims(UUID id, double amt) {
        getConfig().set("praims." + id, getPraims(id) + amt); saveConfig(); return true;
    }
    public boolean givePraims(Player p, double amt) { return givePraims(p.getUniqueId(), amt); }

    private String formatNumber(double n) {
        if (n >= 1e9) return String.format("%.1fB", n/1e9);
        if (n >= 1e6) return String.format("%.1fM", n/1e6);
        if (n >= 1e3) return String.format("%.1fK", n/1e3);
        return String.format("%.0f", n);
    }

    private enum OrderType { SELL, BUY }
    private static class Order {
        UUID owner; OrderType type; double amount; double rate; long timestamp;
        Order(UUID o, OrderType t, double a, double r, long ts) { owner=o; type=t; amount=a; rate=r; timestamp=ts; }
    }
}