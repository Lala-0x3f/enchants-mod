package com.example.autoenchants.enchant;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentTarget;
import net.minecraft.entity.EquipmentSlot;

public class AutomaticEnchantment extends Enchantment {
    public AutomaticEnchantment() {
        super(Rarity.VERY_RARE, EnchantmentTarget.CROSSBOW, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
    }

    @Override
    public int getMinPower(int level) {
        return 12 + (level - 1) * 10;
    }

    @Override
    public int getMaxPower(int level) {
        return getMinPower(level) + 25;
    }

    @Override
    public int getMaxLevel() {
        return 5;
    }

    @Override
    protected boolean canAccept(Enchantment other) {
        return super.canAccept(other) && other != AutoEnchantsMod.TRIPLE_BURST;
    }
}
