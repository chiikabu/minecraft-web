// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.mods;

import com.mojang.blaze3d.webgpu.WebGpuBackend;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.web.shaderpack.ShaderPacks;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft-styled Modrinth project details &amp; version picker screen.
 * Works both directly over HTTPS and through configured WISP Relays, previews required
 * dependencies, and auto-installs missing dependencies bottom-up.
 */
public class ModrinthProjectScreen extends Screen {
	private static final int COLOR_WHITE = -1;
	private static final int COLOR_LIGHT = 0xFFD4D4D4;
	private static final int COLOR_MUTED = 0xFFA0A0A0;
	private static final int COLOR_DIM = 0xFF7A7A7A;
	private static final int COLOR_SUCCESS = 0xFF7CE88C;
	private static final int COLOR_SKY = 0xFF7AD7F0;
	private static final int COLOR_AMBER = 0xFFF5C156;
	private static final int COLOR_ERROR = 0xFFFF6B6B;

	private static final int HERO_TOP = 28;
	private static final int HERO_H = 74;
	private static final int LIST_TOP = 120;
	private static final int FOOTER_H = 54;

	private final Screen lastScreen;
	private final ModrinthClient.ProjectHit hit;
	private ModrinthClient.@Nullable ProjectDetail detail;
	private List<ModrinthClient.ProjectVersion> versions = new ArrayList<>();

	private boolean loadingVersions;
	private boolean fabricOnly;
	private boolean installing;
	private int progressLoaded;
	private int progressTotal;
	private String statusMessage = "";
	private int statusColor = COLOR_MUTED;
	private String depPreviewText = "";
	private String depPreviewVersionId = "";
	private boolean loadedInitial;
	private @Nullable String installedShaderFilename;
	private boolean shaderNeedsWebGlReload;
	private @Nullable String lastSelectedVersionId;

	private @Nullable VersionList versionList;
	private @Nullable Button installButton;
	private @Nullable Button loaderFilterButton;

	public ModrinthProjectScreen(final Screen lastScreen, final ModrinthClient.ProjectHit hit) {
		super(Component.literal(hit.title.isEmpty() ? hit.slug : hit.title));
		this.lastScreen = lastScreen;
		this.hit = hit;
		this.fabricOnly = "mod".equals(hit.projectType);
	}

	@Override
	protected void init() {
		int listH = Math.max(48, this.height - LIST_TOP - FOOTER_H);
		this.versionList = new VersionList(this.minecraft, this.width, listH, LIST_TOP);
		this.addRenderableWidget(this.versionList);

		int btnY = this.height - 26;
		boolean showLoaderBtn = "mod".equals(this.hit.projectType);
		if (showLoaderBtn) {
			int totalW = 114 + 6 + 98 + 6 + 70;
			int x = (this.width - totalW) / 2;
			this.installButton = this.addRenderableWidget(
				Button.builder(Component.literal("Install Version"), b -> this.onInstallButtonClicked())
					.bounds(x, btnY, 114, 20).build());
			this.loaderFilterButton = this.addRenderableWidget(
				Button.builder(Component.literal(this.fabricOnly ? "Loader: Fabric" : "Loader: All"), b -> this.toggleLoaderFilter())
					.bounds(x + 120, btnY, 98, 20).build());
			this.addRenderableWidget(
				Button.builder(CommonComponents.GUI_BACK, b -> this.onClose())
					.bounds(x + 224, btnY, 70, 20).build());
		} else {
			int totalW = 128 + 8 + 80;
			int x = (this.width - totalW) / 2;
			this.installButton = this.addRenderableWidget(
				Button.builder(Component.literal("Install Version"), b -> this.onInstallButtonClicked())
					.bounds(x, btnY, 128, 20).build());
			this.loaderFilterButton = null;
			this.addRenderableWidget(
				Button.builder(CommonComponents.GUI_BACK, b -> this.onClose())
					.bounds(x + 136, btnY, 80, 20).build());
		}

		if (!this.loadedInitial) {
			this.loadedInitial = true;
			this.fetchProjectDetails();
			this.loadVersions();
		} else {
			this.versionList.populate(this.versions, null);
		}
		this.updateButtons();
	}

