package com.milky.hunt.modules;

import com.milky.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * RightClickEntity
 *
 * ZenithProxy-style interaction:
 * - Find a rotation that raycasts to the target entity (line-of-sight verified).
 * - Rotate and interact inside the same Rotations.rotate runnable (avoids spoof/real mismatch).
 * - Uses interactAtLocation + interactEntity + swing.
 */
public class RightClickEntity extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> oneInteractionPerTick = sgGeneral.add(new BoolSetting.Builder()
        .name("one-interaction-per-tick")
        .description("Only schedule one interaction per tick.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to interact with.")
        .defaultValue(EntityType.VILLAGER)
        // NOTE: Do NOT call onlyAttackable() here to avoid unintentionally filtering some interactables.
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

    private final Setting<Boolean> ignoreBabies = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-babies")
        .description("Ignore baby entities.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> oneTime = sgGeneral.add(new BoolSetting.Builder()
        .name("one-time")
        .description("Interact with each entity only one time (tracked by UUID).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> clearInterval = sgGeneral.add(new DoubleSetting.Builder()
        .name("clear-interval")
        .description("How often to clear the interacted list (seconds). 0 = never clear.")
        .defaultValue(10.0)
        .min(0)
        .sliderMax(60)
        .visible(oneTime::get)
        .build()
    );

    private final Set<UUID> used = new HashSet<>();
    private long lastClearTimeMs = 0;

    private static final class Rotation2 {
        final float yaw;
        final float pitch;
        Rotation2(float yaw, float pitch) { this.yaw = yaw; this.pitch = pitch; }
    }

    public RightClickEntity() {
        super(Addon.MilkyModCategory, "RightClickEntity", "Automatically interacts with entities in range.");
    }

    @Override
    public void onActivate() {
        used.clear();
        lastClearTimeMs = System.currentTimeMillis();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.level == null || mc.player == null || mc.gameMode == null) return;

        // Periodically clear used list (if enabled)
        if (oneTime.get()) {
            double intervalMs = clearInterval.get() * 1000.0;
            if (intervalMs > 0 && System.currentTimeMillis() - lastClearTimeMs >= intervalMs) {
                used.clear();
                lastClearTimeMs = System.currentTimeMillis();
            }
        }

        // Pick nearest valid target, with a raycast-valid rotation
        LivingEntity best = null;
        Rotation2 bestRot = null;
        double bestDistSq = Double.POSITIVE_INFINITY;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!isValidTarget(living)) continue;

            Rotation2 rot = findRaycastedRotation(living, range.get());
            if (rot == null) continue;

            double d2 = mc.player.distanceToSqr(living);
            if (d2 < bestDistSq) {
                bestDistSq = d2;
                best = living;
                bestRot = rot;
            }
        }

        if (best == null || bestRot == null) return;

        final LivingEntity target = best;
        final float yaw = bestRot.yaw;
        final float pitch = bestRot.pitch;

        Rotations.rotate(yaw, pitch, () -> {
            if (mc.player == null || mc.gameMode == null || mc.level == null) return;
            if (!isValidTarget(target)) return;

            EntityHitResult hit = raycastEntitySpecific(target, range.get(), yaw, pitch);
            if (hit == null || hit.getEntity() != target) return;

            interactZenithStyle(target, hit);

            if (oneTime.get()) used.add(target.getUUID());
        });

        if (oneInteractionPerTick.get()) {
            // We already chose only ONE target to click per tick.
            // (Leaving this here for behavioral parity with older versions.)
        }
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (mc.player == null) return false;

        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreBabies.get() && entity.isBaby()) return false;

        if (mc.player.distanceTo(entity) > range.get()) return false;
        if (oneTime.get() && used.contains(entity.getUUID())) return false;

        return true;
    }

    private Rotation2 findRaycastedRotation(LivingEntity target, double maxRange) {
        if (mc.player == null) return null;

        Vec3 eye = target.getEyePosition();
        Vec3 center = target.getBoundingBox().getCenter();
        Vec3 mid = target.position().add(0, Math.max(0.2, target.getEyeHeight() * 0.6), 0);

        Vec3[] points = new Vec3[] { eye, mid, center };

        float playerYaw = mc.player.getYRot();
        Rotation2 best = null;
        float bestYawDelta = Float.MAX_VALUE;

        for (Vec3 p : points) {
            float yaw = (float) Rotations.getYaw(p);
            float pitch = (float) Rotations.getPitch(p);

            EntityHitResult hit = raycastEntitySpecific(target, maxRange, yaw, pitch);
            if (hit == null || hit.getEntity() != target) continue;

            float dy = Math.abs(wrapDegrees(yaw - playerYaw));
            if (dy < bestYawDelta) {
                bestYawDelta = dy;
                best = new Rotation2(yaw, pitch);
            }
        }

        return best;
    }

    private EntityHitResult raycastEntitySpecific(LivingEntity target, double maxRange, float yaw, float pitch) {
        if (mc.player == null) return null;

        Vec3 start = mc.player.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        Vec3 end = start.add(dir.scale(maxRange));

        AABB box = mc.player.getBoundingBox()
            .expandTowards(dir.scale(maxRange))
            .inflate(1.0, 1.0, 1.0);

        return ProjectileUtil.getEntityHitResult(
            mc.player,
            start,
            end,
            box,
            e -> e == target,
            maxRange * maxRange
        );
    }

    private void interactZenithStyle(LivingEntity target, EntityHitResult hit) {
        if (mc.player == null || mc.gameMode == null) return;

        mc.gameMode.interact(mc.player, target, hit, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private static float wrapDegrees(float degrees) {
        degrees = degrees % 360.0f;
        if (degrees >= 180.0f) degrees -= 360.0f;
        if (degrees < -180.0f) degrees += 360.0f;
        return degrees;
    }
}
