package com.example.autoenchants.entity;

import com.example.autoenchants.AutoEnchantsMod;
import com.example.autoenchants.LockedOnHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.ElderGuardianEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.GhastEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.RavagerEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Arm;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class SquidMissileEntity extends LivingEntity {

    private static final int GROUND_WAIT_TICKS = 20;
    private static final int GUIDANCE_START_TICKS = 10; // after launch
    private static final int MAX_LIFETIME_TICKS = 200;
    private static final double ACCELERATION = 0.04d;
    private static final double DIVE_ACCELERATION = 0.08d;
    private static final float MAX_TURN_DEGREES = 4.0f;
    private static final double SEARCH_RANGE = 48.0d;
    private static final double LAUNCH_HEIGHT_OFFSET = 32.0d;
    private static final double PROXIMITY_FUSE_RANGE = 3.0d;
    private static final int NO_TARGET_BALLISTIC_TICKS = 50;
    private static final double BLOCKER_Y_TOLERANCE = 5.0d;
    private static final double BLOCKER_XZ_TOLERANCE = 8.0d;

    public enum Phase {
        GROUND_WAIT, ASCENDING, GUIDANCE, DIVING
    }

    /** Target acquisition sub-state machine steps */
    private enum AcqStep {
        SCAN,        // Pick a candidate from priority algorithm
        CALC_APEX,   // Compute apex (relay point) for the candidate
        LOS_CHECK,   // Raycast from apex to candidate
        PROX_CHECK   // Check if candidate is near the blocker block
    }

    private Phase phase = Phase.GROUND_WAIT;
    private int ticksInPhase = 0;
    private int totalFlightTicks = 0;
    @Nullable
    private UUID ownerUuid;
    @Nullable
    private UUID targetUuid;
    private Vec3d relayPoint = Vec3d.ZERO;
    private boolean relayReached = false;
    private double launchY = 0.0d;

    // Target acquisition state machine
    private AcqStep acqStep = AcqStep.SCAN;
    @Nullable
    private UUID candidateUuid;
    private Vec3d candidateApex = Vec3d.ZERO;
    @Nullable
    private BlockPos blockerPos;
    private int guidanceSearchTicks = 0;
    private boolean ballisticMode = false;
    private Vec3d ballisticDir = Vec3d.ZERO;

    public SquidMissileEntity(EntityType<? extends SquidMissileEntity> entityType, World world) {
        super(entityType, world);
        this.noClip = false;
        // Set compact bounding box for missile (width x height)
        this.setBoundingBox(this.getDimensions(this.getPose()).getBoxAt(this.getPos()));
    }

    @Override
    public void calculateDimensions() {
        double x = this.getX();
        double y = this.getY();
        double z = this.getZ();
        super.calculateDimensions();
        this.setPosition(x, y, z);
    }

    @Override
    public net.minecraft.entity.EntityDimensions getDimensions(net.minecraft.entity.EntityPose pose) {
        // Compact missile dimensions: 0.5 wide x 0.8 tall
        return net.minecraft.entity.EntityDimensions.fixed(0.5f, 0.8f);
    }

    public static DefaultAttributeContainer.Builder createMissileAttributes() {
        return LivingEntity.createLivingAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 4.0d)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0d)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0d)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 0.0d);
    }

    public void setOwner(@Nullable PlayerEntity owner) {
        this.ownerUuid = owner != null ? owner.getUuid() : null;
    }

    @Nullable
    public Entity getOwnerEntity() {
        if (ownerUuid == null || !(getWorld() instanceof ServerWorld serverWorld)) return null;
        return serverWorld.getEntity(ownerUuid);
    }

    public Phase getPhase() {
        return phase;
    }

    @Override
    public void tick() {
        // No gravity during flight
        this.setNoGravity(phase != Phase.GROUND_WAIT);

        super.tick();

        if (getWorld().isClient()) return;

        totalFlightTicks++;
        if (totalFlightTicks > MAX_LIFETIME_TICKS) {
            explode();
            return;
        }

        ticksInPhase++;

        switch (phase) {
            case GROUND_WAIT -> tickGroundWait();
            case ASCENDING -> tickAscending();
            case GUIDANCE -> tickGuidance();
            case DIVING -> tickDiving();
        }
    }

    // ==================== GROUND WAIT ====================

    private void tickGroundWait() {
        setVelocity(Vec3d.ZERO);
        if (ticksInPhase >= GROUND_WAIT_TICKS) {
            ignite();
            transitionTo(Phase.ASCENDING);
        } else if (ticksInPhase >= GROUND_WAIT_TICKS - 5) {
            // Pre-ignition smoke
            if (getWorld() instanceof ServerWorld sw) {
                sw.spawnParticles(ParticleTypes.SMOKE, getX(), getY() + 0.2d, getZ(), 3, 0.15d, 0.05d, 0.15d, 0.01d);
            }
        }
    }

    private void ignite() {
        if (!(getWorld() instanceof ServerWorld sw)) return;
        // Ignite ground in 3x3 area
        BlockPos base = getBlockPos().down();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = base.add(dx, 0, dz);
                BlockPos above = pos.up();
                if (sw.getBlockState(above).isAir() && sw.getBlockState(pos).isSolidBlock(sw, pos)) {
                    sw.setBlockState(above, Blocks.FIRE.getDefaultState());
                }
            }
        }
        // Fire particles
        sw.spawnParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 30, 0.5d, 0.1d, 0.5d, 0.05d);
        sw.spawnParticles(ParticleTypes.LAVA, getX(), getY(), getZ(), 10, 0.4d, 0.1d, 0.4d, 0.01d);
    }

    // ==================== ASCENDING ====================

    private void tickAscending() {
        Vec3d vel = getVelocity();
        // Accelerate upward
        double newYSpeed = vel.y + ACCELERATION;
        // Random horizontal drift
        double driftX = (random.nextDouble() - 0.5d) * 0.06d;
        double driftZ = (random.nextDouble() - 0.5d) * 0.06d;
        setVelocity(vel.x + driftX, newYSpeed, vel.z + driftZ);
        velocityModified = true;

        spawnAscentParticles();

        if (ticksInPhase >= GUIDANCE_START_TICKS) {
            transitionTo(Phase.GUIDANCE);
        }

        // Only check collision after initial acceleration to avoid detecting ground
        if (ticksInPhase > 3) {
            checkCollisionAndExplode();
        }
    }

    private void spawnAscentParticles() {
        if (!(getWorld() instanceof ServerWorld sw)) return;
        // Flame exhaust
        sw.spawnParticles(ParticleTypes.FLAME, getX(), getY() - 0.3d, getZ(), 5, 0.1d, 0.05d, 0.1d, 0.02d);
        sw.spawnParticles(ParticleTypes.SQUID_INK, getX(), getY() - 0.2d, getZ(), 3, 0.15d, 0.1d, 0.15d, 0.01d);
    }

    // ==================== GUIDANCE ====================

    private void tickGuidance() {
        guidanceSearchTicks++;

        Vec3d vel = getVelocity();
        double speed = vel.length();
        if (speed < 0.01d) speed = 0.5d;
        speed += ACCELERATION;

        LivingEntity target = getTargetEntity();
        if (target != null) {
            // We have a locked target - fly toward relay/target
            if (!relayReached) {
                if (relayPoint.equals(Vec3d.ZERO)) {
                    // Relay = horizontal midpoint between missile (guidance start) and target, at apex height.
                    // Computed once — the midpoint defines the trajectory's apex in XZ.
                    double midX = (getX() + target.getX()) / 2.0d;
                    double midZ = (getZ() + target.getZ()) / 2.0d;
                    double apexY = Math.max(launchY + LAUNCH_HEIGHT_OFFSET, target.getY());
                    relayPoint = new Vec3d(midX, apexY, midZ);
                }

                Vec3d toRelay = relayPoint.subtract(getPos());
                double distToRelay = toRelay.length();

                if (distToRelay < 5.0d || (getY() >= relayPoint.y && vel.y <= 0.0d)) {
                    relayReached = true;
                    LockedOnHandler.applyLockedAndGlow(target, 60);
                    transitionTo(Phase.DIVING);
                    return;
                }

                double targetBelowApex = relayPoint.y - target.getY();
                double blend = 1.0d - MathHelper.clamp(targetBelowApex / 20.0d, 0.0d, 1.0d);

                Vec3d relayDir = toRelay.normalize();
                Vec3d targetPos = target.getPos().add(0.0d, target.getHeight() * 0.5d, 0.0d);
                Vec3d targetDir = targetPos.subtract(getPos()).normalize();

                Vec3d desiredDir;
                if (blend < 0.01d) {
                    desiredDir = relayDir;
                } else if (blend > 0.99d) {
                    desiredDir = targetDir;
                } else {
                    desiredDir = relayDir.multiply(1.0d - blend).add(targetDir.multiply(blend));
                    if (desiredDir.lengthSquared() < 1.0E-8d) {
                        desiredDir = relayDir;
                    } else {
                        desiredDir = desiredDir.normalize();
                    }
                }

                Vec3d newDir = rotateTowards(vel.normalize(), desiredDir, Math.toRadians(MAX_TURN_DEGREES));
                setVelocity(newDir.multiply(speed));
            }
        } else {
            // No locked target yet - run acquisition state machine
            tickAcquisition();

            if (ballisticMode) {
                // Ballistic arc: follow ballisticDir with gradual downward curve
                Vec3d dir = vel.normalize();
                if (dir.lengthSquared() < 0.01d) dir = ballisticDir;
                Vec3d newDir = new Vec3d(dir.x, dir.y - 0.015d, dir.z);
                if (newDir.lengthSquared() < 1.0E-8d) newDir = new Vec3d(0, -1, 0);
                setVelocity(newDir.normalize().multiply(speed));
            } else {
                // Still ascending while searching
                Vec3d dir = vel.normalize();
                if (dir.lengthSquared() < 0.01d) dir = new Vec3d(0, 1, 0);
                double driftX = (random.nextDouble() - 0.5d) * 0.03d;
                double driftZ = (random.nextDouble() - 0.5d) * 0.03d;
                setVelocity(dir.x + driftX, dir.y, dir.z + driftZ);
                setVelocity(getVelocity().normalize().multiply(speed));
            }
        }

        velocityModified = true;
        spawnGuidanceParticles();
        checkCollisionAndExplode();
    }

    // ==================== TARGET ACQUISITION STATE MACHINE ====================

    private void tickAcquisition() {
        if (!(getWorld() instanceof ServerWorld sw)) return;

        // Check ballistic timeout
        if (!ballisticMode && guidanceSearchTicks >= NO_TARGET_BALLISTIC_TICKS) {
            enterBallisticMode();
        }

        switch (acqStep) {
            case SCAN -> acqScan(sw);
            case CALC_APEX -> acqCalcApex(sw);
            case LOS_CHECK -> acqLosCheck(sw);
            case PROX_CHECK -> acqProxCheck(sw);
        }
    }

    /** Step 1: Pick a candidate using priority algorithm */
    private void acqScan(ServerWorld sw) {
        Entity owner = getOwnerEntity();

        // Priority 1: Locked-on targets (skip LOS check - player already confirmed visibility)
        LivingEntity locked = LockedOnHandler.findNearestLockedTarget(sw, getPos(), SEARCH_RANGE);
        if (locked != null && !isExcluded(locked)) {
            // Directly lock on - no need for LOS check since player marked it
            lockOnCandidate(locked);
            return;
        }

        // Priority 2 & 3: Hostile mobs scored by size/priority
        List<LivingEntity> candidates = sw.getEntitiesByClass(
                LivingEntity.class,
                getBoundingBox().expand(SEARCH_RANGE),
                e -> e.isAlive() && !e.isSpectator() && e != owner && isValidTarget(e) && !isExcluded(e)
        );

        if (candidates.isEmpty()) {
            candidateUuid = null;
            return;
        }

        LivingEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        Vec3d myPos = getPos();

        for (LivingEntity candidate : candidates) {
            double dist = candidate.getPos().distanceTo(myPos);
            if (dist > SEARCH_RANGE) continue;

            double score = 0.0d;
            double volume = candidate.getWidth() * candidate.getWidth() * candidate.getHeight();
            if (isHighPriorityTarget(candidate)) {
                score += 10000.0d + volume * 100.0d;
            } else {
                score += volume * 50.0d;
            }
            score -= dist * 2.0d;

            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best != null) {
            candidateUuid = best.getUuid();
            acqStep = AcqStep.CALC_APEX;
        }
    }

    /** Step 2: Calculate apex point for the candidate */
    private void acqCalcApex(ServerWorld sw) {
        LivingEntity candidate = getCandidateEntity(sw);
        if (candidate == null) {
            resetAcquisition();
            return;
        }

        double apexY = Math.max(launchY + LAUNCH_HEIGHT_OFFSET, candidate.getY());
        candidateApex = new Vec3d(candidate.getX(), apexY, candidate.getZ());
        acqStep = AcqStep.LOS_CHECK;
    }

    /** Step 3: Raycast from apex to candidate using VISUAL shape */
    private void acqLosCheck(ServerWorld sw) {
        LivingEntity candidate = getCandidateEntity(sw);
        if (candidate == null) {
            resetAcquisition();
            return;
        }

        Vec3d targetCenter = candidate.getPos().add(0.0d, candidate.getHeight() * 0.5d, 0.0d);

        BlockHitResult hitResult = sw.raycast(new RaycastContext(
                candidateApex,
                targetCenter,
                RaycastContext.ShapeType.VISUAL,
                RaycastContext.FluidHandling.NONE,
                this
        ));

        if (hitResult.getType() == HitResult.Type.MISS) {
            // No blocks hit - line of sight is clear, lock on
            lockOnCandidate(candidate);
            return;
        }

        // Check if the ray reached close to the target (hit entity or near target)
        double hitDistToTarget = hitResult.getPos().distanceTo(targetCenter);
        if (hitDistToTarget < 2.0d) {
            // Ray hit very close to target - consider line of sight clear
            lockOnCandidate(candidate);
            return;
        }

        // Hit a solid block far from target - store blocker position for proximity check
        blockerPos = hitResult.getBlockPos();
        acqStep = AcqStep.PROX_CHECK;
    }

    /** Step 4: Check if candidate is near the blocker block */
    private void acqProxCheck(ServerWorld sw) {
        LivingEntity candidate = getCandidateEntity(sw);
        if (candidate == null || blockerPos == null) {
            resetAcquisition();
            return;
        }

        double dy = Math.abs(candidate.getY() - blockerPos.getY());
        double dx = Math.abs(candidate.getX() - (blockerPos.getX() + 0.5d));
        double dz = Math.abs(candidate.getZ() - (blockerPos.getZ() + 0.5d));

        if (dy <= BLOCKER_Y_TOLERANCE && dx <= BLOCKER_XZ_TOLERANCE && dz <= BLOCKER_XZ_TOLERANCE) {
            // Target is near the blocker (under a roof/tree) - lock on
            lockOnCandidate(candidate);
        } else {
            // Target is truly underground or unreachable
            resetAcquisition();
        }
    }

    private void lockOnCandidate(LivingEntity candidate) {
        targetUuid = candidate.getUuid();
        candidateUuid = null;
        blockerPos = null;
        acqStep = AcqStep.SCAN;
        relayPoint = Vec3d.ZERO;
        relayReached = false;
    }

    private void resetAcquisition() {
        candidateUuid = null;
        blockerPos = null;
        acqStep = AcqStep.SCAN;
    }

    @Nullable
    private LivingEntity getCandidateEntity(ServerWorld sw) {
        if (candidateUuid == null) return null;
        Entity entity = sw.getEntity(candidateUuid);
        if (entity instanceof LivingEntity living && living.isAlive()) return living;
        return null;
    }

    private void enterBallisticMode() {
        ballisticMode = true;
        double angle = random.nextDouble() * Math.PI * 2.0d;
        ballisticDir = new Vec3d(Math.cos(angle) * 0.7d, 0.3d, Math.sin(angle) * 0.7d).normalize();
    }

    private void spawnGuidanceParticles() {
        if (!(getWorld() instanceof ServerWorld sw)) return;
        sw.spawnParticles(ParticleTypes.SQUID_INK, getX(), getY(), getZ(), 2, 0.08d, 0.08d, 0.08d, 0.01d);
        if (random.nextInt(3) == 0) {
            sw.spawnParticles(ParticleTypes.FIREWORK, getX(), getY(), getZ(), 1, 0.05d, 0.05d, 0.05d, 0.01d);
        }
    }

    // ==================== DIVING ====================

    private void tickDiving() {
        LivingEntity target = getTargetEntity();
        Vec3d vel = getVelocity();
        double speed = vel.length();
        if (speed < 0.01d) speed = 0.5d;

        // Double acceleration during dive
        speed += DIVE_ACCELERATION;

        if (target != null) {
            // Apply glowing to target
            if (ticksInPhase % 10 == 0) {
                LockedOnHandler.applyLockedAndGlow(target, 40);
            }

            Vec3d targetPos = target.getPos().add(0.0d, target.getHeight() * 0.5d, 0.0d);
            Vec3d toTarget = targetPos.subtract(getPos());

            // Check proximity fuse for flying entities
            if (!target.isOnGround() && toTarget.length() < PROXIMITY_FUSE_RANGE) {
                explode();
                return;
            }

            Vec3d desiredDir = toTarget.normalize();
            Vec3d newDir = rotateTowards(vel.normalize(), desiredDir, Math.toRadians(MAX_TURN_DEGREES));
            setVelocity(newDir.multiply(speed));
        } else {
            // Lost target during dive - continue current trajectory
            setVelocity(vel.normalize().multiply(speed));
        }

        velocityModified = true;
        spawnDiveParticles();
        checkCollisionAndExplode();
    }

    private void spawnDiveParticles() {
        if (!(getWorld() instanceof ServerWorld sw)) return;
        sw.spawnParticles(ParticleTypes.WHITE_SMOKE, getX(), getY(), getZ(), 2, 0.06d, 0.06d, 0.06d, 0.005d);
        sw.spawnParticles(ParticleTypes.SQUID_INK, getX(), getY(), getZ(), 1, 0.05d, 0.05d, 0.05d, 0.01d);
    }

    private boolean isValidTarget(LivingEntity entity) {
        // Hostile entities
        if (entity instanceof HostileEntity) return true;
        // Conditional hostiles that are currently targeting something
        if (entity instanceof MobEntity mob && mob.getTarget() != null) return true;
        // Bosses
        if (entity instanceof EnderDragonEntity || entity instanceof WitherEntity) return true;
        return false;
    }

    private boolean isHighPriorityTarget(LivingEntity entity) {
        return entity instanceof EnderDragonEntity
                || entity instanceof WitherEntity
                || entity instanceof WardenEntity
                || entity instanceof GhastEntity
                || entity instanceof RavagerEntity
                || entity instanceof ElderGuardianEntity;
    }

    private boolean isExcluded(LivingEntity entity) {
        if (entity instanceof EndermanEntity) return true;
        // Skip below-proximity check for current tracked target
        if (targetUuid != null && entity.getUuid().equals(targetUuid)) return false;
        
        // Only exclude nearby targets below during ASCENDING phase
        // Once in GUIDANCE phase, can lock any valid target
        if (phase == Phase.ASCENDING) {
            Vec3d vel = getVelocity();
            if (vel.y > 0.0d && entity.getY() < getY()) {
                // Exclude targets too close below (manhattan < 12) during ascent
                double manhattan = Math.abs(entity.getX() - getX())
                        + Math.abs(entity.getY() - getY())
                        + Math.abs(entity.getZ() - getZ());
                if (manhattan < 12.0d) return true;
            }
        }
        return false;
    }

    @Nullable
    private LivingEntity getTargetEntity() {
        if (targetUuid == null || !(getWorld() instanceof ServerWorld sw)) return null;
        Entity entity = sw.getEntity(targetUuid);
        if (entity instanceof LivingEntity living && living.isAlive()) return living;
        targetUuid = null;
        return null;
    }

    // ==================== COLLISION & EXPLOSION ====================

    private void checkCollisionAndExplode() {
        Vec3d vel = getVelocity();
        if (vel.lengthSquared() < 0.001d) return;

        // Check block collision
        Box nextBox = getBoundingBox().stretch(vel).expand(0.05d);
        if (getWorld().getBlockCollisions(this, nextBox).iterator().hasNext()) {
            explode();
            return;
        }

        // Check entity collision
        List<Entity> entities = getWorld().getOtherEntities(this, nextBox,
                e -> e.isAlive() && !(e instanceof ProjectileEntity) && e != this && !isOwner(e));
        for (Entity entity : entities) {
            if (entity instanceof LivingEntity) {
                explode();
                return;
            }
        }
    }

    private boolean isOwner(Entity entity) {
        return ownerUuid != null && entity.getUuid().equals(ownerUuid);
    }

    private void explode() {
        if (getWorld().isClient() || !isAlive()) return;

        ServerWorld sw = (ServerWorld) getWorld();
        Entity owner = getOwnerEntity();

        // Random explosion power 3~5
        float power = 3.0f + random.nextFloat() * 2.0f;

        // Create explosion with fire
        getWorld().createExplosion(
                owner != null ? owner : this,
                getX(), getY(), getZ(),
                power, true,
                World.ExplosionSourceType.MOB
        );

        // Explosion particles (reference: ReactionArmorHandler)
        sw.spawnParticles(ParticleTypes.EXPLOSION, getX(), getY(), getZ(), 3, 0.3d, 0.3d, 0.3d, 0.01d);
        sw.spawnParticles(ParticleTypes.SMOKE, getX(), getY(), getZ(), 20, 0.5d, 0.5d, 0.5d, 0.05d);
        sw.spawnParticles(ParticleTypes.GLOW_SQUID_INK, getX(), getY(), getZ(), 25, 0.6d, 0.4d, 0.6d, 0.03d);
        sw.spawnParticles(ParticleTypes.LANDING_HONEY, getX(), getY(), getZ(), 12, 0.5d, 0.3d, 0.5d, 0.02d);
        sw.spawnParticles(ParticleTypes.LAVA, getX(), getY(), getZ(), 15, 0.5d, 0.3d, 0.5d, 0.02d);
        sw.spawnParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 20, 0.6d, 0.4d, 0.6d, 0.04d);

        // Chain detonation: ignite creepers and TNT
        triggerChainDetonation(sw, owner);

        // Damage nearby entities (sky bombard style)
        LivingEntity target = getTargetEntity();
        if (target != null && target.isAlive()) {
            float directDamage = power * 3.0f;
            target.damage(getDamageSources().explosion(this, owner instanceof LivingEntity lo ? lo : null), directDamage);
        }

        discard();
    }

    private void triggerChainDetonation(ServerWorld sw, @Nullable Entity owner) {
        double radius = 6.0d;
        Box area = getBoundingBox().expand(radius);

        // Ignite TNT entities
        List<TntEntity> tntEntities = sw.getEntitiesByClass(
                TntEntity.class, area,
                Entity::isAlive
        );
        for (TntEntity tnt : tntEntities) {
            tnt.setFuse(Math.min(tnt.getFuse(), 5));
            spawnFlameTrail(sw, getPos(), tnt.getPos());
        }

        // Ignite creepers
        List<CreeperEntity> creepers = sw.getEntitiesByClass(
                CreeperEntity.class, area,
                e -> e.isAlive()
        );
        for (CreeperEntity creeper : creepers) {
            creeper.ignite();
            spawnFlameTrail(sw, getPos(), creeper.getPos());
        }

        // Ignite TNT blocks
        BlockPos center = getBlockPos();
        int r = (int) radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (sw.getBlockState(pos).isOf(Blocks.TNT)) {
                        sw.removeBlock(pos, false);
                        TntEntity tnt = new TntEntity(sw,
                                pos.getX() + 0.5d, pos.getY() + 0.5d, pos.getZ() + 0.5d,
                                owner instanceof LivingEntity lo ? lo : null);
                        tnt.setFuse(10);
                        sw.spawnEntity(tnt);
                        spawnFlameTrail(sw, getPos(), new Vec3d(pos.getX() + 0.5d, pos.getY() + 0.5d, pos.getZ() + 0.5d));
                    }
                }
            }
        }
    }

    private void spawnFlameTrail(ServerWorld sw, Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        int points = Math.max(6, (int) (delta.length() * 3));
        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            double x = from.x + delta.x * t;
            double y = from.y + delta.y * t;
            double z = from.z + delta.z * t;
            sw.spawnParticles(ParticleTypes.FLAME, x, y + 0.1d, z, 1, 0.04d, 0.04d, 0.04d, 0.001d);
        }
    }

    // ==================== PHASE TRANSITION ====================

    private void transitionTo(Phase newPhase) {
        this.phase = newPhase;
        this.ticksInPhase = 0;
        if (newPhase == Phase.ASCENDING) {
            launchY = getY();
        }
        if (newPhase == Phase.GUIDANCE) {
            guidanceSearchTicks = 0;
            ballisticMode = false;
            resetAcquisition();
        }
        if (newPhase == Phase.DIVING) {
            relayReached = true;
        }
    }

    // ==================== ROTATION HELPER ====================

    private static Vec3d rotateTowards(Vec3d from, Vec3d to, double maxAngleRad) {
        if (from.lengthSquared() < 1.0E-8d) return to;
        if (to.lengthSquared() < 1.0E-8d) return from;
        Vec3d nFrom = from.normalize();
        Vec3d nTo = to.normalize();
        double dot = MathHelper.clamp(nFrom.dotProduct(nTo), -1.0d, 1.0d);
        double angle = Math.acos(dot);
        if (angle <= maxAngleRad || angle < 1.0E-6d) {
            return nTo;
        }
        double sinAngle = Math.sin(angle);
        if (sinAngle < 1.0E-6d) {
            // Nearly opposite vectors — pick a perpendicular axis via cross product fallback
            Vec3d ref = Math.abs(nFrom.y) < 0.9d ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
            Vec3d axis = nFrom.crossProduct(ref).normalize();
            // Rodrigues' rotation: rotate nFrom by maxAngleRad around axis
            double cos = Math.cos(maxAngleRad);
            double sin = Math.sin(maxAngleRad);
            return nFrom.multiply(cos)
                    .add(axis.crossProduct(nFrom).multiply(sin))
                    .add(axis.multiply(axis.dotProduct(nFrom) * (1.0d - cos)));
        }
        // Spherical linear interpolation (slerp) — constant angular velocity
        double t = maxAngleRad / angle;
        double coeffFrom = Math.sin((1.0d - t) * angle) / sinAngle;
        double coeffTo = Math.sin(t * angle) / sinAngle;
        return nFrom.multiply(coeffFrom).add(nTo.multiply(coeffTo));
    }

    // ==================== DAMAGE & IMMUNITY ====================

    @Override
    public boolean damage(DamageSource source, float amount) {
        // Immune to fire
        if (source.isIn(DamageTypeTags.IS_FIRE)) return false;
        // Immune to potion/magic effects (indirect magic)
        if (source.isOf(DamageTypes.INDIRECT_MAGIC) || source.isOf(DamageTypes.MAGIC)) return false;
        return super.damage(source, amount);
    }

    @Override
    public boolean canHaveStatusEffect(StatusEffectInstance effect) {
        return false; // Immune to all status effects
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    // ==================== NBT ====================

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("Phase", phase.ordinal());
        nbt.putInt("TicksInPhase", ticksInPhase);
        nbt.putInt("TotalFlightTicks", totalFlightTicks);
        nbt.putBoolean("RelayReached", relayReached);
        nbt.putDouble("LaunchY", launchY);
        nbt.putInt("GuidanceSearchTicks", guidanceSearchTicks);
        nbt.putBoolean("BallisticMode", ballisticMode);
        nbt.putInt("AcqStep", acqStep.ordinal());
        if (ownerUuid != null) nbt.putUuid("Owner", ownerUuid);
        if (targetUuid != null) nbt.putUuid("Target", targetUuid);
        if (candidateUuid != null) nbt.putUuid("Candidate", candidateUuid);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        int phaseOrd = nbt.getInt("Phase");
        if (phaseOrd >= 0 && phaseOrd < Phase.values().length) {
            phase = Phase.values()[phaseOrd];
        }
        ticksInPhase = nbt.getInt("TicksInPhase");
        totalFlightTicks = nbt.getInt("TotalFlightTicks");
        relayReached = nbt.getBoolean("RelayReached");
        launchY = nbt.getDouble("LaunchY");
        guidanceSearchTicks = nbt.getInt("GuidanceSearchTicks");
        ballisticMode = nbt.getBoolean("BallisticMode");
        int acqOrd = nbt.getInt("AcqStep");
        if (acqOrd >= 0 && acqOrd < AcqStep.values().length) {
            acqStep = AcqStep.values()[acqOrd];
        }
        if (nbt.containsUuid("Owner")) ownerUuid = nbt.getUuid("Owner");
        if (nbt.containsUuid("Target")) targetUuid = nbt.getUuid("Target");
        if (nbt.containsUuid("Candidate")) candidateUuid = nbt.getUuid("Candidate");
    }

    // ==================== MISC OVERRIDES ====================

    @Override
    public Iterable<ItemStack> getArmorItems() {
        return java.util.Collections.emptyList();
    }

    @Override
    public net.minecraft.item.ItemStack getEquippedStack(net.minecraft.entity.EquipmentSlot slot) {
        return net.minecraft.item.ItemStack.EMPTY;
    }

    @Override
    public void equipStack(net.minecraft.entity.EquipmentSlot slot, net.minecraft.item.ItemStack stack) {
    }

    @Override
    public Arm getMainArm() {
        return Arm.RIGHT;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 16384.0d;
    }
}