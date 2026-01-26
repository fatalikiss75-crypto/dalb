package com.example.birjaHW;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
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

        getCommand("birja").setExecutor(this);
        getCommand("praims").setExecutor(new AdminCommands(this));

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

    private void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "§6§lБиржа Праймов");
        fillMainMenu(inv, player);
        menuType.put(player.getUniqueId(), "MAIN");
        player.openInventory(inv);
    }

    private void refreshMainMenu(Player player) {
        if (player.getOpenInventory() != null && player.getOpenInventory().getTopInventory().getSize() == 54) {
            Inventory inv = player.getOpenInventory().getTopInventory();
            fillMainMenu(inv, player);
        }
    }

    private void fillMainMenu(Inventory inv, Player player) {
        fillOrdersDisplay(inv, player);

        for (int i = 36; i < 45; i++) {
            inv.setItem(i, createItem(Material.BROWN_STAINED_GLASS_PANE, " "));
        }

        double praims = getPraims(player);
        double money = economy.getBalance(player);
        double rate = calculateRate();

        inv.setItem(45, createItem(Material.ENDER_CHEST, "§e§lМои заявки",
                "§7Активных заявок: §e" + getPlayerOrdersCount(player),
                "",
                "§eНажмите для управления"));

        inv.setItem(46, createItem(Material.BLUE_STAINED_GLASS_PANE, " "));
        inv.setItem(47, createItem(Material.BLUE_STAINED_GLASS_PANE, " "));

        inv.setItem(48, createItem(Material.WRITABLE_BOOK, "§a§lПродать праймы",
                "§7Создать заявку на продажу",
                "§7Обменять праймы на монетки",
                "",
                "§7Ваш баланс праймов: §e" + formatNumber(praims),
                "",
                "§aНажмите для создания"));

        inv.setItem(49, createItem(Material.NETHER_STAR, "§6§lТекущий курс",
                "§e" + formatNumber(rate) + " монеток §7= §a1 прайм",
                "",
                "§7Базовый курс: §f" + formatNumber(getConfig().getDouble("base-rate")) + " монеток",
                "§7Заявок на продажу: §a" + getOrdersByType(OrderType.SELL),
                "§7Заявок на покупку: §b" + getOrdersByType(OrderType.BUY)));

        inv.setItem(50, createItem(Material.ENCHANTED_BOOK, "§b§lКупить праймы",
                "§7Создать заявку на покупку",
                "§7Обменять монетки на праймы",
                "",
                "§7Ваш баланс монеток: §b" + formatNumber(money),
                "",
                "§bНажмите для создания"));

        inv.setItem(51, createItem(Material.BLUE_STAINED_GLASS_PANE, " "));
        inv.setItem(52, createItem(Material.BLUE_STAINED_GLASS_PANE, " "));

        inv.setItem(53, createItem(Material.BARRIER, "§c§lЗакрыть",
                "§7Выйти из биржи"));
    }

    private void fillOrdersDisplay(Inventory inv, Player player) {
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, null);
        }

        List<Map.Entry<UUID, Order>> orders = activeOrders.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().timestamp, a.getValue().timestamp))
                .limit(36)
                .collect(Collectors.toList());

        int slot = 0;
        for (Map.Entry<UUID, Order> entry : orders) {
            if (slot >= 36) break;

            Order order = entry.getValue();
            Player owner = Bukkit.getPlayer(order.owner);
            String ownerName = owner != null ? owner.getName() : "§7Неизвестно";
            boolean isMyOrder = order.owner.equals(player.getUniqueId());

            Material material = isMyOrder ? Material.ENCHANTED_GOLDEN_APPLE :
                    (order.type == OrderType.SELL ? Material.WRITABLE_BOOK : Material.ENCHANTED_BOOK);

            // ИСПРАВЛЕНО: amount теперь всегда количество праймов
            double exchangeAmount = order.amount * order.rate; // монетки за праймы

            List<String> lore = new ArrayList<>();
            lore.add("§7Игрок: " + (isMyOrder ? "§6" : "§f") + ownerName);
            lore.add("§7Тип: " + (order.type == OrderType.SELL ? "§aПродажа" : "§bПокупка"));
            lore.add("");

            if (order.type == OrderType.SELL) {
                lore.add("§7Продаёт: §a" + formatNumber(order.amount) + " праймов");
                lore.add("§7Получит: §b" + formatNumber(exchangeAmount) + " монеток");
            } else {
                lore.add("§7Покупает: §a" + formatNumber(order.amount) + " праймов");
                lore.add("§7Платит: §b" + formatNumber(exchangeAmount) + " монеток");
            }

            lore.add("");
            lore.add("§7Курс: §e" + formatNumber(order.rate) + " монеток §7= §a1 прайм");

            if (isMyOrder) {
                lore.add("");
                lore.add("§e§lЭто ваша заявка!");
            } else {
                lore.add("");
                lore.add("§b§lНажмите чтобы купить");
            }

            ItemStack item = createItem(material,
                    (order.type == OrderType.SELL ? "§a§l" : "§b§l") + "Заявка #" + (slot + 1),
                    lore.toArray(new String[0]));

            int amount = 1;
            if (order.amount >= 1000000000) {
                amount = Math.min(64, (int)(order.amount / 1000000000));
            } else if (order.amount >= 10000000) {
                amount = Math.min(64, (int)(order.amount / 10000000));
            } else if (order.amount >= 1000000) {
                amount = Math.min(64, (int)(order.amount / 1000000));
            }
            item.setAmount(Math.max(1, amount));

            inv.setItem(slot++, item);
        }

        for (int i = slot; i < 36; i++) {
            inv.setItem(i, createItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
    }

    private void openPurchaseMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§b§lПокупка праймов");

        double balance = economy.getBalance(player);
        double rate = calculateRate();

        for (int i = 0; i < 27; i++) {
            inv.setItem(i, createItem(Material.CYAN_STAINED_GLASS_PANE, " "));
        }

        double[] praimsAmounts = {1, 5, 10, 50, 100, 500};
        int[] slots = {10, 11, 12, 14, 15, 16};

        for (int i = 0; i < praimsAmounts.length; i++) {
            inv.setItem(slots[i], createBuyAmountItem(praimsAmounts[i], rate, balance));
        }

        inv.setItem(13, createItem(Material.LIME_STAINED_GLASS_PANE, " "));

        inv.setItem(4, createItem(Material.GOLD_INGOT, "§6§lВаш баланс",
                "§7Монетки: §b" + formatNumber(balance),
                "",
                "§7Текущий курс:",
                "§e" + formatNumber(rate) + " монеток §7= §a1 прайм"));

        inv.setItem(22, createItem(Material.ARROW, "§e§lНазад", "§7Вернуться в главное меню"));

        menuType.put(player.getUniqueId(), "BUY");
        player.openInventory(inv);
    }

    private void openSellMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§a§lПродажа праймов");

        double balance = getPraims(player);
        double rate = calculateRate();

        for (int i = 0; i < 27; i++) {
            inv.setItem(i, createItem(Material.LIME_STAINED_GLASS_PANE, " "));
        }

        double[] praimsAmounts = {1, 5, 10, 50, 100, 500};
        int[] slots = {10, 11, 12, 14, 15, 16};

        for (int i = 0; i < praimsAmounts.length; i++) {
            inv.setItem(slots[i], createSellAmountItem(praimsAmounts[i], rate, balance));
        }

        inv.setItem(13, createItem(Material.LIME_STAINED_GLASS_PANE, " "));

        inv.setItem(4, createItem(Material.GOLD_NUGGET, "§6§lВаш баланс",
                "§7Праймы: §a" + formatNumber(balance),
                "",
                "§7Текущий курс:",
                "§e" + formatNumber(rate) + " монеток §7= §a1 прайм"));

        inv.setItem(22, createItem(Material.ARROW, "§e§lНазад", "§7Вернуться в главное меню"));

        menuType.put(player.getUniqueId(), "SELL");
        player.openInventory(inv);
    }

    private void openMyOrdersMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§e§lМои заявки");

        for (int i = 0; i < 27; i++) {
            inv.setItem(i, createItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        }

        List<Map.Entry<UUID, Order>> playerOrders = activeOrders.entrySet().stream()
                .filter(entry -> entry.getValue().owner.equals(player.getUniqueId()))
                .collect(Collectors.toList());

        if (playerOrders.isEmpty()) {
            inv.setItem(13, createItem(Material.BARRIER, "§c§lНет активных заявок",
                    "§7У вас пока нет заявок на бирже",
                    "",
                    "§7Создайте заявку через главное меню"));
        } else {
            int slot = 10;
            for (Map.Entry<UUID, Order> entry : playerOrders) {
                if (slot >= 17) break;

                Order order = entry.getValue();
                // ИСПРАВЛЕНО: amount = праймы, exchangeAmount = монетки
                double exchangeAmount = order.amount * order.rate;

                Material mat = order.type == OrderType.SELL ? Material.WRITABLE_BOOK : Material.ENCHANTED_BOOK;

                ItemStack item = createItem(mat,
                        (order.type == OrderType.SELL ? "§a§lПродажа праймов" : "§b§lПокупка праймов"),
                        order.type == OrderType.SELL ?
                                "§7Продаю: §a" + formatNumber(order.amount) + " праймов" :
                                "§7Покупаю: §a" + formatNumber(order.amount) + " праймов",
                        order.type == OrderType.SELL ?
                                "§7Получу: §b" + formatNumber(exchangeAmount) + " монеток" :
                                "§7Плачу: §b" + formatNumber(exchangeAmount) + " монеток",
                        "",
                        "§7Курс: §e" + formatNumber(order.rate) + " монеток за 1 прайм",
                        "",
                        "§c§lЛКМ §7- Отменить заявку");

                inv.setItem(slot, item);
                slot++;
            }
        }

        inv.setItem(22, createItem(Material.ARROW, "§e§lНазад", "§7Вернуться в главное меню"));

        menuType.put(player.getUniqueId(), "MY_ORDERS");
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;

        Player player = (Player) e.getWhoClicked();

        if (e.getView() == null || e.getView().getTitle() == null) return;

        String title = ChatColor.stripColor(e.getView().getTitle());

        // ❗ ЛОВИМ ТОЛЬКО НАШИ МЕНЮ
        if (!title.equalsIgnoreCase("КУПИТЬ ПРАЙМЫ")
                && !title.equalsIgnoreCase("ПРОДАТЬ ПРАЙМЫ")
                && !title.equalsIgnoreCase("БИРЖА ПРАЙМОВ")
                && !title.equalsIgnoreCase("МОИ ЗАЯВКИ")) {
            return;
        }

        // 🔒 ПОЛНЫЙ БЛОК
        e.setCancelled(true);

        // ❌ Клики по инвентарю игрока — в игнор
        if (e.getClickedInventory() == null) return;
        if (e.getClickedInventory().equals(player.getInventory())) return;

        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;

        // 🔽 РОУТИНГ ПО МЕНЮ
        if (title.equalsIgnoreCase("КУПИТЬ ПРАЙМЫ")) {
            handleBuyMenuClick(player, item);
            return;
        }

        if (title.equalsIgnoreCase("ПРОДАТЬ ПРАЙМЫ")) {
            handleSellMenuClick(player, item);
            return;
        }

        if (title.equalsIgnoreCase("БИРЖА ПРАЙМОВ")) {
            handleMainMenuClick(player, item);
            return;
        }

        if (title.equalsIgnoreCase("МОИ ЗАЯВКИ")) {
            handleMyOrdersClick(player, item);
        }
    }


    private void handleMainMenuClick(Player player, ItemStack item) {
        if (item.getType() == Material.BARRIER) {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, 1.0f, 1.0f);
        } else if (item.getType() == Material.WRITABLE_BOOK && item.hasItemMeta()) {
            String displayName = item.getItemMeta().getDisplayName();

            // Проверяем, это кнопка "Продать праймы" или заявка на продажу
            if (displayName.contains("Продать праймы")) {
                openSellMenu(player);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            } else {
                // Это клик по заявке на продажу - можно купить
                handleOrderClick(player, item);
            }
        } else if (item.getType() == Material.ENCHANTED_BOOK && item.hasItemMeta()) {
            String displayName = item.getItemMeta().getDisplayName();

            if (displayName.contains("Купить праймы")) {
                openPurchaseMenu(player);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            } else {
                // Это клик по заявке на покупку
                handleOrderClick(player, item);
            }
        } else if (item.getType() == Material.ENDER_CHEST) {
            openMyOrdersMenu(player);
            player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
        } else if (item.getType() == Material.ENCHANTED_GOLDEN_APPLE) {
            // Клик по своей заявке - открываем меню "Мои заявки"
            openMyOrdersMenu(player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        }
    }

    private final Map<UUID, Double> selectedBuyPrice = new HashMap<>();

    private double getSelectedBuyPrice(Player player) {
        return selectedBuyPrice.getOrDefault(player.getUniqueId(), 1.0);
    }

    private void handleBuyMenuClick(Player player, ItemStack item) {

        if (item.getType() == Material.ARROW) {
            openMainMenu(player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            return;
        }

        if ((item.getType() == Material.PAPER || item.getType() == Material.BARRIER)
                && item.hasItemMeta()
                && item.getItemMeta().hasDisplayName()) {

            String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
            String amountStr = name.replaceAll("[^0-9]", "");

            try {
                int praimsAmount = Integer.parseInt(amountStr);

                if (praimsAmount <= 0) return;

                double pricePerUnit = getSelectedBuyPrice(player); // твоя цена

                createBuyOrder(player, praimsAmount, pricePerUnit);
                openMainMenu(player);

            } catch (NumberFormatException ex) {
                player.sendMessage("§cОшибка обработки количества!");
            }
        }
    }

    private final List<BuyOrder> buyOrders = new ArrayList<>();

    // Новый метод для обработки покупки заявок
    private void handleOrderClick(Player player, ItemStack item) {
        player.sendMessage("§e§lПокупка заявок пока не реализована!");
        player.sendMessage("§7Эта функция будет добавлена позже");
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
    }

    private void handleSellMenuClick(Player player, ItemStack item) {
        if (item.getType() == Material.ARROW) {
            openMainMenu(player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            return;
        }

        // ИСПРАВЛЕНО: принимаем и WRITABLE_BOOK и BARRIER
        if ((item.getType() == Material.WRITABLE_BOOK || item.getType() == Material.BARRIER) && item.hasItemMeta()) {
            String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
            String amountStr = name.replaceAll("[^0-9.]", "");

            try {
                double praimsAmount = parseFormattedNumber(amountStr);
                if (praimsAmount > 0) {
                    createSellOrder(player, praimsAmount);
                    openMainMenu(player);
                }
            } catch (Exception ex) {
                player.sendMessage("§cОшибка обработки суммы!");
            }
        }
    }

    private final Map<UUID, Long> orderCooldown = new HashMap<>();

    private boolean canCreateOrder(Player player) {
        long now = System.currentTimeMillis();
        long cooldown = 3000; // 3 секунды

        if (orderCooldown.containsKey(player.getUniqueId())) {
            long last = orderCooldown.get(player.getUniqueId());
            if (now - last < cooldown) {
                long left = (cooldown - (now - last)) / 1000;
                player.sendMessage("§cПодожди §e" + left + "§c сек. перед созданием нового заказа");
                return false;
            }
        }

        orderCooldown.put(player.getUniqueId(), now);
        return true;
    }


    private void handleMyOrdersClick(Player player, ItemStack item) {
        if (item.getType() == Material.ARROW) {
            openMainMenu(player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            return;
        }

        if (item.getType() == Material.WRITABLE_BOOK || item.getType() == Material.ENCHANTED_BOOK) {
            cancelPlayerOrder(player);
            openMainMenu(player);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        menuType.remove(e.getPlayer().getUniqueId());
    }

    private ItemStack createBuyAmountItem(double praims, double rate, double balance) {
        double moneyNeeded = praims * rate;
        boolean canAfford = balance >= moneyNeeded;

        List<String> lore = new ArrayList<>();
        lore.add("§7Купить §a" + formatNumber(praims) + " праймов");
        lore.add("§7Стоимость: §b" + formatNumber(moneyNeeded) + " монеток");
        lore.add("");
        lore.add("§7Ваш баланс: " + (canAfford ? "§a" : "§c") + formatNumber(balance) + " монеток");
        lore.add("");

        if (canAfford) {
            lore.add("§a§l✓ Нажмите для создания заявки");
        } else {
            lore.add("§c§l✗ Недостаточно средств!");
        }

        ItemStack item = new ItemStack(canAfford ? Material.PAPER : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((canAfford ? "§e" : "§c") + formatNumber(praims));
        meta.setLore(lore);
        item.setItemMeta(meta);

        return item;
    }

    private ItemStack createSellAmountItem(double praims, double rate, double balance) {
        double moneyToReceive = praims * rate;
        boolean canAfford = balance >= praims;

        List<String> lore = new ArrayList<>();
        lore.add("§7Продать §a" + formatNumber(praims) + " праймов");
        lore.add("§7Получите: §b" + formatNumber(moneyToReceive) + " монеток");
        lore.add("");
        lore.add("§7Ваш баланс: " + (canAfford ? "§a" : "§c") + formatNumber(balance) + " праймов");
        lore.add("");

        if (canAfford) {
            lore.add("§a§l✓ Нажмите для создания заявки");
        } else {
            lore.add("§c§l✗ Недостаточно средств!");
        }

        ItemStack item = new ItemStack(canAfford ? Material.WRITABLE_BOOK : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((canAfford ? "§e" : "§c") + formatNumber(praims));
        meta.setLore(lore);
        item.setItemMeta(meta);

        return item;
    }

    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void createSellOrder(Player player, double praimsAmount) {
        if (hasActiveOrder(player)) {
            player.sendMessage("§c✗ У вас уже есть активная заявка! Отмените её сначала.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        double balance = getPraims(player);
        if (balance < praimsAmount) {
            player.sendMessage("§c✗ Недостаточно праймов! Нужно: §e" + formatNumber(praimsAmount) + "§c, есть: §e" + formatNumber(balance));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        if (!takePraims(player, praimsAmount)) {
            player.sendMessage("§c✗ Ошибка списания средств!");
            return;
        }

        double rate = calculateRate();
        // ВАЖНО: для продажи amount = количество праймов
        Order order = new Order(player.getUniqueId(), OrderType.SELL, praimsAmount, rate, System.currentTimeMillis());
        UUID orderId = UUID.randomUUID();
        activeOrders.put(orderId, order);
        saveOrders();

        double moneyToReceive = praimsAmount * rate;
        player.sendMessage("");
        player.sendMessage("§a§l✓ ЗАЯВКА УСПЕШНО СОЗДАНА!");
        player.sendMessage("§7Продажа: §a" + formatNumber(praimsAmount) + " праймов");
        player.sendMessage("§7Вы получите: §b" + formatNumber(moneyToReceive) + " монеток");
        player.sendMessage("§7Курс: §e" + formatNumber(rate) + " монеток §7= §a1 прайм");
        player.sendMessage("");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
    }

    private void createBuyOrder(Player player, int amount, double pricePerUnit) {

        if (!canCreateOrder(player)) return;

        double totalPrice = amount * pricePerUnit;

        if (!withdrawMoney(player, totalPrice)) return;

        BuyOrder order = new BuyOrder(
                UUID.randomUUID(),
                player.getUniqueId(),
                amount,
                pricePerUnit
        );

        buyOrders.add(order);

        player.sendMessage("§a✔ Заявка на покупку создана");
        player.sendMessage("§7Количество: §e" + amount);
        player.sendMessage("§7Цена за 1: §e" + pricePerUnit);
        player.sendMessage("§7Списано: §e" + totalPrice);
    }



    private boolean withdrawMoney(Player player, double amount) {
        if (!economy.has(player, amount)) {
            player.sendMessage("§cНедостаточно средств! Нужно §e" + amount);
            return false;
        }

        economy.withdrawPlayer(player, amount);
        return true;
    }


    private void cancelPlayerOrder(Player player) {
        UUID orderToCancel = null;
        Order order = null;

        for (Map.Entry<UUID, Order> entry : activeOrders.entrySet()) {
            if (entry.getValue().owner.equals(player.getUniqueId())) {
                orderToCancel = entry.getKey();
                order = entry.getValue();
                break;
            }
        }

        if (orderToCancel == null) {
            player.sendMessage("§c✗ У вас нет активных заявок!");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        activeOrders.remove(orderToCancel);

        if (order.type == OrderType.SELL) {
            givePraims(player, order.amount);
            player.sendMessage("§a✓ Заявка отменена! Возвращено: §e" + formatNumber(order.amount) + " праймов");
        } else {
            // ИСПРАВЛЕНО: возвращаем монетки через Vault
            double moneyToReturn = order.amount * order.rate;
            economy.depositPlayer(player, moneyToReturn);
            player.sendMessage("§a✓ Заявка отменена! Возвращено: §b" + formatNumber(moneyToReturn) + " монеток");
        }

        saveOrders();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
    }

    private void sellToBuyOrder(Player seller, BuyOrder order, int amount) {

        if (amount > order.getAmount()) amount = order.getAmount();

        // ❗ ПРОВЕРКА ПРЕДМЕТОВ
        if (!hasPrimes(seller, amount)) {
            seller.sendMessage("§cУ тебя нет нужного количества праймов");
            return;
        }

        double totalPrice = amount * order.getPrice();

        // ❗ УДАЛЯЕМ ПРЕДМЕТЫ
        removePrimes(seller, amount);

        // ❗ ВЫДАЁМ ДЕНЬГИ
        economy.depositPlayer(seller, totalPrice);

        // ❗ ОБНОВЛЯЕМ ЗАЯВКУ
        order.setAmount(order.getAmount() - amount);
        if (order.getAmount() <= 0) {
            buyOrders.remove(order);
        }

        seller.sendMessage("§a✔ Ты продал §e" + amount + " §aпраймов");
        seller.sendMessage("§7Получено: §e" + totalPrice);
    }

    private boolean hasPrimes(Player player, int amount) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.ENCHANTED_BOOK) {
                count += item.getAmount();
            }
        }
        return count >= amount;
    }

    private void removePrimes(Player player, int amount) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != Material.ENCHANTED_BOOK) continue;

            int take = Math.min(item.getAmount(), amount);
            item.setAmount(item.getAmount() - take);
            amount -= take;

            if (amount <= 0) break;
        }
    }


    private boolean hasActiveOrder(Player player) {
        return activeOrders.values().stream()
                .anyMatch(order -> order.owner.equals(player.getUniqueId()));
    }

    private int getPlayerOrdersCount(Player player) {
        return (int) activeOrders.values().stream()
                .filter(order -> order.owner.equals(player.getUniqueId()))
                .count();
    }

    private int getOrdersByType(OrderType type) {
        return (int) activeOrders.values().stream()
                .filter(order -> order.type == type)
                .count();
    }

    private double calculateRate() {
        int sellOrders = getOrdersByType(OrderType.SELL);
        int buyOrders = getOrdersByType(OrderType.BUY);

        double baseRate = getConfig().getDouble("base-rate", 400000.0);
        double volatility = getConfig().getDouble("volatility", 0.05);
        double minRate = getConfig().getDouble("min-rate", 100000.0);

        int difference = buyOrders - sellOrders;
        double adjustment = difference * volatility * baseRate;

        return Math.max(baseRate + adjustment, minRate);
    }

    // Работа с праймами через конфиг
    public double getPraims(Player player) {
        return getConfig().getDouble("praims." + player.getUniqueId(), 0.0);
    }

    public boolean takePraims(Player player, double amount) {
        double current = getPraims(player);
        if (current < amount) return false;
        getConfig().set("praims." + player.getUniqueId(), current - amount);
        saveConfig();
        return true;
    }

    public boolean givePraims(Player player, double amount) {
        double current = getPraims(player);
        getConfig().set("praims." + player.getUniqueId(), current + amount);
        saveConfig();
        return true;
    }

    // Работа с коинами через конфиг (можно заменить на другую систему)
    private double getCoins(Player player) {
        return getConfig().getDouble("coins." + player.getUniqueId(), 0.0);
    }

    private boolean takeCoins(Player player, double amount) {
        double current = getCoins(player);
        if (current < amount) return false;
        getConfig().set("coins." + player.getUniqueId(), current - amount);
        saveConfig();
        return true;
    }

    private boolean giveCoins(Player player, double amount) {
        double current = getCoins(player);
        getConfig().set("coins." + player.getUniqueId(), current + amount);
        saveConfig();
        return true;
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

    private double parseFormattedNumber(String str) {
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

    private enum OrderType {
        SELL, BUY
    }

    private static class Order {
        UUID owner;
        OrderType type;
        double amount;
        double rate;
        long timestamp;

        Order(UUID owner, OrderType type, double amount, double rate, long timestamp) {
            this.owner = owner;
            this.type = type;
            this.amount = amount;
            this.rate = rate;
            this.timestamp = timestamp;
        }
    }
}