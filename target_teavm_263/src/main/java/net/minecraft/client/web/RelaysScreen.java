// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.teavm.jso.JSBody;

public class RelaysScreen extends Screen {
	private static final Component TITLE = Component.literal("Relays");
	private static final int GREY = -6250336;
	private static final int ROW = 22;
	private static final int CELL = 150;

	private final Screen lastScreen;
	private boolean probed;
	private EditBox urlEdit;
	private Button autoButton;
	private Button transportButton;
	private final List<String> rowRelays = new ArrayList<>();
	private final List<Button> rowButtons = new ArrayList<>();

	public RelaysScreen(final Screen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	public static void install() {
		TitleScreen.relaysScreen = RelaysScreen::new;
		PauseScreen.openToLanScreen = OpenToLanScreen::new;
	}

	@Override
	protected void init() {
		if (!this.probed) {
			this.probed = true;
			WebRelays.probeAll();
		}
		this.rowRelays.clear();
		this.rowButtons.clear();
		int left = this.width / 2 - 155;
		int right = this.width / 2 + 5;

		this.autoButton = this.addRenderableWidget(
			Button.builder(Component.literal("Auto"), button -> this.pick(RelayStore.AUTO)).bounds(left, 32, 310, 20).build());

		List<String> all = WebRelays.all();
		int builtins = WebRelays.builtins();
		List<String> customs = WebRelays.customs();
		int top = 56;
		int rows = Math.max(1, (this.height - 84 - top) / ROW);
		for (int i = 0; i < Math.min(all.size(), rows * 2); i++) {
			final String relay = all.get(i);
			int x = i % 2 == 0 ? left : right;
			int y = top + (i / 2) * ROW;
			boolean custom = RelayStore.isCustom(relay, builtins, customs);
			Button select = Button.builder(Component.literal("Relay " + (i + 1)), button -> this.pick(relay))
				.bounds(x, y, custom ? CELL - 22 : CELL, 20).build();
			this.addRenderableWidget(select);
			this.rowRelays.add(relay);
			this.rowButtons.add(select);
			if (custom) {
				this.addRenderableWidget(Button.builder(Component.literal("X"), button -> {
					WebRelays.removeCustom(relay);
					this.rebuildWidgets();
				}).bounds(x + CELL - 20, y, 20, 20).build());
			}
		}

		int addY = this.height - 52;
		this.urlEdit = new EditBox(this.font, left, addY, 252, 20, Component.literal("Relay address"));
		this.urlEdit.setMaxLength(256);
		this.urlEdit.setHint(Component.literal("Add a relay: wss://host").withColor(GREY));
		this.addRenderableWidget(this.urlEdit);
		this.addRenderableWidget(Button.builder(Component.literal("Add"), button -> this.addTyped()).bounds(left + 256, addY, 54, 20).build());

		this.transportButton = this.addRenderableWidget(
			Button.builder(transportLabel(), button -> this.cycleTransport()).bounds(left, this.height - 28, 150, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds(right, this.height - 28, 150, 20).build());
	}

	private void addTyped() {
		String typed = this.urlEdit.getValue();
		if (RelayStore.normalize(typed).isEmpty()) {
			return;
		}
		WebRelays.addCustom(typed);
		this.urlEdit.setValue("");
		WebRelays.probeAll();
		this.rebuildWidgets();
	}

	private void pick(final String relay) {
		WebRelays.select(relay);
		this.rebuildWidgets();
	}

	private void cycleTransport() {
		lsSet(RelayTransport.KEY, RelayTransport.next(lsGet(RelayTransport.KEY)));
		WebRelays.probeAll();
		this.rebuildWidgets();
	}

	private static Component transportLabel() {
		if (WebRelayRpc.usedWebSocketFallback(WebRelayAuth.relaySocketUrl())) {
			return Component.literal("Transport: Auto (2)");
		}
		return Component.literal("Transport: " + RelayTransport.label(lsGet(RelayTransport.KEY)));
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (this.getFocused() == this.urlEdit && event.isConfirmation()) {
			this.addTyped();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.lastScreen);
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		this.transportButton.setMessage(transportLabel());
		String selected = WebRelays.selected();
		String current = WebRelays.current();
		String using = WebRelays.label(current);
		this.autoButton.setMessage(mark(Component.literal("Auto" + (using.isEmpty() ? "" : " (" + using + ")")), selected.isEmpty()));
		for (int i = 0; i < this.rowButtons.size(); i++) {
			String relay = this.rowRelays.get(i);
			String status = WebRelays.status(relay);
			Button button = this.rowButtons.get(i);
			MutableComponent label = Component.literal("Relay " + (i + 1));
			int ping = WebRelays.ping(relay);
			if (RelayStore.STATUS_OK.equals(status) && ping > 0) {
				label.append(Component.literal("  " + ping + " ms").withColor(pingColor(ping)));
			} else if (RelayStore.STATUS_CHECKING.equals(status)) {
				label.append(Component.literal("  ...").withColor(GREY));
			}
			button.setMessage(mark(label, relay.equals(selected)));
			button.active = !RelayStore.STATUS_FAIL.equals(status);
		}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 15, -1);
	}

	private static Component mark(final MutableComponent text, final boolean chosen) {
		return chosen ? Component.literal("> ").append(text).append(" <") : text;
	}

	private static int pingColor(final int ping) {
		return ping < 150 ? -11141291 : ping < 300 ? -171 : ping < 600 ? -22016 : -43691;
	}

	@JSBody(params = { "k" }, script = "try { return window.localStorage.getItem(k) || ''; } catch (e) { return ''; }")
	private static native String lsGet(String k);

	@JSBody(params = { "k", "v" }, script = "try { window.localStorage.setItem(k, v); } catch (e) {}")
	private static native void lsSet(String k, String v);
}
