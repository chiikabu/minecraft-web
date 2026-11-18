// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

public class OpenToLanScreen extends Screen {
	private static final int GREY = -6250336;
	private static final int RED = -43691;
	private static final int MIN_PLAYERS = 2;
	private static final int MAX_PLAYERS = 32;

	private final Screen lastScreen;
	private EditBox passwordEdit;
	private CycleButton<Boolean> visibilityButton;
	private Button startButton;
	private Button copyButton;
	private long copiedAt;

	public OpenToLanScreen(final Screen lastScreen) {
		super(Component.translatable("lanServer.title"));
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		int left = this.width / 2 - 155;
		int right = this.width / 2 + 5;
		int y = this.height / 4 + 8;

		GameType mode = WebWorkerLifecycle.lanGameType < 0 ? GameType.SURVIVAL : GameType.byId(WebWorkerLifecycle.lanGameType);
		this.addRenderableWidget(CycleButton.builder(GameType::getShortDisplayName, mode)
			.withValues(GameType.SURVIVAL, GameType.SPECTATOR, GameType.CREATIVE, GameType.ADVENTURE)
			.create(left, y, 150, 20, Component.translatable("selectWorld.gameMode"), (b, v) -> {
				WebWorkerLifecycle.lanGameType = v.getId();
				WebWorkerLifecycle.requestLanSettings();
			}));
		this.addRenderableWidget(CycleButton.onOffBuilder(WebWorkerLifecycle.lanCommands > 0)
			.create(right, y, 150, 20, Component.translatable("selectWorld.allowCommands"), (b, v) -> {
				WebWorkerLifecycle.lanCommands = v ? 1 : 0;
				WebWorkerLifecycle.requestLanSettings();
			}));

		y += 24;
		this.visibilityButton = this.addRenderableWidget(CycleButton.<Boolean>builder(
				priv -> Component.literal(priv ? "Private" : "Public"), WebWorkerLifecycle.lanPrivate)
			.withValues(Boolean.FALSE, Boolean.TRUE)
			.create(left, y, 150, 20, Component.literal("Visibility"), (b, v) -> WebWorkerLifecycle.lanPrivate = v));
		this.addRenderableWidget(new MaxPlayersSlider(right, y, 150, 20));

		y += 24;
		this.passwordEdit = new EditBox(this.font, left, y, 310, 20, Component.literal("Password"));
		this.passwordEdit.setMaxLength(64);
		this.passwordEdit.setHint(Component.literal("Password (optional)").withColor(GREY));
		this.passwordEdit.setValue(WebWorkerLifecycle.lanPassword);
		this.passwordEdit.setResponder(value -> {
			WebWorkerLifecycle.lanPassword = value;
			WebWorkerLifecycle.requestLanSettings();
		});
		this.addRenderableWidget(this.passwordEdit);

		y += 44;
		this.copyButton = this.addRenderableWidget(Button.builder(Component.literal("Copy"), button -> {
			String code = worldCode();
			if (!code.isEmpty()) {
				this.minecraft.keyboardHandler.setClipboard(code);
				this.copiedAt = System.currentTimeMillis();
			}
		}).bounds(this.width / 2 + 60, y - 6, 50, 20).build());

		this.startButton = this.addRenderableWidget(Button.builder(Component.translatable("lanServer.start"), button -> this.toggle())
			.bounds(left, this.height - 28, 150, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds(right, this.height - 28, 150, 20).build());
	}

	private void toggle() {
		if (WebWorkerLifecycle.lanStatus() == WebLifecycleMsg.HOST_BOUND) {
			WebWorkerLifecycle.requestLanClose();
		} else {
			WebWorkerLifecycle.requestLanOpen(WebRelays.unmask(WebRelayAuth.relaySocketUrl()));
		}
	}

	private static String worldCode() {
		int port = WebWorkerLifecycle.lanPort();
		return port <= 0 ? "" : LanServers.encodeCode(RelayStore.builtinIndex(WebRelays.current()), port);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.lastScreen);
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		byte state = WebWorkerLifecycle.lanStatus();
		boolean open = state == WebLifecycleMsg.HOST_BOUND;
		boolean opening = state == WebLifecycleMsg.HOST_OPENING;
		this.startButton.setMessage(open ? Component.literal("Stop LAN World")
			: opening ? Component.literal("Opening...") : Component.translatable("lanServer.start"));
		this.startButton.active = !opening && (open || WebWorkerLifecycle.active());
		this.visibilityButton.active = !open && !opening;
		String code = open ? worldCode() : "";
		this.copyButton.visible = !code.isEmpty();
		if (System.currentTimeMillis() - this.copiedAt < 1500) {
			this.copyButton.setMessage(Component.literal("Copied"));
		} else {
			this.copyButton.setMessage(Component.literal("Copy"));
		}

		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 4 - 12, -1);
		int y = this.copyButton.getY() + 6;
		if (!code.isEmpty()) {
			graphics.text(this.font, Component.literal("Code: " + code), this.width / 2 - 110, y, -1);
		} else if (state == WebLifecycleMsg.HOST_ERROR) {
			graphics.centeredText(this.font, Component.literal(WebWorkerLifecycle.lanMessage()), this.width / 2, y, RED);
		}
	}

	private static final class MaxPlayersSlider extends AbstractSliderButton {
		MaxPlayersSlider(final int x, final int y, final int width, final int height) {
			super(x, y, width, height, CommonComponents.EMPTY, toValue(WebWorkerLifecycle.lanMaxPlayers));
			this.updateMessage();
		}

		private static double toValue(final int players) {
			int clamped = Math.max(MIN_PLAYERS, Math.min(MAX_PLAYERS, players));
			return (clamped - MIN_PLAYERS) / (double)(MAX_PLAYERS - MIN_PLAYERS);
		}

		private int players() {
			return MIN_PLAYERS + (int)Math.round(this.value * (MAX_PLAYERS - MIN_PLAYERS));
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.literal("Max Players: " + this.players()));
		}

		@Override
		protected void applyValue() {
			WebWorkerLifecycle.lanMaxPlayers = this.players();
			WebWorkerLifecycle.requestLanSettings();
		}
	}
}
