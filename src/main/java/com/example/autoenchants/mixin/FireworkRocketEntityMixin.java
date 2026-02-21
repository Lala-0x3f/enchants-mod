package com.example.autoenchants.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {
    private static final String BLAST_FIREWORK_TAG = "autoenchants_blast_firework";

    @Inject(method = "onEntityHit", at = @At("HEAD"))
    private void autoenchants$blastOnEntityHit(EntityHitResult hitResult, CallbackInfo ci) {
        autoenchants$detonateIfTagged();
    }

    @Inject(method = "onBlockHit", at = @At("HEAD"))
    private void autoenchants$blastOnBlockHit(BlockHitResult hitResult, CallbackInfo ci) {
        autoenchants$detonateIfTagged();
    }

    private void autoenchants$detonateIfTagged() {
        Entity self = (Entity) (Object) this;
        if (self.getWorld().isClient()) {
            return;
        }
        if (!self.getCommandTags().contains(BLAST_FIREWORK_TAG)) {
            return;
        }

        World world = self.getWorld();
        world.createExplosion(self, self.getX(), self.getY(), self.getZ(), 4.0f, World.ExplosionSourceType.TNT);
        self.removeCommandTag(BLAST_FIREWORK_TAG);
    }
}
