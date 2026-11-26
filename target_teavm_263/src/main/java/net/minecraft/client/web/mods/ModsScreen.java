package net.minecraft.client.web.mods;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.web.RelaysScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * In-browser Mods Manager &amp; Modrinth Relay Browser screen:
 * <ul>
 *   <li><b>Installed</b> tab: lists installed {@code .js}, {@code .mcmod.zip}, and {@code .jar}
 *       mods stored in {@code /config/mods} with offline JVM-to-JS translation status, optional
 *       local auto-builder progress, color-coded compatibility badges, enable/disable toggle,
 *       {@code Add Mod...}, 1-click {@code Reload Now} / {@code Full Bake} /
 *       {@code Extract Pack}, and {@code Remove}.</li>
 *   <li><b>Browse Modrinth</b> tab: searches Modrinth API v2 &amp; CDN strictly through the
 *       configured WISP Relays (TLS 1.3 + HTTP/1.1 keep-alive), displaying crisp {@code 32x32}
 *       thumbnails, titles, authors, descriptions, download counts, category/MC badges, live
 *       filtering, pagination, project detail view ({@link ModrinthProjectScreen}), and 1-click
 *       installation with live download progress.</li>
 * </ul>
 */
public class ModsScreen extends Screen {
	private static final Component TITLE = Component.literal("Mods");
	private static final String EMPTY_HINT =
		"Drag & drop any .jar, .js, or .mcmod.zip mod anywhere in this window (or use Browse Modrinth)";
	private static final String EMPTY_HINT_LINE_1 = "Drag & drop any .jar, .js, or .mcmod.zip mod";
	private static final String EMPTY_HINT_LINE_2 = "or switch to Browse Modrinth above";

	private static final int TAB_INSTALLED = 0;
	private static final int TAB_MODRINTH = 1;

	private static final int HEADER_H = 32;
	private static final int TOOLBAR_H = 26;
	private static final int FOOTER_H_INSTALLED = 56;
	private static final int FOOTER_H_MODRINTH = 54;
	private static final int PAGE_SIZE = 20;

	private static final int COLOR_ENABLED = 0xFF55FF55;
	private static final int COLOR_DISABLED = 0xFFA0A0A0;
	private static final int COLOR_MUTED = 0xFFA0A0A0;
	private static final int COLOR_LIGHT = 0xFFC8C8C8;
	private static final int COLOR_DIM = 0xFF848484;
	private static final int COLOR_WHITE = -1;
	private static final int COLOR_ERROR = 0xFFFF5555;
	private static final int COLOR_BUILDING = 0xFFFFAA00;
	private static final int COLOR_READY = 0xFF55FFFF;

	private static final String[] TYPE_KEYS = { "mod", "shader", "resourcepack", "datapack", "all" };
	private static final String[] TYPE_LABELS = { "Mods", "Shaders", "Resources", "Data Packs", "All" };

	private static final String[] SORT_KEYS = { "downloads", "relevance", "updated", "newest" };
	private static final String[] SORT_LABELS = { "Downloads", "Relevance", "Updated", "Newest" };

	private static final String[] MC_KEYS = { "any", "26.3", "1.21" };
	private static final String[] MC_LABELS = { "MC: Any", "MC: 26.3", "MC: 1.21" };

	private final Screen lastScreen;

	// Tab & shared header controls
	private int activeTab = TAB_INSTALLED;
	private @Nullable Button installedTabButton;
	private @Nullable Button modrinthTabButton;
	private @Nullable Button relayStatusButton;

	// Installed tab state
	private @Nullable ModList modList;
	private @Nullable Button toggleButton;
	private @Nullable Button actionButton;
	private @Nullable Button removeButton;
	private @Nullable String transientStatus;
	private final Runnable liveListener = this::onLiveUpdate;
	private int lastModListCount = -1;
	private String lastModListSignature = "";

	// Modrinth Browse tab state (persists across resizes, RelaysScreen, and ModrinthProjectScreen)
	private @Nullable EditBox searchBox;
	private @Nullable Button typeFilterButton;
	private @Nullable Button sortFilterButton;
	private @Nullable Button mcFilterButton;
	private @Nullable ModrinthList modrinthList;
	private @Nullable Button mrInstallButton;
	private @Nullable Button mrDetailsButton;
	private @Nullable Button mrRefreshButton;

