package com.example.autoenchants.entity;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

import org.jetbrains.annotations.Nullable;

/**
 * A homing spark projectile fired by PeekabooShellEntity.
 * Unlike ShulkerBulletEntity, this moves freely in 3D space with smooth homing.
 */
public class PeekabooSparkEntity extends ProjectileEntity {
    private static final double SPEED = 0.65d;
    private static final double TURN_RATE = 0.12d;
    private static final int MAX_LIFETIME_TICKS = 160;
    private static final float DAMAGE = 4.0f;

    @Nullable
    private LivingEntity targetEntity;
    private int lifeTicks = 0;

    public PeekabooSparkEntity(EntityType<? extends PeekabooSparkEntity> entityType, World world) {
        super(entityType, world);
        this.noClip = false;
    }

    public PeekabooSparkEntity(World world, LivingEntity owner, @Nullable LivingEntity target) {
        super(AutoEnchantsMod.PEEKABOO_SPARK, world);
        this.setOwner(owner);
        this.targetEntity = target;
        this.setPosition(owner.getX(), owner.getBodyY(0.65d), owner.getZ());

        if (target != null) {
            Vec3d direction = target.getEyePos().subtract(this.getPos()).normalize().multiply(SPEED);
            this.setVelocity(direction);
        } else {
            this.setVelocity(Vec3d.ZERO);
        }
    }

    @Override
    protected void initDataTracker() {
    }

    @Override
    public void tick() {
        super.tick();
        this.lifeTicks++;

        if (this.lifeTicks > MAX_LIFETIME_TICKS) {
            this.discard();
            return;
        }

        if (this.targetEntity != null && !this.targetEntity.isAlive()) {
            this.targetEntity = null;
        }

        Vec3d velocity = this.getVelocity();

        // Homing logic
        if (this.targetEntity != null) {
            Vec3d toTarget = this.targetEntity.getEyePos().subtract(this.getPos());
            double distance = toTarget.length();
            if (distance > 0.01d) {
                Vec3d desired = toTarget.normalize().multiply(SPEED);
                Vec3d newVelocity = velocity.add(desired.subtract(velocity).multiply(TURN_RATE));
                double newSpeed = newVelocity.length();
                if (newSpeed > SPEED) {
                    newVelocity = newVelocity.normalize().multiply(SPEED);
                }
                velocity = newVelocity;
            }
        }

        this.setVelocity(velocity);

        // Collision detection
        HitResult hitResult = ProjectileUtil.getCollision(this, this::canHit);
        if (hitResult.getType() != HitResult.Type.MISS) {
            this.onCollision(hitResult);
        }

        // Move
        Vec3d pos = this.getPos();
        this.setPosition(pos.x + velocity.x, pos.y + velocity.y, pos.z + velocity.z);

        // Trail particles
        if (this.getWorld() instanceof ServerWorld serverWorld) {
            Vector3f color = new Vector3f(
                    0.55f + serverWorld.random.nextFloat() * 0.35f,
                    0.65f + serverWorld.random.nextFloat() * 0.25f,
                    0.95f
            );
            double px = this.getX() - velocity.x * 0.45d;
            double py = this.getY() + 0.12d - velocity.y * 0.35d;
            double pz = this.getZ() - velocity.z * 0.45d;
            serverWorld.spawnParticles(new DustParticleEffect(color, 1.0f), px, py, pz, 2, 0.06d, 0.06d, 0.06d, 0.01d);
            serverWorld.spawnParticles(ParticleTypes.GLOW, px, py, pz, 2, 0.06d, 0.06d, 0.06d, 0.0d);
            serverWorld.spawnParticles(ParticleTypes.BUBBLE_POP, px, py, pz, 1, 0.03d, 0.03d, 0.03d, 0.01d);
        }
    }

    @Override
    protected boolean canHit(Entity entity) {
        if (!super.canHit(entity)) {
            return false;
        }
        if (entity instanceof PeekabooShellEntity || entity instanceof PeekabooSparkEntity) {
            return false;
        }
        Entity owner = this.getOwner();
        if (owner != null && entity == owner) {
            return false;
        }
        return true;
    }

    @Override
    protected void onEntityHit(EntityHitResult entityHitResult) {
        super.onEntityHit(entityHitResult);
        Entity target = entityHitResult.getEntity();

        if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        // Don't damage regular shulkers
        if (target instanceof ShulkerEntity && !(target instanceof PeekabooShellEntity)) {
            this.discard();
            return;
        }

        // Friendly fire: nudge PeekabooShell to teleport
        if (target instanceof PeekabooShellEntity peekabooShell) {
            peekabooShell.triggerEmergencyTeleport();
            spawnHitEffects(serverWorld, target);
            this.discard();
            return;
        }

        if (target instanceof LivingEntity livingTarget) {
            livingTarget.damage(this.getDamageSources().magic(), DAMAGE);
            livingTarget.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 100, 1), this);
            livingTarget.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 100, 0), this);
            spawnHitEffects(serverWorld, target);
        }
        this.discard();
    }

    @Override
    protected void onBlockHit(BlockHitResult blockHitResult) {
        super.onBlockHit(blockHitResult);
        if (this.getWorld() instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(
                    ParticleTypes.CRIT,
                    this.getX(), this.getY(), this.getZ(),
                    6, 0.15d, 0.15d, 0.15d, 0.02d
            );
        }
        this.playSound(SoundEvents.ENTITY_SHULKER_BULLET_HIT, 0.8f, 1.2f);
        this.discard();
    }

    private void spawnHitEffects(ServerWorld serverWorld, Entity target) {
        serverWorld.spawnParticles(ParticleTypes.CRIT, target.getX(), target.getBodyY(0.9d), target.getZ(), 14, 0.22d, 0.18d, 0.22d, 0.04d);
        serverWorld.spawnParticles(ParticleTypes.FIREWORK, target.getX(), target.getBodyY(1.1d), target.getZ(), 12, 0.26d, 0.22d, 0.26d, 0.03d);
        serverWorld.spawnParticles(ParticleTypes.GLOW, target.getX(), target.getBodyY(1.2d), target.getZ(), 10, 0.18d, 0.12d, 0.18d, 0.0d);
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("Life", this.lifeTicks);
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        this.lifeTicks = nbt.getInt("Life");
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 16384.0d;
    }
}