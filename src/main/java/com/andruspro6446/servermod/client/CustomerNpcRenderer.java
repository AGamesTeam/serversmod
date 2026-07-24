package com.andruspro6446.servermod.client;

import com.andruspro6446.servermod.customer.CustomerNpc;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

// Renders CustomerNpc using the vanilla villager model/texture, reused purely for looks - the entity itself
// has none of a Villager's Brain/POI/trading behavior, just simple Goal-based queueing AI (see CustomerNpc).
public class CustomerNpcRenderer extends MobRenderer<CustomerNpc, VillagerModel<CustomerNpc>>
{
    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/entity/villager/villager.png");

    public CustomerNpcRenderer(EntityRendererProvider.Context context)
    {
        super(context, new VillagerModel<>(context.bakeLayer(ModelLayers.VILLAGER)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(CustomerNpc entity)
    {
        return TEXTURE;
    }
}