	private void fetchProjectDetails() {
		ModrinthClient.fetchProject(
			this.hit.id,
			d -> this.detail = d,
			err -> {
			});
	}

	private void toggleLoaderFilter() {
		if (this.installing) {
			return;
		}
		this.fabricOnly = !this.fabricOnly;
		if (this.loaderFilterButton != null) {
			this.loaderFilterButton.setMessage(Component.literal(this.fabricOnly ? "Loader: Fabric" : "Loader: All"));
		}
		this.loadVersions();
	}

	private void loadVersions() {
		this.loadingVersions = true;
		this.versions = new ArrayList<>();
		this.depPreviewText = "";
		this.depPreviewVersionId = "";
		this.statusMessage = "Loading versions...";
		this.statusColor = COLOR_MUTED;
		if (this.versionList != null) {
			this.versionList.showMessage("Loading versions from Modrinth...");
		}
		this.updateButtons();

		String loader = this.fabricOnly ? "fabric" : "";
		ModrinthClient.fetchVersions(
			this.hit.id,
			loader,
			list -> {
				this.loadingVersions = false;
				if (list.isEmpty() && this.fabricOnly) {
					this.fabricOnly = false;
					if (this.loaderFilterButton != null) {
						this.loaderFilterButton.setMessage(Component.literal("Loader: All"));
					}
					this.loadVersions();
					return;
				}
				this.versions = list;
				if (this.versionList != null) {
					this.versionList.populate(list, null);
				}
				if (list.isEmpty()) {
					this.statusMessage = "No downloadable files found on Modrinth";
					this.statusColor = COLOR_AMBER;
				} else {
					ModrinthClient.RelayInfo ri = ModrinthClient.relayInfo();
					this.statusMessage = list.size() + " versions via " + ri.badgeText() + " - double-click or press Enter to install";
					this.statusColor = COLOR_MUTED;
				}
				this.updateDepPreview();
				this.updateButtons();
			},
			err -> {
				this.loadingVersions = false;
				this.statusMessage = err;
				this.statusColor = COLOR_ERROR;
				if (this.versionList != null) {
					this.versionList.showMessage(err);
				}
				this.updateButtons();
			});
	}

	private void updateDepPreview() {
		ModrinthClient.ProjectVersion sel = this.selectedVersion();
		if (sel == null) {
			this.depPreviewText = "";
			this.depPreviewVersionId = "";
			return;
		}
		if (sel.id.equals(this.depPreviewVersionId)) {
			return;
		}
		this.depPreviewVersionId = sel.id;
		if (sel.requiredDepIds.isEmpty()) {
			this.depPreviewText = sel.requiredDepCount > 0
				? (sel.requiredDepCount + " required dep(s) auto-installed")
				: "";
			return;
		}
		final String expectedId = sel.id;
		this.depPreviewText = "Checking dependencies...";
		ModrinthClient.fetchProjectNames(sel.requiredDepIds, names -> {
			if (!expectedId.equals(this.depPreviewVersionId)) {
				return;
			}
			if (names.isEmpty()) {
				this.depPreviewText = sel.requiredDepCount + " required dep(s) auto-installed";
				return;
			}
			StringBuilder sb = new StringBuilder("Requires: ");
			for (int i = 0; i < Math.min(3, names.size()); i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(names.get(i));
			}
			if (names.size() > 3) {
				sb.append(" +").append(names.size() - 3);
			}
			sb.append(" (auto-installed)");
			this.depPreviewText = sb.toString();
		});
	}

	private ModrinthClient.@Nullable ProjectVersion selectedVersion() {
		if (this.versionList == null || this.versionList.getSelected() == null) {
			return null;
		}
		return this.versionList.getSelected().version;
	}

