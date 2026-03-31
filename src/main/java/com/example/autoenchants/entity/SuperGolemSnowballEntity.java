package com.example.autoenchants.entity;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.DragonFireballEntity;
import net.minecraft.entity.projectile.ExplosiveProjectileEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;

public class SuperGolemSnowballEntity extends SnowballEntity {
    public SuperGolemSnowballEntity(World world, LivingEntity owner) {
        super(AutoEnchantsMod.SUPER_GOLEM_SNOWBALL, world);
        this.setOwner(owner);
        this.setPosition(owner.getX(), owner.getEyeY() - 0.1d, owner.getZ());
    }

    public SuperGolemSnowballEntity(EntityType<? extends SuperGolemSnowballEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    protected void onEntityHit(EntityHitResult entityHitResult) {
        super.onEntityHit(entityHitResult);
        Entity target = entityHitResult.getEntity();

        if (target instanceof DragonFireballEntity || target instanceof WitherSkullEntity) {
            deflectExplosiveProjectile(target);
            return;
        }

        target.damage(getDamageSources().thrown(this, getOwner()), 10.0f);

        if (this.getWorld() instanceof ServerWorld serverWorld) {
            double x = target.getX();
            double y = target.getBodyY(0.9d);
            double z = target.getZ();
            serverWorld.spawnParticles(ParticleTypes.FLASH, x, y, z, 2, 0.12d, 0.12d, 0.12d, 0.0d);
            serverWorld.spawnParticles(ParticleTypes.GLOW, x, y, z, 16, 0.22d, 0.2d, 0.22d, 0.0d);
            serverWorld.spawnParticles(ParticleTypes.FIREWORK, x, y, z, 14, 0.24d, 0.22d, 0.24d, 0.03d);
        }
    }

    private void deflectExplosiveProjectile(Entity target) {
        if (!(target instanceof ExplosiveProjectileEntity explosiveProjectile)) {
            return;
        }

        Entity owner = getOwner();
        explosiveProjectile.setOwner(owner instanceof LivingEntity livingOwner ? livingOwner : null);
        explosiveProjectile.setVelocity(explosiveProjectile.getVelocity().multiply(-1.0d));
        explosiveProjectile.velocityDirty = true;

        if (this.getWorld() instanceof ServerWorld serverWorld) {
            double x = target.getX();
            double y = target.getY() + target.getHeight() * 0.5d;
            double z = target.getZ();
            serverWorld.spawnParticles(ParticleTypes.FLASH, x, y, z, 3, 0.15d, 0.15d, 0.15d, 0.0d);
            serverWorld.spawnParticles(ParticleTypes.CRIT, x, y, z, 14, 0.18d, 0.18d, 0.18d, 0.08d);
            serverWorld.spawnParticles(ParticleTypes.SNOWFLAKE, x, y, z, 12, 0.18d, 0.18d, 0.18d, 0.01d);
        }
    }
}