	private String searchQuery = "";
	private int typeIdx;
	private int sortIdx;
	private int mcIdx;
	private final List<ModrinthClient.ProjectHit> browseHits = new ArrayList<>();
	private int browseTotalHits;
	private boolean browseHasMore;
	private boolean browseLoading;
	private boolean browseLoadingMore;
	private boolean browseInitialized;
	private boolean browseHadNoRelayError;
	private String browseStatus = "";
	private int browseStatusColor = COLOR_MUTED;
	private int searchDebounceTicks = -1;
	private boolean mrInstalling;
	private int mrProgressLoaded;
	private int mrProgressTotal;
	private String mrInstallingTitle = "";

	public ModsScreen(final Screen lastScreen, final Options options) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	/** Convenience factory matching {@code Function<Screen, Screen>} title-screen hooks. */
	public static ModsScreen create(final Screen lastScreen) {
		return new ModsScreen(lastScreen, Minecraft.getInstance().options);
	}

	@Override
	protected void init() {
		// Top header bar: centered tab buttons + right-aligned relay pill
		int tabGap = 6;
		int tab1W = 102;
		int tab2W = 114;
		int tabsTotalW = tab1W + tabGap + tab2W;
		int tabsLeft = Math.max(8, (this.width - tabsTotalW) / 2);

		int installedCount = WebMods.listMods().size();
		this.installedTabButton = this.addRenderableWidget(
			Button.builder(Component.literal("Installed (" + installedCount + ")"), b -> this.switchTab(TAB_INSTALLED))
				.bounds(tabsLeft, 6, tab1W, 20).build());
		this.modrinthTabButton = this.addRenderableWidget(
			Button.builder(Component.literal("Browse Modrinth"), b -> this.switchTab(TAB_MODRINTH))
				.bounds(tabsLeft + tab1W + tabGap, 6, tab2W, 20).build());

		int relayW = 86;
		int relayX = Math.max(tabsLeft + tabsTotalW + 6, this.width - relayW - 8);
		if (relayX + relayW <= this.width - 2) {
			this.relayStatusButton = this.addRenderableWidget(
				Button.builder(relayButtonText(), b -> this.minecraft.gui.setScreen(new RelaysScreen(this)))
					.bounds(relayX, 6, relayW, 20).build());
		} else {
			this.relayStatusButton = null;
		}

		// Clear stale tab widget pointers before constructing active tab
		this.modList = null;
		this.toggleButton = null;
		this.actionButton = null;
		this.removeButton = null;
		this.searchBox = null;
		this.typeFilterButton = null;
		this.sortFilterButton = null;
		this.mcFilterButton = null;
		this.modrinthList = null;
		this.mrInstallButton = null;
		this.mrDetailsButton = null;
		this.mrRefreshButton = null;

		if (this.activeTab == TAB_INSTALLED) {
			this.initInstalledTab();
		} else {
			this.initModrinthTab();
		}
		this.updateTabStyles();
	}

	private void initInstalledTab() {
		int listTop = HEADER_H + 2;
		int listH = Math.max(48, this.height - listTop - FOOTER_H_INSTALLED);
		this.modList = new ModList(this.minecraft, this.width, listH, listTop);
		this.addRenderableWidget(this.modList);

		int btnY = this.height - 26;
		int totalW = 64 + 4 + 54 + 4 + 84 + 4 + 54 + 4 + 52;
		int x = (this.width - totalW) / 2;
		this.addRenderableWidget(
			Button.builder(Component.literal("Add Mod..."), b -> WebMods.chooseMod(this::onModAdded))
				.bounds(x, btnY, 64, 20).build());
		this.toggleButton = this.addRenderableWidget(
			Button.builder(Component.literal("Enable"), b -> this.toggleSelected())
				.bounds(x + 68, btnY, 54, 20).build());
		this.actionButton = this.addRenderableWidget(
			Button.builder(Component.literal("Extract Pack"), b -> this.onActionButton())
				.bounds(x + 126, btnY, 84, 20).build());
		this.removeButton = this.addRenderableWidget(
			Button.builder(Component.literal("Remove"), b -> this.removeSelected())
				.bounds(x + 214, btnY, 54, 20).build());
		this.addRenderableWidget(
			Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
				.bounds(x + 272, btnY, 52, 20).build());

		this.updateButtons();
	}

