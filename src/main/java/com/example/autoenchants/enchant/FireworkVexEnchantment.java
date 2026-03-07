package com.example.autoenchants.enchant;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentTarget;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;

public class FireworkVexEnchantment extends Enchantment {
    public FireworkVexEnchantment() {
        super(Rarity.VERY_RARE, EnchantmentTarget.CROSSBOW, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
    }

    @Override
    public int getMinPower(int level) {
        return 20 + (level - 1) * 10;
    }

    @Override
    public int getMaxPower(int level) {
        return getMinPower(level) + 30;
    }

    @Override
    public int getMaxLevel() {
        return 3;
    }

    @Override
    public boolean isAcceptableItem(ItemStack stack) {
        return stack.getItem() instanceof CrossbowItem;
    }

    @Override
    protected boolean canAccept(Enchantment other) {
        return super.canAccept(other) 
                && other != AutoEnchantsMod.BLAST_FIREWORK 
                && other != AutoEnchantsMod.FIREWORK_SHULKER;
    }
}
