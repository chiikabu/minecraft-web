// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

public class ShaderOptionsScreen extends OptionsSubScreen {
	private static final int ROW_HEIGHT = 24;
	private static final int SPACING = 4;

	static final class Session {
		final String pack;
		final PackOptions options;
		final Map<String, String> values;
		final Map<String, String> saved;

		Session(final String pack, final PackOptions options, final Map<String, String> userValues) {
			this.pack = pack;
			this.options = options;
			this.values = options.values(userValues);
			this.saved = options.effective(this.values);
		}

		String value(final PackOptions.Option o) {
			String v = this.values.get(o.name);
			return v == null ? o.defaultValue : v;
		}
	}

	private final Session session;
	private final PackOptions.@Nullable Screen screen;
	private @Nullable RowList rowList;
	private final List<Cell> cells = new ArrayList<>();

	private ShaderOptionsScreen(final Screen lastScreen, final Options options, final Session session, final PackOptions.@Nullable Screen screen,
		final Component title) {
		super(lastScreen, options, title);
		this.session = session;
		this.screen = screen;
	}

	public static @Nullable ShaderOptionsScreen open(final Screen lastScreen, final Options options, final String packFile) {
		PackOptions packOptions;
		try {
			packOptions = ShaderPacks.packOptions(packFile);
		} catch (RuntimeException e) {
			System.out.println("[shaderpack] could not read the options of " + packFile + ": " + e);
			packOptions = null;
		}
		if (packOptions == null) {
			ShaderPacks.toast("Could not open the options", "The pack " + packFile + " could not be read.");
			return null;
		}
		for (String w : packOptions.warnings) {
			System.out.println("[shaderpack] " + packFile + " options: " + w);
		}
		Session session = new Session(packFile, packOptions, ShaderPacks.optionValues(packFile));
		String title = packFile.endsWith(".zip") ? packFile.substring(0, packFile.length() - 4) : packFile;
		return new ShaderOptionsScreen(lastScreen, options, session, packOptions.mainScreen(), Component.literal(title));
	}

	private boolean isMain() {
		return this.screen == null || this.screen.name == null;
	}

	@Override
	protected void addContents() {
		this.rowList = this.layout.addToContents(new RowList(this.minecraft));
		this.cells.clear();
		if (this.screen == null) {
			return;
		}
		int columns = Math.max(1, this.screen.columns);
		int rowWidth = this.rowWidth(columns);
		int cellWidth = (rowWidth - (columns - 1) * SPACING) / columns;
		List<@Nullable AbstractWidget> row = new ArrayList<>();
		for (PackOptions.Element element : this.screen.elements) {
			row.add(this.widget(element, cellWidth));
			if (row.size() == columns) {
				this.rowList.addRow(row, cellWidth, rowWidth);
				row = new ArrayList<>();
			}
		}
		if (!row.isEmpty()) {
			this.rowList.addRow(row, cellWidth, rowWidth);
		}
	}

	private int rowWidth(final int columns) {
		int wanted = columns <= 2 ? 310 : Math.min(150 * columns, 120 * columns + 40);
		return Math.max(100, Math.min(wanted, this.width - 16));
	}

	@Override
	protected void addOptions() {
	}