	private void initModrinthTab() {
		int barW = Math.min(this.width - 16, 444);
		int barL = (this.width - barW) / 2;
		int barY = HEADER_H + 3;

		int typeW = 68;
		int sortW = 68;
		int mcW = 58;
		int gap = 4;
		int searchW = Math.max(80, barW - (typeW + sortW + mcW + gap * 3));

		this.searchBox = new EditBox(this.font, barL, barY, searchW, 20, Component.literal("Search Modrinth"));
		this.searchBox.setMaxLength(120);
		this.searchBox.setValue(this.searchQuery);
		this.searchBox.setHint(Component.literal("Search Modrinth mods, shaders...").withColor(COLOR_DIM));
		this.searchBox.setResponder(this::onSearchInputChanged);
		this.addRenderableWidget(this.searchBox);

		int bx = barL + searchW + gap;
		this.typeFilterButton = this.addRenderableWidget(
			Button.builder(Component.literal(TYPE_LABELS[this.typeIdx]), b -> this.cycleType())
				.bounds(bx, barY, typeW, 20).build());
		bx += typeW + gap;
		this.sortFilterButton = this.addRenderableWidget(
			Button.builder(Component.literal(SORT_LABELS[this.sortIdx]), b -> this.cycleSort())
				.bounds(bx, barY, sortW, 20).build());
		bx += sortW + gap;
		this.mcFilterButton = this.addRenderableWidget(
			Button.builder(Component.literal(MC_LABELS[this.mcIdx]), b -> this.cycleMcVersion())
				.bounds(bx, barY, mcW, 20).build());

		int listTop = HEADER_H + TOOLBAR_H + 2;
		int listH = Math.max(48, this.height - listTop - FOOTER_H_MODRINTH);
		this.modrinthList = new ModrinthList(this.minecraft, this.width, listH, listTop);
		this.addRenderableWidget(this.modrinthList);

		int btnY = this.height - 26;
		int totalW = 78 + 4 + 76 + 4 + 64 + 4 + 60;
		int x = (this.width - totalW) / 2;
		this.mrInstallButton = this.addRenderableWidget(
			Button.builder(Component.literal("Install"), b -> this.quickInstallSelected())
				.bounds(x, btnY, 78, 20).build());
		this.mrDetailsButton = this.addRenderableWidget(
			Button.builder(Component.literal("Details..."), b -> this.openSelectedDetails())
				.bounds(x + 82, btnY, 76, 20).build());
		this.mrRefreshButton = this.addRenderableWidget(
			Button.builder(Component.literal("Refresh"), b -> this.startModrinthSearch(false))
				.bounds(x + 162, btnY, 64, 20).build());
		this.addRenderableWidget(
			Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
				.bounds(x + 230, btnY, 60, 20).build());

		if (!this.browseInitialized) {
			this.browseInitialized = true;
			this.startModrinthSearch(false);
		} else if (this.browseHadNoRelayError && ModrinthClient.relayInfo().relayCount > 0) {
			this.browseHadNoRelayError = false;
			this.startModrinthSearch(false);
		} else {
			this.modrinthList.rebuildFromState();
		}
		this.updateModrinthButtons();
	}

	private void switchTab(final int tab) {
		if (this.activeTab == tab) {
			return;
		}
		this.activeTab = tab;
		this.rebuildWidgets();
	}

	private void updateTabStyles() {
		int count = WebMods.listMods().size();
		if (this.installedTabButton != null) {
			Component label = Component.literal("Installed (" + count + ")");
			this.installedTabButton.setMessage(this.activeTab == TAB_INSTALLED ? label.copy().withColor(COLOR_ENABLED) : label);
		}
		if (this.modrinthTabButton != null) {
			Component label = Component.literal("Browse Modrinth");
			this.modrinthTabButton.setMessage(this.activeTab == TAB_MODRINTH ? label.copy().withColor(COLOR_ENABLED) : label);
		}
		if (this.relayStatusButton != null) {
			this.relayStatusButton.setMessage(relayButtonText());
		}
	}

	private static Component relayButtonText() {
		ModrinthClient.RelayInfo ri = ModrinthClient.relayInfo();
		if (ri.relayCount <= 0) {
			return Component.literal("No Relay").withColor(COLOR_BUILDING);
		}
		String label = ri.badgeText();
		if (label.length() > 13) {
			label = ri.relayLabel.isEmpty() ? "Relay" : ri.relayLabel;
		}
		return Component.literal(label).withColor(COLOR_ENABLED);
	}

	@Override
	public void added() {
		super.added();
		WebMods.setOnChanged(this.liveListener);
		WebMods.probeBuilderIfNeeded();
		this.updateTabStyles();
		if (this.activeTab == TAB_INSTALLED) {
			this.updateButtons();
		} else {
			if (this.browseHadNoRelayError && ModrinthClient.relayInfo().relayCount > 0) {
				this.browseHadNoRelayError = false;
				this.startModrinthSearch(false);
			}
			this.updateModrinthButtons();
		}
	}

	@Override
	public void removed() {
		WebMods.clearOnChanged(this.liveListener);
		super.removed();
	}

