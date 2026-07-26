package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public class RightClickBlock extends Module {
    private static final Direction[] ORDERED_FACES = {
        Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    public enum UseHand { Main, Off, Both }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> oneInteractionPerTick = sgGeneral.add(new BoolSetting.Builder()
        .name("one-interaction-per-tick")
        .description("One interaction per tick.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to interact with.")
        .defaultValue(Blocks.FARMLAND)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Interact range.")
        .min(0)
        .defaultValue(3.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Boolean> oneTime = sgGeneral.add(new BoolSetting.Builder()
        .name("one-time")
        .description("Interact with each face of a block only one time.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> clearInterval = sgGeneral.add(new DoubleSetting.Builder()
        .name("clear-interval")
        .description("How often to clear the list of interacted faces (seconds). Set to 0 to never clear.")
        .defaultValue(10.0)
        .min(0)
        .sliderMax(60)
        .visible(oneTime::get)
        .build()
    );

    private final Setting<Boolean> checkSpaceAir = sgGeneral.add(new BoolSetting.Builder()
        .name("check-space-air")
        .description("Only interact when the block adjacent to the selected face is air (e.g., farmland top must be air).")
        .defaultValue(true)
        .build()
    );

    private final Setting<UseHand> useHand = sgGeneral.add(new EnumSetting.Builder<UseHand>()
        .name("use-hand")
        .description("Which hand to use when interacting.")
        .defaultValue(UseHand.Main)
        .build()
    );

    private final Setting<Boolean> faceUp    = sgGeneral.add(new BoolSetting.Builder().name("face-up").defaultValue(true).build());
    private final Setting<Boolean> faceNorth = sgGeneral.add(new BoolSetting.Builder().name("face-north").defaultValue(false).build());
    private final Setting<Boolean> faceEast  = sgGeneral.add(new BoolSetting.Builder().name("face-east").defaultValue(false).build());
    private final Setting<Boolean> faceSouth = sgGeneral.add(new BoolSetting.Builder().name("face-south").defaultValue(false).build());
    private final Setting<Boolean> faceWest  = sgGeneral.add(new BoolSetting.Builder().name("face-west").defaultValue(false).build());
    private final Setting<Boolean> faceDown  = sgGeneral.add(new BoolSetting.Builder().name("face-down").defaultValue(false).build());

    private final Set<FaceKey> usedFaces = new HashSet<>();
    private long lastClearTime;

    public RightClickBlock() {
        super(Addon.MilkyModCategory, "RightClickBlock", "Automatically right-clicks blocks in range (e.g., plant seeds on farmland).");
    }

    @Override
    public void onActivate() {
        usedFaces.clear();
        lastClearTime = System.currentTimeMillis();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.level == null || mc.player == null) return;

        if (oneTime.get()) {
            double intervalMs = clearInterval.get() * 1000.0;
            if (intervalMs > 0 && System.currentTimeMillis() - lastClearTime >= intervalMs) {
                usedFaces.clear();
                lastClearTime = System.currentTimeMillis();
            }
        }

        if (mc.player.getMainHandItem().isEmpty() && mc.player.getOffhandItem().isEmpty()) return;

        BlockPos playerPos = mc.player.blockPosition();
        int r = Math.max(0, (int) Math.floor(range.get()));
        Set<Block> targetBlocks = new HashSet<>(blocks.get());

        List<Direction> selectedFaces = getSelectedFaces();
        if (selectedFaces.isEmpty()) return;

        outer:
        for (BlockPos pos : BlockPos.betweenClosed(playerPos.offset(-r, -r, -r), playerPos.offset(r, r, r))) {
            BlockState state = mc.level.getBlockState(pos);
            if (!targetBlocks.contains(state.getBlock())) continue;

            boolean didAnyFaceThisBlock = false;

            for (Direction face : ORDERED_FACES) {
                if (!selectedFaces.contains(face)) continue;

                FaceKey key = new FaceKey(pos, face);
                if (oneTime.get() && usedFaces.contains(key)) continue;

                if (checkSpaceAir.get()) {
                    BlockPos neighbor = pos.relative(face);
                    if (!mc.level.getBlockState(neighbor).isAir()) continue;
                }

                Vec3 hitPos = faceCenter(pos, face);
                if (mc.player.getEyePosition().distanceTo(hitPos) > range.get()) continue;

                didAnyFaceThisBlock = true;

                Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), () -> {
                    switch (useHand.get()) {
                        case Main -> {
                            interactBlock(pos, face, hitPos, InteractionHand.MAIN_HAND);
                        }
                        case Off -> {
                            interactBlock(pos, face, hitPos, InteractionHand.OFF_HAND);
                        }
                        case Both -> {
                            if (!mc.player.getMainHandItem().isEmpty())
                                interactBlock(pos, face, hitPos, InteractionHand.MAIN_HAND);
                            if (!mc.player.getOffhandItem().isEmpty())
                                interactBlock(pos, face, hitPos, InteractionHand.OFF_HAND);
                        }
                    }
                    if (oneTime.get()) usedFaces.add(new FaceKey(pos, face));
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

    private void interactBlock(BlockPos pos, Direction face, Vec3 hitPos, InteractionHand hand) {
        if (mc.level == null || mc.gameMode == null) return;
        BlockHitResult bhr = new BlockHitResult(hitPos, face, pos, false);
        mc.gameMode.useItemOn(mc.player, hand, bhr);
        mc.getConnection().send(new ServerboundSwingPacket(hand));
    }

    private static final class FaceKey {
        private final BlockPos pos;
        private final Direction face;
        private FaceKey(BlockPos pos, Direction face) { this.pos = pos; this.face = face; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FaceKey)) return false;
            FaceKey that = (FaceKey) o;
            return pos.equals(that.pos) && face == that.face;
        }
        @Override public int hashCode() { return 31 * pos.hashCode() + face.ordinal(); }
    }
}
