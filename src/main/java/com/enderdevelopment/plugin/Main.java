package com.enderdevelopment.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class Main extends JavaPlugin implements Listener, CommandExecutor {
    private static final double RANGE = 14.0;
    private static final long COOLDOWN_MILLIS = 700L;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private GrabPackItems items;

    @Override
    public void onEnable() {
        items = new GrabPackItems(this);
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("grabpack").setExecutor(this);
    }

    @Override
    public void onDisable() {
        cooldowns.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().sendMessage(Component.text("Use /grabpack to receive the GrabPack hands.", NamedTextColor.AQUA));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        HandType hand = items.getHandType(event.getItem());
        if (hand == null) {
            return;
        }
        event.setCancelled(true);
        if (!ready(player)) {
            return;
        }
        Entity target = findTarget(player);
        if (target != null) {
            if (hand == HandType.RIGHT) {
                pullEntity(player, target);
            } else {
                launchPlayer(player, target.getLocation().toVector());
            }
            showBeam(player, target.getLocation().add(0, target.getHeight() * 0.5, 0));
            markUsed(player);
            return;
        }
        if (hand == HandType.LEFT) {
            var block = player.getTargetBlockExact((int) RANGE);
            if (block != null && block.getType().isSolid()) {
                launchPlayer(player, block.getLocation().add(0.5, 1.0, 0.5).toVector());
                showBeam(player, block.getLocation().add(0.5, 0.5, 0.5));
                markUsed(player);
            }
        }
    }

    private Entity findTarget(Player player) {
        Vector direction = player.getEyeLocation().getDirection().normalize();
        Entity best = null;
        double bestScore = -1;
        for (Entity entity : player.getNearbyEntities(RANGE, RANGE, RANGE)) {
            if (entity == player || entity instanceof org.bukkit.entity.Item || entity instanceof org.bukkit.entity.ExperienceOrb) {
                continue;
            }
            Vector offset = entity.getLocation().add(0, entity.getHeight() * 0.5, 0).toVector()
                    .subtract(player.getEyeLocation().toVector());
            double distance = offset.length();
            if (distance > RANGE || distance == 0) {
                continue;
            }
            double alignment = direction.dot(offset.normalize());
            if (alignment < 0.86) {
                continue;
            }
            double score = alignment / distance;
            if (score > bestScore) {
                best = entity;
                bestScore = score;
            }
        }
        return best;
    }

    private void pullEntity(Player player, Entity target) {
        Vector pull = player.getEyeLocation().toVector().subtract(target.getLocation().toVector());
        if (pull.lengthSquared() > 0) {
            target.setVelocity(pull.normalize().multiply(Math.min(1.8, 0.7 + pull.length() * 0.12)));
        }
    }

    private void launchPlayer(Player player, Vector destination) {
        Vector launch = destination.subtract(player.getLocation().toVector());
        if (launch.lengthSquared() > 0) {
            player.setVelocity(launch.normalize().multiply(Math.min(2.0, 0.8 + launch.length() * 0.08)));
        }
    }

    private void showBeam(Player player, org.bukkit.Location target) {
        World world = player.getWorld();
        Vector start = player.getEyeLocation().toVector();
        Vector difference = target.toVector().subtract(start);
        int points = Math.max(2, (int) (difference.length() * 3));
        Vector step = difference.multiply(1.0 / points);
        for (int i = 0; i <= points; i++) {
            world.spawnParticle(Particle.DUST, start.clone().add(step.clone().multiply(i)), 1,
                    new Particle.DustOptions(Color.RED, 0.55f));
        }
    }

    private boolean ready(Player player) {
        long now = System.currentTimeMillis();
        return now - cooldowns.getOrDefault(player.getUniqueId(), 0L) >= COOLDOWN_MILLIS;
    }

    private void markUsed(Player player) {
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("remove")) {
            int removed = removeHands(player);
            player.sendMessage(Component.text("Removed " + removed + " GrabPack hand(s).", NamedTextColor.GRAY));
            return true;
        }
        giveHands(player);
        player.sendMessage(Component.text("GrabPack equipped: right hand pulls targets, left hand pulls you forward.", NamedTextColor.AQUA));
        return true;
    }

    private void giveHands(Player player) {
        player.getInventory().addItem(items.create(HandType.LEFT), items.create(HandType.RIGHT));
    }

    private int removeHands(Player player) {
        int removed = 0;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (items.getHandType(stack) != null) {
                removed += stack.getAmount();
                player.getInventory().setItem(slot, null);
            }
        }
        return removed;
    }

    private enum HandType { LEFT, RIGHT }

    private static final class GrabPackItems {
        private final Main plugin;

        private GrabPackItems(Main plugin) {
            this.plugin = plugin;
        }

        private ItemStack create(HandType type) {
            ItemStack item = new ItemStack(type == HandType.LEFT ? Material.RED_DYE : Material.BLUE_DYE);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text(type == HandType.LEFT ? "GrabPack Left Hand" : "GrabPack Right Hand",
                    type == HandType.LEFT ? NamedTextColor.RED : NamedTextColor.BLUE));
            meta.getPersistentDataContainer().set(plugin.getKey(type), PersistentDataType.STRING, type.name());
            item.setItemMeta(meta);
            return item;
        }

        private HandType getHandType(ItemStack item) {
            if (item == null || !item.hasItemMeta()) {
                return null;
            }
            ItemMeta meta = item.getItemMeta();
            String value = meta.getPersistentDataContainer().get(plugin.getKey(HandType.LEFT), PersistentDataType.STRING);
            if ("LEFT".equals(value)) return HandType.LEFT;
            value = meta.getPersistentDataContainer().get(plugin.getKey(HandType.RIGHT), PersistentDataType.STRING);
            return "RIGHT".equals(value) ? HandType.RIGHT : null;
        }
    }

    private org.bukkit.NamespacedKey getKey(HandType type) {
        return new org.bukkit.NamespacedKey(this, "grabpack_" + type.name().toLowerCase());
    }
}
