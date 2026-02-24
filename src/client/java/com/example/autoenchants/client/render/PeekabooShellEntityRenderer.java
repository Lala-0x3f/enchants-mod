package com.example.autoenchants.client.render;

import com.example.autoenchants.AutoEnchantsMod;
import com.example.autoenchants.entity.PeekabooShellEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ShulkerEntityRenderer;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;

public class PeekabooShellEntityRenderer extends ShulkerEntityRenderer {
    private static final Identifier[] DYED_TEXTURES = new Identifier[]{
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_white.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_orange.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_magenta.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_light_blue.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_yellow.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_lime.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_pink.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_gray.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_light_gray.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_cyan.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_purple.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_blue.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_brown.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_green.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_red.png"),
            AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_black.png")
    };
    private static final Identifier DEFAULT_TEXTURE = AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell.png");

    public PeekabooShellEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
    }

    @Override
    public Identifier getTexture(ShulkerEntity shulkerEntity) {
        if (!(shulkerEntity instanceof PeekabooShellEntity peekabooShell)) {
            return DEFAULT_TEXTURE;
        }

        DyeColor color = peekabooShell.getColor();
        if (color == null) {
            return DEFAULT_TEXTURE;
        }
        return DYED_TEXTURES[color.getId()];
    }
}
