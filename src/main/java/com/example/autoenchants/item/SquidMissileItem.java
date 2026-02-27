package com.example.autoenchants.item;

import com.example.autoenchants.AutoEnchantsMod;
import com.example.autoenchants.entity.SquidMissileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class SquidMissileItem extends Item {

    public SquidMissileItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }

        PlayerEntity player = context.getPlayer();
        BlockPos pos = context.getBlockPos();
        Direction side = context.getSide();
        BlockPos spawnPos = pos.offset(side);

        SquidMissileEntity missile = new SquidMissileEntity(AutoEnchantsMod.SQUID_MISSILE, world);
        // Position missile properly on the ground (Y + 0.01 to avoid clipping into block)
        missile.setPosition(spawnPos.getX() + 0.5d, spawnPos.getY() + 0.01d, spawnPos.getZ() + 0.5d);
        missile.setOwner(player);
        world.spawnEntity(missile);

        if (player != null && !player.getAbilities().creativeMode) {
            context.getStack().decrement(1);
        }

        return ActionResult.CONSUME;
    }
}