	@Override
	public void tick() {
		super.tick();
		if (this.activeTab == TAB_MODRINTH && this.searchDebounceTicks > 0) {
			this.searchDebounceTicks--;
			if (this.searchDebounceTicks == 0) {
				this.searchDebounceTicks = -1;
				this.startModrinthSearch(false);
			}
		}
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (this.activeTab == TAB_MODRINTH && this.searchBox != null && this.getFocused() == this.searchBox && event.isConfirmation()) {
			this.searchDebounceTicks = -1;
			this.startModrinthSearch(false);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		WebMods.clearOnChanged(this.liveListener);
		this.minecraft.gui.setScreen(this.lastScreen);
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);

		// Crisp Minecraft active-tab underline highlight
		Button activeTabBtn = this.activeTab == TAB_INSTALLED ? this.installedTabButton : this.modrinthTabButton;
		if (activeTabBtn != null) {
			int ux = activeTabBtn.getX() + 2;
			int uy = activeTabBtn.getY() + activeTabBtn.getHeight() + 1;
			int uw = activeTabBtn.getWidth() - 4;
			graphics.fill(ux, uy, ux + uw, uy + 2, COLOR_ENABLED);
		}

		// Footer status / progress rendering
		int maxStatusW = Math.min(this.width - 20, 444);
		if (this.activeTab == TAB_INSTALLED) {
			Component st = this.statusText();
			String raw = st.getString();
			int color = st.getStyle().getColor() != null ? (0xFF000000 | st.getStyle().getColor().getValue()) : COLOR_MUTED;
			graphics.centeredText(this.font, fitText(raw, maxStatusW), this.width / 2, this.height - 44, color);
		} else {
			if (this.mrInstalling) {
				int barW = Math.min(maxStatusW - 24, 300);
				int barX = (this.width - barW) / 2;
				int barY = this.height - 35;
				graphics.fill(barX, barY, barX + barW, barY + 5, 0xFF202020);
				graphics.outline(barX, barY, barW, 5, 0xFF555555);
				if (this.mrProgressTotal > 0) {
					int fillW = (int) Math.min(barW - 2, ((long) this.mrProgressLoaded * (barW - 2)) / this.mrProgressTotal);
					if (fillW > 0) {
						graphics.fill(barX + 1, barY + 1, barX + 1 + fillW, barY + 4, COLOR_ENABLED);
					}
				} else {
					int pulse = (int) ((System.currentTimeMillis() / 12L) % Math.max(1, barW - 40));
					int segW = Math.min(barW - 2, 36);
					graphics.fill(barX + 1 + pulse, barY + 1, Math.min(barX + barW - 1, barX + 1 + pulse + segW), barY + 4, COLOR_ENABLED);
				}
				graphics.centeredText(this.font, fitText(this.browseStatus, maxStatusW), this.width / 2, this.height - 47, this.browseStatusColor);
			} else if (!this.browseStatus.isEmpty()) {
				graphics.centeredText(this.font, fitText(this.browseStatus, maxStatusW), this.width / 2, this.height - 43, this.browseStatusColor);
			}
		}
	}

	// ---- Modrinth Browse Actions ----

	private void onSearchInputChanged(final String value) {
		String trimmed = value == null ? "" : value;
		if (trimmed.equals(this.searchQuery)) {
			return;
		}
		this.searchQuery = trimmed;
		// When user types a query and sort is still Downloads, auto-bias to Relevance only if empty -> non-empty?
		// Keep explicit user sort control predictable; just debounce 9 ticks (~450ms).
		this.searchDebounceTicks = 9;
	}

	private void cycleType() {
		this.typeIdx = (this.typeIdx + 1) % TYPE_KEYS.length;
		if (this.typeFilterButton != null) {
			this.typeFilterButton.setMessage(Component.literal(TYPE_LABELS[this.typeIdx]));
		}
		this.startModrinthSearch(false);
	}

	private void cycleSort() {
		this.sortIdx = (this.sortIdx + 1) % SORT_KEYS.length;
		if (this.sortFilterButton != null) {
			this.sortFilterButton.setMessage(Component.literal(SORT_LABELS[this.sortIdx]));
		}
		this.startModrinthSearch(false);
	}

	private void cycleMcVersion() {
		this.mcIdx = (this.mcIdx + 1) % MC_KEYS.length;
		if (this.mcFilterButton != null) {
			this.mcFilterButton.setMessage(Component.literal(MC_LABELS[this.mcIdx]));
		}
		this.startModrinthSearch(false);
	}

