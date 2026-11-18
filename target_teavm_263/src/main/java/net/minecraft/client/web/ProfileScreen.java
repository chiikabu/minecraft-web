// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

public class ProfileScreen extends Screen {
	private static final Component TITLE = Component.literal("Profile");
	private static final Component USERNAME_LABEL = Component.literal("Username");
	private static final Component SKIN_LABEL = Component.literal("Skin");
	private static final Component UPLOAD = Component.literal("Upload Skin...");
	private static final Component LINK = Component.literal("Link Microsoft Account");
	private static final Component TAB_OFFLINE = Component.literal("Offline");
	private static final Component TAB_MICROSOFT = Component.literal("Microsoft");
	private static final int GREY = -6250336;
	private static final int RED = -43691;
	private static final int TILE = 24;
	private static final int FACE = 16;
	private static final int TAB_Y = 32;
	private static final int BODY_Y = 60;

	private final Screen lastScreen;
	private EditBox nameEdit;
	private Button modelButton;
	private int skinIndex = WebProfile.skinIndex();
	private boolean slim = WebProfile.slim();
	private boolean custom = WebProfile.hasCustomSkin();
	private boolean microsoftTab = WebProfile.microsoftMode();

	public ProfileScreen(final Screen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		this.nameEdit = null;
		this.addRenderableWidget(
			Button.builder(TAB_OFFLINE, button -> this.selectTab(false))
				.bounds(this.width / 2 - 100, TAB_Y, 98, 20).build()
		).active = this.microsoftTab;
		this.addRenderableWidget(
			Button.builder(TAB_MICROSOFT, button -> this.selectTab(true))
				.bounds(this.width / 2 + 2, TAB_Y, 98, 20).build()
		).active = !this.microsoftTab;

		if (this.microsoftTab) {
			this.addAccountButtons();
		} else {
			this.addOfflineBody();
		}

		this.addRenderableWidget(
			Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.width / 2 - 100, this.height - 28, 200, 20).build()
		);

