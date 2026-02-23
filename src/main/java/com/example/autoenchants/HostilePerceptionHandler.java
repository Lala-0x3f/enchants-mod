package com.example.autoenchants;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class HostilePerceptionHandler {
    private static final UUID PERCEPTION_MODIFIER_ID = UUID.fromString("6d3f4884-0a67-4f10-aec0-5e839bc0a93d");
    private static final String PERCEPTION_MODIFIER_NAME = "autoenchants_perception_debuff";
    private static final double PROCESS_RADIUS = 96.0d;
    private static final Map<ServerWorld, Set<UUID>> MODIFIED_HOSTILES = new HashMap<>();

    private HostilePerceptionHandler() {
    }

    public static void tick(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            Set<UUID> tracked = MODIFIED_HOSTILES.computeIfAbsent(world, key -> new HashSet<>());
            tracked.removeIf(uuid -> {
                if (!(world.getEntity(uuid) instanceof HostileEntity hostile) || !hostile.isAlive()) {
                    return true;
                }
                return !applyPerceptionDebuff(hostile);
            });

            Set<HostileEntity> nearbyHostiles = new HashSet<>();
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (!player.isAlive() || player.isSpectator()) {
                    continue;
                }
                Box box = player.getBoundingBox().expand(PROCESS_RADIUS);
                nearbyHostiles.addAll(world.getEntitiesByClass(
                        HostileEntity.class,
                        box,
                        entity -> entity.isAlive() && !entity.isSpectator()
                ));
            }

            for (HostileEntity hostile : nearbyHostiles) {
                if (applyPerceptionDebuff(hostile)) {
                    tracked.add(hostile.getUuid());
                }
            }
        }
    }

    private static boolean applyPerceptionDebuff(HostileEntity hostile) {
        EntityAttributeInstance followRange = hostile.getAttributeInstance(EntityAttributes.GENERIC_FOLLOW_RANGE);
        if (followRange == null) {
            return false;
        }

        boolean blinded = hostile.hasStatusEffect(StatusEffects.BLINDNESS);
        boolean darkened = hostile.hasStatusEffect(StatusEffects.DARKNESS);

        EntityAttributeModifier existing = followRange.getModifier(PERCEPTION_MODIFIER_ID);
        if (!blinded && !darkened) {
            if (existing != null) {
                followRange.removeModifier(PERCEPTION_MODIFIER_ID);
            }
            return false;
        }

        double keepFactor = 1.0d;
        if (blinded) {
            keepFactor = Math.min(keepFactor, 0.30d);
        }
        if (darkened) {
            keepFactor = Math.min(keepFactor, 0.45d);
        }
        double amount = keepFactor - 1.0d;

        if (existing != null) {
            followRange.removeModifier(PERCEPTION_MODIFIER_ID);
        }
        followRange.addTemporaryModifier(new EntityAttributeModifier(
                PERCEPTION_MODIFIER_ID,
                PERCEPTION_MODIFIER_NAME,
                amount,
                EntityAttributeModifier.Operation.MULTIPLY_TOTAL
        ));

        LivingEntity target = hostile.getTarget();
        if (target == null || !target.isAlive()) {
            return true;
        }

        double currentFollowRange = followRange.getValue();
        double maxDistSq = currentFollowRange * currentFollowRange;
        if (hostile.squaredDistanceTo(target) > maxDistSq) {
            hostile.setTarget(null);
        }
        return true;
    }
}
