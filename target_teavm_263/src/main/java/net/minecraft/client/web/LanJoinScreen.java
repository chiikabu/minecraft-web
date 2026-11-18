// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class LanJoinScreen extends Screen {
	private static final int GREY = -6250336;
	private static final int RED = -43691;

	private final Screen lastScreen;
	private final String worldName;
	private final String address;
	private EditBox codeEdit;
	private EditBox passwordEdit;
	private String error = "";

	public LanJoinScreen(final Screen lastScreen, final String worldName, final String address) {
		super(Component.literal(address.isEmpty() ? "Join with Code" : worldName));
		this.lastScreen = lastScreen;
		this.worldName = worldName;
		this.address = address;
	}

	public static void connect(final Screen parent, final String name, final String address, final String password) {
		LanServers.usePassword(address, password);
		ServerData data = new ServerData(name.isEmpty() ? "LAN World" : name, address, ServerData.Type.OTHER);
		ConnectScreen.startConnecting(parent, Minecraft.getInstance(), ServerAddress.parseString(address), data, false, null);
	}

	private boolean byCode() {
		return this.address.isEmpty();
	}

	@Override
	protected void init() {
		int left = this.width / 2 - 100;
		int y = this.height / 2 - 30;
		if (this.byCode()) {
			this.codeEdit = new EditBox(this.font, left, y, 200, 20, Component.literal("Code"));
			this.codeEdit.setMaxLength(12);
			this.codeEdit.setHint(Component.literal("Code").withColor(GREY));
			this.addRenderableWidget(this.codeEdit);
			y += 24;
		}
		this.passwordEdit = new EditBox(this.font, left, y, 200, 20, Component.literal("Password"));
		this.passwordEdit.setMaxLength(64);
		this.passwordEdit.setHint(Component.literal(this.byCode() ? "Password (if any)" : "Password").withColor(GREY));
		this.addRenderableWidget(this.passwordEdit);
		this.addRenderableWidget(Button.builder(Component.translatable("selectServer.select"), button -> this.join())
			.bounds(left, this.height / 2 + 30, 98, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
			.bounds(left + 102, this.height / 2 + 30, 98, 20).build());
	}

	@Override
	protected void setInitialFocus() {
		this.setInitialFocus(this.byCode() ? this.codeEdit : this.passwordEdit);
	}

	private void join() {
		String target = this.address;
		if (this.byCode()) {
			int[] decoded = LanServers.decodeCode(this.codeEdit.getValue());
			if (decoded == null) {
				this.error = "That code isn't right";
				return;
			}
			if (decoded[0] > 0) {
				if (decoded[0] > WebRelays.builtins()) {
					this.error = "That world is on a relay you don't have";
					return;
				}
				String relay = RelayStore.builtin(decoded[0]);
				if (!relay.equals(WebRelays.current())) {
					WebRelays.select(relay);
				}
			}
			target = LanServers.address(decoded[1]);
		}
		connect(this.lastScreen, this.worldName, target, this.passwordEdit.getValue());
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (event.isConfirmation()) {
			this.join();
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
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 60, -1);
		if (!this.error.isEmpty()) {
			graphics.centeredText(this.font, Component.literal(this.error), this.width / 2, this.height / 2 + 56, RED);
		}
	}
}
