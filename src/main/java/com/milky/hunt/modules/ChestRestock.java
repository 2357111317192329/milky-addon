package com.milky.hunt.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class ChestRestock extends Module {
    private enum State { IDLE, PATHING, OPEN_ONCE, WAIT_OPEN, LOOTING }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Item> targetItem = sgGeneral.add(new ItemSetting.Builder()
        .name("target-item")
        .description("Item to keep stocked in your inventory.")
        .defaultValue(net.minecraft.world.item.Items.SEAGRASS)
        .build()
    );

    private final Setting<Integer> restockUntil = sgGeneral.add(new IntSetting.Builder()
        .name("restock-until")
        .description("Target total count in inventory.")
        .defaultValue(2304)
        .min(1)
        .sliderMax(2304)
        .build()
    );

    private final Setting<BlockPos> chestPosSetting = sgGeneral.add(new BlockPosSetting.Builder()
        .name("chest-pos")
        .description("Chest coordinate.")
        .defaultValue(new BlockPos(0, 64, 0))
        .build()
    );

    private final Setting<Double> reachDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach-distance")
        .description("Consider reached when you are within this distance.")
        .defaultValue(3)
        .min(0.5)
        .sliderMax(5.0)
        .build()
    );

    private final Setting<Integer> clicksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-tick")
        .description("Max quick-move clicks per tick while looting.")
        .defaultValue(1)
        .min(1)
        .max(8)
        .build()
    );

    private static final int WAIT_OPEN_TICKS_MAX = 10;
    private State state = State.IDLE;
    private BlockPos chestPos = BlockPos.ZERO;
    private int waitOpenTicks = 0;

    public ChestRestock() {
        super(Addon.MilkyModCategory, "ChestRestock", "One-shot restock from a chest using low-level packets.");
    }

    @Override
    public void onActivate() {
        chestPos = chestPosSetting.get();
        if (mc.player == null || mc.level == null) { toggle(); return; }
        if (countInInventory(targetItem.get()) >= restockUntil.get()) { toggle(); return; }
        state = State.IDLE;
        waitOpenTicks = 0;
    }

    @Override
    public void onDeactivate() {
        stopBaritone();
        closeIfOpen();
        state = State.IDLE;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.level == null) { toggle(); return; }

        if (countInInventory(targetItem.get()) >= restockUntil.get()) { finishAndToggle(); return; }

        switch (state) {
            case IDLE -> {
                startBaritone(chestPos);
                state = State.PATHING;
            }
            case PATHING -> {
                if (mc.player.blockPosition().closerThan(chestPos, reachDistance.get())) {
                    stopBaritone();
                    state = State.OPEN_ONCE;
                }
            }
            case OPEN_ONCE -> {
                tryOpenChestPacket(chestPos);
                state = State.WAIT_OPEN;
                waitOpenTicks = WAIT_OPEN_TICKS_MAX;
            }
            case WAIT_OPEN -> {
                if (isContainerOpen()) {
                    state = State.LOOTING;
                    break;
                }
                if (--waitOpenTicks <= 0) {
                    finishAndToggle();
                }
            }
            case LOOTING -> {
                if (!isContainerOpen()) { finishAndToggle(); return; }
                int moved = lootSome(targetItem.get(), clicksPerTick.get());
                boolean chestNoMoreTarget = moved == 0;
                boolean full = !hasSpaceFor(targetItem.get());
                if (countInInventory(targetItem.get()) >= restockUntil.get() || chestNoMoreTarget || full) {
                    finishAndToggle();
                }
            }
        }
    }

    private void finishAndToggle() {
        closeIfOpen();
        stopBaritone();
        toggle();
    }

    private void startBaritone(BlockPos pos) {
        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
    }

    private void stopBaritone() {
        var b = BaritoneAPI.getProvider().getPrimaryBaritone();
        b.getPathingBehavior().cancelEverything();
        b.getCustomGoalProcess().setGoal(null);
    }

    private boolean isContainerOpen() {
        AbstractContainerMenu h = mc.player.containerMenu;
        return h != null && h != mc.player.inventoryMenu;
    }
    
    private void closeIfOpen() {
        if (mc == null || mc.player == null) return;

        if (isContainerOpen()) {
            mc.player.closeContainer();
        }
        
        if (mc.screen != null) {
            mc.setScreen(null);
        }
    }


    private void tryOpenChestPacket(BlockPos pos) {
        Minecraft m = mc;
        if (m.player == null || m.level == null || m.getConnection() == null) return;

        Vec3 hitVec = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);

        var pum = ((ClientLevel) m.level).getBlockStatePredictionHandler();
        pum.startPredicting();
        int sequence = pum.currentSequence();

        m.getConnection().send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, sequence));
        m.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }

    private int lootSome(Item item, int budget) {
        AbstractContainerMenu h = mc.player.containerMenu;
        if (h == null) return 0;

        int clicks = 0;
        int chestSlots = Math.max(0, h.slots.size() - 36);

        if (h instanceof ChestMenu) {
            for (int i = 0; i < chestSlots && clicks < budget; i++) {
                Slot s = h.slots.get(i);
                ItemStack st = s.getItem();
                if (!st.isEmpty() && st.getItem() == item) {
                    mc.gameMode.handleContainerInput(h.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
                    clicks++;
                }
            }
            return clicks;
        }

        for (int i = 0; i < chestSlots && clicks < budget; i++) {
            Slot s = h.slots.get(i);
            ItemStack st = s.getItem();
            if (!st.isEmpty() && st.getItem() == item) {
                mc.gameMode.handleContainerInput(h.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
                clicks++;
            }
        }
        return clicks;
    }

    private int countInInventory(Item item) {
        int total = 0;
        for (ItemStack st : mc.player.getInventory().getNonEquipmentItems()) {
            if (!st.isEmpty() && st.getItem() == item) total += st.getCount();
        }
        ItemStack off = mc.player.getOffhandItem();
        if (!off.isEmpty() && off.getItem() == item) total += off.getCount();
        return total;
    }

    private boolean hasSpaceFor(Item item) {
        int max = new ItemStack(item).getMaxStackSize();
        for (ItemStack st : mc.player.getInventory().getNonEquipmentItems()) {
            if (st.isEmpty()) return true;
            if (st.getItem() == item && st.getCount() < max) return true;
        }
        ItemStack off = mc.player.getOffhandItem();
        return off.isEmpty() || (off.getItem() == item && off.getCount() < max);
    }

    @Override
    public String getInfoString() {
        FindItemResult r = InvUtils.find(targetItem.get());
        return Names.get(targetItem.get()) + "*" + r.count();
    }
}
