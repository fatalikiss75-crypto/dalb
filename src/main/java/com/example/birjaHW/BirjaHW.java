package com.example.birjaHW;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.NamespacedKey;
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
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
    // Кастомная головка для отображения заявок
    private ItemStack customOrderHead = null;
    // Ключ для PersistentDataContainer
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
        String base64 = getConfig().getString("order-head-base64", null);
        if (base64 != null && !base64.isEmpty()) {
            try {
                byte[] data = Base64.getDecoder().decode(base64);
                ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
                BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
                customOrderHead = (ItemStack) dataInput.readObject();
                dataInput.close();
            } catch (Exception e) {
                getLogger().warning("Ошибка загрузки кастомной головки: " + e.getMessage());
                customOrderHead = null;
            }
        }
    }

    private void saveCustomHead(ItemStack head) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeObject(head);
            dataOutput.close();
            String base64 = Base64.getEncoder().encodeToString(outputStream.toByteArray());
            getConfig().set("order-head-base64", base64);
            saveConfig();
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
            // Открываем биржу если нет прав админа
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
                customOrderHead = itemInHand.clone();
                customOrderHead.setAmount(1);
                saveCustomHead(customOrderHead);
                player.sendMessage("§a§l✓ Головка для заявок установлена!");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                break;

            case "resethead":
                customOrderHead = null;
                getConfig().set("order-head-base64", null);
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
        Inventory inv = Bukkit.createInventory(null, 54, "§6§lБиржа Праймов");
        fillMainMenu(inv, player);
        menuType.put(player.getUniqueId(), "MAIN");
        player.openInventory(inv);
    }

    private void refreshMainMenu(Player player) {
        if (player.getOpenInventory() != null && player.getOpenInventory().getTopInventory().getSize() == 54) {
            Inventory inv = player.getOpenInventory().getTopInventory();
            // Проверяем, что это наше меню
            String title = ChatColor.stripColor(player.getOpenInventory().getTitle());
            if (title.equalsIgnoreCase("БИРЖА ПРАЙМОВ")) {
                fillMainMenu(inv, player);
            }
        }
    }

    private void fillMainMenu(Inventory inv, Player player) {
        // Заполняем фон
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, createItem(Material.BLACK_STAINED_GLASS_PANE, " "));
        }

        fillOrdersDisplay(inv, player);

        // Декоративная полоса под заявками
        for (int i = 36; i < 45; i++) {
            inv.setItem(i, createItem(Material.BROWN_STAINED_GLASS_PANE, " "));
        }

        double praims = getPraims(player);
        double money = economy.getBalance(player);
        double rate = calculateRate();

        // Мои заявки
        inv.setItem(45, createItem(Material.ENDER_CHEST, "§e§lМои заявки",
                "§7Активных заявок: §e" + getPlayerOrdersCount(player),
                "",
                "§eНажмите для управления"));

        // Декорация
        inv.setItem(46, createItem(Material.BLUE_STAINED_GLASS_PANE, " "));
        inv.setItem(47, createItem(Material.BLUE_STAINED_GLASS_PANE, " "));

        // Продать праймы (создать заявку на продажу)
        inv.setItem(48, createItem(Material.EMERALD_BLOCK, "§a§lПродать праймы",
                "§7Создать заявку на продажу",
                "§7Обменять праймы на монетки",
                "",
                "§7Ваш баланс праймов: §e" + formatNumber(praims),
                "",
                "§a§l→ Нажмите для создания"));

        // Центр - информация о курсе
        inv.setItem(49, createItem(Material.NETHER_STAR, "§6§lТекущий курс",
                "§e" + formatNumber(rate) + " монеток §7= §a1 прайм",
                "",
                "§7Базовый курс: §f" + formatNumber(getConfig().getDouble("base-rate")) + " монеток",
                "§7Заявок на продажу: §a" + getOrdersByType(OrderType.SELL),
                "§7Заявок на покупку: §b" + getOrdersByType(OrderType.BUY)));

        // Купить праймы (создать заявку на покупку)
        inv.setItem(50, createItem(Material.DIAMOND_BLOCK, "§b§lКупить праймы",
                "§7Создать заявку на покупку",
                "§7Обменять монетки на праймы",
                "",
                "§7Ваш баланс монеток: §b" + formatNumber(money),
                "",
                "§b§l→ Нажмите для создания"));

        // Декорация
        inv.setItem(51, createItem(Material.BLUE_STAINED_GLASS_PANE, " "));
        inv.setItem(52, createItem(Material.BLUE_STAINED_GLASS_PANE, " "));

        // Закрыть
        inv.setItem(53, createItem(Material.BARRIER, "§c§lЗакрыть",
                "§7Выйти из биржи"));
    }

    private ItemStack getOrderDisplayItem(boolean isMyOrder, OrderType type) {
        if (isMyOrder) {
            return new ItemStack(Material.ENCHANTED_GOLDEN_APPLE);
        }
        if (customOrderHead != null) {
            return customOrderHead.clone();
        }
        return new ItemStack(type == OrderType.SELL ? Material.WRITABLE_BOOK : Material.ENCHANTED_BOOK);
    }

    /**
     * Сортирует заявки по выгодности:
     * - SELL заявки: сначала с наименьшим курсом (выгоднее купить)
     * - BUY заявки: сначала с наибольшим курсом (выгоднее продать)
     */
    private List<Map.Entry<UUID, Order>> getSortedOrders() {
        return activeOrders.entrySet().stream()
                .sorted((a, b) -> {
                    Order orderA = a.getValue();
                    Order orderB = b.getValue();

                    // Сначала SELL заявки (которые выгоднее для покупателя)
                    // Внутри SELL - по возрастанию курса (дешевле = лучше)
                    if (orderA.type == OrderType.SELL && orderB.type == OrderType.SELL) {
                        return Double.compare(orderA.rate, orderB.rate);
                    }

                    // Затем BUY заявки (которые выгоднее для продавца)
                    // Внутри BUY - по убыванию курса (дороже = лучше)
                    if (orderA.type == OrderType.BUY && orderB.type == OrderType.BUY) {
                        return Double.compare(orderB.rate, orderA.rate);
                    }

                    // SELL идут перед BUY
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

            // Сохраняем ID заявки в предмете
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

    // ==================== МЕНЮ ВЫБОРА КОЛИЧЕСТВА ДЛЯ ПОКУПКИ ====================

    private void openPurchaseAmountMenu(Player player, UUID orderId, Order order) {
        selectedAmount.put(player.getUniqueId(), 1);
        selectedOrder.put(player.getUniqueId(), orderId);

        Inventory inv = Bukkit.createInventory(null, 54, "§b§lВыбор количества для покупки");
        fillPurchaseAmountMenu(inv, player, order);

        menuType.put(player.getUniqueId(), "BUY_AMOUNT");
        player.openInventory(inv);
    }

    private void fillPurchaseAmountMenu(Inventory inv, Player player, Order order) {
        // Фон
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

        // Информация о продавце
        inv.setItem(4, createItem(Material.PLAYER_HEAD, "§e§lИнформация о заявке",
                "§7Продавец: §f" + sellerName,
                "§7Доступно: §a" + formatNumber(order.amount) + " праймов",
                "§7Курс: §e" + formatNumber(rate) + " монеток/прайм",
                "",
                "§7Вы можете купить макс: §b" + formatNumber(maxCanBuy) + " праймов"));

        // Кнопки + (зеленые)
        int[] plusSlots = {20, 21, 22, 23, 24};
        int[] plusValues = {1, 5, 10, 50, 100};
        Material[] plusMaterials = {
            Material.LIME_STAINED_GLASS_PANE,
            Material.LIME_STAINED_GLASS,
            Material.LIME_TERRACOTTA,
            Material.LIME_CONCRETE,
            Material.LIME_WOOL
        };

        for (int i = 0; i < plusValues.length; i++) {
            inv.setItem(plusSlots[i], createItem(plusMaterials[i], "§a§l+" + plusValues[i],
                    "§7Добавить §a" + plusValues[i] + " §7праймов",
                    "",
                    "§eКлик чтобы добавить"));
        }

        // Текущее выбранное количество (центр)
        inv.setItem(31, createItem(Material.ENCHANTED_BOOK, "§6§lВыбрано: §e" + selectedAmt + " праймов",
                "§7Стоимость: §b" + formatNumber(totalCost) + " монеток",
                "§7Ваш баланс: " + (canAfford ? "§a" : "§c") + formatNumber(playerMoney) + " монеток",
                "",
                canAfford ? "§a§l✓ Достаточно средств" : "§c§l✗ Недостаточно средств!"));

        // Кнопки - (красные)
        int[] minusSlots = {38, 39, 40, 41, 42};
        int[] minusValues = {1, 5, 10, 50, 100};
        Material[] minusMaterials = {
            Material.RED_STAINED_GLASS_PANE,
            Material.RED_STAINED_GLASS,
            Material.RED_TERRACOTTA,
            Material.RED_CONCRETE,
            Material.RED_WOOL
        };

        for (int i = 0; i < minusValues.length; i++) {
            inv.setItem(minusSlots[i], createItem(minusMaterials[i], "§c§l-" + minusValues[i],
                    "§7Убрать §c" + minusValues[i] + " §7праймов",
                    "",
                    "§eКлик чтобы убрать"));
        }

        // Кнопка купить
        inv.setItem(48, createItem(canAfford ? Material.EMERALD_BLOCK : Material.BARRIER,
                canAfford ? "§a§l✓ Купить праймы" : "§c§l✗ Недостаточно средств",
                "§7Количество: §e" + selectedAmt + " праймов",
                "§7Стоимость: §b" + formatNumber(totalCost) + " монеток",
                "",
                canAfford ? "§a§lКлик чтобы купить!" : "§cНужно больше монеток!"));

        // Купить максимум
        if (maxCanBuy > 0) {
            inv.setItem(49, createItem(Material.GOLD_BLOCK, "§6§l⚡ Купить максимум",
                    "§7Купить: §e" + formatNumber(maxCanBuy) + " праймов",
                    "§7Стоимость: §b" + formatNumber(maxCanBuy * rate) + " монеток",
                    "",
                    "§eКлик для максимальной покупки"));
        }

        // Назад
        inv.setItem(50, createItem(Material.ARROW, "§e§lНазад", "§7Вернуться к бирже"));
    }

    private void handlePurchaseAmountMenuClick(Player player, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;

        String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        UUID orderId = selectedOrder.get(player.getUniqueId());

        if (orderId == null) {
            player.closeInventory();
            return;
        }

        Order order = activeOrders.get(orderId);
        if (order == null) {
            player.sendMessage("§cЗаявка больше не существует!");
            openMainMenu(player);
            return;
        }

        // Кнопка назад
        if (name.contains("Назад")) {
            selectedAmount.remove(player.getUniqueId());
            selectedOrder.remove(player.getUniqueId());
            openMainMenu(player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            return;
        }

        // Кнопки +
        if (name.startsWith("+")) {
            try {
                int value = Integer.parseInt(name.substring(1));
                int current = selectedAmount.getOrDefault(player.getUniqueId(), 0);
                int newAmount = Math.min(current + value, (int)order.amount);
                selectedAmount.put(player.getUniqueId(), newAmount);
                // Переоткрываем меню для обновления
                openPurchaseAmountMenuRefresh(player, orderId, order);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.2f);
            } catch (NumberFormatException e) {
                getLogger().warning("Ошибка парсинга числа: " + name);
            }
            return;
        }

        // Кнопки -
        if (name.startsWith("-")) {
            try {
                int value = Integer.parseInt(name.substring(1));
                int current = selectedAmount.getOrDefault(player.getUniqueId(), 0);
                int newAmount = Math.max(1, current - value);
                selectedAmount.put(player.getUniqueId(), newAmount);
                // Переоткрываем меню для обновления
                openPurchaseAmountMenuRefresh(player, orderId, order);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 0.8f);
            } catch (NumberFormatException e) {
                getLogger().warning("Ошибка парсинга числа: " + name);
            }
            return;
        }

        // Купить максимум
        if (name.contains("Купить максимум")) {
            double rate = order.rate;
            double playerMoney = economy.getBalance(player);
            int maxCanBuy = (int)Math.min(order.amount, Math.floor(playerMoney / rate));
            if (maxCanBuy > 0) {
                selectedAmount.put(player.getUniqueId(), maxCanBuy);
                executePurchase(player, orderId, order, maxCanBuy);
            }
            return;
        }

        // Купить
        if (name.contains("Купить праймы")) {
            int amount = selectedAmount.getOrDefault(player.getUniqueId(), 1);
            executePurchase(player, orderId, order, amount);
        }
    }

    private void openPurchaseAmountMenuRefresh(Player player, UUID orderId, Order order) {
        // Обновляем текущий инвентарь без закрытия
        Inventory inv = player.getOpenInventory().getTopInventory();
        fillPurchaseAmountMenu(inv, player, order);
    }

    private void executePurchase(Player player, UUID orderId, Order order, int amount) {
        if (amount <= 0 || amount > order.amount) {
            player.sendMessage("§cНекорректное количество!");
            return;
        }

        double totalCost = amount * order.rate;

        if (!economy.has(player, totalCost)) {
            player.sendMessage("§c§l✗ Недостаточно монеток!");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        // Списываем деньги
        economy.withdrawPlayer(player, totalCost);

        // Даем праймы покупателю
        givePraims(player, amount);

        // Обновляем заявку
        if (amount >= order.amount) {
            // Заявка полностью выполнена
            Player seller = Bukkit.getPlayer(order.owner);
            if (seller != null && seller.isOnline()) {
                economy.depositPlayer(seller, totalCost);
                seller.sendMessage("§a§l✓ Ваша заявка на продажу выполнена!");
                seller.sendMessage("§7Продано: §e" + formatNumber(order.amount) + " праймов");
                seller.sendMessage("§7Получено: §b" + formatNumber(totalCost) + " монеток");
                seller.playSound(seller.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
            }
            activeOrders.remove(orderId);
        } else {
            // Частичное выполнение
            order.amount -= amount;
            Player seller = Bukkit.getPlayer(order.owner);
            if (seller != null && seller.isOnline()) {
                economy.depositPlayer(seller, totalCost);
                seller.sendMessage("§e§l⚡ Частичная продажа!");
                seller.sendMessage("§7Продано: §e" + formatNumber(amount) + " праймов");
                seller.sendMessage("§7Получено: §b" + formatNumber(totalCost) + " монеток");
                seller.sendMessage("§7Осталось в заявке: §e" + formatNumber(order.amount) + " праймов");
                seller.playSound(seller.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
            }
        }

        saveOrders();

        // Сообщение покупателю
        player.sendMessage("");
        player.sendMessage("§a§l✓ ПОКУПКА УСПЕШНА!");
        player.sendMessage("§7Куплено: §e" + formatNumber(amount) + " праймов");
        player.sendMessage("§7Потрачено: §b" + formatNumber(totalCost) + " монеток");
        player.sendMessage("");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 2.0f);

        // Очищаем данные и возвращаем в меню
        selectedAmount.remove(player.getUniqueId());
        selectedOrder.remove(player.getUniqueId());
        openMainMenu(player);
    }

    // ==================== МЕНЮ ВЫБОРА КОЛИЧЕСТВА ДЛЯ ПРОДАЖИ (BUY ORDER) ====================

    private void openSellToOrderMenu(Player player, UUID orderId, Order order) {
        selectedAmount.put(player.getUniqueId(), 1);
        selectedOrder.put(player.getUniqueId(), orderId);

        Inventory inv = Bukkit.createInventory(null, 54, "§a§lВыбор количества для продажи");
        fillSellToOrderMenu(inv, player, order);

        menuType.put(player.getUniqueId(), "SELL_TO_ORDER");
        player.openInventory(inv);
    }

    private void fillSellToOrderMenu(Inventory inv, Player player, Order order) {
        // Фон
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, createItem(Material.BLACK_STAINED_GLASS_PANE, " "));
        }

        int selectedAmt = selectedAmount.getOrDefault(player.getUniqueId(), 1);
        double rate = order.rate;
        double totalReward = selectedAmt * rate;
        double playerPraims = getPraims(player);
        boolean canSell = playerPraims >= selectedAmt;
        double maxCanSell = Math.min(order.amount, playerPraims);

        Player buyer = Bukkit.getPlayer(order.owner);
        String buyerName = buyer != null ? buyer.getName() : "§7Неизвестно";

        // Информация о покупателе
        inv.setItem(4, createItem(Material.PLAYER_HEAD, "§e§lИнформация о заявке",
                "§7Покупатель: §f" + buyerName,
                "§7Нужно: §a" + formatNumber(order.amount) + " праймов",
                "§7Курс: §e" + formatNumber(rate) + " монеток/прайм",
                "",
                "§7Вы можете продать макс: §b" + formatNumber(maxCanSell) + " праймов"));

        // Кнопки + (зеленые)
        int[] plusSlots = {20, 21, 22, 23, 24};
        int[] plusValues = {1, 5, 10, 50, 100};
        Material[] plusMaterials = {
            Material.LIME_STAINED_GLASS_PANE,
            Material.LIME_STAINED_GLASS,
            Material.LIME_TERRACOTTA,
            Material.LIME_CONCRETE,
            Material.LIME_WOOL
        };

        for (int i = 0; i < plusValues.length; i++) {
            inv.setItem(plusSlots[i], createItem(plusMaterials[i], "§a§l+" + plusValues[i],
                    "§7Добавить §a" + plusValues[i] + " §7праймов",
                    "",
                    "§eКлик чтобы добавить"));
        }

        // Текущее выбранное количество (центр)
        inv.setItem(31, createItem(Material.WRITABLE_BOOK, "§6§lВыбрано: §e" + selectedAmt + " праймов",
                "§7Вы получите: §b" + formatNumber(totalReward) + " монеток",
                "§7Ваш баланс: " + (canSell ? "§a" : "§c") + formatNumber(playerPraims) + " праймов",
                "",
                canSell ? "§a§l✓ Достаточно праймов" : "§c§l✗ Недостаточно праймов!"));

        // Кнопки - (красные)
        int[] minusSlots = {38, 39, 40, 41, 42};
        int[] minusValues = {1, 5, 10, 50, 100};
        Material[] minusMaterials = {
            Material.RED_STAINED_GLASS_PANE,
            Material.RED_STAINED_GLASS,
            Material.RED_TERRACOTTA,
            Material.RED_CONCRETE,
            Material.RED_WOOL
        };

        for (int i = 0; i < minusValues.length; i++) {
            inv.setItem(minusSlots[i], createItem(minusMaterials[i], "§c§l-" + minusValues[i],
                    "§7Убрать §c" + minusValues[i] + " §7праймов",
                    "",
                    "§eКлик чтобы убрать"));
        }

        // Кнопка продать
        inv.setItem(48, createItem(canSell ? Material.DIAMOND_BLOCK : Material.BARRIER,
                canSell ? "§a§l✓ Продать праймы" : "§c§l✗ Недостаточно праймов",
                "§7Количество: §e" + selectedAmt + " праймов",
                "§7Вы получите: §b" + formatNumber(totalReward) + " монеток",
                "",
                canSell ? "§a§lКлик чтобы продать!" : "§cНужно больше праймов!"));

        // Продать максимум
        if (maxCanSell > 0) {
            inv.setItem(49, createItem(Material.GOLD_BLOCK, "§6§l⚡ Продать максимум",
                    "§7Продать: §e" + formatNumber(maxCanSell) + " праймов",
                    "§7Вы получите: §b" + formatNumber(maxCanSell * rate) + " монеток",
                    "",
                    "§eКлик для максимальной продажи"));
        }

        // Назад
        inv.setItem(50, createItem(Material.ARROW, "§e§lНазад", "§7Вернуться к бирже"));
    }

    private void handleSellToOrderMenuClick(Player player, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;

        String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        UUID orderId = selectedOrder.get(player.getUniqueId());

        if (orderId == null) {
            player.closeInventory();
            return;
        }

        Order order = activeOrders.get(orderId);
        if (order == null) {
            player.sendMessage("§cЗаявка больше не существует!");
            openMainMenu(player);
            return;
        }

        // Кнопка назад
        if (name.contains("Назад")) {
            selectedAmount.remove(player.getUniqueId());
            selectedOrder.remove(player.getUniqueId());
            openMainMenu(player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            return;
        }

        // Кнопки +
        if (name.startsWith("+")) {
            try {
                int value = Integer.parseInt(name.substring(1));
                int current = selectedAmount.getOrDefault(player.getUniqueId(), 0);
                int newAmount = Math.min(current + value, (int)order.amount);
                selectedAmount.put(player.getUniqueId(), newAmount);
                openSellToOrderMenuRefresh(player, orderId, order);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.2f);
            } catch (NumberFormatException e) {
                getLogger().warning("Ошибка парсинга числа: " + name);
            }
            return;
        }

        // Кнопки -
        if (name.startsWith("-")) {
            try {
                int value = Integer.parseInt(name.substring(1));
                int current = selectedAmount.getOrDefault(player.getUniqueId(), 0);
                int newAmount = Math.max(1, current - value);
                selectedAmount.put(player.getUniqueId(), newAmount);
                openSellToOrderMenuRefresh(player, orderId, order);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 0.8f);
            } catch (NumberFormatException e) {
                getLogger().warning("Ошибка парсинга числа: " + name);
            }
            return;
        }

        // Продать максимум
        if (name.contains("Продать максимум")) {
            double playerPraims = getPraims(player);
            int maxCanSell = (int)Math.min(order.amount, playerPraims);
            if (maxCanSell > 0) {
                selectedAmount.put(player.getUniqueId(), maxCanSell);
                executeSellToOrder(player, orderId, order, maxCanSell);
            }
            return;
        }

        // Продать
        if (name.contains("Продать праймы")) {
            int amount = selectedAmount.getOrDefault(player.getUniqueId(), 1);
            executeSellToOrder(player, orderId, order, amount);
        }
    }

    private void openSellToOrderMenuRefresh(Player player, UUID orderId, Order order) {
        Inventory inv = player.getOpenInventory().getTopInventory();
        fillSellToOrderMenu(inv, player, order);
    }

    private void executeSellToOrder(Player player, UUID orderId, Order order, int amount) {
        if (amount <= 0 || amount > order.amount) {
            player.sendMessage("§cНекорректное количество!");
            return;
        }

        double totalReward = amount * order.rate;

        if (getPraims(player) < amount) {
            player.sendMessage("§c§l✗ Недостаточно праймов!");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        // Списываем праймы у продавца
        takePraims(player, amount);

        // Даем монетки продавцу
        economy.depositPlayer(player, totalReward);

        // Обновляем заявку
        if (amount >= order.amount) {
            // Заявка полностью выполнена
            Player buyer = Bukkit.getPlayer(order.owner);
            if (buyer != null && buyer.isOnline()) {
                givePraims(buyer, (int)order.amount);
                buyer.sendMessage("§a§l✓ Ваша заявка на покупку выполнена!");
                buyer.sendMessage("§7Куплено: §e" + formatNumber(order.amount) + " праймов");
                buyer.sendMessage("§7Потрачено: §b" + formatNumber(totalReward) + " монеток");
                buyer.playSound(buyer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
            }
            activeOrders.remove(orderId);
        } else {
            // Частичное выполнение
            order.amount -= amount;
            Player buyer = Bukkit.getPlayer(order.owner);
            if (buyer != null && buyer.isOnline()) {
                givePraims(buyer, amount);
                buyer.sendMessage("§e§l⚡ Частичная покупка!");
                buyer.sendMessage("§7Куплено: §e" + formatNumber(amount) + " праймов");
                buyer.sendMessage("§7Потрачено: §b" + formatNumber(totalReward) + " монеток");
                buyer.sendMessage("§7Осталось купить: §e" + formatNumber(order.amount) + " праймов");
                buyer.playSound(buyer.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
            }
        }

        saveOrders();

        // Сообщение продавцу
        player.sendMessage("");
        player.sendMessage("§a§l✓ ПРОДАЖА УСПЕШНА!");
        player.sendMessage("§7Продано: §e" + formatNumber(amount) + " праймов");
        player.sendMessage("§7Получено: §b" + formatNumber(totalReward) + " монеток");
        player.sendMessage("");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 2.0f);

        // Очищаем данные и возвращаем в меню
        selectedAmount.remove(player.getUniqueId());
        selectedOrder.remove(player.getUniqueId());
        openMainMenu(player);
    }

    // ==================== МЕНЮ СОЗДАНИЯ ЗАЯВОК ====================

    private void openPurchaseMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "§b§lСоздать заявку на покупку");

        double balance = economy.getBalance(player);
        double rate = calculateRate();

        // Фон
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, createItem(Material.CYAN_STAINED_GLASS_PANE, " "));
        }

        // Информация о балансе
        inv.setItem(4, createItem(Material.GOLD_INGOT, "§6§lВаш баланс",
                "§7Монетки: §b" + formatNumber(balance),
                "",
                "§7Текущий курс:",
                "§e" + formatNumber(rate) + " монеток §7= §a1 прайм"));

        // Варианты количества
        double[] praimsAmounts = {1, 5, 10, 50, 100, 500, 1000, 5000};
        int[] slots = {20, 21, 22, 23, 24, 29, 30, 31};

        for (int i = 0; i < praimsAmounts.length; i++) {
            inv.setItem(slots[i], createBuyAmountItem(praimsAmounts[i], rate, balance));
        }

        // Кнопка назад
        inv.setItem(49, createItem(Material.ARROW, "§e§lНазад", "§7Вернуться в главное меню"));

        menuType.put(player.getUniqueId(), "BUY");
        player.openInventory(inv);
    }

    private void openSellMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "§a§lСоздать заявку на продажу");

        double balance = getPraims(player);
        double rate = calculateRate();

        // Фон
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, createItem(Material.LIME_STAINED_GLASS_PANE, " "));
        }

        // Информация о балансе
        inv.setItem(4, createItem(Material.GOLD_NUGGET, "§6§lВаш баланс",
                "§7Праймы: §a" + formatNumber(balance),
                "",
                "§7Текущий курс:",
                "§e" + formatNumber(rate) + " монеток §7= §a1 прайм"));

        // Варианты количества
        double[] praimsAmounts = {1, 5, 10, 50, 100, 500, 1000, 5000};
        int[] slots = {20, 21, 22, 23, 24, 29, 30, 31};

        for (int i = 0; i < praimsAmounts.length; i++) {
            inv.setItem(slots[i], createSellAmountItem(praimsAmounts[i], rate, balance));
        }

        // Кнопка назад
        inv.setItem(49, createItem(Material.ARROW, "§e§lНазад", "§7Вернуться в главное меню"));

        menuType.put(player.getUniqueId(), "SELL");
        player.openInventory(inv);
    }

    private void openMyOrdersMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "§e§lМои заявки");

        // Фон
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, createItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        }

        List<Map.Entry<UUID, Order>> playerOrders = activeOrders.entrySet().stream()
                .filter(entry -> entry.getValue().owner.equals(player.getUniqueId()))
                .collect(Collectors.toList());

        if (playerOrders.isEmpty()) {
            inv.setItem(22, createItem(Material.BARRIER, "§c§lНет активных заявок",
                    "§7У вас пока нет заявок на бирже",
                    "",
                    "§7Создайте заявку через главное меню"));
        } else {
            int slot = 20;
            for (Map.Entry<UUID, Order> entry : playerOrders) {
                if (slot > 34) break;

                Order order = entry.getValue();
                double exchangeAmount = order.amount * order.rate;

                Material mat = order.type == OrderType.SELL ? Material.EMERALD_BLOCK : Material.DIAMOND_BLOCK;

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
                        "§c§l✖ ЛКМ §7- Отменить заявку");

                // Сохраняем ID заявки
                ItemMeta meta = item.getItemMeta();
                meta.getPersistentDataContainer().set(orderIdKey, PersistentDataType.STRING, entry.getKey().toString());
                item.setItemMeta(meta);

                inv.setItem(slot, item);
                slot++;
                if (slot % 9 == 7) slot += 4; // Переход на следующую строку
            }
        }

        inv.setItem(49, createItem(Material.ARROW, "§e§lНазад", "§7Вернуться в главное меню"));

        menuType.put(player.getUniqueId(), "MY_ORDERS");
        player.openInventory(inv);
    }

    // ==================== ОБРАБОТЧИКИ КЛИКОВ ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;

        Player player = (Player) e.getWhoClicked();

        if (e.getView() == null || e.getView().getTitle() == null) return;

        String title = ChatColor.stripColor(e.getView().getTitle());

        // Проверяем наши меню
        if (!title.equalsIgnoreCase("ВЫБОР КОЛИЧЕСТВА ДЛЯ ПОКУПКИ")
                && !title.equalsIgnoreCase("ВЫБОР КОЛИЧЕСТВА ДЛЯ ПРОДАЖИ")
                && !title.equalsIgnoreCase("СОЗДАТЬ ЗАЯВКУ НА ПОКУПКУ")
                && !title.equalsIgnoreCase("СОЗДАТЬ ЗАЯВКУ НА ПРОДАЖУ")
                && !title.equalsIgnoreCase("БИРЖА ПРАЙМОВ")
                && !title.equalsIgnoreCase("МОИ ЗАЯВКИ")) {
            return;
        }

        e.setCancelled(true);

        if (e.getClickedInventory() == null) return;
        if (e.getClickedInventory().equals(player.getInventory())) return;

        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;

        // Роутинг по меню
        if (title.equalsIgnoreCase("ВЫБОР КОЛИЧЕСТВА ДЛЯ ПОКУПКИ")) {
            handlePurchaseAmountMenuClick(player, item);
            return;
        }

        if (title.equalsIgnoreCase("ВЫБОР КОЛИЧЕСТВА ДЛЯ ПРОДАЖИ")) {
            handleSellToOrderMenuClick(player, item);
            return;
        }

        if (title.equalsIgnoreCase("СОЗДАТЬ ЗАЯВКУ НА ПОКУПКУ")) {
            handleBuyMenuClick(player, item);
            return;
        }

        if (title.equalsIgnoreCase("СОЗДАТЬ ЗАЯВКУ НА ПРОДАЖУ")) {
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
        } else if (item.getType() == Material.EMERALD_BLOCK) {
            if (item.hasItemMeta() && item.getItemMeta().getDisplayName().contains("Продать")) {
                openSellMenu(player);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            }
        } else if (item.getType() == Material.DIAMOND_BLOCK) {
            if (item.hasItemMeta() && item.getItemMeta().getDisplayName().contains("Купить")) {
                openPurchaseMenu(player);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            }
        } else if (item.getType() == Material.ENDER_CHEST) {
            openMyOrdersMenu(player);
            player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
        } else if (item.getType() == Material.ENCHANTED_GOLDEN_APPLE) {
            openMyOrdersMenu(player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        } else {
            // Проверяем, это клик по заявке
            handleOrderClick(player, item);
        }
    }

    private void handleOrderClick(Player player, ItemStack item) {
        if (!item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        // Пробуем получить ID заявки из PersistentDataContainer
        PersistentDataContainer container = meta.getPersistentDataContainer();
        String orderIdStr = container.get(orderIdKey, PersistentDataType.STRING);

        if (orderIdStr == null) {
            // Fallback: ищем по имени предмета
            String displayName = meta.getDisplayName();
            if (displayName == null || !displayName.contains("Заявка #")) return;

            // Находим заявку по слоту
            int slot = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack slotItem = player.getOpenInventory().getTopInventory().getItem(i);
                if (slotItem != null && slotItem.equals(item)) {
                    slot = i;
                    break;
                }
            }

            if (slot == -1) return;

            List<Map.Entry<UUID, Order>> orders = getSortedOrders();
            if (slot >= orders.size()) return;

            Map.Entry<UUID, Order> entry = orders.get(slot);
            processOrderInteraction(player, entry.getKey(), entry.getValue());
        } else {
            UUID orderId = UUID.fromString(orderIdStr);
            Order order = activeOrders.get(orderId);
            if (order != null) {
                processOrderInteraction(player, orderId, order);
            } else {
                player.sendMessage("§cЗаявка больше не существует!");
                openMainMenu(player);
            }
        }
    }

    private void processOrderInteraction(Player player, UUID orderId, Order order) {
        // Проверяем, не своя ли это заявка
        if (order.owner.equals(player.getUniqueId())) {
            openMyOrdersMenu(player);
            return;
        }

        // Если это заявка на продажу (SELL) - покупаем праймы
        if (order.type == OrderType.SELL) {
            openPurchaseAmountMenu(player, orderId, order);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        } else {
            // Заявка на покупку (BUY) - продаем праймы
            openSellToOrderMenu(player, orderId, order);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        }
    }

    private void handleBuyMenuClick(Player player, ItemStack item) {
        if (item.getType() == Material.ARROW) {
            openMainMenu(player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            return;
        }

        if (item.getType() != Material.PAPER && item.getType() != Material.BARRIER) return;
        if (!item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        // Получаем сумму из PersistentDataContainer
        PersistentDataContainer container = meta.getPersistentDataContainer();
        Double amount = container.get(amountKey, PersistentDataType.DOUBLE);

        if (amount == null) {
            // Fallback: парсим из имени
            String name = ChatColor.stripColor(meta.getDisplayName());
            try {
                amount = parseFormattedNumber(name);
            } catch (Exception e) {
                player.sendMessage("§cОшибка обработки количества!");
                return;
            }
        }

        if (amount <= 0) return;

        double rate = calculateRate();
        createBuyOrder(player, amount.intValue(), rate);
        openMainMenu(player);
    }

    private void handleSellMenuClick(Player player, ItemStack item) {
        if (item.getType() == Material.ARROW) {
            openMainMenu(player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            return;
        }

        if (item.getType() != Material.WRITABLE_BOOK && item.getType() != Material.BARRIER) return;
        if (!item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        // Получаем сумму из PersistentDataContainer
        PersistentDataContainer container = meta.getPersistentDataContainer();
        Double amount = container.get(amountKey, PersistentDataType.DOUBLE);

        if (amount == null) {
            // Fallback: парсим из имени
            String name = ChatColor.stripColor(meta.getDisplayName());
            try {
                amount = parseFormattedNumber(name);
            } catch (Exception e) {
                player.sendMessage("§cОшибка обработки количества!");
                return;
            }
        }

        if (amount <= 0) return;

        createSellOrder(player, amount);
        openMainMenu(player);
    }

    private void handleMyOrdersClick(Player player, ItemStack item) {
        if (item.getType() == Material.ARROW) {
            openMainMenu(player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            return;
        }

        if (item.getType() == Material.EMERALD_BLOCK || item.getType() == Material.DIAMOND_BLOCK) {
            if (!item.hasItemMeta()) return;

            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;

            PersistentDataContainer container = meta.getPersistentDataContainer();
            String orderIdStr = container.get(orderIdKey, PersistentDataType.STRING);

            if (orderIdStr != null) {
                UUID orderId = UUID.fromString(orderIdStr);
                cancelSpecificOrder(player, orderId);
            } else {
                cancelPlayerOrder(player);
            }
            openMyOrdersMenu(player);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        menuType.remove(e.getPlayer().getUniqueId());
        selectedAmount.remove(e.getPlayer().getUniqueId());
        selectedOrder.remove(e.getPlayer().getUniqueId());
    }

    // ==================== СОЗДАНИЕ ПРЕДМЕТОВ ====================

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

        // Сохраняем оригинальное значение
        meta.getPersistentDataContainer().set(amountKey, PersistentDataType.DOUBLE, praims);

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
            lore.add("§c§l✗ Недостаточно праймов!");
        }

        ItemStack item = new ItemStack(canAfford ? Material.WRITABLE_BOOK : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((canAfford ? "§e" : "§c") + formatNumber(praims));
        meta.setLore(lore);

        // Сохраняем оригинальное значение
        meta.getPersistentDataContainer().set(amountKey, PersistentDataType.DOUBLE, praims);

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

    // ==================== СОЗДАНИЕ/ОТМЕНА ЗАЯВОК ====================

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

    private void createSellOrder(Player player, double praimsAmount) {
        if (hasActiveOrder(player)) {
            player.sendMessage("§c§l✗ У вас уже есть активная заявка! Отмените её сначала.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        double balance = getPraims(player);
        if (balance < praimsAmount) {
            player.sendMessage("§c§l✗ Недостаточно праймов! Нужно: §e" + formatNumber(praimsAmount) + "§c, есть: §e" + formatNumber(balance));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        if (!takePraims(player, praimsAmount)) {
            player.sendMessage("§c§l✗ Ошибка списания средств!");
            return;
        }

        double rate = calculateRate();
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

        if (!economy.has(player, totalPrice)) {
            player.sendMessage("§c§l✗ Недостаточно монеток! Нужно: §b" + formatNumber(totalPrice));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        economy.withdrawPlayer(player, totalPrice);

        Order order = new Order(player.getUniqueId(), OrderType.BUY, amount, pricePerUnit, System.currentTimeMillis());
        UUID orderId = UUID.randomUUID();
        activeOrders.put(orderId, order);
        saveOrders();

        player.sendMessage("");
        player.sendMessage("§a§l✓ ЗАЯВКА НА ПОКУПКУ СОЗДАНА!");
        player.sendMessage("§7Покупка: §a" + formatNumber(amount) + " праймов");
        player.sendMessage("§7Потрачено: §b" + formatNumber(totalPrice) + " монеток");
        player.sendMessage("§7Курс: §e" + formatNumber(pricePerUnit) + " монеток §7= §a1 прайм");
        player.sendMessage("");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
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
            player.sendMessage("§c§l✗ У вас нет активных заявок!");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        activeOrders.remove(orderToCancel);

        if (order.type == OrderType.SELL) {
            givePraims(player, (int)order.amount);
            player.sendMessage("§a§l✓ Заявка отменена! Возвращено: §e" + formatNumber(order.amount) + " праймов");
        } else {
            double moneyToReturn = order.amount * order.rate;
            economy.depositPlayer(player, moneyToReturn);
            player.sendMessage("§a§l✓ Заявка отменена! Возвращено: §b" + formatNumber(moneyToReturn) + " монеток");
        }

        saveOrders();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
    }

    private void cancelSpecificOrder(Player player, UUID orderId) {
        Order order = activeOrders.get(orderId);
        if (order == null || !order.owner.equals(player.getUniqueId())) {
            player.sendMessage("§c§l✗ Заявка не найдена!");
            return;
        }

        activeOrders.remove(orderId);

        if (order.type == OrderType.SELL) {
            givePraims(player, (int)order.amount);
            player.sendMessage("§a§l✓ Заявка отменена! Возвращено: §e" + formatNumber(order.amount) + " праймов");
        } else {
            double moneyToReturn = order.amount * order.rate;
            economy.depositPlayer(player, moneyToReturn);
            player.sendMessage("§a§l✓ Заявка отменена! Возвращено: §b" + formatNumber(moneyToReturn) + " монеток");
        }

        saveOrders();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

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
