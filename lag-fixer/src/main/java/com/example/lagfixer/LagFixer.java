package com.example.lagfixer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class LagFixer extends JavaPlugin implements TabExecutor, Listener {

    /** Profession name -> icon shown in the GUI */
    private static final LinkedHashMap<String, Material> PROFESSIONS = new LinkedHashMap<>();
    /** Preset name -> enchantment key (villager sells that enchanted book) */
    private static final LinkedHashMap<String, String> PRESETS = new LinkedHashMap<>();

    static {
        PROFESSIONS.put("armorer", Material.BLAST_FURNACE);
        PROFESSIONS.put("butcher", Material.SMOKER);
        PROFESSIONS.put("cartographer", Material.CARTOGRAPHY_TABLE);
        PROFESSIONS.put("cleric", Material.BREWING_STAND);
        PROFESSIONS.put("farmer", Material.COMPOSTER);
        PROFESSIONS.put("fisherman", Material.BARREL);
        PROFESSIONS.put("fletcher", Material.FLETCHING_TABLE);
        PROFESSIONS.put("leatherworker", Material.CAULDRON);
        PROFESSIONS.put("librarian", Material.LECTERN);
        PROFESSIONS.put("mason", Material.STONECUTTER);
        PROFESSIONS.put("shepherd", Material.LOOM);
        PROFESSIONS.put("toolsmith", Material.SMITHING_TABLE);
        PROFESSIONS.put("weaponsmith", Material.GRINDSTONE);

        PRESETS.put("mending", "mending");
        PRESETS.put("unbreaking", "unbreaking");
        PRESETS.put("efficiency", "efficiency");
        PRESETS.put("fortune", "fortune");
        PRESETS.put("silk_touch", "silk_touch");
        PRESETS.put("sharpness", "sharpness");
        PRESETS.put("protection", "protection");
        PRESETS.put("looting", "looting");
        PRESETS.put("feather_falling", "feather_falling");
    }

    private static class VillagerGui implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    @Override
    public void onEnable() {
        Objects.requireNonNull(getCommand("villager")).setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // /villager  -> open GUI
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Only players can open the GUI. Use /villager give <player> <type>");
                return true;
            }
            if (!p.hasPermission("villager.use")) {
                p.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                return true;
            }
            openGui(p);
            return true;
        }

        // /villager give <player> <type> [amount]
        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("villager.give")) {
                sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(Component.text("Usage: /villager give <player> <type> [amount]", NamedTextColor.RED));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found: " + args[1], NamedTextColor.RED));
                return true;
            }
            String type = args[2].toLowerCase(Locale.ROOT);
            if (!PROFESSIONS.containsKey(type) && !PRESETS.containsKey(type)) {
                sender.sendMessage(Component.text("Unknown type. Options: "
                        + String.join(", ", allTypes()), NamedTextColor.RED));
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Math.max(1, Math.min(10, Integer.parseInt(args[3])));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Amount must be a number.", NamedTextColor.RED));
                    return true;
                }
            }
            for (int i = 0; i < amount; i++) spawnVillager(target.getLocation(), type);
            sender.sendMessage(Component.text("Spawned " + amount + " " + type + " villager(s) at "
                    + target.getName() + ".", NamedTextColor.GREEN));
            if (sender != target) {
                target.sendMessage(Component.text("You received a " + type + " villager!", NamedTextColor.GREEN));
            }
            return true;
        }

        sender.sendMessage(Component.text("Usage: /villager  |  /villager give <player> <type> [amount]", NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("give"), args[0]);
        if (args[0].equalsIgnoreCase("give")) {
            if (args.length == 2) {
                return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()), args[1]);
            }
            if (args.length == 3) return filter(allTypes(), args[2]);
            if (args.length == 4) return filter(List.of("1", "2", "3", "5", "10"), args[3]);
        }
        return List.of();
    }

    private List<String> allTypes() {
        List<String> all = new ArrayList<>(PRESETS.keySet());
        all.addAll(PROFESSIONS.keySet());
        return all;
    }

    private List<String> filter(List<String> options, String start) {
        String s = start.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(s)).collect(Collectors.toList());
    }

    // -------------------------------------------------------------------- GUI

    private void openGui(Player p) {
        VillagerGui holder = new VillagerGui();
        Inventory inv = Bukkit.createInventory(holder, 36,
                Component.text("Villagers", NamedTextColor.DARK_GREEN));
        holder.inv = inv;

        int slot = 0;
        for (Map.Entry<String, Material> e : PROFESSIONS.entrySet()) {
            inv.setItem(slot++, icon(e.getValue(), e.getKey(), "Click to spawn a " + e.getKey()));
        }
        slot = 18; // enchanted-book villagers on the bottom half
        for (String preset : PRESETS.keySet()) {
            inv.setItem(slot++, icon(Material.ENCHANTED_BOOK, preset + " villager",
                    "Librarian selling " + preset + " (1 emerald)"));
        }
        p.openInventory(inv);
    }

    private ItemStack icon(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(capitalize(name), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof VillagerGui)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;

        int slot = e.getSlot();
        String type = null;
        if (slot >= 0 && slot < PROFESSIONS.size()) {
            type = new ArrayList<>(PROFESSIONS.keySet()).get(slot);
        } else if (slot >= 18 && slot - 18 < PRESETS.size()) {
            type = new ArrayList<>(PRESETS.keySet()).get(slot - 18);
        }
        if (type == null) return;
        if (!p.hasPermission("villager.give")) {
            p.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }
        spawnVillager(p.getLocation(), type);
        p.sendMessage(Component.text("Spawned a " + type + " villager.", NamedTextColor.GREEN));
    }

    // --------------------------------------------------------------- spawning

    private void spawnVillager(Location loc, String type) {
        Villager.Profession profession;
        List<MerchantRecipe> recipes = null;

        if (PRESETS.containsKey(type)) {
            profession = profession("librarian");
            recipes = List.of(bookTrade(PRESETS.get(type)));
        } else {
            profession = profession(type);
        }

        final Villager.Profession prof = profession;
        final List<MerchantRecipe> finalRecipes = recipes;
        loc.getWorld().spawn(loc, Villager.class, v -> {
            v.setProfession(prof);
            v.setVillagerType(Villager.Type.PLAINS);
            v.setVillagerLevel(finalRecipes != null ? 5 : 1);
            v.setVillagerExperience(1); // locks the profession in
            if (finalRecipes != null) v.setRecipes(finalRecipes);
        });
    }

    private Villager.Profession profession(String name) {
        Villager.Profession p = Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(name));
        return p != null ? p : Villager.Profession.NONE;
    }

    private MerchantRecipe bookTrade(String enchantKey) {
        Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchantKey));
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        if (ench != null) {
            meta.addStoredEnchant(ench, ench.getMaxLevel(), true);
        }
        book.setItemMeta(meta);

        MerchantRecipe recipe = new MerchantRecipe(book, 0, 9999, true);
        recipe.addIngredient(new ItemStack(Material.EMERALD, 1));
        return recipe;
    }

    private String capitalize(String s) {
        s = s.replace('_', ' ');
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
