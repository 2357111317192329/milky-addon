package com.milky.hunt.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.ChestSwap;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.milky.hunt.Addon;
import java.util.List;

import static com.milky.hunt.Utils.*;

public class BoostedBounce extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgObstaclePasser = settings.createGroup("Obstacle Passer");

    // -----------------------------------------------------------------------------------------
    // Hard-disabled feature toggles (keep code, disable by constants)
    // -----------------------------------------------------------------------------------------
    private static final boolean MOTION_Y_BOOST = false;        // Motion Y Boost (always off)
    private static final boolean ONLY_WHILE_COLLIDING = true;   // kept for the preserved code path
    private static final boolean TUNNEL_BOUNCE = false;         // kept for the preserved code path
    private static final double TARGET_SPEED_BPS = 100.0;       // kept for the preserved code path

    private static final boolean FAKE_FLY = false;              // Chestplate/FakeFly (always off)

    /*
    // bounce option removed — always on while module is active

    private final Setting<Boolean> motionYBoost = sgGeneral.add(new BoolSetting.Builder()
        .name("Motion Y Boost")
        .description("Greatly increases speed by cancelling Y momentum.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyWhileColliding = sgGeneral.add(new BoolSetting.Builder()
        .name("Only While Colliding")
        .description("Only enables motion y boost if colliding with a wall.")
        .defaultValue(true)
        .visible(motionYBoost::get)
        .build()
    );

    private final Setting<Boolean> tunnelBounce = sgGeneral.add(new BoolSetting.Builder()
        .name("Tunnel Bounce")
        .description("Allows you to bounce in 1x2 tunnels. This should not be on if you are not in a tunnel.")
        .defaultValue(false)
        .visible(motionYBoost::get)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("Speed")
        .description("The speed in blocks per second to keep you at.")
        .defaultValue(100.0)
        .sliderRange(20, 250)
        .visible(motionYBoost::get)
        .build()
    );

    private final Setting<Boolean> lockPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("Lock Pitch")
        .description("Whether to lock your pitch when bounce is enabled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("Pitch")
        .description("The pitch to set when bounce is enabled.")
        .defaultValue(90.0)
        .sliderRange(-90, 90)
        .visible(lockPitch::get)
        .build()
    );

    private final Setting<Boolean> lockYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("Lock Yaw")
        .description("Whether to lock your yaw when bounce is enabled.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> fakeFly = sgGeneral.add(new BoolSetting.Builder()
        .name("Chestplate / Fakefly")
        .description("Lets you fly using a chestplate to use almost 0 elytra durability. Must have elytra in hotbar.")
        .defaultValue(false)
        .build()
    );
    */

    private final Setting<Boolean> useCustomYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("Use Custom Yaw")
        .description("Enable this if you want to use a yaw that isn't a factor of 45.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> yaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("Yaw")
        .description("The yaw to set when bounce is enabled. This is auto set to the closest 45 deg angle to you unless Use Custom Yaw is enabled.")
        .defaultValue(0.0)
        .sliderRange(0, 359)
        .visible(useCustomYaw::get)
        .build()
    );

    private final Setting<Boolean> highwayObstaclePasser = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("Highway Obstacle Passer")
        .description("Uses baritone to pass obstacles.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> useCustomStartPos = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("Use Custom Start Position")
        .description("Enable and set this ONLY if you are on a ringroad or don't want to be locked to a highway. Otherwise (0, 0) is the start position and will be automatically used.")
        .defaultValue(false)
        .visible(highwayObstaclePasser::get)
        .build()
    );

    private final Setting<BlockPos> startPos = sgObstaclePasser.add(new BlockPosSetting.Builder()
        .name("Start Position")
        .description("The start position to use when using a custom start position.")
        .defaultValue(new BlockPos(0, 0, 0))
        .visible(() -> highwayObstaclePasser.get() && useCustomStartPos.get())
        .build()
    );

    private final Setting<Boolean> awayFromStartPos = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("Away From Start Position")
        .description("If true, will go away from the start position instead of towards it. The start pos is (0,0) if it is not set to a custom start pos.")
        .defaultValue(true)
        .visible(highwayObstaclePasser::get)
        .build()
    );

    private final Setting<Double> distance = sgObstaclePasser.add(new DoubleSetting.Builder()
        .name("Distance")
        .description("The distance to set the baritone goal for path realignment.")
        .defaultValue(10.0)
        .visible(highwayObstaclePasser::get)
        .build()
    );

    private final Setting<Integer> targetY = sgObstaclePasser.add(new IntSetting.Builder()
        .name("Y Level")
        .description("The Y level to bounce at. This must be correct or bounce will not start properly.")
        .defaultValue(120)
        .visible(() -> highwayObstaclePasser.get() && !useCustomStartPos.get())
        .build()
    );

    private final Setting<Boolean> avoidPortalTraps = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("Avoid Portal Traps")
        .description("Will attempt to detect portal traps on chunk load and avoid them.")
        .defaultValue(false)
        .visible(highwayObstaclePasser::get)
        .build()
    );

    private final Setting<Double> portalAvoidDistance = sgObstaclePasser.add(new DoubleSetting.Builder()
        .name("Portal Avoid Distance")
        .description("The distance to a portal trap where the obstacle passer will takeover and go around it.")
        .defaultValue(20)
        .min(0)
        .sliderMax(50)
        .visible(() -> highwayObstaclePasser.get() && avoidPortalTraps.get())
        .build()
    );

    private final Setting<Integer> portalScanWidth = sgObstaclePasser.add(new IntSetting.Builder()
        .name("Portal Scan Width")
        .description("The width on the axis of the highway that will be scanned for portal traps.")
        .defaultValue(5)
        .min(3)
        .sliderMax(10)
        .visible(() -> highwayObstaclePasser.get() && avoidPortalTraps.get())
        .build()
    );

    private final Setting<Boolean> toggleElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("Toggle Elytra")
        .description("Equips an elytra on activate, and a chestplate on deactivate.")
        .defaultValue(false)
        .visible(() -> !FAKE_FLY)
        .build()
    );

    // --- Auto replace Elytra when durability is low ---
    private final Setting<Boolean> autoReplaceElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Replace Elytra")
        .description("Automatically replace Elytra when durability falls below threshold.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minElytraDurability = sgGeneral.add(new IntSetting.Builder()
        .name("Min Elytra Durability")
        .description("If current Elytra has less than this remaining durability, attempt to replace it.")
        .defaultValue(10)
        .sliderRange(1, 432)
        .visible(autoReplaceElytra::get)
        .build()
    );

    // --- Auto wear Gold armor (helmet/leggings/boots) ---
    private final Setting<Boolean> autoWearGold = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Wear Gold")
        .description("Automatically wear selected gold armor pieces above a minimum durability.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> minGoldDurability = sgGeneral.add(new IntSetting.Builder()
        .name("Min Gold Durability")
        .description("If equipped gold piece has less than this remaining durability, replace it.")
        .defaultValue(10)
        .sliderRange(1, 100)
        .visible(autoWearGold::get)
        .build()
    );

    private final Setting<List<Item>> goldPieces = sgGeneral.add(new ItemListSetting.Builder()
        .name("Gold Pieces")
        .description("Choose which gold armor types to maintain: helmet, leggings, boots.")
        .filter(it -> it == Items.GOLDEN_HELMET || it == Items.GOLDEN_LEGGINGS || it == Items.GOLDEN_BOOTS)
        .defaultValue(List.of(Items.GOLDEN_HELMET, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS))
        .visible(autoWearGold::get)
        .build()
    );

    public BoostedBounce() {
        super(Addon.MilkyModCategory, "BoostedBounce", "Elytra fly with some more features.");
    }

    private boolean startSprinting;
    private BlockPos portalTrap = null;
    private boolean paused = false;

    private boolean elytraToggled = false;

    private Vec3 lastUnstuckPos;
    private int stuckTimer = 0;

    private Vec3 lastPos;

    private final double maxDistance = 16 * 5;
    private BlockPos tempPath = null;
    private boolean waitingForChunksToLoad;
    private int reopenTicks = 0;

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            // no-op
        } else if (event.packet instanceof ClientboundContainerClosePacket) {
            event.cancel();
        }
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.player.getAbilities().mayfly) return;

        startSprinting = mc.player.isSprinting();
        tempPath = null;
        portalTrap = null;
        paused = false;
        waitingForChunksToLoad = false;
        elytraToggled = false;
        lastPos = mc.player.position();
        lastUnstuckPos = mc.player.position();
        stuckTimer = 0;

        if (mc.player.position().multiply(1, 0, 1).length() >= 100) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            }

            if (!useCustomStartPos.get()) {
                startPos.set(new BlockPos(0, 0, 0));
            }

            if (!useCustomYaw.get()) {
                if (mc.player.blockPosition().distSqr(startPos.get()) < 10_000 || !highwayObstaclePasser.get()) {
                    double playerAngleNormalized = angleOnAxis(mc.player.getYRot());
                    yaw.set(playerAngleNormalized);
                } else {
                    BlockPos directionVec = mc.player.blockPosition().subtract(startPos.get());
                    double angle = Math.toDegrees(Math.atan2(-directionVec.getX(), directionVec.getZ()));
                    double angleNormalized = angleOnAxis(angle);
                    if (!awayFromStartPos.get()) angleNormalized += 180;
                    yaw.set(angleNormalized);
                }
            }
        }
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || event.type != MoverType.SELF || !enabled() || !MOTION_Y_BOOST) return;

        if (ONLY_WHILE_COLLIDING && !mc.player.horizontalCollision) return;

        if (lastPos != null) {
            double speedBps = mc.player.position().subtract(lastPos).multiply(20, 0, 20).length();

            Timer timer = Modules.get().get(Timer.class);
            if (timer.isActive()) speedBps *= timer.getMultiplier();

            if (mc.player.onGround() && mc.player.isSprinting() && speedBps < TARGET_SPEED_BPS) {
                if (speedBps > 20 || TUNNEL_BOUNCE) {
                    ((IVec3) event.movement).meteor$setY(0.0);
                }
                mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, 0.0, mc.player.getDeltaMovement().z);
            }
        }

        lastPos = mc.player.position();
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null) return;

        if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
        }

        mc.player.setSprinting(startSprinting);

        if (toggleElytra.get() && !FAKE_FLY) {
            maybeSwapBackChestplate();
            maybeSwapBackLeggings();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.getAbilities().mayfly) return;

        if (toggleElytra.get() && !FAKE_FLY && !elytraToggled) {
            if (!(mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem().equals(Items.ELYTRA))) {
                Modules.get().get(ChestSwap.class).swap();
            } else {
                elytraToggled = true;
            }
        }

        if (enabled()) mc.player.setSprinting(true);

        if (autoReplaceElytra.get() && !FAKE_FLY) {
            maybeReplaceElytra();
        }
        if (reopenTicks > 0) {
            tryStartFallFlying();
            reopenTicks--;
        }

        if (autoWearGold.get()) {
            maintainGoldArmor();
        }

        if (tempPath != null && mc.player.blockPosition().distSqr(tempPath) < 500) {
            tempPath = null;
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
        } else if (tempPath != null) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(tempPath));
            return;
        }

        if (highwayObstaclePasser.get() && BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().getGoal() != null) {
            return;
        }

        if (mc.player.distanceToSqr(lastUnstuckPos) < 25) stuckTimer++;
        else {
            stuckTimer = 0;
            lastUnstuckPos = mc.player.position();
        }

        int ty = getTargetY();
        if (highwayObstaclePasser.get() && mc.player.position().length() > 100 && (
            mc.player.getY() < ty || mc.player.getY() > ty + 2 ||
                (mc.player.horizontalCollision && isFrontBlocked(mc.player)) ||
                (portalTrap != null && portalTrap.distSqr(mc.player.blockPosition()) < portalAvoidDistance.get() * portalAvoidDistance.get()) ||
                waitingForChunksToLoad || stuckTimer > 50)) {

            waitingForChunksToLoad = false;
            paused = true;
            BlockPos goal = mc.player.blockPosition();
            double currDistance = distance.get();

            if (portalTrap != null) {
                currDistance += mc.player.position().distanceTo(portalTrap.getCenter());
                portalTrap = null;
                info("Pathing around portal.");
            }

            do {
                if (currDistance > maxDistance) {
                    tempPath = goal;
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
                    return;
                }

                Vec3 unitYawVec = yawToDirection(pathYaw());
                Vec3 travelVec = mc.player.position().subtract(startPos.get().getCenter());
                double parallelCurrPosDot = travelVec.multiply(new Vec3(1, 0, 1)).dot(unitYawVec);
                Vec3 parallelCurrPosComponent = unitYawVec.scale(parallelCurrPosDot);
                Vec3 pos = startPos.get().getCenter().add(parallelCurrPosComponent);
                pos = positionInDirection(pos, pathYaw(), currDistance);

                goal = new BlockPos((int) Math.floor(pos.x), ty, (int) Math.floor(pos.z));
                currDistance++;

                if (mc.level.getBlockState(goal).getBlock() == Blocks.VOID_AIR) {
                    waitingForChunksToLoad = true;
                    return;
                }
            }
            while (!mc.level.getBlockState(goal.below()).isRedstoneConductor(mc.level, goal.below()) ||
                mc.level.getBlockState(goal).getBlock() == Blocks.NETHER_PORTAL ||
                !mc.level.getBlockState(goal).isAir());

            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
        } else {
            paused = false;
            if (!enabled()) return;

            if (!FAKE_FLY) {
                if (mc.player.onGround() && (!MOTION_Y_BOOST || Utils.getPlayerSpeed().multiply(1, 0, 1).length() < TARGET_SPEED_BPS)) {
                    mc.player.jumpFromGround();
                }
            }

            // lock pitch/yaw feature removed (settings removed, do not force rotations)
            // if (lockYaw.get()) mc.player.setYaw(yaw.get().floatValue());
            // if (lockPitch.get()) mc.player.setPitch(pitch.get().floatValue());
        }

        if (enabled()) {
            if (FAKE_FLY) doGrimEflyStuff();
            else sendStartFlyingPacket();
        }
    }

    public boolean enabled() {
        return this.isActive() && !paused && mc.player != null && (FAKE_FLY || mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem().equals(Items.ELYTRA));
    }

    private void doGrimEflyStuff() {
        FindItemResult itemResult = InvUtils.findInHotbar(Items.ELYTRA);
        if (!itemResult.found()) return;

        swapToItem(itemResult.slot());
        sendStartFlyingPacket();

        if (mc.player.onGround() && (!MOTION_Y_BOOST || Utils.getPlayerSpeed().multiply(1, 0, 1).length() < TARGET_SPEED_BPS)) {
            mc.player.jumpFromGround();
        }

        swapToItem(itemResult.slot());
    }

    @EventHandler
    private void onPlaySound(PlaySoundEvent event) {
        List<Identifier> armorEquipSounds = List.of(
            Identifier.parse("minecraft:item.armor.equip_generic"),
            Identifier.parse("minecraft:item.armor.equip_netherite"),
            Identifier.parse("minecraft:item.armor.equip_elytra"),
            Identifier.parse("minecraft:item.armor.equip_diamond"),
            Identifier.parse("minecraft:item.armor.equip_gold"),
            Identifier.parse("minecraft:item.armor.equip_iron"),
            Identifier.parse("minecraft:item.armor.equip_chain"),
            Identifier.parse("minecraft:item.armor.equip_leather"),
            Identifier.parse("minecraft:item.elytra.flying")
        );
        for (Identifier identifier : armorEquipSounds) {
            if (identifier.equals(event.sound.getIdentifier())) {
                event.cancel();
                break;
            }
        }
    }

    // hotbar<->chest swap for FakeFly (preserved; FAKE_FLY is hard-disabled above)
    private void swapToItem(int slot) {
        ItemStack chestItem = mc.player.getInventory().getItem(38);
        ItemStack hotbarSwapItem = mc.player.getInventory().getItem(slot);

        Int2ObjectMap<HashedStack> changedSlots = new Int2ObjectOpenHashMap<>();
        changedSlots.put(6, HashedStack.create(hotbarSwapItem, mc.getConnection().decoratedHashOpsGenenerator()));
        changedSlots.put(slot + 36, HashedStack.create(chestItem, mc.getConnection().decoratedHashOpsGenenerator()));

        sendSwapPacket(changedSlots, (byte) slot);
    }

    private void sendStartFlyingPacket() {
        if (mc.player == null) return;
        mc.player.connection.send(new ServerboundPlayerCommandPacket(
            mc.player,
            ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
        ));
    }

    private void sendSwapPacket(Int2ObjectMap<HashedStack> changedSlots, byte buttonNum) {
        int syncId = mc.player.containerMenu.containerId;
        int stateId = mc.player.containerMenu.getStateId();

        mc.player.connection.send(new ServerboundContainerClickPacket(
            syncId,
            stateId,
            (short)6,
            buttonNum,
            ContainerInput.SWAP,
            changedSlots,
            HashedStack.EMPTY
        ));
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!avoidPortalTraps.get() || !highwayObstaclePasser.get()) return;
        ChunkPos pos = event.chunk().getPos();

        int ty = getTargetY();
        BlockPos centerPos = pos.getMiddleBlockPosition(ty);

        Vec3 moveDir = yawToDirection(pathYaw());
        double distanceToHighway = distancePointToDirection(Vec3.atLowerCornerOf(centerPos), moveDir, mc.player.position());
        if (distanceToHighway > 21) return;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = ty; y < ty + 3; y++) {
                    BlockPos position = new BlockPos(pos.x() * 16 + x, y, pos.z() * 16 + z);
                    if (distancePointToDirection(Vec3.atLowerCornerOf(position), moveDir, mc.player.position()) > portalScanWidth.get()) continue;

                    if (mc.level.getBlockState(position).getBlock().equals(Blocks.NETHER_PORTAL)) {
                        BlockPos posBehind = new BlockPos(
                            (int) Math.floor(position.getX() + moveDir.x),
                            position.getY(),
                            (int) Math.floor(position.getZ() + moveDir.z)
                        );
                        if (mc.level.getBlockState(posBehind).isRedstoneConductor(mc.level, posBehind) ||
                            mc.level.getBlockState(posBehind).getBlock() == Blocks.NETHER_PORTAL) {
                            if (portalTrap == null ||
                                (portalTrap.distSqr(posBehind) > 100 &&
                                    mc.player.blockPosition().distSqr(posBehind) < mc.player.blockPosition().distSqr(portalTrap))) {
                                portalTrap = posBehind;
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isFrontBlocked(net.minecraft.world.entity.player.Player p) {
        if (p == null || p.isRemoved()) return false;
        Level w = p.level();
        AABB bb = p.getBoundingBox();
        Direction facing = p.getDirection();
        Vec3 fwd = new Vec3(facing.getStepX(), 0, facing.getStepZ());
        double probe = 0.62;
        double[] ys = new double[]{bb.minY + 0.2, (bb.minY + bb.maxY) * 0.5, bb.maxY - 0.1};
        for (double y : ys) {
            BlockPos pos = BlockPos.containing(p.getX() + fwd.x * probe, y, p.getZ() + fwd.z * probe);
            if (isHard(w.getBlockState(pos), w, pos)) return true;
        }
        return false;
    }

    private static boolean isHard(BlockState s, Level w, BlockPos pos) {
        if (s.isAir()) return false;
        if (s.is(Blocks.NETHER_PORTAL)) return true;
        return s.isCollisionShapeFullBlock(w, pos) && s.isRedstoneConductor(w, pos);
    }

    private static double roundAngle(double angleDeg) {
        double a = ((angleDeg % 360.0) + 360.0) % 360.0;
        double snapped = Math.round(a / 45.0) * 45.0;
        return ((snapped % 360.0) + 360.0) % 360.0;
    }

    private double pathYaw() {
        return roundAngle(yaw.get());
    }

    private int getTargetY() {
        return useCustomStartPos.get() ? startPos.get().getY() : targetY.get();
    }

    private boolean isHealthyElytra(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.ELYTRA)) return false;
        int remaining = stack.getMaxDamage() - stack.getDamageValue();
        return remaining >= minElytraDurability.get();
    }

    private int findBestElytraSlot() {
        int bestSlot = -1;
        int bestRemain = -1;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(Items.ELYTRA)) {
                int remain = s.getMaxDamage() - s.getDamageValue();
                if (remain >= minElytraDurability.get() && remain > bestRemain) {
                    bestRemain = remain;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private void maybeReplaceElytra() {
        ItemStack chest = mc.player.getInventory().getItem(SlotUtils.ARMOR_START + 2);
        if (isHealthyElytra(chest)) return;
        int slot = findBestElytraSlot();
        if (slot == -1) return;
        InvUtils.move().from(slot).toArmor(2);
        reopenTicks = 3; // Give a few ticks to re-open gliding
    }

    private void tryStartFallFlying() {
        sendStartFlyingPacket();
    }

    private void maintainGoldArmor() {
        if (mc.player == null) return;
        List<Item> targets = goldPieces.get();
        if (targets == null || targets.isEmpty()) return;

        for (Item it : targets) {
            int armorIdx = armorSlotIndexFor(it);
            if (armorIdx == -1) continue;

            int invArmorSlot = (armorIdx == 3 ? 39 : armorIdx == 2 ? 38 : armorIdx == 1 ? 37 : 36);
            ItemStack equipped = mc.player.getInventory().getItem(invArmorSlot);

            boolean ok = equipped != null && !equipped.isEmpty() && equipped.is(it)
                && remainingDurability(equipped) >= minGoldDurability.get();
            if (ok) continue;

            int best = findBestGoldSlot(it);
            if (best == -1) continue;

            InvUtils.move().from(best).toArmor(armorIdx);
        }
    }

    private int armorSlotIndexFor(Item it) {
        if (it == Items.GOLDEN_HELMET) return 3;
        if (it == Items.GOLDEN_LEGGINGS) return 1;
        if (it == Items.GOLDEN_BOOTS) return 0;
        return -1;
    }

    private int findBestGoldSlot(Item it) {
        int best = -1, bestRemain = -1;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(it)) {
                int r = remainingDurability(s);
                if (r >= minGoldDurability.get() && r > bestRemain) {
                    bestRemain = r;
                    best = i;
                }
            }
        }
        return best;
    }

    private int remainingDurability(ItemStack s) {
        return s.getMaxDamage() - s.getDamageValue();
    }

    private void maybeSwapBackChestplate() {
        int best = findBestChestplateSlot();
        if (best != -1) InvUtils.move().from(best).toArmor(2);
    }

    private int findBestChestplateSlot() {
        int best = -1, bestTier = -1, bestRemain = -1;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            int tier = chestplateTier(s.getItem());
            if (tier < 0) continue;
            int r = remainingDurability(s);
            if (tier > bestTier || (tier == bestTier && r > bestRemain)) {
                bestTier = tier;
                bestRemain = r;
                best = i;
            }
        }
        return best;
    }

    private int chestplateTier(Item it) {
        if (it == Items.NETHERITE_CHESTPLATE) return 5;
        if (it == Items.DIAMOND_CHESTPLATE) return 4;
        if (it == Items.IRON_CHESTPLATE) return 3;
        if (it == Items.CHAINMAIL_CHESTPLATE) return 2;
        if (it == Items.GOLDEN_CHESTPLATE) return 1;
        if (it == Items.LEATHER_CHESTPLATE) return 0;
        return -1;
    }

    private void maybeSwapBackLeggings() {
        ItemStack legs = mc.player.getInventory().getItem(SlotUtils.ARMOR_START + 1);
        boolean wearingGoldOrEmpty = legs == null || legs.isEmpty() || legs.is(Items.GOLDEN_LEGGINGS);
        if (!wearingGoldOrEmpty) return;
        int best = findBestNonGoldLeggingsSlot();
        if (best != -1) InvUtils.move().from(best).toArmor(1);
    }

    private int findBestNonGoldLeggingsSlot() {
        Item[][] tiers = new Item[][]{
            {Items.NETHERITE_LEGGINGS},
            {Items.DIAMOND_LEGGINGS},
            {Items.IRON_LEGGINGS},
            {Items.CHAINMAIL_LEGGINGS},
            {Items.LEATHER_LEGGINGS}
        };
        for (Item[] tier : tiers) {
            int best = -1, bestRemain = -1;
            for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
                ItemStack s = mc.player.getInventory().getItem(i);
                if (s.isEmpty()) continue;
                if (s.is(Items.GOLDEN_LEGGINGS)) continue;
                for (Item it : tier) {
                    if (s.is(it)) {
                        int r = remainingDurability(s);
                        if (r > bestRemain) {
                            bestRemain = r;
                            best = i;
                        }
                        break;
                    }
                }
            }
            if (best != -1) return best;
        }
        return -1;
    }
}