	private void startModrinthSearch(final boolean loadMore) {
		if (loadMore && (this.browseLoading || this.browseLoadingMore || !this.browseHasMore)) {
			return;
		}
		int offset = loadMore ? this.browseHits.size() : 0;
		int gen = ModrinthClient.nextSearchGeneration();
		this.searchDebounceTicks = -1;
		this.browseHadNoRelayError = false;

		if (loadMore) {
			this.browseLoadingMore = true;
			this.browseStatus = "Loading more results via Relay...";
			this.browseStatusColor = COLOR_MUTED;
		} else {
			this.browseLoading = true;
			this.browseLoadingMore = false;
			this.browseHits.clear();
			this.browseTotalHits = 0;
			this.browseHasMore = false;
			this.browseStatus = "Searching Modrinth via Relay...";
			this.browseStatusColor = COLOR_MUTED;
			if (this.modrinthList != null) {
				this.modrinthList.rebuildFromState();
			}
		}
		this.updateModrinthButtons();

		String q = this.searchQuery.trim();
		String sortKey = SORT_KEYS[this.sortIdx];
		// When user types a query while sort is default Downloads, Relevance still makes sense if chosen; honor chosen sort.
		ModrinthClient.search(
			gen,
			q,
			TYPE_KEYS[this.typeIdx],
			MC_KEYS[this.mcIdx],
			sortKey,
			offset,
			PAGE_SIZE,
			res -> {
				this.browseLoading = false;
				this.browseLoadingMore = false;
				if (!loadMore) {
					this.browseHits.clear();
				}
				this.browseHits.addAll(res.hits);
				this.browseTotalHits = res.totalHits;
				this.browseHasMore = res.hasMore();

				String via = res.relayLabel.isEmpty()
					? "Relay"
					: (res.relayLabel + ("websocket".equals(res.transport) ? " (WS)" : ("webrtc".equals(res.transport) ? " (RTC)" : "")));
				if (this.browseHits.isEmpty()) {
					this.browseStatus = "No Modrinth results matched your filter (" + via + ")";
					this.browseStatusColor = COLOR_BUILDING;
				} else {
					this.browseStatus = "Showing " + this.browseHits.size() + " of "
						+ ModrinthClient.formatCount(this.browseTotalHits) + " results via " + via;
					this.browseStatusColor = COLOR_MUTED;
				}
				if (this.modrinthList != null) {
					this.modrinthList.rebuildFromState();
				}
				this.updateTabStyles();
				this.updateModrinthButtons();
			},
			err -> {
				this.browseLoading = false;
				this.browseLoadingMore = false;
				this.browseHadNoRelayError = err.contains("No Relay");
				this.browseStatus = err;
				this.browseStatusColor = this.browseHadNoRelayError ? COLOR_BUILDING : COLOR_ERROR;
				if (this.modrinthList != null) {
					this.modrinthList.rebuildFromState();
				}
				this.updateTabStyles();
				this.updateModrinthButtons();
			});
	}

	private ModrinthClient.@Nullable ProjectHit selectedHit() {
		if (this.modrinthList == null || this.modrinthList.getSelected() == null) {
			return null;
		}
		return this.modrinthList.getSelected().hit;
	}

	private void openSelectedDetails() {
		ModrinthClient.ProjectHit hit = this.selectedHit();
		if (hit == null) {
			return;
		}
		this.minecraft.gui.setScreen(new ModrinthProjectScreen(this, hit));
	}

	private void quickInstallSelected() {
		if (this.mrInstalling) {
			return;
		}
		ModrinthClient.ProjectHit hit = this.selectedHit();
		if (hit == null) {
			return;
		}
		this.mrInstalling = true;
		this.mrProgressLoaded = 0;
		this.mrProgressTotal = 0;
		this.mrInstallingTitle = hit.title.isEmpty() ? hit.slug : hit.title;
		this.browseStatus = "Resolving latest version for " + this.mrInstallingTitle + " via Relay...";
		this.browseStatusColor = COLOR_READY;
		this.updateModrinthButtons();

		ModrinthClient.quickInstall(
			hit,
			(label, step, totalSteps, loaded, total) -> {
				this.mrProgressLoaded = loaded;
				this.mrProgressTotal = total;
				String stepTag = totalSteps > 1 ? ("[" + step + "/" + totalSteps + "] ") : "";
				if (step == 0) {
					this.browseStatus = label;
				} else if (total > 0) {
					int pct = (int) Math.min(100L, (loaded * 100L) / total);
					this.browseStatus = stepTag + "Downloading " + label + " (" + pct + "% - " + formatBytes(loaded) + ")";
				} else {
					this.browseStatus = stepTag + "Downloading " + label + " (" + formatBytes(loaded) + ")";
				}
			},
			(ok, msg) -> {
				this.mrInstalling = false;
				this.mrProgressLoaded = 0;
				this.mrProgressTotal = 0;
				this.browseStatus = msg;
				this.browseStatusColor = ok ? COLOR_ENABLED : COLOR_ERROR;
				this.updateTabStyles();
				this.updateModrinthButtons();
			});
	}

