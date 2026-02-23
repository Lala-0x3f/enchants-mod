package com.example.autoenchants.enchant;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentTarget;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;

public class RequiemEnchantment extends Enchantment {
    public RequiemEnchantment() {
        super(Rarity.VERY_RARE, EnchantmentTarget.BREAKABLE, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
    }

    @Override
    public int getMinPower(int level) {
        return 18 + (level - 1) * 12;
    }

    @Override
    public int getMaxPower(int level) {
        return getMinPower(level) + 25;
    }

    @Override
    public int getMaxLevel() {
        return 4;
    }

    @Override
    public boolean isAcceptableItem(ItemStack stack) {
        return stack.isOf(Items.GOAT_HORN);
    }
}

