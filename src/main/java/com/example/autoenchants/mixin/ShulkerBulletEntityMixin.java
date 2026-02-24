package com.example.autoenchants.mixin;

import com.example.autoenchants.AutoEnchantsMod;
import com.example.autoenchants.entity.PeekabooShellEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShulkerBulletEntity.class)
public abstract class ShulkerBulletEntityMixin {
    private static final String SHULKER_BULLET_FX_TAG = "autoenchants_firework_shulker_bullet_fx";

    @Inject(method = "onEntityHit", at = @At("HEAD"), cancellable = true)
    private void autoenchants$handleSpecialTargetRules(EntityHitResult hitResult, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        Entity target = hitResult.getEntity();
        boolean isSpark = self.getCommandTags().contains(AutoEnchantsMod.PEEKABOO_SHELL_SPARK_TAG);

        if (!isSpark && target instanceof PeekabooShellEntity) {
            ci.cancel();
            self.discard();
            return;
        }
        if (!isSpark) {
            return;
        }

        if (!(self.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        if (target instanceof ShulkerEntity && !(target instanceof PeekabooShellEntity)) {
            ci.cancel();
            self.discard();
            return;
        }
        if (target instanceof PeekabooShellEntity peekabooShell) {
            ci.cancel();
            peekabooShell.triggerEmergencyTeleport();
            autoenchants$spawnHitEffects(serverWorld, target);
            self.discard();
            return;
        }

        if (target instanceof LivingEntity livingTarget) {
            ci.cancel();
            livingTarget.damage(self.getDamageSources().magic(), 4.0f);
            livingTarget.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 100, 1), self);
            livingTarget.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 100, 0), self);
            autoenchants$spawnHitEffects(serverWorld, target);
            self.discard();
        }
    }

    @Inject(method = "onEntityHit", at = @At("HEAD"))
    private void autoenchants$spawnGlowOnEntityHit(EntityHitResult hitResult, CallbackInfo ci) {
        autoenchants$spawnGlowParticles();
    }

    @Inject(method = "onBlockHit", at = @At("HEAD"))
    private void autoenchants$spawnGlowOnBlockHit(BlockHitResult hitResult, CallbackInfo ci) {
        autoenchants$spawnGlowParticles();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void autoenchants$spawnSparkTrail(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }
        if (!self.getCommandTags().contains(AutoEnchantsMod.PEEKABOO_SHELL_SPARK_TAG)) {
            return;
        }

        Vec3d velocity = self.getVelocity();
        double px = self.getX() - velocity.x * 0.45d;
        double py = self.getY() + 0.12d - velocity.y * 0.35d;
        double pz = self.getZ() - velocity.z * 0.45d;
        Vector3f color = new Vector3f(
                0.55f + serverWorld.random.nextFloat() * 0.35f,
                0.65f + serverWorld.random.nextFloat() * 0.25f,
                0.95f
        );
        serverWorld.spawnParticles(new DustParticleEffect(color, 1.0f), px, py, pz, 2, 0.06d, 0.06d, 0.06d, 0.01d);
        serverWorld.spawnParticles(ParticleTypes.GLOW, px, py, pz, 2, 0.06d, 0.06d, 0.06d, 0.0d);
        serverWorld.spawnParticles(ParticleTypes.BUBBLE_POP, px, py, pz, 1, 0.03d, 0.03d, 0.03d, 0.01d);
    }

    private void autoenchants$spawnGlowParticles() {
        Entity self = (Entity) (Object) this;
        if (!(self.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }
        if (!self.getCommandTags().contains(SHULKER_BULLET_FX_TAG)) {
            return;
        }

        serverWorld.spawnParticles(ParticleTypes.WAX_OFF, self.getX(), self.getY() + 0.2d, self.getZ(), 24, 0.35d, 0.35d, 0.35d, 0.02d);
        serverWorld.spawnParticles(ParticleTypes.INSTANT_EFFECT, self.getX(), self.getY() + 0.2d, self.getZ(), 18, 0.3d, 0.3d, 0.3d, 0.02d);
        self.removeCommandTag(SHULKER_BULLET_FX_TAG);
    }

    private void autoenchants$spawnHitEffects(ServerWorld serverWorld, Entity target) {
        serverWorld.spawnParticles(ParticleTypes.CRIT, target.getX(), target.getBodyY(0.9d), target.getZ(), 14, 0.22d, 0.18d, 0.22d, 0.04d);
        serverWorld.spawnParticles(ParticleTypes.FIREWORK, target.getX(), target.getBodyY(1.1d), target.getZ(), 12, 0.26d, 0.22d, 0.26d, 0.03d);
        serverWorld.spawnParticles(ParticleTypes.GLOW, target.getX(), target.getBodyY(1.2d), target.getZ(), 10, 0.18d, 0.12d, 0.18d, 0.0d);
    }
}