	private void updateModrinthButtons() {
		ModrinthClient.ProjectHit hit = this.selectedHit();
		if (this.mrInstallButton != null) {
			if (this.mrInstalling) {
				this.mrInstallButton.setMessage(Component.literal("Installing...").withColor(COLOR_BUILDING));
				this.mrInstallButton.active = false;
			} else if (hit != null) {
				boolean installed = ModrinthClient.isInstalled(hit);
				this.mrInstallButton.setMessage(installed
					? Component.literal("Reinstall").withColor(COLOR_READY)
					: Component.literal("Install").withColor(COLOR_ENABLED));
				this.mrInstallButton.active = true;
			} else {
				this.mrInstallButton.setMessage(Component.literal("Install"));
				this.mrInstallButton.active = false;
			}
		}
		if (this.mrDetailsButton != null) {
			this.mrDetailsButton.active = hit != null && !this.mrInstalling;
		}
		if (this.mrRefreshButton != null) {
			this.mrRefreshButton.active = !this.browseLoading && !this.mrInstalling;
		}
	}

	// ---- Installed Mods Actions (preserved from original ModsScreen) ----

	private WebMods.@Nullable InstalledMod selectedMod() {
		if (this.modList == null || this.modList.getSelected() == null) {
			return null;
		}
		return this.modList.getSelected().mod;
	}

	private void onModAdded() {
		this.transientStatus = WebMods.status();
		this.refreshInstalled(null);
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
		this.updateTabStyles();
		this.updateButtons();
	}

	private void refreshInstalled(final @Nullable String keepFile) {
		if (this.modList != null) {
			this.modList.reload(keepFile);
		}
		this.updateTabStyles();
		this.updateButtons();
	}

	private void toggleSelected() {
		WebMods.InstalledMod mod = this.selectedMod();
		if (mod == null || !mod.isToggleable()) {
			return;
		}
		WebMods.setEnabled(mod.file, !mod.enabled);
		this.transientStatus = WebMods.status();
		this.refreshInstalled(mod.file);
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
	}

	private void extractSelected() {
		WebMods.InstalledMod mod = this.selectedMod();
		if (mod == null || !mod.canExtractPack()) {
			return;
		}
		WebMods.extractPack(mod.file);
		this.transientStatus = WebMods.status();
		this.refreshInstalled(mod.file);
	}

	private void removeSelected() {
		WebMods.InstalledMod mod = this.selectedMod();
		if (mod == null) {
			return;
		}
		WebMods.remove(mod.file);
		this.transientStatus = WebMods.status();
		this.refreshInstalled(null);
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

	private String fitText(final String text, final int maxWidth) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		if (this.font.width(text) <= maxWidth) {
			return text;
		}
		int ellipsisW = this.font.width("...");
		if (maxWidth <= ellipsisW) {
			return "";
		}
		return this.font.plainSubstrByWidth(text, maxWidth - ellipsisW) + "...";
	}

	private static String formatBytes(final int bytes) {
		if (bytes >= 1024 * 1024) {
			int tenths = (int) ((bytes * 10L) / (1024L * 1024L));
			return (tenths / 10) + "." + (tenths % 10) + " MB";
		}
		return Math.max(1, bytes / 1024) + " KB";
	}

	// ---- Modrinth Results List (32x32 thumbnails, titles, authors, descriptions, badges, Load More) ----

	private final class ModrinthList extends ObjectSelectionList<ModrinthList.Entry> {
		ModrinthList(final Minecraft minecraft, final int width, final int height, final int y) {
			super(minecraft, width, height, y, 42);
		}

