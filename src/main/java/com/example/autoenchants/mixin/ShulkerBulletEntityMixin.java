package com.example.autoenchants.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShulkerBulletEntity.class)
public abstract class ShulkerBulletEntityMixin {
    private static final String SHULKER_BULLET_FX_TAG = "autoenchants_firework_shulker_bullet_fx";

    @Inject(method = "onEntityHit", at = @At("HEAD"))
    private void autoenchants$spawnGlowOnEntityHit(EntityHitResult hitResult, CallbackInfo ci) {
        autoenchants$spawnGlowParticles();
    }

    @Inject(method = "onBlockHit", at = @At("HEAD"))
    private void autoenchants$spawnGlowOnBlockHit(BlockHitResult hitResult, CallbackInfo ci) {
        autoenchants$spawnGlowParticles();
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
}
