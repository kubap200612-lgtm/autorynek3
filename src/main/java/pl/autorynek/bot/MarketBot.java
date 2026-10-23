package pl.autorynek.bot;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import pl.autorynek.data.AutoRynekConfig;
import pl.autorynek.data.AutoRynekConfig.Settings;
import pl.autorynek.data.Stats;
import pl.autorynek.data.WatchedItem;
import pl.autorynek.ui.ScreenHooks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maszyna stanow bota. Dziala w kazdym ticku klienta (20 razy/s).
 * Tryby: ANALYSIS (liczenie cen skupu) -> BUYING (petla odswiezania i kupowania).
 * Oba tryby przegladaja kolejne strony rynku (zielony przycisk "Nastepna strona") i licza cene ZA SZTUKE.
 */
public class MarketBot {
    public enum Mode { OFF, ANALYSIS, BUYING }

    private enum Step { OPEN, WAIT_SCREEN, SETTLE, SORT_PLAN, SORT_CLICK, SORT_CHECK, SCAN, PAGE_WAIT, WAIT_CONFIRM }

    /** Oferta w oknie rynku: slot, cena calkowita, liczba sztuk i cena za sztuke. */
    private record Offer(int slot, long total, int count, double unit) {}

    private static final Pattern PAGE_PATTERN = Pattern.compile("\\((\\d+)\\s*/\\s*(\\d+)\\)");

    private final AutoRynekConfig cfg;

    private Mode mode = Mode.OFF;
    private Step step = Step.OPEN;
    private boolean analysisOnly;

    private int delay;
    private int waitTicks;
    private int analysisIndex;
    private int buyIndex;
    private int refreshCount;
    private int baseSyncId;
    private long cycleStartNs;
    private long clickNs;
    private long lastAnalysisMs;
    private long lastAfkMs = System.currentTimeMillis();

    private WatchedItem current;
    private WatchedItem pendingItem;
    private long pendingPrice;
    private int pendingCount = 1;
    private boolean awaitingResult;
    private int pendingTicks;

    private boolean afkActive;
    private int afkTicks;

    private int sortSlot = -1;
    private int sortClicksLeft;
    private int sortClicksDone;

    // przegladanie stron
    private final List<Offer> collected = new ArrayList<>();
    private int pagesScanned;
    private int expectedPage = -1;
    private String pageSignature = "";

    public MarketBot(AutoRynekConfig cfg) {
        this.cfg = cfg;
    }

    // ------------------------------------------------------------------ API

    public Mode getMode() {
        return mode;
    }

    public boolean isRunning() {
        return mode != Mode.OFF;
    }

    public void start(boolean analysisOnly) {
        if (cfg.watchlist.isEmpty()) {
            stats().log("Lista pusta - dodaj przedmioty klawiszem L");
            return;
        }
        this.analysisOnly = analysisOnly;
        mode = Mode.ANALYSIS;
        analysisIndex = 0;
        step = Step.OPEN;
        delay = 0;
        waitTicks = 0;
        afkActive = false;
        lastAfkMs = System.currentTimeMillis();
        stats().log(analysisOnly ? "Start: tylko analiza rynku" : "Start: analiza + skupowanie");
    }

