package com.example.autoenchants.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.TntEntity;
import net.minecraft.world.World;

/**
 * 投弹悦灵专用 TNT：触地立即爆炸（覆盖原版基于引信倒计时的逻辑）。
 * 其他行为保持与原版 TntEntity 一致（重力、爆炸半径、点火逻辑）。
 */
public class BomberTntEntity extends TntEntity {
    public BomberTntEntity(EntityType<? extends BomberTntEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    public void tick() {
        // 若已触地（上一 tick 末判定），将引信压到 1，使本 tick 的 super.tick() 立即触发爆炸。
        if (this.isOnGround() && !this.isRemoved() && this.getFuse() > 1) {
            this.setFuse(1);
        }
        super.tick();
    }
}