	@Override
	protected void addFooter() {
		LinearLayout buttons = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		if (this.isMain()) {
			buttons.addChild(Button.builder(Component.literal("Reset"), b -> this.resetAll())
				.tooltip(Tooltip.create(Component.literal("Every option of this pack back to its default"))).width(100).build());
			buttons.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> this.minecraft.gui.setScreen(this.lastScreen)).width(100).build());
			buttons.addChild(Button.builder(CommonComponents.GUI_DONE, b -> this.onDone()).width(100).build());
		} else {
			buttons.addChild(Button.builder(CommonComponents.GUI_BACK, b -> this.minecraft.gui.setScreen(this.lastScreen)).width(150).build());
		}
	}

	@Override
	protected void repositionElements() {
		super.repositionElements();
		if (this.rowList != null) {
			this.rowList.updateSize(this.width, this.layout);
		}
	}

	@Override
	public void added() {
		super.added();
		this.refresh();
	}

	private void refresh() {
		for (Cell c : this.cells) {
			c.refresh();
		}
	}

	private void resetAll() {
		this.session.values.clear();
		this.session.values.putAll(this.session.options.values(null));
		this.refresh();
	}

	private void onDone() {
		Map<String, String> now = this.session.options.effective(this.session.values);
		if (!now.equals(this.session.saved)) {
			ShaderPacks.saveOptionValues(this.session.pack, now);
			this.session.saved.clear();
			this.session.saved.putAll(now);
		}
		this.minecraft.gui.setScreen(this.lastScreen);
	}

	@Override
	public void onClose() {
		if (this.isMain()) {
			this.onDone();
		} else {
			this.minecraft.gui.setScreen(this.lastScreen);
		}
	}

	private interface Cell {
		void refresh();
	}

	private @Nullable AbstractWidget widget(final PackOptions.Element element, final int width) {
		PackOptions opts = this.session.options;
		switch (element.kind) {
			case EMPTY:
				return null;
			case SCREEN: {
				PackOptions.Screen sub = opts.screen(element.name);
				Button b = Button.builder(Component.literal(opts.screenLabel(element.name) + "..."), button -> {
					if (sub != null) {
						this.minecraft.gui.setScreen(new ShaderOptionsScreen(this, this.options, this.session, sub,
							Component.literal(PackOptions.stripFormatting(opts.screenLabel(element.name)))));
					}
				}).width(width).build();
				String comment = opts.screenComment(element.name);
				if (comment != null) {
					b.setTooltip(Tooltip.create(Component.literal(comment)));
				}
				b.active = sub != null;
				return b;
			}
			case PROFILE: {
				ProfileButton b = new ProfileButton(width);
				this.cells.add(b);
				return b;
			}
			case UNKNOWN: {
				Button b = Button.builder(Component.literal(element.name), button -> {
				}).width(width).build();
				b.active = false;
				b.setTooltip(Tooltip.create(Component.literal("Not an option of this pack's shader files (or its defaults conflict)")));
				return b;
			}
			case OPTION:
			default: {
				PackOptions.Option o = opts.option(element.name);
				if (o == null) {
					return null;
				}
				AbstractWidget w = opts.isSlider(o) && o.values.size() > 1 ? new OptionSlider(o, width) : new OptionButton(o, width);
				String comment = opts.optionComment(o);
				if (comment != null) {
					w.setTooltip(Tooltip.create(Component.literal(comment)));
				}
				this.cells.add((Cell) w);
				return w;
			}
		}
	}

	private Component message(final PackOptions.Option o) {
		PackOptions opts = this.session.options;
		String value = this.session.value(o);
		boolean changed = !value.equals(o.defaultValue);
		MutableComponent m = Component.empty();
		m.append(changed
			? Component.literal(PackOptions.stripFormatting(opts.optionLabel(o))).withStyle(ChatFormatting.YELLOW)
			: Component.literal(opts.optionLabel(o)));
		m.append(": ");
		if (o.bool) {
			boolean on = value.equals("true");
			m.append(Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
		} else {
			m.append(Component.literal(opts.valueLabel(o, value)));
		}
		return m;
	}

	private static boolean backwards(final InputWithModifiers input) {
		return input.hasShiftDown() || input instanceof MouseButtonEvent event && event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
	}

	private abstract static class TwoWayButton extends Button.Plain implements Cell {
		TwoWayButton(final int width, final Component message) {
			super(0, 0, width, 20, message, b -> {
			}, supplier -> supplier.get());
		}

		@Override
		protected boolean isValidClickButton(final MouseButtonInfo buttonInfo) {
			return buttonInfo.button() == InputConstants.MOUSE_BUTTON_LEFT || buttonInfo.button() == InputConstants.MOUSE_BUTTON_RIGHT;
		}

		@Override
		public void onPress(final InputWithModifiers input) {
			this.step(backwards(input) ? -1 : 1);
		}

		abstract void step(int direction);
	}

	private final class OptionButton extends TwoWayButton {
		private final PackOptions.Option option;

		OptionButton(final PackOptions.Option option, final int width) {
			super(width, Component.empty());
			this.option = option;
			this.refresh();
		}

		@Override
		void step(final int direction) {
			List<String> values = this.option.values;
			int i = values.indexOf(ShaderOptionsScreen.this.session.value(this.option));
			int next = Math.floorMod((i < 0 ? 0 : i) + direction, values.size());
			ShaderOptionsScreen.this.session.values.put(this.option.name, values.get(next));
			ShaderOptionsScreen.this.refresh();
		}

		@Override
		public void refresh() {
			this.setMessage(ShaderOptionsScreen.this.message(this.option));
		}
	}

	private final class ProfileButton extends TwoWayButton {
		ProfileButton(final int width) {
			super(width, Component.empty());
			this.refresh();
		}

		@Override
		void step(final int direction) {
			PackOptions opts = ShaderOptionsScreen.this.session.options;
			List<String> names = new ArrayList<>();
			for (PackOptions.Profile p : opts.profiles()) {
				names.add(p.name);
			}
			if (names.isEmpty()) {
				return;
			}
			int i = names.indexOf(opts.currentProfile(ShaderOptionsScreen.this.session.values));
			int next = i < 0 ? (direction > 0 ? 0 : names.size() - 1) : Math.floorMod(i + direction, names.size());
			opts.applyProfile(names.get(next), ShaderOptionsScreen.this.session.values);
			ShaderOptionsScreen.this.refresh();
		}

		@Override
		public void refresh() {
			PackOptions opts = ShaderOptionsScreen.this.session.options;
			String current = opts.currentProfile(ShaderOptionsScreen.this.session.values);
			String label = opts.lang("profile");
			MutableComponent m = Component.literal(label == null ? "Profile" : label).append(": ");
			m.append(current == null ? Component.literal("Custom").withStyle(ChatFormatting.YELLOW) : Component.literal(opts.profileLabel(current)));
			this.setMessage(m);
			String comment = current == null ? opts.lang("profile.comment") : opts.profileComment(current);
			this.setTooltip(comment == null ? null : Tooltip.create(Component.literal(comment)));
		}
	}

	private final class OptionSlider extends AbstractSliderButton implements Cell {
		private final PackOptions.Option option;

		OptionSlider(final PackOptions.Option option, final int width) {
			super(0, 0, width, 20, Component.empty(), 0.0);
			this.option = option;
			this.refresh();
		}

		private int steps() {
			return this.option.values.size() - 1;
		}

		@Override
		protected void setValue(final double newValue) {
			int index = (int) Math.round(Math.max(0.0, Math.min(1.0, newValue)) * this.steps());
			super.setValue((double) index / this.steps());
		}

		@Override
		public boolean keyPressed(final KeyEvent event) {
			if (this.canChangeValue && (event.isLeft() || event.isRight())) {
				int index = (int) Math.round(this.value * this.steps()) + (event.isLeft() ? -1 : 1);
				this.setValue((double) Math.max(0, Math.min(this.steps(), index)) / this.steps());
				return true;
			}
			return super.keyPressed(event);
		}

		@Override
		protected void updateMessage() {
			this.setMessage(ShaderOptionsScreen.this.message(this.option));
		}

		@Override
		protected void applyValue() {
			int index = (int) Math.round(this.value * this.steps());
			ShaderOptionsScreen.this.session.values.put(this.option.name, this.option.values.get(index));
			for (Cell c : ShaderOptionsScreen.this.cells) {
				if (c != this) {
					c.refresh();
				}
			}
		}

		@Override
		public void refresh() {
			int index = this.option.values.indexOf(ShaderOptionsScreen.this.session.value(this.option));
			this.value = index < 0 ? 0.0 : (double) index / this.steps();
			this.updateMessage();
		}
	}

	private final class RowList extends ContainerObjectSelectionList<RowList.Row> {
		private int rowWidth = 310;

		RowList(final Minecraft minecraft) {
			super(minecraft, ShaderOptionsScreen.this.width, ShaderOptionsScreen.this.layout.getContentHeight(),
				ShaderOptionsScreen.this.layout.getHeaderHeight(), ROW_HEIGHT);
			this.centerListVertically = false;
		}

		void addRow(final List<@Nullable AbstractWidget> widgets, final int cellWidth, final int rowWidth) {
			this.rowWidth = rowWidth;
			this.addEntry(new Row(widgets, cellWidth, rowWidth));
		}

		@Override
		public int getRowWidth() {
			return this.rowWidth;
		}

		final class Row extends ContainerObjectSelectionList.Entry<Row> {
			private final List<@Nullable AbstractWidget> cells;
			private final List<AbstractWidget> widgets = new ArrayList<>();
			private final int cellWidth;
			private final int width;

			Row(final List<@Nullable AbstractWidget> cells, final int cellWidth, final int width) {
				this.cells = cells;
				this.cellWidth = cellWidth;
				this.width = width;
				for (AbstractWidget w : cells) {
					if (w != null) {
						this.widgets.add(w);
					}
				}
			}

			@Override
			public void extractContent(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final boolean hovered, final float a) {
				int x = ShaderOptionsScreen.this.width / 2 - this.width / 2;
				for (AbstractWidget w : this.cells) {
					if (w != null) {
						w.setPosition(x, this.getContentY());
						w.extractRenderState(graphics, mouseX, mouseY, a);
					}
					x += this.cellWidth + SPACING;
				}
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return this.widgets;
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return this.widgets;
			}
		}
	}
}
