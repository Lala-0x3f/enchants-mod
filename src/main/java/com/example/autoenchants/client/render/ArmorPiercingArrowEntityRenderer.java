package com.example.autoenchants.client.render;

import com.example.autoenchants.entity.ArmorPiercingArrowEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ProjectileEntityRenderer;
import net.minecraft.util.Identifier;

public class ArmorPiercingArrowEntityRenderer extends ProjectileEntityRenderer<ArmorPiercingArrowEntity> {
    private static final Identifier TEXTURE = new Identifier("autoenchants", "textures/entity/projectiles/armor_piercing_arrow.png");

    public ArmorPiercingArrowEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public Identifier getTexture(ArmorPiercingArrowEntity entity) {
        return TEXTURE;
    }
}