		void rebuildFromState() {
			ModrinthClient.ProjectHit prev = ModsScreen.this.selectedHit();
			String prevId = prev == null ? null : prev.id;
			double prevScroll = this.scrollAmount();
			this.clearEntries();

			if (ModsScreen.this.browseLoading && ModsScreen.this.browseHits.isEmpty()) {
				this.addEntry(new Entry(null, "Connecting to Modrinth through Relay...", COLOR_MUTED, false));
				this.setSelected(null);
				return;
			}
			if (ModsScreen.this.browseHits.isEmpty()) {
				if (ModsScreen.this.browseHadNoRelayError) {
					this.addEntry(new Entry(null, "No Relay configured - click 'No Relay' in the top-right to add one", COLOR_BUILDING, false));
				} else if (!ModsScreen.this.browseStatus.isEmpty()) {
					this.addEntry(new Entry(null, ModsScreen.this.browseStatus, ModsScreen.this.browseStatusColor, false));
				} else {
					this.addEntry(new Entry(null, "No projects found. Try another search term or filter.", COLOR_MUTED, false));
				}
				this.setSelected(null);
				return;
			}

			Entry pick = null;
			for (ModrinthClient.ProjectHit hit : ModsScreen.this.browseHits) {
				Entry e = new Entry(hit, null, COLOR_WHITE, false);
				this.addEntry(e);
				if (prevId != null && prevId.equals(hit.id)) {
					pick = e;
				}
			}
			if (ModsScreen.this.browseHasMore) {
				String moreLabel = ModsScreen.this.browseLoadingMore
					? "Loading more results via Relay..."
					: ("Load More Results (" + ModsScreen.this.browseHits.size() + " / "
						+ ModrinthClient.formatCount(ModsScreen.this.browseTotalHits) + ")");
				this.addEntry(new Entry(null, moreLabel, COLOR_READY, true));
			}
			this.setSelected(pick != null ? pick : this.children().get(0));
			if (prevScroll > 0.0) {
				this.setScrollAmount(prevScroll);
			}
		}

		@Override
		public void setSelected(final @Nullable Entry selected) {
			if (selected != null && selected.hit == null) {
				super.setSelected(null);
			} else {
				super.setSelected(selected);
			}
			ModsScreen.this.updateModrinthButtons();
		}

		@Override
		public int getRowWidth() {
			return Math.min(ModsScreen.this.width - 20, 444);
		}

		final class Entry extends ObjectSelectionList.Entry<Entry> {
			final ModrinthClient.@Nullable ProjectHit hit;
			final @Nullable String message;
			final int messageColor;
			final boolean isLoadMore;

			Entry(final ModrinthClient.@Nullable ProjectHit hit, final @Nullable String message, final int messageColor, final boolean isLoadMore) {
				this.hit = hit;
				this.message = message;
				this.messageColor = messageColor;
				this.isLoadMore = isLoadMore;
			}

			@Override
			public void extractContent(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final boolean hovered, final float a) {
				int x = this.getContentX();
				int y = this.getContentY();
				int w = this.getContentWidth();
				int h = this.getContentHeight();

				if (this.hit == null) {
					if (this.isLoadMore) {
						graphics.fill(x + 2, y + 4, x + w - 2, y + h - 4, hovered ? 0x55336644 : 0x441A2A20);
						graphics.outline(x + 2, y + 4, w - 4, h - 8, hovered ? COLOR_ENABLED : 0xFF3A5A40);
					}
					graphics.centeredText(
						ModsScreen.this.font,
						ModsScreen.this.fitText(this.message == null ? "" : this.message, w - 12),
						x + w / 2,
						this.getContentYMiddle() - 4,
						this.messageColor);
					return;
				}

				// Subtle Minecraft card backing
				graphics.fill(x, y, x + w, y + h, hovered ? 0x36FFFFFF : 0x26000000);

				// Left 32x32 thumbnail slot with crisp 1px border
				int thumbX = x + 3;
				int thumbY = y + Math.max(2, (h - 32) / 2);
				int borderColor = (hovered || ModrinthList.this.getSelected() == this) ? 0xFF707070 : 0xFF383838;
				graphics.fill(thumbX - 1, thumbY - 1, thumbX + 33, thumbY + 33, borderColor);
				graphics.fill(thumbX, thumbY, thumbX + 32, thumbY + 32, 0xFF181818);

				Identifier tex = ModrinthClient.getOrRequestThumbnail(this.hit.iconUrl, null);
				if (tex != null) {
					graphics.blit(
						RenderPipelines.GUI_TEXTURED, tex,
						thumbX, thumbY,
						0.0F, 0.0F,
						32, 32,
						ModrinthClient.THUMB_SIZE, ModrinthClient.THUMB_SIZE,
						ModrinthClient.THUMB_SIZE, ModrinthClient.THUMB_SIZE);
				} else {
					graphics.fill(thumbX, thumbY, thumbX + 32, thumbY + 32, this.hit.accentColor());
					graphics.centeredText(ModsScreen.this.font, this.hit.monogram(), thumbX + 16, thumbY + 12, COLOR_WHITE);
				}

				// Content columns to the right of the thumbnail
				int tx = thumbX + 38;
				int rightEdge = x + w - 4;
				int textW = Math.max(40, rightEdge - tx);

				// Line 1 right side: [Installed] [Type] <downloads> DL
				int rx = rightEdge;
				boolean installed = ModrinthClient.isInstalled(this.hit);
				if (installed) {
					String inst = "[Installed]";
					int iw = ModsScreen.this.font.width(inst);
					rx -= iw;
					graphics.text(ModsScreen.this.font, inst, rx, y + 3, COLOR_ENABLED);
					rx -= 4;
				}
				String typeBadge = this.hit.typeBadge();
				int tbw = ModsScreen.this.font.width(typeBadge);
				rx -= tbw;
				graphics.text(ModsScreen.this.font, typeBadge, rx, y + 3, this.hit.typeBadgeColor());
				rx -= 5;

				String dlStr = this.hit.formattedDownloads() + " DL";
				int dlw = ModsScreen.this.font.width(dlStr);
				if (rx - dlw > tx + 56) {
					rx -= dlw;
					graphics.text(ModsScreen.this.font, dlStr, rx, y + 3, COLOR_LIGHT);
					rx -= 6;
				}

				// Line 1 left side: bright title + muted "by <author>"
				int maxTitleW = Math.max(24, rx - tx);
				String titleStr = this.hit.title.isEmpty() ? this.hit.slug : this.hit.title;
				int tw = ModsScreen.this.font.width(titleStr);
				if (tw <= maxTitleW) {
					graphics.text(ModsScreen.this.font, titleStr, tx, y + 3, COLOR_WHITE);
					if (!this.hit.author.isEmpty() && maxTitleW - tw > 26) {
						String by = " by " + this.hit.author;
						graphics.text(ModsScreen.this.font, ModsScreen.this.fitText(by, maxTitleW - tw), tx + tw, y + 3, COLOR_MUTED);
					}
				} else {
					graphics.text(ModsScreen.this.font, ModsScreen.this.fitText(titleStr, maxTitleW), tx, y + 3, COLOR_WHITE);
				}

				// Line 2: concise single-line description
				String desc = this.hit.description.replace('\r', ' ').replace('\n', ' ').trim();
				graphics.text(ModsScreen.this.font, ModsScreen.this.fitText(desc, textW), tx, y + 15, COLOR_LIGHT);

				// Line 3: categories + latest MC version + client tag
				String meta = this.hit.metaSummary();
				if (!meta.isEmpty()) {
					graphics.text(ModsScreen.this.font, ModsScreen.this.fitText(meta, textW), tx, y + 27, COLOR_DIM);
				}
			}

