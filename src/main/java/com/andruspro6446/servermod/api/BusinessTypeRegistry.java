package com.andruspro6446.servermod.api;

import com.andruspro6446.servermod.business.BusinessType;
import com.andruspro6446.servermod.business.BusinessTypes;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

// Public entry point for addons that add new kinds of business (e.g. the Shipping addon). Call register()
// once, from your own mod's FMLCommonSetupEvent - both mods' common setup runs before any business is looked
// up by type, so ordering between mods doesn't matter.
public final class BusinessTypeRegistry
{
    private static final Map<ResourceLocation, BusinessType> TYPES = new LinkedHashMap<>();

    static
    {
        TYPES.put(BusinessTypes.SHOP_ID, BusinessTypes.SHOP);
    }

    private BusinessTypeRegistry() {}

    // Throws if a type with this id is already registered (including the built-in "servermod:shop") - addon
    // ids should be namespaced under the addon's own modid to avoid collisions.
    public static void register(BusinessType type)
    {
        if (TYPES.containsKey(type.id()))
            throw new IllegalStateException("Business type already registered: " + type.id());
        TYPES.put(type.id(), type);
    }

    public static BusinessType get(ResourceLocation id)
    {
        return TYPES.get(id);
    }

    // Falls back to the built-in Shop type if `id` isn't (or is no longer, e.g. addon uninstalled) registered,
    // so a business never ends up with no behavior at all.
    public static BusinessType getOrShop(ResourceLocation id)
    {
        return TYPES.getOrDefault(id, BusinessTypes.SHOP);
    }

    public static Collection<BusinessType> all()
    {
        return TYPES.values();
    }
}
