// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class CreditsScreen extends Screen {
	private static final Component TITLE = Component.literal(Credits.BUTTON);
	private static final int GREY = -6250336;
	private static int lineFrames;

	private final Screen lastScreen;

	public CreditsScreen(final Screen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	public static Button button(final Screen parent, final int x, final int y, final int width) {
		return Button.builder(TITLE, button -> Minecraft.getInstance().gui.setScreen(new CreditsScreen(parent)))
			.bounds(x, y, width, 20)
			.build();
	}

	public static void line(final GuiGraphicsExtractor graphics, final Font font, final int height, final int color) {
		graphics.text(font, Credits.LINE, 2, height - 20, color);
		lineFrames++;
	}

	public static Button.OnPress guard(final Screen screen, final Button.OnPress action) {
		return button -> {
			if (lineFrames > 0 && hasButton(screen)) {
				action.onPress(button);
			}
		};
	}

	private static boolean hasButton(final Screen screen) {
		for (GuiEventListener child : screen.children()) {
			if (child instanceof Button button && Credits.BUTTON.equals(button.getMessage().getString())) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected void init() {
		int y = this.height / 2 + 30;
		this.addRenderableWidget(
			Button.builder(Component.literal("GitHub"), button -> com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(Credits.URL)))
				.bounds(this.width / 2 - 100, y, 98, 20)
				.build()
		);
		this.addRenderableWidget(
			Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).bounds(this.width / 2 + 2, y, 98, 20).build()
		);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.lastScreen);
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int x = this.width / 2;
		int y = this.height / 2 - 50;
		graphics.centeredText(this.font, this.title, x, 15, -1);
		graphics.centeredText(this.font, Component.literal(Credits.PROJECT), x, y, -1);
		graphics.centeredText(this.font, Component.literal("Minecraft: Java Edition in the browser"), x, y + 14, GREY);
		graphics.centeredText(this.font, Component.literal("Made by " + Credits.AUTHOR), x, y + 38, -1);
		graphics.centeredText(this.font, Component.literal(Credits.GITHUB), x, y + 52, GREY);
	}
}