			@Override
			public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
				if (this.isLoadMore) {
					ModsScreen.this.startModrinthSearch(true);
					return true;
				}
				if (this.hit == null) {
					return false;
				}
				ModrinthList.this.setSelected(this);
				if (doubleClick) {
					ModsScreen.this.openSelectedDetails();
				}
				return super.mouseClicked(event, doubleClick);
			}

			@Override
			public Component getNarration() {
				if (this.hit == null) {
					return Component.literal(this.message == null ? "" : this.message);
				}
				return Component.translatable("narrator.select", Component.literal(this.hit.title + " " + this.hit.typeBadge()));
			}
		}
	}

	// ---- Installed Mods List (preserved) ----

	private final class ModList extends ObjectSelectionList<ModList.Entry> {
		ModList(final Minecraft minecraft, final int width, final int height, final int y) {
			super(minecraft, width, height, y, 28);
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
		}

		@Override
		public int getRowWidth() {
			return Math.min(ModsScreen.this.width - 20, 444);
		}

		final class Entry extends ObjectSelectionList.Entry<Entry> {
			final WebMods.@Nullable InstalledMod mod;

			Entry(final WebMods.@Nullable InstalledMod mod) {
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

				int maxTitleW = Math.max(24, badgeX - (x + 2 + stateW) - 6);
				String title = ModsScreen.this.fitText(info.displayTitle(), maxTitleW);
				graphics.text(ModsScreen.this.font, title, x + 2 + stateW, y + 2, this.mod.enabled ? COLOR_WHITE : COLOR_DISABLED);

				graphics.text(ModsScreen.this.font, badge, badgeX, y + 2, badgeColor);

				String subtitle = buildSubtitle(this.mod);
				boolean hasErr = (!this.mod.runtimeError.isEmpty() && !this.mod.jvmTranslated)
					|| (!WebMods.modBuildError(this.mod.file).isEmpty() && !this.mod.jvmTranslated);
				int subColor = hasErr ? COLOR_ERROR : COLOR_MUTED;
				graphics.text(ModsScreen.this.font, ModsScreen.this.fitText(subtitle, Math.max(24, w - 4)), x + 2, y + 14, subColor);
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
