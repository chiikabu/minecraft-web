// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

public class WebOptionsList extends OptionsList {
	@Nullable
	private Component pendingHeader;

	public WebOptionsList(Minecraft minecraft, int width, OptionsSubScreen screen) {
		super(minecraft, width, screen);
	}

	@Override
	public void addBig(OptionInstance<?> option) {
		if (!hidden(option)) {
			flushHeader();
			super.addBig(option);
		}
	}

	@Override
	public void addBig(AbstractWidget widget) {
		flushHeader();
		super.addBig(widget);
	}

	@Override
	public void addSmall(OptionInstance<?>... options) {
		List<OptionInstance<?>> kept = new ArrayList<>(options.length);
		for (OptionInstance<?> option : options) {
			if (!hidden(option)) {
				kept.add(option);
			}
		}
		if (!kept.isEmpty()) {
			flushHeader();
			super.addSmall(kept.toArray(new OptionInstance<?>[0]));
		}
	}

	@Override
	public void addSmall(AbstractWidget firstOption, @Nullable AbstractWidget secondOption) {
		flushHeader();
		super.addSmall(firstOption, secondOption);
	}

	@Override
	public void addSmall(AbstractWidget firstOption, OptionInstance<?> firstOptionInstance, @Nullable AbstractWidget secondOption) {
		if (!hidden(firstOptionInstance)) {
			flushHeader();
			super.addSmall(firstOption, firstOptionInstance, secondOption);
		} else if (secondOption != null) {
			addSmall(secondOption, null);
		}
	}

	@Override
	public void addHeader(Component text) {
		this.pendingHeader = text;
	}

	private void flushHeader() {
		if (this.pendingHeader != null) {
			Component text = this.pendingHeader;
			this.pendingHeader = null;
			super.addHeader(text);
		}
	}

	private static boolean hidden(OptionInstance<?> option) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.options == null) {
			return false;
		}
		Options options = minecraft.options;
		return option == options.exclusiveFullscreen()
			|| option == options.preferredGraphicsBackend()
			|| option == options.soundDevice()
			|| option == options.realmsNotifications()
			|| option == options.prioritizeChunkUpdates()
			|| isFullscreenResolution(option);
	}

	private static boolean isFullscreenResolution(OptionInstance<?> option) {
		return Component.translatable("options.fullscreen.resolution").getString().equals(option.toString());
	}
}
