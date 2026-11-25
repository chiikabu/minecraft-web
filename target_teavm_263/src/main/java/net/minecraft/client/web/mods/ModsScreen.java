package net.minecraft.client.web.mods;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * In-browser Mods Manager screen: lists installed {@code .js}, {@code .mcmod.zip}, and {@code .jar}
 * mods stored in {@code /config/mods} with offline JVM-to-JS translation status, optional local
 * auto-builder progress, color-coded compatibility badges ({@code [JS]}, {@code [Data/Assets]},
 * {@code [Java - Live]}, {@code [Java - Partial]}, {@code [Java - Off]}, {@code [Baked / Active]},
 * {@code [Building...]}, {@code [Ready - Reload]}, {@code [Forge - Unsupported]}), enable/disable
 * toggle, {@code Add Mod...}, 1-click {@code Reload Now} / {@code Full Bake} /
 * {@code Extract Pack}, and {@code Remove}.
 */
public class ModsScreen extends OptionsSubScreen {
	private static final Component TITLE = Component.literal("Mods");
	private static final String EMPTY_HINT =
		"Drag & drop any .jar, .js, or .mcmod.zip mod anywhere in this window (or click Add Mod...)";
	private static final String EMPTY_HINT_LINE_1 = "Drag & drop any .jar, .js, or .mcmod.zip mod";
	private static final String EMPTY_HINT_LINE_2 = "anywhere in this window (or click Add Mod...)";
	private static final int FOOTER_HEIGHT = 82;
	private static final int COLOR_ENABLED = 0xFF55FF55;
	private static final int COLOR_DISABLED = 0xFFA0A0A0;
	private static final int COLOR_MUTED = 0xFFA0A0A0;
	private static final int COLOR_WHITE = -1;
	private static final int COLOR_ERROR = 0xFFFF5555;
	private static final int COLOR_BUILDING = 0xFFFFAA00;
	private static final int COLOR_READY = 0xFF55FFFF;

	private @Nullable ModList modList;
	private @Nullable MultiLineTextWidget statusLine;
	private @Nullable Button toggleButton;
	private @Nullable Button actionButton;
	private @Nullable Button removeButton;
	private @Nullable String transientStatus;
	private final Runnable liveListener = this::onLiveUpdate;
	private int lastModListCount = -1;
	private String lastModListSignature = "";

	public ModsScreen(final Screen lastScreen, final Options options) {
		super(lastScreen, options, TITLE);
		this.layout.setFooterHeight(FOOTER_HEIGHT);
	}

	/** Convenience factory matching {@code Function<Screen, Screen>} title-screen hooks. */
	public static ModsScreen create(final Screen lastScreen) {
		return new ModsScreen(lastScreen, Minecraft.getInstance().options);
	}

	@Override
	protected void addContents() {
		this.modList = this.layout.addToContents(new ModList(this.minecraft));
	}

