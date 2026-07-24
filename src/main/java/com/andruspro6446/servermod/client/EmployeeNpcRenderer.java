package com.andruspro6446.servermod.client;

import com.andruspro6446.servermod.customer.EmployeeNpc;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

// Reuses the vanilla villager model/texture, same reasoning as CustomerNpcRenderer - looks match, behavior
// doesn't (an employee never moves or shops, see EmployeeNpc).
public class EmployeeNpcRenderer extends MobRenderer<EmployeeNpc, VillagerModel<EmployeeNpc>>
{
    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/entity/villager/villager.png");

    public EmployeeNpcRenderer(EntityRendererProvider.Context context)
    {
        super(context, new VillagerModel<>(context.bakeLayer(ModelLayers.VILLAGER)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(EmployeeNpc entity)
    {
        return TEXTURE;
    }
}
