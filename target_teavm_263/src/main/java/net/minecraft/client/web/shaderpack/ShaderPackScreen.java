// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

public class ShaderPackScreen extends OptionsSubScreen {
	private static final Component TITLE = Component.literal("Shader Packs");
	private static final Component OFF = Component.literal("(Off)");
	private @Nullable PackList packList;
	private @Nullable MultiLineTextWidget statusLine;
	private @Nullable Button optionsButton;

	public ShaderPackScreen(final Screen lastScreen, final Options options) {
		super(lastScreen, options, TITLE);
		this.layout.setFooterHeight(72);
	}

	@Override
	protected void addContents() {
		this.packList = this.layout.addToContents(new PackList(this.minecraft));
	}

	@Override
	protected void addOptions() {
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical()).spacing(6);
		footer.defaultCellSetting().alignHorizontallyCenter();
		this.statusLine = footer.addChild(new MultiLineTextWidget(this.statusText(), this.font).setMaxWidth(310).setCentered(true));
		LinearLayout buttons = footer.addChild(LinearLayout.horizontal().spacing(6));
		buttons.addChild(Button.builder(Component.literal("Add Pack..."), b -> ShaderPacks.choosePack(this::refresh)).width(75).build());
		this.optionsButton = buttons.addChild(Button.builder(Component.literal("Options..."), b -> this.openOptions()).width(75).build());
		buttons.addChild(Button.builder(Component.literal("Remove"), b -> this.removeSelected()).width(75).build());
		buttons.addChild(Button.builder(CommonComponents.GUI_DONE, b -> this.onDone()).width(75).build());
		this.updateOptionsButton();
	}

	private void updateOptionsButton() {
		if (this.optionsButton != null) {
			this.optionsButton.active = this.packList != null && this.packList.getSelected() != null && this.packList.getSelected().file != null;
		}
	}

	private void openOptions() {
		if (this.packList == null || this.packList.getSelected() == null || this.packList.getSelected().file == null) {
			return;
		}
		ShaderOptionsScreen screen = ShaderOptionsScreen.open(this, this.options, this.packList.getSelected().file);
		if (screen != null) {
			this.minecraft.gui.setScreen(screen);
		}
	}

	@Override
	public void added() {
		super.added();
		this.updateStatus();
	}

	private Component statusText() {
		String selected = ShaderPacks.selected();
		if (selected == null) {
			return Component.literal("Shaders are off. Packs shade terrain and water, cast terrain shadows and run their effects; entities and sky stay vanilla.")
				.withColor(0xFFA0A0A0);
		}
		String s = ShaderPacks.status();
		return Component.literal(selected + (s.isEmpty() ? "" : ": " + s)).withColor(0xFFA0A0A0);
	}

	@Override
	protected void repositionElements() {
		super.repositionElements();
		if (this.packList != null) {
			this.packList.updateSize(this.width, this.layout);
		}
	}

	private void refresh() {
		if (this.packList != null) {
			this.packList.reload();
		}
	}

	private void removeSelected() {
		if (this.packList != null && this.packList.getSelected() != null && this.packList.getSelected().file != null) {
			ShaderPacks.remove(this.packList.getSelected().file);
			this.packList.reload();
			this.updateStatus();
		}
	}

	private void updateStatus() {
		if (this.statusLine != null) {
			this.statusLine.setMessage(this.statusText());
		}
	}

	private void onDone() {
		if (this.packList != null && this.packList.getSelected() != null) {
			ShaderPacks.select(this.packList.getSelected().file);
		}
		this.minecraft.gui.setScreen(this.lastScreen);
	}

	@Override
	public void onClose() {
		this.onDone();
	}

	private final class PackList extends ObjectSelectionList<PackList.Entry> {
		PackList(final Minecraft minecraft) {
			super(minecraft, ShaderPackScreen.this.width, ShaderPackScreen.this.height - 33 - 72, 33, 18);
			this.reload();
		}

		void reload() {
			this.clearEntries();
			String current = ShaderPacks.selected();
			Entry off = new Entry(null);
			this.addEntry(off);
			Entry pick = off;
			List<String> packs = ShaderPacks.list();
			for (String name : packs) {
				Entry e = new Entry(name);
				this.addEntry(e);
				if (name.equals(current)) {
					pick = e;
				}
			}
			this.setSelected(pick);
		}

		@Override
		public void setSelected(final @Nullable Entry selected) {
			super.setSelected(selected);
			ShaderPackScreen.this.updateOptionsButton();
		}

		@Override
		public int getRowWidth() {
			return super.getRowWidth() + 50;
		}

		final class Entry extends ObjectSelectionList.Entry<Entry> {
			final @Nullable String file;
			private final Component label;

			Entry(final @Nullable String file) {
				this.file = file;
				this.label = file == null ? OFF : Component.literal(file.endsWith(".zip") ? file.substring(0, file.length() - 4) : file);
			}

			@Override
			public void extractContent(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final boolean hovered, final float a) {
				boolean active = this.file == null ? ShaderPacks.selected() == null : this.file.equals(ShaderPacks.selected());
				graphics.centeredText(ShaderPackScreen.this.font, this.label, PackList.this.width / 2, this.getContentYMiddle() - 9 / 2,
					active ? 0xFFFFFF55 : -1);
			}

			@Override
			public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
				PackList.this.setSelected(this);
				if (doubleClick) {
					ShaderPackScreen.this.onDone();
				}
				return super.mouseClicked(event, doubleClick);
			}

			@Override
			public Component getNarration() {
				return Component.translatable("narrator.select", this.label);
			}
		}
	}
}
