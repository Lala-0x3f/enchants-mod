package com.example.autoenchants.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.TntEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TntEntity.class)
public abstract class TntEntityMixin {
    private static final String BLAST_LAUNCHED_TNT_TAG = "autoenchants_blast_launched_tnt";

    @Inject(method = "tick", at = @At("HEAD"))
    private void autoenchants$explodeOnBlockHit(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!self.getCommandTags().contains(BLAST_LAUNCHED_TNT_TAG) || self.getWorld().isClient()) {
            return;
        }

        autoenchants$spawnFlightFlame(self);
        if (!autoenchants$hitsBlock(self)) {
            return;
        }

        World world = self.getWorld();
        world.createExplosion(self, self.getX(), self.getY(), self.getZ(), 4.0f, true, World.ExplosionSourceType.TNT);
        self.removeCommandTag(BLAST_LAUNCHED_TNT_TAG);
        self.discard();
    }

    @Unique
    private static boolean autoenchants$hitsBlock(Entity self) {
        Vec3d velocity = self.getVelocity();
        if (velocity.lengthSquared() < 0.01d) {
            return false;
        }
        Box nextBox = self.getBoundingBox().stretch(velocity).expand(0.05d);
        return self.getWorld().getBlockCollisions(self, nextBox).iterator().hasNext();
    }

    @Unique
    private static void autoenchants$spawnFlightFlame(Entity self) {
        if (!(self.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }
        serverWorld.spawnParticles(ParticleTypes.FLAME, self.getX(), self.getY() + 0.1d, self.getZ(), 2, 0.08d, 0.08d, 0.08d, 0.001d);
    }
}
