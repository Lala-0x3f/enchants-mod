package com.example.autoenchants.enchant;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentTarget;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.HorseArmorItem;
import net.minecraft.item.ItemStack;

public class ReactionArmorEnchantment extends Enchantment {
    public ReactionArmorEnchantment() {
        super(Rarity.RARE, EnchantmentTarget.ARMOR_CHEST, new EquipmentSlot[]{EquipmentSlot.CHEST});
    }

    @Override
    public int getMinPower(int level) {
        return 12 + (level - 1) * 8;
    }

    @Override
    public int getMaxPower(int level) {
        return getMinPower(level) + 24;
    }

    @Override
    public int getMaxLevel() {
        return 5;
    }

    @Override
    public boolean isAcceptableItem(ItemStack stack) {
        return super.isAcceptableItem(stack) || stack.getItem() instanceof HorseArmorItem;
    }
}

