package com.example.autoenchants.enchant;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentTarget;
import net.minecraft.entity.EquipmentSlot;

public class FireworkShulkerEnchantment extends Enchantment {
    public FireworkShulkerEnchantment() {
        super(Rarity.VERY_RARE, EnchantmentTarget.CROSSBOW, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
    }

    @Override
    public int getMinPower(int level) {
        return 22 + (level - 1) * 8;
    }

    @Override
    public int getMaxPower(int level) {
        return getMinPower(level) + 24;
    }

    @Override
    public int getMaxLevel() {
        return 6;
    }

    @Override
    protected boolean canAccept(Enchantment other) {
        return super.canAccept(other) && other != AutoEnchantsMod.BLAST_FIREWORK;
    }
}
