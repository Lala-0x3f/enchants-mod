package com.example.autoenchants.item;

import com.example.autoenchants.AutoEnchantsMod;
import com.example.autoenchants.entity.BeeMissileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 蜜蜂导弹：右键投掷出一只制导蜜蜂导弹。冷却 10 ticks。
 */
public class BeeMissileItem extends Item {
    private static final int COOLDOWN_TICKS = 10;
    private static final double THROW_SPEED = 1.4d;

    public BeeMissileItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);

        // 冷却
        user.getItemCooldownManager().set(this, COOLDOWN_TICKS);

        world.playSound(null, user.getX(), user.getY(), user.getZ(),
                SoundEvents.ENTITY_BEE_LOOP, SoundCategory.PLAYERS,
                0.6f, 1.4f / (world.getRandom().nextFloat() * 0.4f + 0.8f));

        if (!world.isClient) {
            BeeMissileEntity missile = new BeeMissileEntity(AutoEnchantsMod.BEE_MISSILE, world);
            Vec3d look = user.getRotationVec(1.0f);
            Vec3d eye = user.getEyePos();
            double spawnX = eye.x + look.x * 0.6d;
            double spawnY = eye.y + look.y * 0.6d - 0.15d;
            double spawnZ = eye.z + look.z * 0.6d;
            missile.refreshPositionAndAngles(spawnX, spawnY, spawnZ, user.getYaw(), user.getPitch());
            missile.setOwner(user);
            missile.setInitialDirection(look, THROW_SPEED, user.getVelocity());
            world.spawnEntity(missile);
        }

        user.incrementStat(Stats.USED.getOrCreateStat(this));
        if (!user.getAbilities().creativeMode) {
            stack.decrement(1);
        }
        return TypedActionResult.success(stack, world.isClient());
    }
}
