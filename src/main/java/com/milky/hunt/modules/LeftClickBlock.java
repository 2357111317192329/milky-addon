package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Collections;

public class LeftClickBlock extends Module {
    private static final Direction[] ORDERED_FACES = {
        Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> oneInteractionPerTick = sgGeneral.add(new BoolSetting.Builder()
        .name("one-interaction-per-tick")
        .description("One interaction per tick.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to attack (break).")
        .defaultValue(Blocks.CARROTS)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Attack range.")
        .min(0)
        .defaultValue(3.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Boolean> sendStopSameTick = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-same-tick")
        .description("Also send STOP_DESTROY_BLOCK in the same tick (useful for zero-hardness crops).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Send a hand swing packet after starting the break.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyMatureCrops = sgGeneral.add(new BoolSetting.Builder()
        .name("only-mature-crops")
        .description("Only break crops when fully grown. Ignored for non-crop blocks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> faceUp    = sgGeneral.add(new BoolSetting.Builder().name("face-up").defaultValue(true).build());
    private final Setting<Boolean> faceNorth = sgGeneral.add(new BoolSetting.Builder().name("face-north").defaultValue(false).build());
    private final Setting<Boolean> faceEast  = sgGeneral.add(new BoolSetting.Builder().name("face-east").defaultValue(false).build());
    private final Setting<Boolean> faceSouth = sgGeneral.add(new BoolSetting.Builder().name("face-south").defaultValue(false).build());
    private final Setting<Boolean> faceWest  = sgGeneral.add(new BoolSetting.Builder().name("face-west").defaultValue(false).build());
    private final Setting<Boolean> faceDown  = sgGeneral.add(new BoolSetting.Builder().name("face-down").defaultValue(false).build());

    public LeftClickBlock() {
        super(Addon.MilkyModCategory, "LeftClickBlock",
            "Automatically left-clicks (breaks) target blocks in range using low-level packets (e.g., harvest carrots/potatoes).");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.level == null || mc.player == null) return;

        BlockPos playerPos = mc.player.blockPosition();
        int r = Math.max(0, (int) Math.floor(range.get()));
        Set<Block> targetBlocks = new HashSet<>(blocks.get());

        List<Direction> selectedFaces = getSelectedFaces();
        if (selectedFaces.isEmpty()) return;

        outer:
        for (BlockPos pos : BlockPos.betweenClosed(playerPos.offset(-r, -r, -r), playerPos.offset(r, r, r))) {
            BlockState state = mc.level.getBlockState(pos);
            if (!targetBlocks.contains(state.getBlock())) continue;

            if (!passesMaturityFilter(state)) continue;

            boolean didAnyFaceThisBlock = false;

            for (Direction face : ORDERED_FACES) {
                if (!selectedFaces.contains(face)) continue;

                Vec3 hitPos = faceCenter(pos, face);
                if (mc.player.getEyePosition().distanceTo(hitPos) > range.get()) continue;

                didAnyFaceThisBlock = true;

                Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), () -> {
                    attackBlockLowLevel(pos, face);
                });
            }

            if (didAnyFaceThisBlock && oneInteractionPerTick.get()) break outer;
        }
    }

    private List<Direction> getSelectedFaces() {
        List<Direction> list = new ArrayList<>(6);
        if (faceUp.get())    list.add(Direction.UP);
        if (faceNorth.get()) list.add(Direction.NORTH);
        if (faceEast.get())  list.add(Direction.EAST);
        if (faceSouth.get()) list.add(Direction.SOUTH);
        if (faceWest.get())  list.add(Direction.WEST);
        if (faceDown.get())  list.add(Direction.DOWN);
        return list;
    }

    private Vec3 faceCenter(BlockPos pos, Direction dir) {
        Vec3 c = Vec3.atCenterOf(pos);
        switch (dir) {
            case UP:    return c.add(0, 0.5, 0);
            case DOWN:  return c.add(0, -0.5, 0);
            case EAST:  return c.add(0.5, 0, 0);
            case WEST:  return c.add(-0.5, 0, 0);
            case SOUTH: return c.add(0, 0, 0.5);
            case NORTH: return c.add(0, 0, -0.5);
            default:    return c;
        }
    }

    private void attackBlockLowLevel(BlockPos pos, Direction face) {
        if (mc.level == null || mc.getConnection() == null) return;

        mc.getConnection().send(
            new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face)
        );

        if (swingHand.get()) {
            mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }

        if (sendStopSameTick.get()) {
            mc.getConnection().send(
                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face)
            );
        }
    }


    private boolean passesMaturityFilter(BlockState state) {
        if (!onlyMatureCrops.get()) return true;

        IntegerProperty ageProp = getAgeProperty(state);
        if (ageProp == null) {
            return true;
        }

        int age = state.getValue(ageProp);
        int maxAge = Collections.max(ageProp.getPossibleValues());
        return age >= maxAge;
    }

    private IntegerProperty getAgeProperty(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if (prop instanceof IntegerProperty intProp && "age".equals(prop.getName())) {
                return intProp;
            }
        }
        return null;
    }
}