	private void onInstallButtonClicked() {
		if (this.installing) {
			return;
		}
		if (this.shaderNeedsWebGlReload && this.installedShaderFilename != null && !this.installedShaderFilename.isEmpty()) {
			ShaderPacks.select(this.installedShaderFilename);
			WebMods.reloadNow();
			return;
		}
		this.installSelected();
	}

	private static String resolveInstalledShaderFilename(final String rawFilename) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < rawFilename.length() && out.length() < 80; i++) {
			char c = rawFilename.charAt(i);
			out.append(c == '/' || c == '\\' || c == ':' || c < ' ' ? '_' : c);
		}
		String sanitized = out.toString();
		if (!sanitized.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			sanitized = sanitized + ".zip";
		}
		for (String existing : ShaderPacks.list()) {
			if (existing.equals(sanitized) || existing.equalsIgnoreCase(sanitized) || existing.equalsIgnoreCase(rawFilename)) {
				return existing;
			}
		}
		return sanitized;
	}

	private void installSelected() {
		if (this.installing) {
			return;
		}
		ModrinthClient.ProjectVersion v = this.selectedVersion();
		if (v == null || v.fileUrl.isEmpty()) {
			return;
		}
		this.installing = true;
		this.shaderNeedsWebGlReload = false;
		this.installedShaderFilename = null;
		this.progressLoaded = 0;
		this.progressTotal = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, v.fileSize));
		this.statusMessage = "Downloading " + v.filename + "...";
		this.statusColor = COLOR_SKY;
		this.updateButtons();

		String title = this.hit.title.isEmpty() ? this.hit.slug : this.hit.title;
		final String installedFile = v.filename;
		ModrinthClient.downloadAndInstall(
			this.hit.id,
			this.hit.slug,
			title,
			this.hit.projectType,
			v,
			(label, step, totalSteps, loaded, total) -> {
				this.progressLoaded = loaded;
				this.progressTotal = total;
				String stepTag = totalSteps > 1 ? ("[" + step + "/" + totalSteps + "] ") : "";
				if (step == 0) {
					this.statusMessage = label;
				} else if (total > 0) {
					int pct = (int) Math.min(100L, (loaded * 100L) / total);
					this.statusMessage = stepTag + "Downloading " + label + " (" + pct + "% - "
						+ formatBytes(loaded) + " / " + formatBytes(total) + ")";
				} else {
					this.statusMessage = stepTag + "Downloading " + label + " (" + formatBytes(loaded) + ")";
				}
			},
			(ok, msg) -> {
				this.installing = false;
				this.progressLoaded = 0;
				this.progressTotal = 0;
				if (ok && "shader".equals(this.hit.projectType) && WebGpuBackend.live()) {
					this.installedShaderFilename = resolveInstalledShaderFilename(installedFile);
					this.shaderNeedsWebGlReload = true;
					this.statusMessage = "Installed " + title + " - click 'Enable & Reload' to switch to WebGL2";
					this.statusColor = COLOR_SUCCESS;
				} else {
					this.statusMessage = msg;
					this.statusColor = ok ? COLOR_SUCCESS : COLOR_ERROR;
				}
				this.updateButtons();
			});
	}

	private void updateButtons() {
		ModrinthClient.ProjectVersion sel = this.selectedVersion();
		if (this.installButton != null) {
			if (this.installing) {
				this.installButton.setMessage(Component.literal("Installing..."));
				this.installButton.active = false;
			} else if (this.shaderNeedsWebGlReload && this.installedShaderFilename != null) {
				this.installButton.setMessage(Component.literal("Enable & Reload").withColor(COLOR_SUCCESS));
				this.installButton.active = true;
			} else if (sel != null) {
				this.installButton.setMessage(Component.literal("Install Version"));
				this.installButton.active = true;
			} else {
				this.installButton.setMessage(Component.literal("Install Version"));
				this.installButton.active = false;
			}
		}
		if (this.loaderFilterButton != null) {
			this.loaderFilterButton.active = !this.installing && !this.loadingVersions;
		}
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (event.isConfirmation() && !this.installing && (this.shaderNeedsWebGlReload || this.selectedVersion() != null)) {
			this.onInstallButtonClicked();
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

		int panelW = Math.min(this.width - 20, 444);
		int panelL = (this.width - panelW) / 2;
		int panelR = panelL + panelW;

		// Header title & subtle route badge
		graphics.centeredText(this.font, this.title, this.width / 2, 10, COLOR_WHITE);
		ModrinthClient.RelayInfo ri = ModrinthClient.relayInfo();
		String routeBadge = ri.badgeText();
		int badgeColor = ri.needsRelaySetup() ? COLOR_AMBER : COLOR_MUTED;
		int badgeW = this.font.width(routeBadge);
		graphics.text(this.font, routeBadge, Math.min(this.width - badgeW - 8, panelR - badgeW), 10, badgeColor);

		// Hero project summary card
		int cardTop = HERO_TOP;
		int cardBot = HERO_TOP + HERO_H;
		graphics.fill(panelL, cardTop, panelR, cardBot, 0x84000000);
		graphics.outline(panelL, cardTop, panelW, HERO_H, 0xFF3E3E3E);

		// 48x48 thumbnail
		int thumbX = panelL + 10;
		int thumbY = cardTop + 13;
		int thumbSize = 48;
		graphics.fill(thumbX - 1, thumbY - 1, thumbX + thumbSize + 1, thumbY + thumbSize + 1, 0xFF525252);
		graphics.fill(thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize, 0xFF1A1A1A);
		String iconUrl = (this.detail != null && !this.detail.iconUrl.isEmpty()) ? this.detail.iconUrl : this.hit.iconUrl;
		Identifier tex = ModrinthClient.getOrRequestThumbnail(iconUrl, null);
		if (tex != null) {
			graphics.blit(
				RenderPipelines.GUI_TEXTURED, tex,
				thumbX, thumbY,
				0.0F, 0.0F,
				thumbSize, thumbSize,
				ModrinthClient.THUMB_SIZE, ModrinthClient.THUMB_SIZE,
				ModrinthClient.THUMB_SIZE, ModrinthClient.THUMB_SIZE);
		} else {
			graphics.fill(thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize, this.hit.accentColor());
			graphics.centeredText(this.font, this.hit.monogram(), thumbX + thumbSize / 2, thumbY + thumbSize / 2 - 4, COLOR_WHITE);
		}

		int tx = thumbX + thumbSize + 10;
		int tw = Math.max(40, panelR - tx - 10);

		// Line 1: title + author + right-aligned badges
		String typeBadge = this.hit.typeBadge();
		boolean installed = ModrinthClient.isInstalled(this.hit);
		int rightX = panelR - 10;
		if (installed) {
			String instBadge = "[Installed]";
			int iw = this.font.width(instBadge);
			rightX -= iw;
			graphics.text(this.font, instBadge, rightX, cardTop + 8, COLOR_SUCCESS);
			rightX -= 5;
		}
		int tbw = this.font.width(typeBadge);
		rightX -= tbw;
		graphics.text(this.font, typeBadge, rightX, cardTop + 8, this.hit.typeBadgeColor());

		int titleMaxW = Math.max(24, rightX - tx - 6);
		String titleStr = this.hit.title.isEmpty() ? this.hit.slug : this.hit.title;
		String authorPart = this.hit.author.isEmpty() ? "" : (" by " + this.hit.author);
		int titleW = this.font.width(titleStr);
		if (titleW <= titleMaxW) {
			graphics.text(this.font, titleStr, tx, cardTop + 8, COLOR_WHITE);
			if (!authorPart.isEmpty() && titleMaxW - titleW > 24) {
				graphics.text(this.font, fitText(authorPart, titleMaxW - titleW), tx + titleW, cardTop + 8, COLOR_MUTED);
			}
		} else {
			graphics.text(this.font, fitText(titleStr, titleMaxW), tx, cardTop + 8, COLOR_WHITE);
		}

		// Line 2: stats
		long downloads = this.detail != null ? this.detail.downloads : this.hit.downloads;
		long followers = this.detail != null ? this.detail.followers : this.hit.follows;
		StringBuilder stats = new StringBuilder();
		stats.append(ModrinthClient.formatCount(downloads)).append(" downloads");
		if (followers > 0L) {
			stats.append(" | ").append(ModrinthClient.formatCount(followers)).append(" followers");
		}
		if (this.detail != null && !this.detail.license.isEmpty()) {
			stats.append(" | ").append(this.detail.license);
		}
		if (!this.hit.clientSide.isEmpty() && !"unknown".equals(this.hit.clientSide)) {
			stats.append(" | Client: ").append(this.hit.clientSide);
		}
		graphics.text(this.font, fitText(stats.toString(), tw), tx, cardTop + 20, COLOR_SKY);

		// Lines 3..5: wrapped description
		String desc = (this.detail != null && !this.detail.description.isEmpty())
			? this.detail.description
			: this.hit.description;
		List<String> lines = wrapLines(desc, tw, 3);
		for (int i = 0; i < lines.size(); i++) {
			graphics.text(this.font, lines.get(i), tx, cardTop + 33 + i * 11, COLOR_LIGHT);
		}

		// Versions section header + live dependency preview
		graphics.text(this.font, "Available Versions", panelL + 2, LIST_TOP - 13, COLOR_WHITE);
		if (!this.depPreviewText.isEmpty()) {
			int leftEnd = panelL + 2 + this.font.width("Available Versions") + 8;
			int availW = Math.max(40, panelR - leftEnd - 2);
			String depStr = fitText(this.depPreviewText, availW);
			graphics.text(this.font, depStr, panelR - this.font.width(depStr) - 2, LIST_TOP - 13, COLOR_SKY);
		} else {
			String vCount = this.loadingVersions
				? "Fetching..."
				: (this.versions.size() + (this.fabricOnly ? " Fabric releases" : " files"));
			int vcw = this.font.width(vCount);
			graphics.text(this.font, vCount, panelR - vcw - 2, LIST_TOP - 13, COLOR_MUTED);
		}

		// Footer progress bar + status line
		int statusY = this.height - 46;
		if (this.installing) {
			int barW = Math.min(panelW - 24, 300);
			int barX = (this.width - barW) / 2;
			int barY = this.height - 35;
			graphics.fill(barX, barY, barX + barW, barY + 5, 0xFF202020);
			graphics.outline(barX, barY, barW, 5, 0xFF555555);
			int fillW;
			if (this.progressTotal > 0) {
				fillW = (int) Math.min(barW - 2, ((long) this.progressLoaded * (barW - 2)) / this.progressTotal);
			} else {
				int pulse = (int) ((System.currentTimeMillis() / 12L) % Math.max(1, barW - 40));
				fillW = Math.min(barW - 2, 36);
				graphics.fill(barX + 1 + pulse, barY + 1, Math.min(barX + barW - 1, barX + 1 + pulse + fillW), barY + 4, COLOR_SUCCESS);
				fillW = -1;
			}
			if (fillW > 0) {
				graphics.fill(barX + 1, barY + 1, barX + 1 + fillW, barY + 4, COLOR_SUCCESS);
			}
			graphics.centeredText(this.font, fitText(this.statusMessage, panelW), this.width / 2, statusY - 1, this.statusColor);
		} else if (!this.statusMessage.isEmpty()) {
			graphics.centeredText(this.font, fitText(this.statusMessage, panelW), this.width / 2, statusY + 3, this.statusColor);
		}
	}

	private String fitText(final String text, final int maxWidth) {
		if (text == null || text.isEmpty() || maxWidth <= 0) {
			return "";
		}
		if (this.font.width(text) <= maxWidth) {
			return text;
		}
		int ellipsisW = this.font.width("...");
		if (maxWidth <= ellipsisW) {
			return this.font.plainSubstrByWidth(text, maxWidth);
		}
		return this.font.plainSubstrByWidth(text, maxWidth - ellipsisW) + "...";
	}

	private List<String> wrapLines(final String text, final int maxWidth, final int maxLines) {
		List<String> out = new ArrayList<>();
		if (text == null || text.isEmpty() || maxWidth <= 16 || maxLines <= 0) {
			return out;
		}
		String rem = text.replace('\r', ' ').replace('\n', ' ').trim();
		while (!rem.isEmpty() && out.size() < maxLines) {
			if (this.font.width(rem) <= maxWidth) {
				out.add(rem);
				break;
			}
			if (out.size() == maxLines - 1) {
				out.add(fitText(rem, maxWidth));
				break;
			}
			String chunk = this.font.plainSubstrByWidth(rem, maxWidth);
			int splitAt = chunk.lastIndexOf(' ');
			if (splitAt > 4) {
				chunk = chunk.substring(0, splitAt);
			}
			if (chunk.isEmpty()) {
				chunk = rem.substring(0, 1);
			}
			out.add(chunk.trim());
			rem = rem.substring(chunk.length()).trim();
		}
		return out;
	}

	private static String formatBytes(final int bytes) {
		if (bytes >= 1024 * 1024) {
			int tenths = (int) ((bytes * 10L) / (1024L * 1024L));
			return (tenths / 10) + "." + (tenths % 10) + " MB";
		}
		return Math.max(1, bytes / 1024) + " KB";
	}

	private final class VersionList extends ObjectSelectionList<VersionList.Entry> {
		VersionList(final Minecraft minecraft, final int width, final int height, final int y) {
			super(minecraft, width, height, y, 30);
		}

		void showMessage(final String msg) {
			this.clearEntries();
			this.addEntry(new Entry(null, msg));
			this.setSelected(null);
		}

		void populate(final List<ModrinthClient.ProjectVersion> list, final @Nullable String keepId) {
			this.clearEntries();
			if (list.isEmpty()) {
				this.addEntry(new Entry(null, "No downloadable versions available."));
				this.setSelected(null);
				return;
			}
			Entry pick = null;
			Entry best263 = null;
			for (ModrinthClient.ProjectVersion v : list) {
				Entry e = new Entry(v, null);
				this.addEntry(e);
				if (keepId != null && keepId.equals(v.id)) {
					pick = e;
				}
				if (best263 == null && has263(v.gameVersions)) {
					best263 = e;
				}
			}
			this.setSelected(pick != null ? pick : (best263 != null ? best263 : this.children().get(0)));
		}

		@Override
		public void setSelected(final @Nullable Entry selected) {
			super.setSelected(selected);
			String nextId = (selected != null && selected.version != null) ? selected.version.id : null;
			if (!ModrinthProjectScreen.this.installing
					&& ModrinthProjectScreen.this.lastSelectedVersionId != null
					&& !ModrinthProjectScreen.this.lastSelectedVersionId.equals(nextId)) {
				ModrinthProjectScreen.this.shaderNeedsWebGlReload = false;
				ModrinthProjectScreen.this.installedShaderFilename = null;
			}
			ModrinthProjectScreen.this.lastSelectedVersionId = nextId;
			ModrinthProjectScreen.this.updateDepPreview();
			ModrinthProjectScreen.this.updateButtons();
		}

		@Override
		public int getRowWidth() {
			return Math.min(ModrinthProjectScreen.this.width - 20, 444);
		}

		final class Entry extends ObjectSelectionList.Entry<Entry> {
			final ModrinthClient.@Nullable ProjectVersion version;
			final @Nullable String message;

			Entry(final ModrinthClient.@Nullable ProjectVersion version, final @Nullable String message) {
				this.version = version;
				this.message = message;
			}

			@Override
			public void extractContent(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final boolean hovered, final float a) {
				int x = this.getContentX();
				int y = this.getContentY();
				int w = this.getContentWidth();

				if (this.version == null) {
					graphics.centeredText(
						ModrinthProjectScreen.this.font,
						ModrinthProjectScreen.this.fitText(this.message == null ? "" : this.message, w - 8),
						x + w / 2,
						this.getContentYMiddle() - 4,
						COLOR_MUTED);
					return;
				}

				if (hovered) {
					graphics.fill(x, y, x + w, y + this.getContentHeight(), 0x1EFFFFFF);
				}

				String badge = this.version.channelBadge();
				int badgeW = ModrinthProjectScreen.this.font.width(badge);
				graphics.text(ModrinthProjectScreen.this.font, badge, x + 3, y + 3, this.version.channelColor());

				String rightInfo = buildRightMeta(this.version);
				int rightW = ModrinthProjectScreen.this.font.width(rightInfo);
				int rightX = x + w - rightW - 3;
				boolean is26x = has26x(this.version.gameVersions);
				graphics.text(ModrinthProjectScreen.this.font, rightInfo, rightX, y + 3, is26x ? COLOR_SKY : COLOR_MUTED);

				String label = this.version.name.isEmpty() ? this.version.versionNumber : this.version.name;
				int maxLabelW = Math.max(24, rightX - (x + 3 + badgeW + 5) - 6);
				graphics.text(ModrinthProjectScreen.this.font, ModrinthProjectScreen.this.fitText(label, maxLabelW),
					x + 3 + badgeW + 5, y + 3, COLOR_WHITE);

				String sub = buildSubMeta(this.version);
				graphics.text(ModrinthProjectScreen.this.font, ModrinthProjectScreen.this.fitText(sub, w - 8),
					x + 3, y + 15, COLOR_DIM);
			}

			@Override
			public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
				if (this.version == null) {
					return false;
				}
				VersionList.this.setSelected(this);
				if (doubleClick) {
					ModrinthProjectScreen.this.installSelected();
				}
				return super.mouseClicked(event, doubleClick);
			}

			@Override
			public Component getNarration() {
				if (this.version == null) {
					return Component.literal(this.message == null ? "" : this.message);
				}
				return Component.translatable("narrator.select", Component.literal(this.version.name));
			}
		}

		private String buildRightMeta(final ModrinthClient.ProjectVersion v) {
			String mc = v.mcVersionsShort();
			String size = v.formattedSize();
			if (!mc.isEmpty() && !size.isEmpty()) {
				return "MC " + mc + " | " + size;
			}
			if (!mc.isEmpty()) {
				return "MC " + mc;
			}
			return size;
		}

		private String buildSubMeta(final ModrinthClient.ProjectVersion v) {
			StringBuilder sb = new StringBuilder();
			if (!v.filename.isEmpty()) {
				sb.append(v.filename);
			}
			if (!v.loaders.isEmpty()) {
				if (sb.length() > 0) {
					sb.append(" | ");
				}
				for (int i = 0; i < Math.min(4, v.loaders.size()); i++) {
					if (i > 0) {
						sb.append(", ");
					}
					sb.append(v.loaders.get(i));
				}
			}
			if (v.requiredDepCount > 0) {
				if (sb.length() > 0) {
					sb.append(" | ");
				}
				sb.append(v.requiredDepCount).append(v.requiredDepCount == 1 ? " dep" : " deps");
			}
			if (v.datePublished.length() >= 10) {
				if (sb.length() > 0) {
					sb.append(" | ");
				}
				sb.append(v.datePublished.substring(0, 10));
			}
			return sb.toString();
		}

		private boolean has263(final List<String> gameVersions) {
			for (String gv : gameVersions) {
				if ("26.3".equals(gv)) {
					return true;
				}
			}
			return false;
		}

		private boolean has26x(final List<String> gameVersions) {
			for (String gv : gameVersions) {
				if (gv.startsWith("26.")) {
					return true;
				}
			}
			return false;
		}
	}
}
