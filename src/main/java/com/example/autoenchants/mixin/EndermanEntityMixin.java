package com.example.autoenchants.mixin;

import com.example.autoenchants.entity.PeekabooShellEntity;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.mob.EndermanEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndermanEntity.class)
public abstract class EndermanEntityMixin {
    @Inject(method = "initGoals", at = @At("TAIL"))
    private void autoenchants$addPeekabooAvoidGoal(CallbackInfo ci) {
        MobEntityAccessor accessor = (MobEntityAccessor) this;
        accessor.autoenchants$getGoalSelector().add(2, new FleeEntityGoal<>((EndermanEntity) (Object) this, PeekabooShellEntity.class, 10.0f, 1.0d, 1.3d));
    }
}
