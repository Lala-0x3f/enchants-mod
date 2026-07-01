package com.example.autoenchants.entity;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class StingerMissileEntity extends BeeMissileEntity {
    private static final int MIN_PIERCING_LEVEL = 1;
    private static final int MAX_PIERCING_LEVEL = 3;
    private static final int[] ARROW_COUNTS = {40, 50, 60};
    private static final double[] CONE_HALF_ANGLES_DEG = {7.5d, 5.0d, 2.5d};
    private static final double MIN_ARROW_SPEED = 8.0d;
    private static final double MAX_ARROW_SPEED = 20.0d;
    private static final float EXPLOSION_POWER = 0f;
    private static final List<DelayedArrowRelease> DELAYED_RELEASES = new ArrayList<>(); 

    private int piercingLevel = MIN_PIERCING_LEVEL;

    public StingerMissileEntity(EntityType<? extends BeeEntity> type, World world) {
        super(type, world);
    }

    public void setPiercingLevel(int piercingLevel) {
        this.piercingLevel = clampPiercingLevel(piercingLevel);
    }

    @Override
    protected void onDetonate(ServerWorld sw, @Nullable Entity owner, DetonationCause cause) {
        Vec3d origin = this.getPos().add(0.0d, this.getHeight() * 0.5d, 0.0d);
        this.getWorld().createExplosion(
                owner != null ? owner : this,
                getX(), getY(), getZ(),
                EXPLOSION_POWER, false,
                World.ExplosionSourceType.MOB
        );

        if (cause == DetonationCause.EXPIRED) {
            sw.spawnParticles(ParticleTypes.EXPLOSION, origin.x, origin.y, origin.z,
                    1, 0.12d, 0.12d, 0.12d, 0.0d);
            return;
        }

        Vec3d forward = getForwardDirection();
        if (forward.lengthSquared() < 1.0E-6d) {
            forward = new Vec3d(0.0d, 0.0d, 1.0d);
        }
        forward = forward.normalize();

        DELAYED_RELEASES.add(new DelayedArrowRelease(
                sw,
                origin,
                forward,
                owner != null ? owner.getUuid() : null,
                getArrowCount(piercingLevel),
                getConeHalfAngleDeg(piercingLevel),
                1
        ));
    }

    public static void tickDelayedReleases(MinecraftServer server) {
        if (DELAYED_RELEASES.isEmpty()) {
            return;
        }

        Iterator<DelayedArrowRelease> iterator = DELAYED_RELEASES.iterator();
        while (iterator.hasNext()) {
            DelayedArrowRelease release = iterator.next();
            if (--release.delayTicks > 0) {
                continue;
            }
            releaseArrows(release);
            iterator.remove();
        }
    }

    private static void releaseArrows(DelayedArrowRelease release) {
        ServerWorld sw = release.world;
        Entity owner = release.ownerUuid != null ? sw.getEntity(release.ownerUuid) : null;

        for (int i = 0; i < release.arrowCount; i++) {
            Vec3d direction = randomDirectionInCone(sw, release.forward, release.coneHalfAngleDeg);
            double speed = MIN_ARROW_SPEED + sw.random.nextDouble() * (MAX_ARROW_SPEED - MIN_ARROW_SPEED);

            ArmorPiercingArrowEntity arrow = new ArmorPiercingArrowEntity(AutoEnchantsMod.ARMOR_PIERCING_ARROW, sw);
            arrow.refreshPositionAndAngles(release.origin.x, release.origin.y, release.origin.z, 0.0f, 0.0f);
            arrow.setOwner(owner);
            arrow.setVelocity(direction.multiply(speed));
            arrow.velocityModified = true;

            float yaw = (float) (MathHelper.atan2(direction.z, direction.x) * (180.0d / Math.PI)) - 90.0f;
            float pitch = (float) (MathHelper.atan2(direction.y, direction.horizontalLength()) * (180.0d / Math.PI));
            arrow.setYaw(yaw);
            arrow.setPitch(pitch);
            arrow.prevYaw = yaw;
            arrow.prevPitch = pitch;
            sw.spawnEntity(arrow);
        }

        sw.spawnParticles(ParticleTypes.CRIT, release.origin.x, release.origin.y, release.origin.z,
                45, 0.35d, 0.25d, 0.35d, 0.2d);
        sw.spawnParticles(ParticleTypes.ENCHANTED_HIT, release.origin.x, release.origin.y, release.origin.z,
                24, 0.28d, 0.2d, 0.28d, 0.08d);
        sw.playSound(null, release.origin.x, release.origin.y, release.origin.z,
                SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.HOSTILE, 1.4f, 1.7f);
    }

    private static Vec3d randomDirectionInCone(ServerWorld world, Vec3d forward, double halfAngleDeg) {
        Vec3d up = Math.abs(forward.y) < 0.95d ? new Vec3d(0.0d, 1.0d, 0.0d) : new Vec3d(1.0d, 0.0d, 0.0d);
        Vec3d right = forward.crossProduct(up).normalize();
        Vec3d localUp = right.crossProduct(forward).normalize();

        double cosMin = Math.cos(Math.toRadians(halfAngleDeg));
        double cosTheta = cosMin + world.random.nextDouble() * (1.0d - cosMin);
        double sinTheta = Math.sqrt(1.0d - cosTheta * cosTheta);
        double phi = world.random.nextDouble() * Math.PI * 2.0d;

        return forward.multiply(cosTheta)
                .add(right.multiply(Math.cos(phi) * sinTheta))
                .add(localUp.multiply(Math.sin(phi) * sinTheta))
                .normalize();
    }

    private static int getArrowCount(int piercingLevel) {
        return ARROW_COUNTS[clampPiercingLevel(piercingLevel) - 1];
    }

    private static double getConeHalfAngleDeg(int piercingLevel) {
        return CONE_HALF_ANGLES_DEG[clampPiercingLevel(piercingLevel) - 1];
    }

    private static int clampPiercingLevel(int piercingLevel) {
        return MathHelper.clamp(piercingLevel, MIN_PIERCING_LEVEL, MAX_PIERCING_LEVEL);
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("Piercing", piercingLevel);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        piercingLevel = nbt.contains("Piercing") ? clampPiercingLevel(nbt.getInt("Piercing")) : MIN_PIERCING_LEVEL;
    }

    private static class DelayedArrowRelease {
        private final ServerWorld world;
        private final Vec3d origin;
        private final Vec3d forward;
        @Nullable
        private final UUID ownerUuid;
        private final int arrowCount;
        private final double coneHalfAngleDeg;
        private int delayTicks;

        private DelayedArrowRelease(ServerWorld world, Vec3d origin, Vec3d forward,
                                    @Nullable UUID ownerUuid, int arrowCount, double coneHalfAngleDeg, int delayTicks) {
            this.world = world;
            this.origin = origin;
            this.forward = forward;
            this.ownerUuid = ownerUuid;
            this.arrowCount = arrowCount;
            this.coneHalfAngleDeg = coneHalfAngleDeg;
            this.delayTicks = delayTicks;
        }
    }
}
