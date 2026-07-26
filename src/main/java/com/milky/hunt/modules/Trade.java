package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Trade extends Module {
    public enum Mode { Buy, Sell }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Buy: pay emeralds for result. Sell: give items to get emeralds.")
        .defaultValue(Mode.Buy)
        .build()
    );

    private final Setting<Item> buyItem = sgGeneral.add(new ItemSetting.Builder()
        .name("buy-item")
        .description("Result item to obtain in Buy mode (e.g., BOOKSHELF or ENCHANTED_BOOK).")
        .defaultValue(Items.BOOKSHELF)
        .visible(() -> mode.get() == Mode.Buy)
        .build()
    );

    private final Setting<Item> sellCostItem = sgGeneral.add(new ItemSetting.Builder()
        .name("sell-cost-item")
        .description("Cost item to give in Sell mode (result must be EMERALD).")
        .defaultValue(Items.STRING)
        .visible(() -> mode.get() == Mode.Sell)
        .build()
    );

    private final Setting<String> enchTargetsLine = sgGeneral.add(new StringSetting.Builder()
        .name("enchanted-targets")
        .description("For ENCHANTED_BOOK in Buy mode. Semicolon-separated entries like: minecraft:silk_touch:1; minecraft:sharpness:5")
        .defaultValue("minecraft:silk_touch:1; minecraft:sharpness:5")
        .visible(() -> mode.get() == Mode.Buy && buyItem.get() == Items.ENCHANTED_BOOK)
        .build()
    );

    private final Setting<Integer> buyMaxPrice = sgGeneral.add(new IntSetting.Builder()
        .name("buy-max-price")
        .description("Max emeralds to spend in Buy mode (0 = no limit).")
        .defaultValue(0)
        .min(0)
        .sliderMax(64)
        .visible(() -> mode.get() == Mode.Buy)
        .build()
    );

    private final Setting<Integer> sellMinPrice = sgGeneral.add(new IntSetting.Builder()
        .name("sell-min-price")
        .description("Min emeralds to receive in Sell mode (0 = no limit).")
        .defaultValue(0)
        .min(0)
        .sliderMax(64)
        .visible(() -> mode.get() == Mode.Sell)
        .build()
    );

    private final Setting<Boolean> closeAfter = sgGeneral.add(new BoolSetting.Builder()
        .name("close-after")
        .description("Close the merchant screen after trading.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> selectDelayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("select-delay-ticks")
        .description("Ticks to wait after selecting the recipe before clicking the result (helps avoid UI/server desync).")
        .defaultValue(3)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private boolean haveOffers = false;
    private int screenSyncId = -1;
    private MerchantOffers offers = null;
    private int selectedOfferIdx = -1;
    private enum Step { Idle, SelectOffer, DelayThenClick, ClickResult, CloseAfterDelay }
    private Step step = Step.Idle;
    private int delayTicks = 0;

    public Trade() {
        super(Addon.MilkyModCategory, "Trade", "Auto trades with villagers.");
    }

    @Override
    public void onActivate() {
        resetSession();
    }

    @Override
    public void onDeactivate() {
        resetSession();
    }

    private void resetSession() {
        haveOffers = false;
        screenSyncId = -1;
        offers = null;
        selectedOfferIdx = -1;
        step = Step.Idle;
        delayTicks = 0;
    }

    private void closeTradeScreen(MerchantMenu handler) {
        if (mc.player != null) mc.player.closeContainer();
        if (mc.getConnection() != null) mc.getConnection().send(new ServerboundContainerClosePacket(handler.containerId));
        resetSession();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof ClientboundMerchantOffersPacket pkt)) return;
        if (!(mc.player != null && mc.player.containerMenu instanceof MerchantMenu handler)) return;
        if (pkt.getContainerId() != handler.containerId) return;
        applyOffers(handler, pkt.getOffers(), "packet");
    }

    private void tryPullOffersFromHandler(MerchantMenu handler) {
        MerchantOffers list = handler.getOffers();
        if (list != null && !list.isEmpty()) applyOffers(handler, list, "handler:getRecipes");
    }

    private void applyOffers(MerchantMenu handler, MerchantOffers list, String source) {
        offers = list;
        screenSyncId = handler.containerId;
        haveOffers = offers != null && !offers.isEmpty();
        selectedOfferIdx = -1;
        step = Step.Idle;
        if (!haveOffers) return;
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer o = offers.get(i);
            if (o.isOutOfStock() || o.getUses() >= o.getMaxUses()) continue;
            if (matchesByMode(o)) { selectedOfferIdx = i; break; }
        }
        if (selectedOfferIdx >= 0) {
            step = Step.SelectOffer;
        } else {
            if (closeAfter.get()) {
                delayTicks = Math.max(0, selectDelayTicks.get());
                step = Step.CloseAfterDelay;
            } else {
                resetSession();
            }
        }
    }

    private boolean matchesByMode(MerchantOffer o) {
        if (mode.get() == Mode.Buy) {
            Item wantResult = buyItem.get();
            if (wantResult == null) return false;
            Item resultItem = o.getResult().getItem();
            if (resultItem != wantResult) return false;
            if (resultItem == Items.ENCHANTED_BOOK) {
                String line = enchTargetsLine.get();
                if (!(line == null || line.isBlank())) {
                    List<EnchTarget> targets = parseEnchTargets(line);
                    if (!targets.isEmpty() && !enchantedBookMatchesExactly(o.getResult(), targets)) return false;
                }
            }
            int emeraldCost = emeraldCostOfOffer(o);
            int maxP = buyMaxPrice.get();
            if (maxP > 0 && emeraldCost > maxP) return false;
            return true;
        } else {
            if (o.getResult().getItem() != Items.EMERALD) return false;
            Item targetCost = sellCostItem.get();
            if (targetCost == null) return false;
            Item a = o.getBaseCostA().getItem();
            Item b = o.getItemCostB().map(t -> t.item().value()).orElse(null);
            if (!(a == targetCost || (b != null && b == targetCost))) return false;
            int emeraldOut = o.getResult().getCount();
            int minP = sellMinPrice.get();
            if (minP > 0 && emeraldOut < minP) return false;
            return true;
        }
    }

    private int emeraldCostOfOffer(MerchantOffer o) {
        int cost = 0;
        ItemStack displayed = o.getCostA();
        if (displayed.getItem() == Items.EMERALD) {
            cost += Math.max(1, displayed.getCount());
        }
        var sb = o.getItemCostB();
        if (sb.isPresent() && sb.get().item().value() == Items.EMERALD) {
            cost += sb.get().count();
        }
        return cost;
    }

    private static class EnchTarget {
        final Identifier id;
        final int level;
        EnchTarget(Identifier id, int level) { this.id = id; this.level = level; }
    }

    private List<EnchTarget> parseEnchTargets(String line) {
        List<EnchTarget> out = new ArrayList<>();
        String[] parts = line.split(";");
        for (String raw : parts) {
            if (raw == null) continue;
            String s = raw.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            String ns = "minecraft";
            String name;
            int lv;
            String[] fields = s.split(":");
            if (fields.length == 2) {
                name = fields[0];
                lv = parseIntSafe(fields[1], -1);
            } else if (fields.length == 3) {
                ns = fields[0];
                name = fields[1];
                lv = parseIntSafe(fields[2], -1);
            } else {
                continue;
            }
            if (lv <= 0) continue;
            Identifier id = Identifier.fromNamespaceAndPath(ns, name);
            out.add(new EnchTarget(id, lv));
        }
        return out;
    }

    private boolean enchantedBookMatchesExactly(ItemStack book, List<EnchTarget> targets) {
        if (book.get(DataComponents.STORED_ENCHANTMENTS) == null) return false;
        var enchMap = EnchantmentHelper.getEnchantmentsForCrafting(book);
        for (var entry : enchMap.entrySet()) {
            Holder<Enchantment> key = entry.getKey();
            int level = entry.getIntValue();
            if (mc.level == null) continue;
            var optReg = mc.level.registryAccess().lookup(Registries.ENCHANTMENT);
            if (optReg.isEmpty()) continue;
            Identifier onBook = optReg.get().getKey(key.value());
            if (onBook == null) continue;
            for (EnchTarget t : targets) {
                if (t.id.equals(onBook) && level == t.level) return true;
            }
        }
        return false;
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception ignored) { return def; }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        if (!(mc.player.containerMenu instanceof MerchantMenu handler)) {
            if (haveOffers) resetSession();
            return;
        }
        if (!haveOffers) {
            tryPullOffersFromHandler(handler);
            return;
        }
        if (handler.containerId != screenSyncId) { resetSession(); return; }

        if (step != Step.CloseAfterDelay) {
            if (selectedOfferIdx < 0 || selectedOfferIdx >= offers.size()) return;
        }

        switch (step) {
            case SelectOffer -> {
                mc.getConnection().send(new ServerboundSelectTradePacket(selectedOfferIdx));
                delayTicks = Math.max(0, selectDelayTicks.get());
                step = Step.DelayThenClick;
            }
            case DelayThenClick -> {
                if (delayTicks-- <= 0) step = Step.ClickResult;
            }
            case ClickResult -> {
                int resultSlot = 2;
                int revision = handler.getStateId();
                Int2ObjectMap<HashedStack> changedSlots = new Int2ObjectOpenHashMap<>();
                mc.getConnection().send(new ServerboundContainerClickPacket(
                    handler.containerId, revision, (short)resultSlot,(byte)0,
                    ContainerInput.QUICK_MOVE, changedSlots,HashedStack.EMPTY 
                ));
                if (closeAfter.get()) {
                    if (mc.player != null) mc.player.closeContainer();
                    mc.getConnection().send(new ServerboundContainerClosePacket(handler.containerId));
                }
                resetSession();
            }
            case CloseAfterDelay -> {
                if (delayTicks-- <= 0) {
                    if (closeAfter.get()) closeTradeScreen(handler);
                    else resetSession();
                }
            }
            case Idle -> {
                step = Step.SelectOffer;
            }
        }
    }
}
