package com.example.autoenchants.enchant;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentTarget;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ElytraItem;
import net.minecraft.item.ItemStack;

public class FastElytraEnchantment extends Enchantment {
    public FastElytraEnchantment() {
        super(Rarity.RARE, EnchantmentTarget.ARMOR_CHEST, new EquipmentSlot[]{EquipmentSlot.CHEST});
    }

    @Override
    public int getMinPower(int level) {
        return 15 + (level - 1) * 10;
    }

    @Override
    public int getMaxPower(int level) {
        return getMinPower(level) + 25;
    }

    @Override
    public int getMaxLevel() {
        return 3;
    }

    @Override
    public boolean isAcceptableItem(ItemStack stack) {
        return super.isAcceptableItem(stack) || stack.getItem() instanceof ElytraItem;
    }
}
