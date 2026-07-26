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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

public class AutoInvertedY extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private enum THeight {
        Medium, Large, Extra_Large
    }

    private final Setting<Block> block = sgGeneral.add(new BlockSetting.Builder()
    .name("block")
    .description("The block to use when placing the inverted Y.")
    .defaultValue(Blocks.OBSIDIAN)
    .build()
    );

    private final Setting<THeight> height = sgGeneral.add(new EnumSetting.Builder<THeight>()
        .name("height")
        .description("Height of the inverted T.")
        .defaultValue(THeight.Medium)
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

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Render the Y shape while placing.")
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

    private final List<BlockPos> tBlocks = new ArrayList<>();
    private int delay = 0;
    private int index = 0;

    public AutoInvertedY() {
        super(Addon.MilkyModCategory, "AutoInvertedY", "Places an inverted Y shape using your favorite block.");
    }

    @Override
    public void onActivate() {
        tBlocks.clear();
        index = 0;
        delay = 0;

        Vec3 dir = mc.player.getViewVector(1.0f);
        Vec3 horizontal = new Vec3(dir.x, 0, dir.z).normalize().scale(2.0);
        Vec3 target = mc.player.position().add(horizontal).add(0, 2, 0);
        BlockPos basePos = BlockPos.containing(target);

        // Horizontal bar
        boolean eastWest = Math.abs(dir.z) >= Math.abs(dir.x);  // true = wings on west/east
        // Horizontal bar
        tBlocks.add(basePos);
        if (eastWest) {
            // Player is facing mostly north/south → use west/east wings
            tBlocks.add(basePos.west().below());
            tBlocks.add(basePos.east().below());
        } else {
            // Player is facing mostly east/west → use north/south wings
            tBlocks.add(basePos.north().below());
            tBlocks.add(basePos.south().below());
        }

        int stemHeight = switch (height.get()) {
            case Medium -> 1;
            case Large -> 2;
            case Extra_Large -> 3;
        };

        for (int i = 1; i <= stemHeight; i++) {
            tBlocks.add(basePos.above(i));
        }
        
        Item targetItem = block.get().asItem();
        
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == block.get().asItem()) {
                count += mc.player.getInventory().getItem(i).getCount();
            }
        }

        if (count < tBlocks.size()) {
            error("Not enough " + Names.get(block.get().asItem()) + " (need " + tBlocks.size() + ").");
            toggle();
            return;
        }

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == block.get().asItem()) {
                mc.player.getInventory().setSelectedSlot(i);
                break;
            }
        }
    }

    @Override
    public void onDeactivate() {
        tBlocks.clear();
        index = 0;
        delay = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        if (index >= tBlocks.size()) {
            info("Y shape complete.");
            toggle();
            return;
        }

        delay++;
        if (delay < placeDelay.get()) return;

        for (int i = 0; i < blocksPerTick.get() && index < tBlocks.size(); i++) {
            BlockPos pos = tBlocks.get(index);

            if (!mc.level.getBlockState(pos).canBeReplaced()) return;

            // Find block
            int slotToUse = -1;
            for (int s = 0; s < 9; s++) {
                if (mc.player.getInventory().getItem(s).getItem() == block.get().asItem()) {
                    slotToUse = s;
                    break;
                }
            }

            if (slotToUse == -1) {
                error("No "+ Names.get(block.get().asItem()) + " in hotbar.");
                toggle();
                return;
            }

            mc.player.getInventory().setSelectedSlot(slotToUse);

            if (!(mc.player.getMainHandItem().getItem() instanceof BlockItem)) {
                error("Main hand is not a block.");
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

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        for (int i = index; i < tBlocks.size(); i++) {
            event.renderer.box(tBlocks.get(i), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
} 
