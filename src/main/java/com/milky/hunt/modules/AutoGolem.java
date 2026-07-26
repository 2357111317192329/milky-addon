package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

public class AutoGolem extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum GolemType {
    Snowman,
    Ironman,
    Wither
}
    private final Setting<GolemType> type = sgGeneral.add(new EnumSetting.Builder<GolemType>()
        .name("type")
        .description("The type of golem to build.")
        .defaultValue(GolemType.Snowman)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks between each block placement.")
        .defaultValue(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place each tick.")
        .defaultValue(1)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Boolean> continuous = sgGeneral.add(new BoolSetting.Builder()
        .name("continuous")
        .description("Continuously builds snow golems if materials are available.")
        .defaultValue(false)
        .visible(() -> type.get() != GolemType.Wither)
        .build()
    );

    private final Setting<Integer> loopDelay = sgGeneral.add(new IntSetting.Builder()
        .name("loop-delay")
        .description("Ticks to wait between snow golems when in continuous mode.")
        .defaultValue(20)
        .sliderRange(0, 200)
        .visible(continuous::get)
        .build()
    );

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Render the snowman frame while placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the box is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 255, 255, 20))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .build()
    );

    private final List<BlockPos> snowmanBlocks = new ArrayList<>();
    private final List<BlockPos> ironmanBlocks = new ArrayList<>();
    private final List<BlockPos> witherBlocks = new ArrayList<>();
    
    private final List<BlockPos> waitingForBreak = new ArrayList<>();
    private int delay = 0;
    private int index = 0;

    private boolean waitingForNextLoop = false;
    private int loopDelayTimer = 0;
    private boolean waitingForSlotSync = false;

    public AutoGolem() {
        super(Addon.MilkyModCategory, "AutoGolem", "Automatically builds a snow golem, iron golem, or wither.");
    }

    @Override
    public void onActivate() {
        switch (type.get()) {
            case Snowman -> onActivateSnowman();
            case Ironman -> onActivateIronman();
            case Wither -> onActivateWither();
        }
    }

    public void onActivateSnowman() {
        snowmanBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        loopDelayTimer = 0;
        waitingForNextLoop = false;
        waitingForSlotSync = false;

        Vec3 dir = mc.player.getViewVector(1.0f);
        Vec3 horizontal = new Vec3(dir.x, 0, dir.z).normalize().scale(2.0);
        Vec3 target = mc.player.position().add(horizontal).add(0, 2, 0);
        BlockPos basePos = BlockPos.containing(target);

        snowmanBlocks.add(basePos);
        snowmanBlocks.add(basePos.above());
        snowmanBlocks.add(basePos.above(2));

        int snowBlockCount = 0;
        int pumpkinCount = 0;
        for (int i = 0; i < 36; i++) {
            Item item = mc.player.getInventory().getItem(i).getItem();
            if (item == Items.SNOW_BLOCK) snowBlockCount += mc.player.getInventory().getItem(i).getCount();
            if (item == Items.CARVED_PUMPKIN) pumpkinCount += mc.player.getInventory().getItem(i).getCount();
        }

        if (snowBlockCount < 2) {
            error("Not enough snow blocks (need at least 2).");
            toggle();
            return;
        }

        if (pumpkinCount < 1) {
            error("Need at least 1 carved pumpkin.");
            toggle();
            return;
        }

        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getItem(i).getItem();
            if (item == Items.SNOW_BLOCK) {
                mc.player.getInventory().setSelectedSlot(i);
                break;
            }
        }
    }
    public void onActivateIronman() {
        ironmanBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        loopDelayTimer = 0;
        waitingForNextLoop = false;
        waitingForSlotSync = false;

        Vec3 dir = mc.player.getViewVector(1.0f);
        Vec3 horizontal = new Vec3(dir.x, 0, dir.z).normalize().scale(2.0);
        Vec3 target = mc.player.position().add(horizontal).add(0, 3, 0);
        BlockPos basePos = BlockPos.containing(target);

        // Iron Golem body structure
        ironmanBlocks.add(basePos);                     // center iron block
        ironmanBlocks.add(basePos.south());                // upper iron block
        ironmanBlocks.add(basePos.west());              // left arm
        ironmanBlocks.add(basePos.east());              // right arm
        ironmanBlocks.add(basePos.north());               // pumpkin head

        int ironCount = 0;
        int pumpkinCount = 0;
        for (int i = 0; i < 36; i++) {
            Item item = mc.player.getInventory().getItem(i).getItem();
            if (item == Items.IRON_BLOCK) ironCount += mc.player.getInventory().getItem(i).getCount();
            if (item == Items.CARVED_PUMPKIN) pumpkinCount += mc.player.getInventory().getItem(i).getCount();
        }

        if (ironCount < 4) {
            error("Not enough iron blocks (need at least 4).");
            toggle();
            return;
        }

        if (pumpkinCount < 1) {
            error("Need at least 1 carved pumpkin.");
            toggle();
            return;
        }

        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getItem(i).getItem();
            if (item == Items.IRON_BLOCK) {
                mc.player.getInventory().setSelectedSlot(i);
                break;
            }
        }
    }
    public void onActivateWither() {
        
        witherBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        waitingForSlotSync = false;

        Vec3 dir = mc.player.getViewVector(1.0f);
        Vec3 horizontal = new Vec3(dir.x, 0, dir.z).normalize().scale(2.0);
        Vec3 target = mc.player.position().add(horizontal).add(0, 2, 0);
        BlockPos basePos = BlockPos.containing(target);

        // Wither body structure
        witherBlocks.add(basePos);
        witherBlocks.add(basePos.west());
        witherBlocks.add(basePos.east());
        witherBlocks.add(basePos.below());
        witherBlocks.add(basePos.above().west());
        witherBlocks.add(basePos.above());
        witherBlocks.add(basePos.above().east());

        int soulCount = 0;
        int skullCount = 0;
        for (int i = 0; i < 36; i++) {
            Item item = mc.player.getInventory().getItem(i).getItem();
            if (item == Items.SOUL_SAND) soulCount += mc.player.getInventory().getItem(i).getCount();
            if (item == Items.WITHER_SKELETON_SKULL) skullCount += mc.player.getInventory().getItem(i).getCount();
        }

        if (soulCount < 4) {
            error("Not enough soul sand (need at least 4).");
            toggle();
            return;
        }

        if (skullCount < 3) {
            error("Need at least 3 Wither Skeleton Skulls.");
            toggle();
            return;
        }

        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getItem(i).getItem();
            if (item == Items.SOUL_SAND) {
                mc.player.getInventory().setSelectedSlot(i);
                break;
            }
        }
    }
    @Override
    public void onDeactivate() {
        switch (type.get()) {
            case Snowman -> onDeactivateSnowman();
            case Ironman -> onDeactivateIronman();
            case Wither -> onDeactivateWither();
        }
    }
    public void onDeactivateSnowman() {
        snowmanBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        loopDelayTimer = 0;
        waitingForNextLoop = false;
        waitingForSlotSync = false;
    }
    
    public void onDeactivateIronman() {
        ironmanBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        loopDelayTimer = 0;
        waitingForNextLoop = false;
        waitingForSlotSync = false;
    }
    
    public void onDeactivateWither() {
        witherBlocks.clear();
        waitingForBreak.clear();
        index = 0;
        delay = 0;
        waitingForSlotSync = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
    switch (type.get()) {
        case Snowman -> onTickSnowman(event);
        case Ironman -> onTickIronman(event);
        case Wither -> onTickWither(event);
    }
}
    
     private void onTickSnowman(TickEvent.Post event) {
    if (mc.player == null || mc.level == null) return;

    if (waitingForNextLoop) {
        loopDelayTimer++;
        if (loopDelayTimer >= loopDelay.get()) {
            waitingForNextLoop = false;
            onActivate();
        }
        return;
    }

    if (waitingForSlotSync) {
        waitingForSlotSync = false;
        return;
    }

    if (index >= snowmanBlocks.size()) {
        info("Snowman complete.");
        if (continuous.get()) {
            waitingForNextLoop = true;
            loopDelayTimer = 0;
        } else {
            toggle();
        }
        return;
    }

    delay++;
    if (delay < placeDelay.get()) return;

    for (int i = 0; i < blocksPerTick.get() && index < snowmanBlocks.size(); i++) {
        BlockPos pos = snowmanBlocks.get(index);

        if (!mc.level.getBlockState(pos).canBeReplaced()) {
            if (!waitingForBreak.contains(pos)) {
                mc.gameMode.startDestroyBlock(pos, Direction.UP);
                mc.player.swing(InteractionHand.MAIN_HAND);
                waitingForBreak.add(pos);
            }
            return;
        }

        waitingForBreak.remove(pos);

        Item needed = (index < 2) ? Items.SNOW_BLOCK : Items.CARVED_PUMPKIN;

        int slotToSelect = -1;
        boolean foundItem = false;
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getItem(slot).getItem() == needed) {
                slotToSelect = slot;
                foundItem = true;
                break;
            }
        }

        if (!foundItem) {
            error("Missing required block: " + Names.get(needed));
            toggle();
            return;
        }

        if (mc.player.getInventory().getSelectedSlot() != slotToSelect) {
            mc.player.getInventory().setSelectedSlot(slotToSelect);
            waitingForSlotSync = true;
            return;
        }

        if (!(mc.player.getMainHandItem().getItem() instanceof BlockItem)) {
            error("Main hand item is not a block.");
            toggle();
            return;
        }

        BlockHitResult bhr = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);

        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
        mc.player.connection.send(new ServerboundUseItemOnPacket(
            InteractionHand.OFF_HAND, bhr, mc.player.containerMenu.getStateId() + 2));
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
        mc.player.swing(InteractionHand.MAIN_HAND);

        index++;
    }

    delay = 0;
}

    private void onTickIronman(TickEvent.Post event) {
        
        if (mc.player == null || mc.level == null) return;

        if (waitingForNextLoop) {
            loopDelayTimer++;
            if (loopDelayTimer >= loopDelay.get()) {
                waitingForNextLoop = false;
                onActivate();
            }
            return;
        }

        if (waitingForSlotSync) {
            waitingForSlotSync = false;
            return;
        }

        if (index >= ironmanBlocks.size()) {
            info("Iron Golem complete.");
            if (continuous.get()) {
                waitingForNextLoop = true;
                loopDelayTimer = 0;
            } else {
                toggle();
            }
            return;
        }

        delay++;
        if (delay < placeDelay.get()) return;

        for (int i = 0; i < blocksPerTick.get() && index < ironmanBlocks.size(); i++) {
            BlockPos pos = ironmanBlocks.get(index);

            if (!mc.level.getBlockState(pos).canBeReplaced()) {
                if (!waitingForBreak.contains(pos)) {
                    mc.gameMode.startDestroyBlock(pos, Direction.UP);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    waitingForBreak.add(pos);
                }
                return;
            }

            waitingForBreak.remove(pos);

            Item needed = (index < 4) ? Items.IRON_BLOCK : Items.CARVED_PUMPKIN;

            int slotToSelect = -1;
            boolean foundItem = false;
            for (int slot = 0; slot < 9; slot++) {
                if (mc.player.getInventory().getItem(slot).getItem() == needed) {
                    slotToSelect = slot;
                    foundItem = true;
                    break;
                }
            }

            if (!foundItem) {
                error("Missing required block: " + Names.get(needed));
                toggle();
                return;
            }

            if (mc.player.getInventory().getSelectedSlot() != slotToSelect) {
                mc.player.getInventory().setSelectedSlot(slotToSelect);
                waitingForSlotSync = true;
                return;
            }

            if (!(mc.player.getMainHandItem().getItem() instanceof BlockItem)) {
                error("Main hand item is not a block.");
                toggle();
                return;
            }

            BlockHitResult bhr = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);

            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            mc.player.connection.send(new ServerboundUseItemOnPacket(
                InteractionHand.OFF_HAND, bhr, mc.player.containerMenu.getStateId() + 2));
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            mc.player.swing(InteractionHand.MAIN_HAND);

            index++;
        }

        delay = 0;
}
    private void onTickWither(TickEvent.Post event) {
        
        if (mc.player == null || mc.level == null) return;

        if (waitingForSlotSync) {
            waitingForSlotSync = false;
            return;
        }

        if (index >= witherBlocks.size()) {
            info("Wither complete.");
            toggle();
            return;
        }

        delay++;
        if (delay < placeDelay.get()) return;

        for (int i = 0; i < blocksPerTick.get() && index < witherBlocks.size(); i++) {
            BlockPos pos = witherBlocks.get(index);

            if (!mc.level.getBlockState(pos).canBeReplaced()) {
                if (!waitingForBreak.contains(pos)) {
                    mc.gameMode.startDestroyBlock(pos, Direction.UP);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    waitingForBreak.add(pos);
                }
                return;
            }

            waitingForBreak.remove(pos);

            Item needed = (index < 4) ? Items.SOUL_SAND : Items.WITHER_SKELETON_SKULL;

            int slotToSelect = -1;
            boolean foundItem = false;
            for (int slot = 0; slot < 9; slot++) {
                if (mc.player.getInventory().getItem(slot).getItem() == needed) {
                    slotToSelect = slot;
                    foundItem = true;
                    break;
                }
            }

            if (!foundItem) {
                error("Missing required block: " + Names.get(needed));
                toggle();
                return;
            }

            if (mc.player.getInventory().getSelectedSlot() != slotToSelect) {
                mc.player.getInventory().setSelectedSlot(slotToSelect);
                waitingForSlotSync = true;
                return;
            }

            if (!(mc.player.getMainHandItem().getItem() instanceof BlockItem)) {
                error("Main hand item is not a block.");
                toggle();
                return;
            }

            BlockPos placeOn = pos;
            Direction direction = Direction.UP;

            if (needed == Items.WITHER_SKELETON_SKULL) {
                placeOn = pos.below();
                direction = Direction.UP;
            }

            BlockHitResult bhr = new BlockHitResult(Vec3.atCenterOf(placeOn), direction, placeOn, false);


            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            mc.player.connection.send(new ServerboundUseItemOnPacket(
                InteractionHand.OFF_HAND, bhr, mc.player.containerMenu.getStateId() + 2));
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            mc.player.swing(InteractionHand.MAIN_HAND);

            index++;
        }

        delay = 0;
}

    @EventHandler
    private void onRender(Render3DEvent event) {
         if (!render.get()) return;
        
        switch (type.get()) {
        case Snowman -> onRenderSnowman(event);
        case Ironman -> onRenderIronman(event);
        case Wither  -> onRenderWither(event);
    }
}

    private void onRenderSnowman(Render3DEvent event) {
        if (!render.get()) return;
        for (int i = index; i < snowmanBlocks.size(); i++) {
            BlockPos pos = snowmanBlocks.get(i);
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
    
    private void onRenderIronman(Render3DEvent event) {
        if (!render.get()) return;
        for (int i = index; i < ironmanBlocks.size(); i++) {
            BlockPos pos = ironmanBlocks.get(i);
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
    
    private void onRenderWither(Render3DEvent event) {
        if (!render.get()) return;
        for (int i = index; i < witherBlocks.size(); i++) {
            BlockPos pos = witherBlocks.get(i);
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