	@Override
	protected void addOptions() {
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical()).spacing(5);
		footer.defaultCellSetting().alignHorizontallyCenter();
		this.statusLine = footer.addChild(
			new MultiLineTextWidget(this.statusText(), this.font).setMaxWidth(340).setMaxRows(2).setCentered(true));
		LinearLayout buttons = footer.addChild(LinearLayout.horizontal().spacing(4));
		buttons.addChild(Button.builder(Component.literal("Add Mod..."), b -> WebMods.chooseMod(this::onModAdded)).width(62).build());
		this.toggleButton = buttons.addChild(Button.builder(Component.literal("Enable"), b -> this.toggleSelected()).width(54).build());
		this.actionButton = buttons.addChild(Button.builder(Component.literal("Extract Pack"), b -> this.onActionButton()).width(84).build());
		this.removeButton = buttons.addChild(Button.builder(Component.literal("Remove"), b -> this.removeSelected()).width(52).build());
		buttons.addChild(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).width(48).build());
		this.updateButtons();
	}

	@Override
	public void added() {
		super.added();
		WebMods.setOnChanged(this.liveListener);
		WebMods.probeBuilderIfNeeded();
		this.updateButtons();
		this.updateStatus();
	}

	@Override
	public void removed() {
		WebMods.clearOnChanged(this.liveListener);
		super.removed();
	}

	@Override
	protected void repositionElements() {
		super.repositionElements();
		if (this.modList != null) {
			this.modList.updateSize(this.width, this.layout);
		}
	}

	@Override
	public void onClose() {
		WebMods.clearOnChanged(this.liveListener);
		this.minecraft.gui.setScreen(this.lastScreen);
	}

	private @Nullable WebMods.InstalledMod selectedMod() {
		if (this.modList == null || this.modList.getSelected() == null) {
			return null;
		}
		return this.modList.getSelected().mod;
	}

	private void onModAdded() {
		this.transientStatus = WebMods.status();
		this.refresh(null);
	}

	private static String modListSignature(final List<WebMods.InstalledMod> all) {
		StringBuilder sig = new StringBuilder();
		for (WebMods.InstalledMod m : all) {
			sig.append(m.file).append(':')
				.append(m.enabled ? '1' : '0')
				.append(m.jvmTranslated ? 'J' : '-')
				.append(m.jvmHasUnsupportedMixins ? 'P' : '-')
				.append(m.runtimeError.isEmpty() ? '0' : 'E')
				.append('|');
		}
		return sig.toString();
	}

	private void onLiveUpdate() {
		List<WebMods.InstalledMod> all = WebMods.listMods();
		String nextSig = modListSignature(all);
		if (all.size() != this.lastModListCount || !nextSig.equals(this.lastModListSignature)) {
			WebMods.InstalledMod cur = this.selectedMod();
			String keep = cur == null ? null : cur.file;
			this.lastModListCount = all.size();
			this.lastModListSignature = nextSig;
			if (this.modList != null) {
				this.modList.reload(keep);
			}
		}
		if (!WebMods.buildStatus().isEmpty()) {
			this.transientStatus = null;
		}
		this.updateButtons();
		this.updateStatus();
	}

	private void refresh(final @Nullable String keepFile) {
		if (this.modList != null) {
			this.modList.reload(keepFile);
		}
		this.updateButtons();
		this.updateStatus();
	}

	private void toggleSelected() {
		WebMods.InstalledMod mod = this.selectedMod();
		if (mod == null || !mod.isToggleable()) {
			return;
		}
		WebMods.setEnabled(mod.file, !mod.enabled);
		this.transientStatus = WebMods.status();
		this.refresh(mod.file);
	}

	private void onActionButton() {
		if (WebMods.buildReadyForReload() || WebMods.profileNeedsReload()) {
			WebMods.reloadNow();
			return;
		}
		if (WebMods.isBuilding()) {
			return;
		}
		WebMods.InstalledMod mod = this.selectedMod();
		if (mod != null && mod.canRunViaTranslator()) {
			if (WebMods.isBuilderConnected()) {
				this.rebuildMods();
				return;
			}
			if (mod.canExtractPack()) {
				this.extractSelected();
			}
			return;
		}
		if (mod != null && mod.canExtractPack()) {
			this.extractSelected();
			return;
		}
		if (WebMods.hasFabricMods() && WebMods.isBuilderConnected()) {
			this.rebuildMods();
		}
	}

	private void rebuildMods() {
		WebMods.triggerRebuild();
		this.transientStatus = WebMods.status();
		this.updateButtons();
		this.updateStatus();
	}

	private void extractSelected() {
		WebMods.InstalledMod mod = this.selectedMod();
		if (mod == null || !mod.canExtractPack()) {
			return;
		}
		WebMods.extractPack(mod.file);
		this.transientStatus = WebMods.status();
		this.refresh(mod.file);
	}

	private void removeSelected() {
		WebMods.InstalledMod mod = this.selectedMod();
		if (mod == null) {
			return;
		}
		WebMods.remove(mod.file);
		this.transientStatus = WebMods.status();
		this.refresh(null);
	}

	private void updateButtons() {
		WebMods.InstalledMod mod = this.selectedMod();
		if (this.toggleButton != null) {
			if (mod != null && mod.isToggleable()) {
				this.toggleButton.setMessage(Component.literal(mod.enabled ? "Disable" : "Enable"));
				this.toggleButton.active = true;
			} else {
				this.toggleButton.setMessage(Component.literal("Enable"));
				this.toggleButton.active = false;
			}
		}
		if (this.actionButton != null) {
			if (WebMods.buildReadyForReload() || WebMods.profileNeedsReload()) {
				boolean inWorld = this.minecraft != null && this.minecraft.level != null;
				String label = inWorld ? "Save & Reload" : "Reload Now";
				this.actionButton.setMessage(Component.literal(label).withColor(COLOR_ENABLED));
				this.actionButton.active = true;
			} else if (WebMods.isBuilding()) {
				this.actionButton.setMessage(Component.literal("Building...").withColor(COLOR_BUILDING));
				this.actionButton.active = false;
			} else if (mod != null && mod.canRunViaTranslator()) {
				if (WebMods.isBuilderConnected()) {
					this.actionButton.setMessage(Component.literal("Full Bake"));
					this.actionButton.active = true;
				} else if (mod.canExtractPack()) {
					this.actionButton.setMessage(Component.literal("Extract Pack"));
					this.actionButton.active = true;
				} else {
					this.actionButton.setMessage(Component.literal("Full Bake"));
					this.actionButton.active = false;
				}
			} else if (mod != null && mod.canExtractPack()) {
				this.actionButton.setMessage(Component.literal("Extract Pack"));
				this.actionButton.active = true;
			} else if (WebMods.hasFabricMods() && WebMods.isBuilderConnected()) {
				this.actionButton.setMessage(Component.literal("Full Bake"));
				this.actionButton.active = true;
			} else {
				this.actionButton.setMessage(Component.literal("Extract Pack"));
				this.actionButton.active = false;
			}
		}
		if (this.removeButton != null) {
			this.removeButton.active = mod != null;
		}
	}

	private void updateStatus() {
		if (this.statusLine != null) {
			this.statusLine.setMessage(this.statusText());
		}
	}

	private Component statusText() {
		String liveBuild = WebMods.buildStatus();
		if (WebMods.isBuilding()) {
			return Component.literal(liveBuild.isEmpty() ? "Building Fabric mods..." : liveBuild)
				.withColor(COLOR_BUILDING);
		}
		if (WebMods.buildReadyForReload() || WebMods.profileNeedsReload()) {
			String readyMsg = !liveBuild.isEmpty()
				? liveBuild
				: (WebMods.selectedProfile().isEmpty()
					? "Switched to Vanilla - click Reload Now to apply"
					: "Baked WASM profile '" + WebMods.selectedProfile() + "' ready - click Reload Now");
			return Component.literal(readyMsg).withColor(COLOR_ENABLED);
		}
		if (this.transientStatus != null && !this.transientStatus.isEmpty()) {
			return Component.literal(this.transientStatus).withColor(COLOR_MUTED);
		}
		WebMods.InstalledMod mod = this.selectedMod();
		if (mod != null) {
			if (!mod.runtimeError.isEmpty() && !mod.jvmTranslated) {
				String errPrefix = mod.canRunViaTranslator() ? "Java Error: " : "JS Error: ";
				return Component.literal(errPrefix + mod.runtimeError).withColor(COLOR_ERROR);
			}
			String buildErr = WebMods.modBuildError(mod.file);
			if (!buildErr.isEmpty() && !mod.jvmTranslated) {
				return Component.literal(buildErr).withColor(COLOR_ERROR);
			}
			StringBuilder sb = new StringBuilder();
			sb.append(resolveBadge(mod)).append(' ');
			int textColor = COLOR_MUTED;
			if (mod.canRunViaTranslator()) {
				if (!mod.enabled) {
					sb.append("Disabled - click Enable to run offline");
				} else if (WebMods.isModInBootedProfile(mod.file, mod.info.id)) {
					sb.append("Active in running WASM profile '").append(WebMods.bootedProfile()).append('\'');
					textColor = COLOR_ENABLED;
				} else if (mod.jvmTranslated) {
					sb.append("Translated offline: ").append(mod.jvmSummary);
					if (mod.jvmHasUnsupportedMixins) {
						textColor = COLOR_BUILDING;
						if (WebMods.isBuilderConnected()) {
							sb.append(" (click Full Bake for deep mixins)");
						}
					} else {
						textColor = COLOR_ENABLED;
					}
				} else {
					sb.append(mod.info.compatibilitySummary);
				}
			} else {
				sb.append(mod.info.compatibilitySummary);
			}
			if (mod.hasExtractedPacks()) {
				sb.append(" [Extracted]");
			}
			return Component.literal(sb.toString()).withColor(textColor);
		}
		if (!liveBuild.isEmpty()) {
			return Component.literal(liveBuild).withColor(COLOR_MUTED);
		}
		String profile = WebMods.bootedProfile();
		String profileNote = profile.isEmpty() ? "Vanilla" : ("WASM: " + profile);
		String runtimeNote = WebMods.isBuilderConnected()
			? "Offline Java Translator: Ready | Full Bake: Online"
			: "Offline Java Translator: Ready";
		return Component.literal(runtimeNote + " | " + profileNote).withColor(COLOR_MUTED);
	}

	private static String resolveBadge(final WebMods.InstalledMod mod) {
		if (!mod.canRunViaTranslator()) {
			return mod.info.kind.badge();
		}
		String st = WebMods.modBuildState(mod.file);
		if ("building".equals(st)) {
			return "[Building...]";
		}
		if ("ready".equals(st)) {
			return "[Ready - Reload]";
		}
		if (!mod.enabled) {
			return "[Java - Off]";
		}
		if (WebMods.isModInBootedProfile(mod.file, mod.info.id)) {
			return "[Baked / Active]";
		}
		if (mod.jvmTranslated) {
			return mod.jvmHasUnsupportedMixins ? "[Java - Partial]" : "[Java - Live]";
		}
		if ("blocked".equals(st)) {
			return "[Blocked]";
		}
		if ("conflict".equals(st)) {
			return "[Conflict]";
		}
		if ("failed".equals(st)) {
			return "[Build Failed]";
		}
		if (!mod.runtimeError.isEmpty()) {
			return "[Java - Error]";
		}
		return "[Java - Staged]";
	}

	private static int resolveBadgeColor(final WebMods.InstalledMod mod) {
		if (!mod.canRunViaTranslator()) {
			return mod.info.kind.badgeColor();
		}
		String st = WebMods.modBuildState(mod.file);
		if ("building".equals(st)) {
			return COLOR_BUILDING;
		}
		if ("ready".equals(st)) {
			return COLOR_READY;
		}
		if (!mod.enabled) {
			return COLOR_DISABLED;
		}
		if (WebMods.isModInBootedProfile(mod.file, mod.info.id)) {
			return COLOR_ENABLED;
		}
		if (mod.jvmTranslated) {
			return mod.jvmHasUnsupportedMixins ? COLOR_BUILDING : COLOR_ENABLED;
		}
		if ("blocked".equals(st) || "conflict".equals(st) || "failed".equals(st) || !mod.runtimeError.isEmpty()) {
			return COLOR_ERROR;
		}
		return COLOR_BUILDING;
	}

	private final class ModList extends ObjectSelectionList<ModList.Entry> {
		ModList(final Minecraft minecraft) {
			super(minecraft, ModsScreen.this.width, ModsScreen.this.height - 33 - FOOTER_HEIGHT, 33, 28);
			this.reload(null);
		}

		void reload(final @Nullable String selectFile) {
			this.clearEntries();
			List<WebMods.InstalledMod> all = WebMods.listMods();
			ModsScreen.this.lastModListCount = all.size();
			ModsScreen.this.lastModListSignature = modListSignature(all);
			if (all.isEmpty()) {
				this.addEntry(new Entry(null));
				this.setSelected(null);
				return;
			}
			Entry pick = null;
			for (WebMods.InstalledMod mod : all) {
				Entry entry = new Entry(mod);
				this.addEntry(entry);
				if (selectFile != null && selectFile.equals(mod.file)) {
					pick = entry;
				}
			}
			this.setSelected(pick != null ? pick : this.children().get(0));
		}

		@Override
		public void setSelected(final @Nullable Entry selected) {
			super.setSelected(selected);
			ModsScreen.this.transientStatus = null;
			ModsScreen.this.updateButtons();
			ModsScreen.this.updateStatus();
		}

		@Override
		public int getRowWidth() {
			return Math.min(ModsScreen.this.width - 24, super.getRowWidth() + 120);
		}

		final class Entry extends ObjectSelectionList.Entry<Entry> {
			final @Nullable WebMods.InstalledMod mod;

			Entry(final @Nullable WebMods.InstalledMod mod) {
				this.mod = mod;
			}

			@Override
			public void extractContent(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final boolean hovered, final float a) {
				int x = this.getContentX();
				int y = this.getContentY();
				int w = this.getContentWidth();

				if (this.mod == null) {
					if (ModsScreen.this.font.width(EMPTY_HINT) <= w - 4) {
						graphics.centeredText(
							ModsScreen.this.font,
							Component.literal(EMPTY_HINT).withColor(COLOR_MUTED),
							x + w / 2,
							this.getContentYMiddle() - 4,
							COLOR_MUTED);
					} else {
						graphics.centeredText(
							ModsScreen.this.font,
							Component.literal(EMPTY_HINT_LINE_1).withColor(COLOR_MUTED),
							x + w / 2,
							y + 2,
							COLOR_MUTED);
						graphics.centeredText(
							ModsScreen.this.font,
							Component.literal(EMPTY_HINT_LINE_2).withColor(COLOR_MUTED),
							x + w / 2,
							y + 14,
							COLOR_MUTED);
					}
					return;
				}

				ModJarInspector.ModInfo info = this.mod.info;
				String badge = resolveBadge(this.mod);
				int badgeColor = resolveBadgeColor(this.mod);
				int badgeW = ModsScreen.this.font.width(badge);
				int badgeX = x + w - badgeW - 2;

				// Status prefix
				String stateTag;
				int stateColor;
				if (this.mod.canRunViaTranslator()) {
					String st = WebMods.modBuildState(this.mod.file);
					if ("building".equals(st)) {
						stateTag = "[..] ";
						stateColor = COLOR_BUILDING;
					} else if ("ready".equals(st)) {
						stateTag = "[OK] ";
						stateColor = COLOR_READY;
					} else if (!this.mod.enabled) {
						stateTag = "[OFF] ";
						stateColor = COLOR_DISABLED;
					} else if (WebMods.isModInBootedProfile(this.mod.file, info.id)) {
						stateTag = "[ON] ";
						stateColor = COLOR_ENABLED;
					} else if (this.mod.jvmTranslated) {
						stateTag = "[ON] ";
						stateColor = this.mod.jvmHasUnsupportedMixins ? COLOR_BUILDING : COLOR_ENABLED;
					} else if ("blocked".equals(st) || "conflict".equals(st) || "failed".equals(st) || !this.mod.runtimeError.isEmpty()) {
						stateTag = "[ERR] ";
						stateColor = COLOR_ERROR;
					} else {
						stateTag = "[--] ";
						stateColor = COLOR_MUTED;
					}
				} else if (!this.mod.canRunInBrowser()) {
					stateTag = "[--] ";
					stateColor = COLOR_MUTED;
				} else if (!this.mod.runtimeError.isEmpty()) {
					stateTag = "[ERR] ";
					stateColor = COLOR_ERROR;
				} else if (this.mod.enabled) {
					stateTag = "[ON] ";
					stateColor = COLOR_ENABLED;
				} else {
					stateTag = "[OFF] ";
					stateColor = COLOR_DISABLED;
				}
				int stateW = ModsScreen.this.font.width(stateTag);
				graphics.text(ModsScreen.this.font, stateTag, x + 2, y + 2, stateColor);

				// Mod display name + version, truncated cleanly before the right-aligned badge
				int maxTitleW = Math.max(24, badgeX - (x + 2 + stateW) - 6);
				String title = fitText(info.displayTitle(), maxTitleW);
				graphics.text(ModsScreen.this.font, title, x + 2 + stateW, y + 2, this.mod.enabled ? COLOR_WHITE : COLOR_DISABLED);

				// Status/kind badge on the right
				graphics.text(ModsScreen.this.font, badge, badgeX, y + 2, badgeColor);

				// Second line: filename + concise detail summary (or exact translation/build error)
				String subtitle = buildSubtitle(this.mod);
				boolean hasErr = (!this.mod.runtimeError.isEmpty() && !this.mod.jvmTranslated)
					|| (!WebMods.modBuildError(this.mod.file).isEmpty() && !this.mod.jvmTranslated);
				int subColor = hasErr ? COLOR_ERROR : COLOR_MUTED;
				graphics.text(ModsScreen.this.font, fitText(subtitle, Math.max(24, w - 4)), x + 2, y + 14, subColor);
			}

			@Override
			public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
				if (this.mod == null) {
					return false;
				}
				ModList.this.setSelected(this);
				if (doubleClick && this.mod.isToggleable()) {
					ModsScreen.this.toggleSelected();
				}
				return super.mouseClicked(event, doubleClick);
			}

			@Override
			public Component getNarration() {
				if (this.mod == null) {
					return Component.literal(EMPTY_HINT);
				}
				return Component.translatable("narrator.select", Component.literal(this.mod.info.name + " " + resolveBadge(this.mod)));
			}
		}

		private String fitText(final String text, final int maxWidth) {
			if (ModsScreen.this.font.width(text) <= maxWidth) {
				return text;
			}
			int ellipsisW = ModsScreen.this.font.width("...");
			if (maxWidth <= ellipsisW) {
				return "";
			}
			return ModsScreen.this.font.plainSubstrByWidth(text, maxWidth - ellipsisW) + "...";
		}

		private String buildSubtitle(final WebMods.InstalledMod mod) {
			if (!mod.runtimeError.isEmpty() && !mod.jvmTranslated) {
				return mod.file + " - " + mod.runtimeError;
			}
			String buildErr = WebMods.modBuildError(mod.file);
			if (!buildErr.isEmpty() && !mod.jvmTranslated) {
				return mod.file + " - " + buildErr;
			}
			StringBuilder sb = new StringBuilder(mod.file);
			ModJarInspector.ModInfo info = mod.info;
			if (info.isJavaScript()) {
				if (!info.description.isEmpty()) {
					sb.append(" - ").append(info.description);
				} else if (!info.author.isEmpty()) {
					sb.append(" - by ").append(info.author);
				}
			} else if (info.kind == ModJarInspector.ModKind.DATA_ASSET_MOD) {
				sb.append(" - ").append(info.assetFileCount).append(" assets, ").append(info.dataFileCount).append(" data");
				if (mod.hasExtractedPacks()) {
					sb.append(" (active)");
				}
			} else if (mod.canRunViaTranslator() && mod.jvmTranslated && !mod.jvmSummary.isEmpty()) {
				sb.append(" - Translated offline: ").append(mod.jvmSummary);
				if (mod.hasExtractedPacks()) {
					sb.append(" (assets active)");
				}
			} else {
				sb.append(" - ").append(info.classCount).append(" classes");
				if (info.hasMixins) {
					sb.append(", mixins");
				}
				if (mod.hasExtractedPacks()) {
					sb.append(" (assets active)");
				} else if (info.hasAssets() || info.hasData()) {
					sb.append(" (pack extractable)");
				}
			}
			return sb.toString();
		}
	}
}