    public void stop(String reason) {
        if (mode != Mode.OFF) stats().log("Stop: " + reason);
        mode = Mode.OFF;
        afkActive = false;
        awaitingResult = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null) mc.options.jumpKey.setPressed(false);
    }

    /** Reset rynku: ponowne otwarcie /ah search od zera. */
    public void resetMarket() {
        if (mode == Mode.OFF) return;
        step = Step.OPEN;
        delay = 0;
        waitTicks = 0;
        stats().log("Reset rynku (reczny)");
    }

    /** Wywolywane dla kazdej wiadomosci systemowej z serwera. */
    public void onGameMessage(String message) {
        if (!awaitingResult) return;
        String lower = message.toLowerCase(Locale.ROOT);
        for (String kw : cfg.settings.successKeywords) {
            if (!kw.isBlank() && lower.contains(kw.toLowerCase(Locale.ROOT))) {
                recordPurchase();
                return;
            }
        }
    }

    // ----------------------------------------------------------------- tick

    public void tick(MinecraftClient mc) {
        if (mode == Mode.OFF) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            stop("rozlaczono z serwerem");
            return;
        }
        if (tickAfk(mc)) return;
        if (awaitingResult && ++pendingTicks > 60) {
            awaitingResult = false;
            stats().log("Brak potwierdzenia zakupu");
        }
        if (delay > 0) {
            delay--;
            return;
        }
        if (mode == Mode.ANALYSIS) tickAnalysis(mc);
        else tickBuying(mc);
    }

    // ------------------------------------------------------------- anty-AFK

    /** @return true gdy anty-AFK aktualnie przejmuje sterowanie */
    private boolean tickAfk(MinecraftClient mc) {
        if (!afkActive) {
            long intervalMs = cfg.settings.afkIntervalSeconds * 1000L;
            if (System.currentTimeMillis() - lastAfkMs < intervalMs) return false;
            if (step != Step.OPEN) return false; // czekamy na bezpieczny moment miedzy cyklami
            afkActive = true;
            afkTicks = 0;
            if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
            stats().log("Anty-AFK: wyjscie z rynku + skok");
        }
        afkTicks++;
        if (afkTicks == 6 || afkTicks == 14) mc.options.jumpKey.setPressed(true);
        if (afkTicks == 9 || afkTicks == 17) mc.options.jumpKey.setPressed(false);
        if (afkTicks >= 30) {
            afkActive = false;
            lastAfkMs = System.currentTimeMillis();
            return false;
        }
        return true;
    }

    // --------------------------------------------------- wspolne: otwarcie GUI

    /** Otwiera /ah search, czeka na GUI i sortuje. @return true gdy gotowe do skanowania (step >= SCAN). */
    private boolean prepare(MinecraftClient mc, WatchedItem w) {
        Settings s = cfg.settings;
        switch (step) {
            case OPEN -> {
                baseSyncId = mc.player.currentScreenHandler.syncId;
                cycleStartNs = System.nanoTime();
                if (mode == Mode.BUYING) stats().marketResets++;
                collected.clear();
                pagesScanned = 0;
                expectedPage = -1;
                mc.getNetworkHandler().sendChatCommand("ah search " + w.searchQuery);
                waitTicks = 0;
                delay = s.commandCooldownTicks;
                step = Step.WAIT_SCREEN;
            }
            case WAIT_SCREEN -> {
                GenericContainerScreenHandler h = container(mc, false);
                if (h != null && h.syncId != baseSyncId) {
                    waitTicks = s.settleTicks;
                    step = Step.SETTLE;
                } else if (++waitTicks > 100) {
                    stats().log("Brak odpowiedzi na /ah search");
                    waitTicks = 0;
                    delay = 40;
                    step = Step.OPEN;
                }
            }
            case SETTLE -> {
                if (--waitTicks <= 0) {
                    waitTicks = 0;
                    step = s.sortEnabled ? Step.SORT_PLAN : Step.SCAN;
                }
            }
            case SORT_PLAN -> {
                GenericContainerScreenHandler h = container(mc, true);
                if (h == null) {
                    if (++waitTicks > 40) {
                        waitTicks = 0;
                        step = Step.OPEN;
                    }
                } else {
                    sortSlot = findSortSlot(h);
                    if (sortSlot < 0) {
                        if (++waitTicks > 10) {
                            stats().log("Brak przycisku sortowania (" + s.sortItemId + ")");
                            waitTicks = 0;
                            step = Step.SCAN;
                        }
                    } else {
                        sortClicksLeft = clicksNeeded(h.getSlot(sortSlot).getStack());
                        sortClicksDone = 0;
                        waitTicks = 0;
                        step = Step.SORT_CLICK;
                    }
                }
            }
            case SORT_CLICK -> {
                GenericContainerScreenHandler h = container(mc, true);
                if (h == null) {
                    if (++waitTicks > 40) {
                        waitTicks = 0;
                        step = Step.OPEN;
                    }
                } else if (sortClicksLeft > 0) {
                    waitTicks = 0;
                    click(mc, sortSlot, 1); // PRAWY przycisk myszy przelacza tryb sortowania
                    sortClicksLeft--;
                    sortClicksDone++;
                    delay = s.sortClickDelayTicks;
                } else {
                    waitTicks = 0;
                    delay = s.settleTicks + 2;
                    step = Step.SORT_CHECK;
                }
            }
            case SORT_CHECK -> {
                GenericContainerScreenHandler h = container(mc, true);
                if (h != null && sortSlot >= 0 && sortSlot < h.slots.size()) {
                    int sel = selectedSortOption(h.getSlot(sortSlot).getStack());
                    if (sel >= 0) {
                        stats().log("Sortowanie: " + s.sortOptions.get(sel));
                    } else {
                        stats().log("Sortowanie: " + sortClicksDone + "x PPM (nie odczytano trybu)");
                    }
                }
                waitTicks = 0;
                step = Step.SCAN;
            }
            default -> {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- analiza

    private void tickAnalysis(MinecraftClient mc) {
        if (analysisIndex >= cfg.watchlist.size()) {
            finishAnalysis(mc);
            return;
        }
        WatchedItem w = cfg.watchlist.get(analysisIndex);
        if (!prepare(mc, w)) return;
        switch (step) {
            case SCAN -> analysisScan(mc, w);
            case PAGE_WAIT -> {
                int r = waitPageChange(mc);
                if (r == 1) {
                    step = Step.SCAN;
                } else if (r == -1) {
                    stats().log("Brak zmiany strony - koniec przeszukiwania");
                    finishItemAnalysis(mc, w);
                }
            }
            default -> {}
        }
    }

    /** Zbiera oferty z biezacej strony i przechodzi dalej albo konczy analize przedmiotu. */
    private void analysisScan(MinecraftClient mc, WatchedItem w) {
        Settings s = cfg.settings;
        GenericContainerScreenHandler h = container(mc, true);
        if (h == null) {
            if (++waitTicks > 40) {
                waitTicks = 0;
                step = Step.OPEN;
            }
            return;
        }
        waitTicks = 0;
        if (pagesScanned == 0 && analysisIndex == 0) dumpMarket(mc, "autorynek-dump-analiza.txt");

        collectOffers(h, w);
        pagesScanned++;

        int[] pg = pageInfo(mc);
        boolean lastPage = pg != null && pg[0] >= pg[1];
        boolean enough = !s.analysisScanAllPages && collected.size() >= s.analysisOffers;
        int next = findNextPageSlot(h);
        if (!enough && !lastPage && next >= 0 && pagesScanned < s.maxPages) {
            startPageChange(mc, h, next, pg);
        } else {
            finishItemAnalysis(mc, w);
        }
    }

    /** Dodaje do listy wszystkie pasujace oferty z biezacej strony (cena za sztuke = cena / liczba sztuk). */
    private void collectOffers(GenericContainerScreenHandler h, WatchedItem w) {
        Settings s = cfg.settings;
        int last = Math.min(s.lastListingSlot, h.getRows() * 9 - 1);
        for (int i = Math.max(0, s.firstListingSlot); i <= last; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) continue;
            if (s.analysisRequireMatch && !w.key.matches(st)) continue;
            long total = PriceParser.parse(st);
            if (total <= 0) continue;
            int count = Math.max(1, st.getCount());
            collected.add(new Offer(i, total, count, (double) total / count));
        }
    }

    /** Srednia cena za sztuke z analysisOffers najtanszych (za sztuke) ofert ze wszystkich przejrzanych stron. */
    private void finishItemAnalysis(MinecraftClient mc, WatchedItem w) {
        Settings s = cfg.settings;
        w.analyzed = true;
        if (collected.isEmpty()) {
            w.marketPrice = 0;
            w.buyPrice = 0;
            dumpMarket(mc, "autorynek-dump-blad.txt");
            stats().log("Analiza: brak ofert - " + w.label + " (" + pagesScanned + " str.)");
        } else {
            collected.sort(Comparator.comparingDouble(Offer::unit));
            int n = Math.min(Math.max(1, s.analysisOffers), collected.size());
            double sum = 0;
            StringBuilder details = new StringBuilder();
            for (int i = 0; i < n; i++) {
                Offer o = collected.get(i);
                sum += o.unit();
                if (i > 0) details.append(", ");
                details.append(Math.round(o.unit()));
                if (o.count() > 1) details.append("(").append(o.total()).append("/").append(o.count()).append(")");
            }
            w.marketPrice = Math.round(sum / n);
            w.recalc(s.taxMultiplier);
            stats().log("Analiza: " + w.label + " sr. " + w.marketPrice + "/szt z " + n + " ofert ["
                    + details + "], " + pagesScanned + " str. -> skup " + w.buyPrice);
        }
        cfg.save();
        analysisIndex++;
        step = Step.OPEN;
        delay = 10;
    }

    private void finishAnalysis(MinecraftClient mc) {
        if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        lastAnalysisMs = System.currentTimeMillis();
        stats().log("Analiza zakonczona");
        if (analysisOnly) {
            stop("analiza zakonczona");
            return;
        }
        mode = Mode.BUYING;
        step = Step.OPEN;
        buyIndex = 0;
        delay = 10;
    }

    // -------------------------------------------------------------- kupowanie

    private void tickBuying(MinecraftClient mc) {
        Settings s = cfg.settings;
        if (step == Step.OPEN && System.currentTimeMillis() - lastAnalysisMs > s.reanalysisMinutes * 60_000L) {
            stats().log("Ponowna analiza rynku");
            mode = Mode.ANALYSIS;
            analysisOnly = false;
            analysisIndex = 0;
            return;
        }
        if (step == Step.OPEN) {
            List<WatchedItem> active = cfg.watchlist.stream().filter(w -> w.buyPrice > 0).toList();
            if (active.isEmpty()) {
                stop("brak przedmiotow z cena skupu");
                return;
            }
            current = active.get(Math.floorMod(buyIndex++, active.size()));
            refreshCount = 0;
        }
        if (current == null) {
            step = Step.OPEN;
            return;
        }
        if (!prepare(mc, current)) return;
        switch (step) {
            case SCAN -> scanForPurchase(mc);
            case PAGE_WAIT -> {
                int r = waitPageChange(mc);
                if (r == 1) {
                    step = Step.SCAN;
                } else if (r == -1) {
                    stats().log("Brak zmiany strony");
                    step = Step.OPEN;
                }
            }
            case WAIT_CONFIRM -> waitConfirm(mc);
            default -> {}
        }
    }

    private void scanForPurchase(MinecraftClient mc) {
        Settings s = cfg.settings;
        GenericContainerScreenHandler h = container(mc, true);
        if (h == null) {
            if (++waitTicks > 40) {
                waitTicks = 0;
                step = Step.OPEN;
            }
            return;
        }
        waitTicks = 0;
        stats().lastRefreshMs = (System.nanoTime() - cycleStartNs) / 1_000_000L;

        // cena skupu jest ZA SZTUKE - porownujemy z cena oferty / liczba sztuk
        Offer o = findOffer(h, current, current.buyPrice);
        if (o != null) {
            stats().attempts++;
            pendingItem = current;
            pendingPrice = o.total();
            pendingCount = o.count();
            clickNs = System.nanoTime();
            baseSyncId = h.syncId;
            click(mc, o.slot());
            if (s.confirmSlot >= 0) {
                waitTicks = 0;
                step = Step.WAIT_CONFIRM;
            } else {
                beginPending();
                step = Step.OPEN;
                delay = 10;
            }
            return;
        }

        // nic na tej stronie - sprawdz kolejne strony
        pagesScanned++;
        int[] pg = pageInfo(mc);
        boolean lastPage = pg != null && pg[0] >= pg[1];
        int next = findNextPageSlot(h);
        if (!lastPage && next >= 0 && pagesScanned < s.buyMaxPages) {
            startPageChange(mc, h, next, pg);
            return;
        }

        // koniec stron - odswiez (po przegladaniu stron zawsze otwieramy rynek od nowa)
        boolean paged = pagesScanned > 1;
        pagesScanned = 0;
        if (!paged && s.refreshSlot >= 0 && ++refreshCount < s.refreshesPerItem) {
            cycleStartNs = System.nanoTime();
            click(mc, s.refreshSlot);
            delay = s.actionDelayTicks;
        } else {
            step = Step.OPEN;
        }
    }

    private void waitConfirm(MinecraftClient mc) {
        GenericContainerScreenHandler h = container(mc, false);
        if (h != null && h.syncId != baseSyncId) {
            stats().lastConfirmMs = (System.nanoTime() - clickNs) / 1_000_000L;
            click(mc, cfg.settings.confirmSlot);
            beginPending();
            step = Step.OPEN;
            delay = 10;
        } else if (++waitTicks > cfg.settings.confirmTimeoutTicks) {
            stats().log("Brak okna potwierdzenia");
            waitTicks = 0;
            step = Step.OPEN;
        }
    }

    private void beginPending() {
        pendingTicks = 0;
        if (cfg.settings.successKeywords.isEmpty()) {
            recordPurchase(); // brak slow kluczowych - liczymy zakup od razu po kliknieciu
        } else {
            awaitingResult = true;
        }
    }

    private void recordPurchase() {
        awaitingResult = false;
        if (pendingItem == null) return;
        Stats st = stats();
        String label = pendingItem.label + (pendingCount > 1 ? " x" + pendingCount : "");
        st.purchases++;
        st.spent += pendingPrice;
        st.expectedRevenue += Math.round(pendingItem.marketPrice * cfg.settings.taxMultiplier) * pendingCount;
        st.history.add(new Stats.HistoryEntry(System.currentTimeMillis(), label, pendingPrice,
                pendingItem.marketPrice * pendingCount));
        while (st.history.size() > 100) st.history.remove(0);
        st.log("KUPIONO " + label + " za " + pendingPrice);
        cfg.save();
    }

    // ------------------------------------------------------------ strony

    /** Klika "Nastepna strona" i zapamietuje stan, po ktorym poznamy, ze strona sie zmienila. */
    private void startPageChange(MinecraftClient mc, GenericContainerScreenHandler h, int nextSlot, int[] pg) {
        pageSignature = signature(h);
        expectedPage = pg != null ? pg[0] + 1 : -1;
        click(mc, nextSlot, 0);
        waitTicks = 0;
        delay = cfg.settings.actionDelayTicks;
        step = Step.PAGE_WAIT;
    }

    /** @return 1 = nowa strona jest juz widoczna, 0 = czekamy, -1 = przekroczono czas */
    private int waitPageChange(MinecraftClient mc) {
        Settings s = cfg.settings;
        GenericContainerScreenHandler h = container(mc, true);
        if (h != null) {
            int[] pg = pageInfo(mc);
            boolean changed = (expectedPage > 0 && pg != null)
                    ? pg[0] >= expectedPage
                    : !signature(h).equals(pageSignature);
            if (changed) {
                waitTicks = 0;
                delay = s.settleTicks + 1; // poczekaj, az dojda przedmioty nowej strony
                return 1;
            }
        }
        if (++waitTicks > s.pageTimeoutTicks) {
            waitTicks = 0;
            return -1;
        }
        return 0;
    }

    /** Numer strony z tytulu okna, np. "Rynek (2/9)" -> {2, 9}. @return null gdy tytul nie ma numeru strony */
    private int[] pageInfo(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof GenericContainerScreen scr)) return null;
        Matcher m = PAGE_PATTERN.matcher(scr.getTitle().getString());
        if (!m.find()) return null;
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Szuka przycisku "Nastepna strona" (zielony barwnik) w dolnym rzedzie okna. */
    private int findNextPageSlot(GenericContainerScreenHandler h) {
        Settings s = cfg.settings;
        int max = h.getRows() * 9;
        if (s.nextPageSlot >= 0) return s.nextPageSlot < max ? s.nextPageSlot : -1;
        String hint = s.nextPageNameContains == null ? "" : s.nextPageNameContains.toLowerCase(Locale.ROOT);
        for (int i = max - 1; i >= Math.max(0, max - 9); i--) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) continue;
            if (!Registries.ITEM.getId(st.getItem()).toString().equals(s.nextPageItemId)) continue;
            if (!hint.isBlank() && !st.getName().getString().toLowerCase(Locale.ROOT).contains(hint)) continue;
            return i;
        }
        return -1;
    }

    /** Odcisk zawartosci strony (do wykrycia zmiany, gdy tytul nie ma numeru strony). */
    private String signature(GenericContainerScreenHandler h) {
        Settings s = cfg.settings;
        StringBuilder sb = new StringBuilder();
        int end = Math.min(s.lastListingSlot + 1, h.getRows() * 9);
        for (int i = Math.max(0, s.firstListingSlot); i < end; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) {
                sb.append('-');
                continue;
            }
            sb.append(Registries.ITEM.getId(st.getItem())).append('x').append(st.getCount())
                    .append('@').append(PriceParser.parse(st)).append(';');
        }
        return sb.toString();
    }

    // --------------------------------------------------------------- helpery

    /** Najtansza (za sztuke) pasujaca oferta na biezacej stronie, nie drozsza niz maxUnit za sztuke. */
    private Offer findOffer(GenericContainerScreenHandler h, WatchedItem w, double maxUnit) {
        Settings s = cfg.settings;
        int last = Math.min(s.lastListingSlot, h.getRows() * 9 - 1);
        Offer best = null;
        for (int i = Math.max(0, s.firstListingSlot); i <= last; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty() || !w.key.matches(st)) continue;
            long total = PriceParser.parse(st);
            if (total <= 0) continue;
            int count = Math.max(1, st.getCount());
            double unit = (double) total / count;
            if (unit > maxUnit) continue;
            if (best == null || unit < best.unit()) best = new Offer(i, total, count, unit);
        }
        return best;
    }

    private void click(MinecraftClient mc, int slot) {
        click(mc, slot, 0);
    }

    /** @param button 0 = lewy przycisk myszy, 1 = prawy */
    private void click(MinecraftClient mc, int slot, int button) {
        if (slot < 0) return;
        GenericContainerScreenHandler h = container(mc, false);
        if (h == null || slot >= h.slots.size()) return;
        mc.interactionManager.clickSlot(h.syncId, slot, button, SlotActionType.PICKUP, mc.player);
    }

    /** Zapisuje zrzut aktualnego okna do config/<fileName> (do diagnozy ukladu rynku). */
    private void dumpMarket(MinecraftClient mc, String fileName) {
        if (!(mc.currentScreen instanceof HandledScreen<?> hs)) return;
        try {
            ScreenHooks.writeDump(hs, FabricLoader.getInstance().getConfigDir().resolve(fileName));
            stats().log("Zapisano zrzut: " + fileName);
        } catch (IOException | RuntimeException e) {
            stats().log("Nie zapisano zrzutu: " + e.getMessage());
        }
    }

    // --------------------------------------------------------- sortowanie

    /** Szuka przycisku sortowania (domyslnie repeater) w dolnym rzedzie okna. */
    private int findSortSlot(GenericContainerScreenHandler h) {
        Settings s = cfg.settings;
        int max = h.getRows() * 9;
        if (s.sortButtonSlot >= 0) return s.sortButtonSlot < max ? s.sortButtonSlot : -1;
        for (int i = max - 1; i >= Math.max(0, max - 9); i--) {
            ItemStack st = h.getSlot(i).getStack();
            if (!st.isEmpty() && Registries.ITEM.getId(st.getItem()).toString().equals(s.sortItemId)) return i;
        }
        return -1;
    }

    /** Ile razy trzeba kliknac PRAWYM, zeby z aktualnego trybu dojsc do sortTargetText. */
    private int clicksNeeded(ItemStack button) {
        Settings s = cfg.settings;
        int target = -1;
        for (int i = 0; i < s.sortOptions.size(); i++) {
            if (s.sortOptions.get(i).equalsIgnoreCase(s.sortTargetText)) target = i;
        }
        int selected = selectedSortOption(button);
        if (target >= 0 && selected >= 0) {
            return Math.floorMod(target - selected, s.sortOptions.size());
        }
        return s.sortClicks; // nie wiadomo, jaki tryb jest ustawiony - klikamy tyle razy, ile w configu (domyslnie 2)
    }

    /** Odczytuje z opisu przycisku zaznaczony (podkreslony) tryb sortowania. @return indeks w sortOptions lub -1 */
    private int selectedSortOption(ItemStack button) {
        LoreComponent lore = button.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        List<String> options = cfg.settings.sortOptions;
        int found = -1;
        for (Text line : lore.lines()) {
            String text = line.getString().toLowerCase(Locale.ROOT);
            for (int i = 0; i < options.size(); i++) {
                if (text.contains(options.get(i).toLowerCase(Locale.ROOT)) && isUnderlined(line)) {
                    if (found >= 0 && found != i) return -1; // niejednoznaczne
                    found = i;
                }
            }
        }
        return found;
    }

    public static boolean isUnderlined(Text text) {
        if (text.getStyle().isUnderlined()) return true;
        for (Text sibling : text.getSiblings()) {
            if (isUnderlined(sibling)) return true;
        }
        return false;
    }

    /** @param market true = dodatkowo wymagaj, aby tytul okna zawieral marketTitleKeyword */
    private GenericContainerScreenHandler container(MinecraftClient mc, boolean market) {
        if (!(mc.currentScreen instanceof GenericContainerScreen scr)) return null;
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler h)) return null;
        String kw = cfg.settings.marketTitleKeyword;
        if (market && kw != null && !kw.isBlank()
                && !scr.getTitle().getString().toLowerCase(Locale.ROOT).contains(kw.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return h;
    }

    private Stats stats() {
        return cfg.stats;
    }
}
