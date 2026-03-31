package com.example.autoenchants.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.FlyingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.SnowGolemEntity;
import net.minecraft.entity.projectile.DragonFireballEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class SuperSnowGolemEntity extends SnowGolemEntity {
    private static final double ENGAGE_RANGE = 96.0d;
    private static final double ENGAGE_RANGE_SQ = ENGAGE_RANGE * ENGAGE_RANGE;
    private static final float PROJECTILE_SPEED = 3.8f;
    private static final double SNOWBALL_GRAVITY = 0.03d;
    private static final double SNOWBALL_DRAG = 0.99d;
    private static final int FIRE_INTERVAL_TICKS = 1;
    private static final int MAX_SOLVE_TICKS = 140;
    private static final double INTERCEPT_PROJECTILE_RANGE = 128.0d;
    private static final double INTERCEPT_PROJECTILE_RANGE_SQ = INTERCEPT_PROJECTILE_RANGE * INTERCEPT_PROJECTILE_RANGE;
    private static final double INTERCEPT_LOOKAHEAD_TICKS = 20.0d;
    private static final double INTERCEPT_CLOSE_RADIUS = 5.5d;

    private int fireCooldown;
    private UUID trackedTargetId;
    private Vec3d filteredPos;
    private Vec3d filteredVel;
    private Vec3d filteredAcc;

    public SuperSnowGolemEntity(EntityType<? extends SnowGolemEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    protected void initGoals() {
        // Fixed C-RAM turret: does not pathfind or roam.
    }

    @Override
    public void tick() {
        super.tick();

        if (getWorld().isClient()) {
            return;
        }

        if ((age & 3) == 0 && getWorld() instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(ParticleTypes.ENCHANT, getX(), getBodyY(0.65d), getZ(), 5, 0.35d, 0.45d, 0.35d, 0.12d);
        }

        if (fireCooldown > 0) {
            fireCooldown--;
        }

        ProjectileEntity interceptProjectile = acquireInterceptProjectile();
        if (interceptProjectile != null) {
            aimAt(interceptProjectile);
            if (fireCooldown <= 0) {
                Vec3d launchVelocity = solveProjectileInterceptVelocity(interceptProjectile);
                if (launchVelocity != null) {
                    fireSnowball(launchVelocity, interceptProjectile);
                    fireCooldown = FIRE_INTERVAL_TICKS;
                }
            }
            return;
        }

        LivingEntity target = acquireBestTarget();
        if (target == null) {
            clearTrack();
            return;
        }

        updateTrack(target);
        aimAt(target);

        if (fireCooldown > 0) {
            return;
        }

        Vec3d launchVelocity = solveLaunchVelocity();
        if (launchVelocity == null) {
            return;
        }

        fireSnowball(launchVelocity, target);
        fireCooldown = FIRE_INTERVAL_TICKS;
    }

    @Override
    public void travel(Vec3d movementInput) {
        super.travel(Vec3d.ZERO);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (source.getSource() instanceof SnowballEntity) {
            return false;
        }
        return super.damage(source, amount);
    }

    private LivingEntity acquireBestTarget() {
        List<LivingEntity> candidates = getWorld().getEntitiesByClass(
                LivingEntity.class,
                getBoundingBox().expand(ENGAGE_RANGE),
                this::isValidHostileTarget
        );

        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (LivingEntity candidate : candidates) {
            if (!canSee(candidate)) {
                continue;
            }
            double score = scoreTarget(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private boolean isValidHostileTarget(LivingEntity entity) {
        if (entity == this || !entity.isAlive() || entity.isSpectator()) {
            return false;
        }
        if (entity instanceof PeekabooShellEntity) {
            return false;
        }
        if (!(entity instanceof Monster)
                && !(entity instanceof HostileEntity)
                && !(entity instanceof EnderDragonEntity)
                && !(entity instanceof WitherEntity)) {
            return false;
        }
        return squaredDistanceTo(entity) <= ENGAGE_RANGE_SQ;
    }

    private double scoreTarget(LivingEntity entity) {
        double distSq = squaredDistanceTo(entity);
        double score = -distSq * 0.015d;

        if (isFlyingPriority(entity)) {
            score += 320.0d;
        }

        if (trackedTargetId != null && trackedTargetId.equals(entity.getUuid())) {
            score += 35.0d;
        }

        Vec3d relVel = entity.getVelocity().subtract(getVelocity());
        score += Math.min(40.0d, relVel.length() * 22.0d);
        return score;
    }

    private boolean isFlyingPriority(LivingEntity entity) {
        return entity instanceof FlyingEntity
                || entity instanceof EnderDragonEntity
                || entity instanceof WitherEntity
                || !entity.isOnGround()
                || Math.abs(entity.getVelocity().y) > 0.12d;
    }

    private void updateTrack(LivingEntity target) {
        Vec3d measurement = target.getPos().add(0.0d, target.getStandingEyeHeight() * 0.45d, 0.0d);
        Vec3d measuredVelocity = target.getVelocity();

        if (filteredPos == null || filteredVel == null || trackedTargetId == null || !trackedTargetId.equals(target.getUuid())) {
            trackedTargetId = target.getUuid();
            filteredPos = measurement;
            filteredVel = measuredVelocity;
            filteredAcc = Vec3d.ZERO;
            return;
        }

        Vec3d predictedPos = filteredPos.add(filteredVel);
        Vec3d innovation = measurement.subtract(predictedPos);
        double innovationMag = innovation.length();

        // IMM-like blend: low maneuver model + high maneuver model.
        double modeBlend = MathHelper.clamp((innovationMag - 0.06d) / 0.90d, 0.0d, 1.0d);
        double alpha = MathHelper.lerp(modeBlend, 0.35d, 0.72d);
        double beta = MathHelper.lerp(modeBlend, 0.04d, 0.24d);

        filteredPos = predictedPos.add(innovation.multiply(alpha));
        Vec3d oldVel = filteredVel;
        filteredVel = filteredVel.multiply(0.94d).add(measuredVelocity.multiply(0.06d)).add(innovation.multiply(beta));
        Vec3d measuredAcc = filteredVel.subtract(oldVel);
        if (filteredAcc == null) {
            filteredAcc = measuredAcc;
        } else {
            filteredAcc = filteredAcc.multiply(0.85d).add(measuredAcc.multiply(0.15d));
        }
    }

    private void clearTrack() {
        trackedTargetId = null;
        filteredPos = null;
        filteredVel = null;
        filteredAcc = null;
        setTarget(null);
    }

    private ProjectileEntity acquireInterceptProjectile() {
        Box searchBox = getBoundingBox().expand(INTERCEPT_PROJECTILE_RANGE);
        return getWorld().getEntitiesByClass(
                        ProjectileEntity.class,
                        searchBox,
                        this::isInterceptCandidate
                ).stream()
                .min(Comparator.comparingDouble(this::scoreInterceptProjectile))
                .orElse(null);
    }

    private boolean isInterceptCandidate(ProjectileEntity projectile) {
        if (!projectile.isAlive() || projectile.isRemoved() || projectile.getOwner() == this) {
            return false;
        }
        if (!(projectile instanceof DragonFireballEntity) && !(projectile instanceof WitherSkullEntity)) {
            return false;
        }
        if (squaredDistanceTo(projectile) > INTERCEPT_PROJECTILE_RANGE_SQ) {
            return false;
        }
        Vec3d futurePos = projectile.getPos().add(projectile.getVelocity().multiply(INTERCEPT_LOOKAHEAD_TICKS));
        double futureDistSq = squaredDistanceTo(futurePos);
        if (futureDistSq > INTERCEPT_PROJECTILE_RANGE_SQ) {
            return false;
        }
        return futureDistSq < squaredDistanceTo(projectile) || futureDistSq < INTERCEPT_CLOSE_RADIUS * INTERCEPT_CLOSE_RADIUS;
    }

    private double scoreInterceptProjectile(ProjectileEntity projectile) {
        Vec3d futurePos = projectile.getPos().add(projectile.getVelocity().multiply(INTERCEPT_LOOKAHEAD_TICKS));
        double futureDistSq = squaredDistanceTo(futurePos);
        double currentDistSq = squaredDistanceTo(projectile);
        return futureDistSq * 0.7d + currentDistSq * 0.3d;
    }

    private void aimAt(Entity target) {
        if (target instanceof LivingEntity livingTarget) {
            setTarget(livingTarget);
        } else {
            setTarget(null);
        }
        Vec3d aimPoint = target.getPos().add(0.0d, target.getHeight() * 0.5d, 0.0d);
        Vec3d look = aimPoint.subtract(getEyePos());
        double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        float yaw = (float) (MathHelper.atan2(look.z, look.x) * (180.0d / Math.PI)) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(look.y, horizontal) * (180.0d / Math.PI)));
        setYaw(yaw);
        setPitch(pitch);
        bodyYaw = yaw;
        headYaw = yaw;
    }

    private Vec3d solveLaunchVelocity() {
        if (filteredPos == null || filteredVel == null) {
            return null;
        }

        Vec3d muzzlePos = getEyePos();
        double tof = MathHelper.clamp(muzzlePos.distanceTo(filteredPos) / PROJECTILE_SPEED, 1.0d, MAX_SOLVE_TICKS);
        BallisticSolution solution = null;

        // Iterative TOF: target prediction (vel+acc) + ballistic solve + drag-aware time correction.
        for (int i = 0; i < 8; i++) {
            Vec3d predictedTarget = predictTargetPosition(tof);
            solution = solveBallisticToPoint(muzzlePos, predictedTarget, PROJECTILE_SPEED, SNOWBALL_GRAVITY);
            if (solution == null) {
                break;
            }

            double correctedTof = estimateBestInterceptTime(muzzlePos, solution.velocity, predictedTarget);
            if (!Double.isFinite(correctedTof)) {
                break;
            }
            correctedTof = MathHelper.clamp(correctedTof, 1.0d, MAX_SOLVE_TICKS);
            if (Math.abs(correctedTof - tof) < 0.35d) {
                tof = correctedTof;
                break;
            }
            tof = MathHelper.lerp(0.45d, tof, correctedTof);
        }

        if (solution != null) {
            Vec3d predictedTarget = predictTargetPosition(tof);
            Vec3d impactPos = simulateProjectilePosition(muzzlePos, solution.velocity, tof);
            Vec3d miss = impactPos.subtract(predictedTarget);
            if (miss.lengthSquared() > 0.04d) {
                Vec3d compensatedTarget = predictedTarget.subtract(miss.multiply(0.9d));
                BallisticSolution compensated = solveBallisticToPoint(muzzlePos, compensatedTarget, PROJECTILE_SPEED, SNOWBALL_GRAVITY);
                if (compensated != null) {
                    solution = compensated;
                }
            }
        }

        if (solution != null) {
            return solution.velocity;
        }

        Vec3d fallbackAim = predictTargetPosition(tof).subtract(muzzlePos);
        if (fallbackAim.lengthSquared() < 1.0E-6d) {
            return null;
        }
        return fallbackAim.normalize().multiply(PROJECTILE_SPEED);
    }

    private Vec3d predictTargetPosition(double tofTicks) {
        Vec3d acc = filteredAcc == null ? Vec3d.ZERO : filteredAcc;
        // Limit acceleration term to avoid over-leading on erratic packets/teleports.
        Vec3d clampedAcc = new Vec3d(
                MathHelper.clamp(acc.x, -0.08d, 0.08d),
                MathHelper.clamp(acc.y, -0.08d, 0.08d),
                MathHelper.clamp(acc.z, -0.08d, 0.08d)
        );
        return filteredPos
                .add(filteredVel.multiply(tofTicks))
                .add(clampedAcc.multiply(0.5d * tofTicks * tofTicks));
    }

    private BallisticSolution solveBallisticToPoint(Vec3d from, Vec3d to, double speed, double gravity) {
        Vec3d delta = to.subtract(from);
        double dxz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (dxz < 1.0E-5d) {
            return null;
        }

        double s2 = speed * speed;
        double inside = (s2 * s2) - gravity * (gravity * dxz * dxz + 2.0d * delta.y * s2);
        if (inside <= 0.0d) {
            return null;
        }

        double root = Math.sqrt(inside);
        double tanTheta = (s2 - root) / (gravity * dxz);
        double cosTheta = 1.0d / Math.sqrt(1.0d + tanTheta * tanTheta);
        double sinTheta = tanTheta * cosTheta;

        Vec3d flatDir = new Vec3d(delta.x / dxz, 0.0d, delta.z / dxz);
        Vec3d velocity = flatDir.multiply(speed * cosTheta).add(0.0d, speed * sinTheta, 0.0d);
        double time = dxz / (speed * cosTheta);
        if (!Double.isFinite(time) || time <= 0.0d) {
            return null;
        }
        return new BallisticSolution(velocity, time);
    }

    private double estimateBestInterceptTime(Vec3d launchPos, Vec3d initialVelocity, Vec3d predictedTarget) {
        Vec3d pos = launchPos;
        Vec3d vel = initialVelocity;

        double bestTick = 1.0d;
        double bestDistSq = Double.MAX_VALUE;
        for (int tick = 1; tick <= MAX_SOLVE_TICKS; tick++) {
            pos = pos.add(vel);
            vel = vel.multiply(SNOWBALL_DRAG).add(0.0d, -SNOWBALL_GRAVITY, 0.0d);

            double distSq = pos.squaredDistanceTo(predictedTarget);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestTick = tick;
            } else if (tick > 6 && distSq > bestDistSq * 1.25d) {
                break;
            }
        }
        return bestTick;
    }

    private Vec3d simulateProjectilePosition(Vec3d launchPos, Vec3d initialVelocity, double timeTicks) {
        int wholeTicks = Math.max(0, MathHelper.floor(timeTicks));
        double partial = Math.max(0.0d, timeTicks - wholeTicks);

        Vec3d pos = launchPos;
        Vec3d vel = initialVelocity;
        for (int i = 0; i < wholeTicks; i++) {
            pos = pos.add(vel);
            vel = vel.multiply(SNOWBALL_DRAG).add(0.0d, -SNOWBALL_GRAVITY, 0.0d);
        }

        if (partial > 0.0d) {
            pos = pos.add(vel.multiply(partial));
        }
        return pos;
    }

    private Vec3d solveProjectileInterceptVelocity(ProjectileEntity projectile) {
        Vec3d muzzlePos = getEyePos();
        Vec3d projectilePos = projectile.getPos().add(0.0d, projectile.getHeight() * 0.5d, 0.0d);
        Vec3d projectileVelocity = projectile.getVelocity();
        double tof = MathHelper.clamp(muzzlePos.distanceTo(projectilePos) / PROJECTILE_SPEED, 1.0d, INTERCEPT_LOOKAHEAD_TICKS);

        for (int i = 0; i < 6; i++) {
            Vec3d predictedTarget = projectilePos.add(projectileVelocity.multiply(tof));
            BallisticSolution solution = solveBallisticToPoint(muzzlePos, predictedTarget, PROJECTILE_SPEED, SNOWBALL_GRAVITY);
            if (solution == null) {
                return null;
            }
            if (Math.abs(solution.time - tof) < 0.35d) {
                return solution.velocity;
            }
            tof = MathHelper.clamp(MathHelper.lerp(0.5d, tof, solution.time), 1.0d, INTERCEPT_LOOKAHEAD_TICKS);
        }

        Vec3d fallbackAim = projectilePos.add(projectileVelocity.multiply(tof)).subtract(muzzlePos);
        if (fallbackAim.lengthSquared() < 1.0E-6d) {
            return null;
        }
        return fallbackAim.normalize().multiply(PROJECTILE_SPEED);
    }

    private void fireSnowball(Vec3d velocity, Entity target) {
        World world = getWorld();
        SuperGolemSnowballEntity snowball = new SuperGolemSnowballEntity(world, this);
        Vec3d muzzle = getEyePos().add(getRotationVec(1.0f).multiply(0.35d));
        snowball.refreshPositionAndAngles(muzzle.x, muzzle.y, muzzle.z, getYaw(), getPitch());
        snowball.setVelocity(velocity);

        world.spawnEntity(snowball);

        if (world instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(ParticleTypes.SNOWFLAKE, muzzle.x, muzzle.y, muzzle.z, 3, 0.06d, 0.06d, 0.06d, 0.02d);
            serverWorld.spawnParticles(ParticleTypes.CRIT, target.getX(), target.getY() + target.getHeight() * 0.75d, target.getZ(), 1, 0.02d, 0.02d, 0.02d, 0.0d);
        }

        world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_SNOW_GOLEM_SHOOT, SoundCategory.HOSTILE, 0.8f, 0.95f + random.nextFloat() * 0.1f);
    }

    private record BallisticSolution(Vec3d velocity, double time) {
    }
}
