package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

public class QuickCommand extends Module {
    private final Setting<String> command = settings.getDefaultGroup().add(new StringSetting.Builder()
        .name("command")
        .description("Send a quick message/command with rich placeholders.")
        .defaultValue("/w 23571113_ {CoordX} {CoordY} {CoordZ} {Dimension}")
        .build()
    );

    private final Setting<Integer> delayMs = settings.getDefaultGroup().add(new IntSetting.Builder()
        .name("delay-ms")
        .description("Delay before sending, in milliseconds.")
        .defaultValue(0)
        .min(0)
        .max(60_000)
        .build()
    );

    private boolean hasSent;
    private int totalDelayMsSnapshot;
    private long sendAtNanos;

    public QuickCommand() {
        super(Addon.MilkyWayCategory, "QuickCommand", "Send a message/command with rich placeholders like {CoordX}, {Health}, etc.");
    }

    @Override
    public void onActivate() {
        hasSent = false;
        totalDelayMsSnapshot = delayMs.get();
        sendAtNanos = totalDelayMsSnapshot > 0
            ? System.nanoTime() + (long) totalDelayMsSnapshot * 1_000_000L
            : 0L;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        if (hasSent) return;

        if (totalDelayMsSnapshot > 0 && System.nanoTime() < sendAtNanos) {
            return;
        }

        String parsed = parseCommand(command.get());

        if (parsed.startsWith("/")) {
            mc.getConnection().send(new ServerboundChatCommandPacket(parsed.substring(1)));
        } else {
            mc.getConnection().sendChat(parsed);
        }

        hasSent = true;
        toggle();
    }

    @Override
    public String getInfoString() {
        if (hasSent) return null;
        if (totalDelayMsSnapshot <= 0) return null;
        if (sendAtNanos == 0L) return "0/" + totalDelayMsSnapshot + "ms";

        long now = System.nanoTime();
        long remaining = Math.max(0L, sendAtNanos - now);
        long elapsed = (long) totalDelayMsSnapshot * 1_000_000L - remaining;
        long elapsedMs = Math.max(0L, Math.min(totalDelayMsSnapshot, elapsed / 1_000_000L));
        return elapsedMs + "/" + totalDelayMsSnapshot + "ms";
    }

    private String parseCommand(String input) {
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();

        String time = LocalTime.now().toString().split("\\.")[0];
        String timestamp = LocalDateTime.now().toString().replace("T", " ").split("\\.")[0];

        String dimension = mc.level.dimension().identifier().toString();
        String playerName = mc.player.getName().getString();
        String uuid = mc.player.getStringUUID();

        float health = mc.player.getHealth();
        float maxHealth = mc.player.getMaxHealth();
        int hunger = mc.player.getFoodData().getFoodLevel();
        int xp = mc.player.experienceLevel;
        String facing = mc.player.getDirection().toString();

        String serverIp = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "localhost";
        String serverName = mc.getCurrentServer() != null ? mc.getCurrentServer().name : "singleplayer";

        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();

        ItemStack helmet = mc.player.getInventory().getItem(SlotUtils.ARMOR_START + 3);
        ItemStack chest = mc.player.getInventory().getItem(SlotUtils.ARMOR_START + 2);
        ItemStack legs = mc.player.getInventory().getItem(SlotUtils.ARMOR_START + 1);
        ItemStack boots = mc.player.getInventory().getItem(SlotUtils.ARMOR_START);

        BlockPos posUnder = mc.player.blockPosition().below();
        Block blockUnder = mc.level.getBlockState(posUnder).getBlock();
        String biome = mc.level.getBiome(posUnder).unwrapKey().get().identifier().toString();
        int light = mc.level.getMaxLocalRawBrightness(posUnder);

        boolean sneaking = mc.player.isShiftKeyDown();
        boolean sprinting = mc.player.isSprinting();
        boolean onGround = mc.player.onGround();
        int air = mc.player.getAirSupply();
        int fireTicks = mc.player.getRemainingFireTicks();

        List<String> nearbyNames = mc.level.players().stream()
            .filter(p -> !p.getUUID().equals(mc.player.getUUID()))
            .map(p -> p.getGameProfile().name())
            .collect(Collectors.toList());

        String nearbyPlayers = String.join(", ", nearbyNames);

        String result = input
            .replace("{CoordX}", String.format("%.1f", x))
            .replace("{CoordY}", String.format("%.1f", y))
            .replace("{CoordZ}", String.format("%.1f", z))
            .replace("{Dimension}", dimension)
            .replace("{Player}", playerName)
            .replace("{UUID}", uuid)
            .replace("{IP}", serverIp)
            .replace("{ServerName}", serverName)
            .replace("{Time}", time)
            .replace("{Timestamp}", timestamp)
            .replace("{Health}", String.format("%.1f", health))
            .replace("{MaxHealth}", String.format("%.1f", maxHealth))
            .replace("{Hunger}", String.valueOf(hunger))
            .replace("{XP}", String.valueOf(xp))
            .replace("{Facing}", facing)
            .replace("{Sneaking}", String.valueOf(sneaking))
            .replace("{Sprinting}", String.valueOf(sprinting))
            .replace("{OnGround}", String.valueOf(onGround))
            .replace("{Air}", String.valueOf(air))
            .replace("{FireTicks}", String.valueOf(fireTicks))
            .replace("{SelectedSlot}", String.valueOf(mc.player.getInventory().getSelectedSlot()))
            .replace("{BlockUnder}", blockUnder.getName().getString())
            .replace("{Biome}", biome)
            .replace("{LightLevel}", String.valueOf(light))
            .replace("{MainHand}", mainHand.getHoverName().getString())
            .replace("{MainHandRaw}", BuiltInRegistries.ITEM.getKey(mainHand.getItem()).toString())
            .replace("{OffHand}", offHand.getHoverName().getString())
            .replace("{OffHandRaw}", BuiltInRegistries.ITEM.getKey(offHand.getItem()).toString())
            .replace("{Helmet}", helmet.getHoverName().getString())
            .replace("{HelmetRaw}", BuiltInRegistries.ITEM.getKey(helmet.getItem()).toString())
            .replace("{Chestplate}", chest.getHoverName().getString())
            .replace("{ChestplateRaw}", BuiltInRegistries.ITEM.getKey(chest.getItem()).toString())
            .replace("{Leggings}", legs.getHoverName().getString())
            .replace("{LeggingsRaw}", BuiltInRegistries.ITEM.getKey(legs.getItem()).toString())
            .replace("{Boots}", boots.getHoverName().getString())
            .replace("{BootsRaw}", BuiltInRegistries.ITEM.getKey(boots.getItem()).toString());

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            String name = stack.isEmpty() ? "air" : stack.getHoverName().getString();
            String raw = stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            result = result.replace("{Inventory" + i + "}", name);
            result = result.replace("{Inventory" + i + "Raw}", raw);
        }

        return result;
    }
}
