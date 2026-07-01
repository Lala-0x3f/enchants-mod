package com.example.autoenchants.item;

import com.example.autoenchants.AutoEnchantsMod;
import com.example.autoenchants.entity.StingerMissileEntity;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class StingerMissileItem extends Item {
    private static final String PIERCING_NBT_KEY = "Piercing";
    private static final int MIN_PIERCING_LEVEL = 1;
    private static final int MAX_PIERCING_LEVEL = 3;
    private static final int COOLDOWN_TICKS = 10;
    private static final double THROW_SPEED = 1.4d;

    public StingerMissileItem(Settings settings) {
        super(settings);
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }

    public static ItemStack createStack(int piercingLevel) {
        ItemStack stack = new ItemStack(AutoEnchantsMod.STINGER_MISSILE_ITEM);
        stack.getOrCreateNbt().putInt(PIERCING_NBT_KEY, clampPiercingLevel(piercingLevel));
        return stack;
    }

    public static int getPiercingLevel(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null || !nbt.contains(PIERCING_NBT_KEY)) {
            return MIN_PIERCING_LEVEL;
        }
        return clampPiercingLevel(nbt.getInt(PIERCING_NBT_KEY));
    }

    @Override
    public Text getName(ItemStack stack) {
        int piercingLevel = getPiercingLevel(stack);
        return Text.translatable(
                "item.autoenchants.stinger_missile.with_piercing",
                Text.translatable("item.autoenchants.stinger_missile"),
                toRoman(piercingLevel)
        );
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        int piercingLevel = getPiercingLevel(stack);
        tooltip.add(Text.translatable(
                "item.autoenchants.stinger_missile.piercing.tooltip",
                toRoman(piercingLevel)
        ).formatted(Formatting.GRAY));
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);

        user.getItemCooldownManager().set(this, COOLDOWN_TICKS);
        world.playSound(null, user.getX(), user.getY(), user.getZ(),
                SoundEvents.ENTITY_BEE_LOOP, SoundCategory.PLAYERS,
                0.7f, 1.7f / (world.getRandom().nextFloat() * 0.4f + 0.8f));

        if (!world.isClient) {
            StingerMissileEntity missile = new StingerMissileEntity(AutoEnchantsMod.STINGER_MISSILE, world);
            Vec3d look = user.getRotationVec(1.0f);
            Vec3d eye = user.getEyePos();
            double spawnX = eye.x + look.x * 0.6d;
            double spawnY = eye.y + look.y * 0.6d - 0.15d;
            double spawnZ = eye.z + look.z * 0.6d;
            missile.refreshPositionAndAngles(spawnX, spawnY, spawnZ, user.getYaw(), user.getPitch());
            missile.setOwner(user);
            missile.setPiercingLevel(getPiercingLevel(stack));
            missile.setInitialDirection(look, THROW_SPEED, user.getVelocity());
            world.spawnEntity(missile);
        }

        user.incrementStat(Stats.USED.getOrCreateStat(this));
        if (!user.getAbilities().creativeMode) {
            stack.decrement(1);
        }
        return TypedActionResult.success(stack, world.isClient());
    }

    private static int clampPiercingLevel(int piercingLevel) {
        return MathHelper.clamp(piercingLevel, MIN_PIERCING_LEVEL, MAX_PIERCING_LEVEL);
    }

    private static String toRoman(int level) {
        return switch (clampPiercingLevel(level)) {
            case 2 -> "II";
            case 3 -> "III";
            default -> "I";
        };
    }
}