		WebRelayAuth.setListener(this::rebuild);
		WebProfile.setCustomSkinListener(() -> {
			this.custom = true;
			this.rebuild();
		});
	}

	private void selectTab(final boolean microsoft) {
		if (this.microsoftTab == microsoft) {
			return;
		}
		if (!microsoft && this.nameEdit != null) {
			WebProfile.save(this.nameEdit.getValue(), this.skinIndex, this.slim);
		}
		this.microsoftTab = microsoft;
		WebProfile.setMicrosoftMode(microsoft);
		this.rebuild();
	}

	private void addOfflineBody() {
		this.nameEdit = new EditBox(this.font, this.width / 2 - 100, BODY_Y + 12, 200, 20, USERNAME_LABEL);
		this.nameEdit.setMaxLength(WebProfile.MAX_NAME);
		this.nameEdit.setValue(WebProfile.username());
		this.nameEdit.setResponder(value -> {
			String clean = WebProfile.sanitize(value);
			if (!clean.equals(value)) {
				this.nameEdit.setValue(clean);
			}
		});
		this.addWidget(this.nameEdit);

		int count = DefaultPlayerSkin.CHARACTERS.length + (this.custom ? 1 : 0);
		int rowWidth = count * (TILE + 2) - 2;
		int tilesY = BODY_Y + 52;
		for (int i = 0; i < count; i++) {
			final int index = i;
			final boolean isCustom = i == DefaultPlayerSkin.CHARACTERS.length;
			this.addRenderableWidget(
				new SkinTile(
					this.width / 2 - rowWidth / 2 + i * (TILE + 2),
					tilesY,
					isCustom ? Component.literal("uploaded skin") : Component.literal(DefaultPlayerSkin.CHARACTERS[index]),
					() -> isCustom ? WebProfile.skin() : DefaultPlayerSkin.builtin((this.slim ? 0 : DefaultPlayerSkin.CHARACTERS.length) + index),
					() -> isCustom ? this.custom : !this.custom && this.skinIndex == index,
					() -> {
						this.custom = isCustom;
						if (!isCustom) {
							this.skinIndex = index;
						}
					}
				)
			);
		}

		this.modelButton = this.addRenderableWidget(
			Button.builder(this.modelLabel(), button -> {
				this.slim = !this.slim;
				this.modelButton.setMessage(this.modelLabel());
			}).bounds(this.width / 2 - 100, tilesY + 36, 200, 20).build()
		);
		this.addRenderableWidget(
			Button.builder(UPLOAD, button -> WebProfile.chooseSkinFile())
				.bounds(this.width / 2 - 100, tilesY + 60, 98, 20).build()
		);
		this.addRenderableWidget(
			Button.builder(Component.literal("Remove Upload"), button -> {
				WebProfile.clearCustomSkin();
				this.custom = false;
				this.rebuild();
			}).bounds(this.width / 2 + 2, tilesY + 60, 98, 20).build()
		).active = this.custom;
	}

	private void addAccountButtons() {
		int y = BODY_Y + 12;
		switch (WebRelayAuth.state()) {
			case WebRelayAuth.PENDING -> {
				this.addRenderableWidget(
					Button.builder(Component.literal("Open Microsoft Login"), button -> this.openVerificationUri())
						.bounds(this.width / 2 - 100, y, 98, 20).build()
				);
				this.addRenderableWidget(
					Button.builder(CommonComponents.GUI_CANCEL, button -> WebRelayAuth.cancel())
						.bounds(this.width / 2 + 2, y, 98, 20).build()
				);
			}
			case WebRelayAuth.LINKED -> this.addRenderableWidget(
				Button.builder(Component.literal("Unlink Account"), button -> {
					WebRelayAuth.unlink();
					this.rebuild();
				}).bounds(this.width / 2 - 100, y, 200, 20).build()
			);
			default -> this.addRenderableWidget(
				Button.builder(LINK, button -> WebRelayAuth.startLink()).bounds(this.width / 2 - 100, y, 200, 20).build()
			);
		}
	}

	private void openVerificationUri() {
		String uri = WebRelayAuth.verificationUriComplete();
		if (!uri.isEmpty()) {
			com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(uri));
		}
	}

	@Override
	public void tick() {
		super.tick();
		WebRelayAuth.tick();
	}

	private Component modelLabel() {
		return Component.literal(this.slim ? "Model: Slim (Alex)" : "Model: Classic (Steve)");
	}

	private void rebuild() {
		String name = this.nameEdit != null ? this.nameEdit.getValue() : WebProfile.username();
		this.rebuildWidgets();
		if (this.nameEdit != null) {
			this.nameEdit.setValue(name);
		}
	}

	@Override
	protected void setInitialFocus() {
		if (this.nameEdit != null) {
			this.setInitialFocus(this.nameEdit);
		}
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (this.nameEdit != null && this.getFocused() == this.nameEdit && event.isConfirmation()) {
			this.onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void resize(final int width, final int height) {
		String name = this.nameEdit != null ? this.nameEdit.getValue() : WebProfile.username();
		this.init(width, height);
		if (this.nameEdit != null) {
			this.nameEdit.setValue(name);
		}
	}

	@Override
	public void onClose() {
		if (this.nameEdit != null) {
			WebProfile.save(this.nameEdit.getValue(), this.skinIndex, this.slim);
			if (this.custom != WebProfile.hasCustomSkin()) {
				WebProfile.clearCustomSkin();
			}
		}
		WebProfile.setMicrosoftMode(this.microsoftTab);
		this.minecraft.gui.setScreen(this.lastScreen);
	}

	@Override
	public void removed() {
		WebProfile.setCustomSkinListener(null);
		WebRelayAuth.setListener(null);
	}

	private Component accountStatus() {
		String error = WebRelayAuth.error();
		if (!error.isEmpty()) {
			return Component.literal(error);
		}
		return switch (WebRelayAuth.state()) {
			case WebRelayAuth.PENDING -> Component.literal("Enter code " + WebRelayAuth.userCode() + " at " + WebRelayAuth.verificationUri());
			case WebRelayAuth.LINKED -> Component.literal("Linked as " + WebRelayAuth.linkedName());
			default -> Component.literal("Not linked");
		};
	}

	private int statusColor() {
		return WebRelayAuth.error().isEmpty() ? GREY : RED;
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 12, -1);
		if (this.microsoftTab) {
			graphics.text(this.font, Component.literal("Account"), this.width / 2 - 100 + 1, BODY_Y, GREY);
			graphics.centeredText(this.font, this.accountStatus(), this.width / 2, this.height - 44, this.statusColor());
		} else {
			graphics.text(this.font, USERNAME_LABEL, this.width / 2 - 100 + 1, BODY_Y, GREY);
			graphics.text(this.font, SKIN_LABEL, this.width / 2 - 100 + 1, BODY_Y + 40, GREY);
			if (this.nameEdit != null) {
				this.nameEdit.extractRenderState(graphics, mouseX, mouseY, a);
			}
		}
	}

	private static class SkinTile extends AbstractButton {
		private final java.util.function.Supplier<PlayerSkin> skin;
		private final java.util.function.BooleanSupplier selected;
		private final Runnable onSelect;

		SkinTile(
			final int x,
			final int y,
			final Component name,
			final java.util.function.Supplier<PlayerSkin> skin,
			final java.util.function.BooleanSupplier selected,
			final Runnable onSelect
		) {
			super(x, y, TILE, TILE, name);
			this.skin = skin;
			this.selected = selected;
			this.onSelect = onSelect;
		}

		@Override
		public void onPress(final InputWithModifiers input) {
			this.onSelect.run();
		}

		@Override
		protected void extractContents(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
			this.active = !this.selected.getAsBoolean();
			this.extractDefaultSprite(graphics);
			PlayerSkin current = this.skin.get();
			if (current != null) {
				PlayerFaceExtractor.extractRenderState(
					graphics, current, this.getX() + (TILE - FACE) / 2, this.getY() + (TILE - FACE) / 2, FACE
				);
			}
		}

		@Override
		protected void extractDefaultLabel(final ActiveTextCollector output) {
		}

		@Override
		public void updateWidgetNarration(final NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}
	}
}
