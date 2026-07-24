package com.andruspro6446.servermod.client;

import com.andruspro6446.servermod.money.Money;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

// Draws the player's current money balance in the top-right corner of the screen.
public class MoneyOverlay implements IGuiOverlay
{
    private static final int MARGIN = 8;
    private static final int COLOR = 0x55FF55;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight)
    {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null)
            return;

        String text = Money.format(ClientMoneyData.getMoney());
        Font font = minecraft.font;
        int x = screenWidth - font.width(text) - MARGIN;
        int y = MARGIN;

        guiGraphics.drawString(font, text, x, y, COLOR, true);
    }
}
