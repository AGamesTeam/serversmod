package com.andruspro6446.servermod.business;

import com.andruspro6446.servermod.ServerMod;
import net.minecraft.resources.ResourceLocation;

// The base mod's only built-in business type - every business defaults to this unless an addon registers
// and assigns another (see com.andruspro6446.servermod.api.BusinessTypeRegistry).
public final class BusinessTypes
{
    public static final ResourceLocation SHOP_ID = new ResourceLocation(ServerMod.MODID, "shop");

    public static final BusinessType SHOP = new BusinessType()
    {
        @Override
        public ResourceLocation id() { return SHOP_ID; }

        @Override
        public String displayName() { return "Shop"; }
    };

    private BusinessTypes() {}
}
