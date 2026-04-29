package com.example.autoenchants.item;

import com.example.autoenchants.entity.ArmorPiercingArrowEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ArrowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

public class ArmorPiercingArrowItem extends ArrowItem {

    public ArmorPiercingArrowItem(Settings settings) {
        super(settings);
    }

    @Override
    public PersistentProjectileEntity createArrow(World world, ItemStack stack, LivingEntity shooter) {
        return new ArmorPiercingArrowEntity(world, shooter);
    }
}
