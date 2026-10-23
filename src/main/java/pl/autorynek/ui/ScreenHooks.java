package pl.autorynek.ui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import pl.autorynek.AutoRynekClient;
import pl.autorynek.bot.MarketBot;
import pl.autorynek.bot.PriceParser;
import pl.autorynek.data.AutoRynekConfig;
import pl.autorynek.data.ItemKey;
import pl.autorynek.data.WatchedItem;
import pl.autorynek.mixin.HandledScreenAccessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Klawisze w oknach (L = dodaj przedmiot, J = zrzut GUI) oraz podswietlanie dobrych ofert. */
public final class ScreenHooks {
    private ScreenHooks() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
            if (!(screen instanceof HandledScreen<?> hs)) return;

            ScreenKeyboardEvents.afterKeyPress(screen).register((scr, key, scancode, modifiers) -> {
                if (hs instanceof AnvilScreen || hs instanceof CreativeInventoryScreen) return; // pola tekstowe
                if (AutoRynekClient.ADD_KEY.matchesKey(key, scancode)) addHovered(client, hs);
                else if (AutoRynekClient.DUMP_KEY.matchesKey(key, scancode)) dump(client, hs);
            });

            if (screen instanceof GenericContainerScreen gcs) {
                ScreenEvents.afterRender(screen).register((scr, ctx, mouseX, mouseY, delta) -> highlight(client, gcs, ctx));
            }
        });
    }

    // ------------------------------------------------------- L: dodaj przedmiot

    private static void addHovered(MinecraftClient mc, HandledScreen<?> hs) {
        Slot slot = ((HandledScreenAccessor) hs).autorynek$getFocusedSlot();
        if (slot == null || !slot.hasStack()) {
            msg(mc, "Najedź kursorem na przedmiot i naciśnij L");
            return;
        }
        AutoRynekConfig cfg = AutoRynekClient.CONFIG;
        ItemStack st = slot.getStack();
        ItemKey key = ItemKey.of(st);
        for (WatchedItem w : cfg.watchlist) {
            if (w.key.equals(key)) {
                msg(mc, "Ten przedmiot już jest na liście: " + w.label);
                return;
            }
            if (w.key.components() == null && w.key.matches(st)) {
                // stary wpis sprzed komponentow - uzupelnij o komponenty zaznaczonego przedmiotu
                w.key = key;
                cfg.save();
                msg(mc, "Zaktualizowano (dodano komponenty): " + w.label);
                return;
            }
        }
        String label = st.getName().getString();
        String query = st.getItem().getName().getString();
        cfg.watchlist.add(new WatchedItem(key, label, query, cfg.settings.defaultMargin));
        cfg.save();
        cfg.stats.log("Dodano: " + label);
        msg(mc, "Dodano: " + label + " - ceny pojawią się po analizie rynku");
    }

    // ----------------------------------------------- J: zrzut GUI do kalibracji

    private static void dump(MinecraftClient mc, HandledScreen<?> hs) {
        Path out = FabricLoader.getInstance().getConfigDir().resolve("autorynek-dump.txt");
        try {
            writeDump(hs, out);
            msg(mc, "Zapisano zrzut GUI: " + out);
        } catch (IOException e) {
            msg(mc, "Nie udało się zapisać zrzutu: " + e.getMessage());
        }
    }

    /** Zapisuje sloty otwartego okna: id, nazwa, odczytana cena i opis ([podkreslone] = styl podkreslenia w opisie). */
    public static void writeDump(HandledScreen<?> hs, Path out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Tytul okna: ").append(hs.getTitle().getString()).append('\n');
        int limit = hs.getScreenHandler() instanceof GenericContainerScreenHandler g ? g.getRows() * 9 : Integer.MAX_VALUE;
        for (Slot sl : hs.getScreenHandler().slots) {
            if (sl.id >= limit) break;
            ItemStack st = sl.getStack();
            if (st.isEmpty()) continue;
            sb.append("slot ").append(sl.id).append(": ")
                    .append(net.minecraft.registry.Registries.ITEM.getId(st.getItem()))
                    .append(" x").append(st.getCount())
                    .append(" | nazwa: ").append(st.getName().getString())
                    .append(" | cena odczytana: ").append(PriceParser.parse(st)).append('\n');
            sb.append("      komponenty (").append(ItemKey.describe(st)).append(")\n");
            LoreComponent lore = st.get(DataComponentTypes.LORE);
            if (lore != null) {
                for (Text line : lore.lines()) {
                    sb.append("      lore: ").append(line.getString())
                            .append(MarketBot.isUnderlined(line) ? "  [podkreslone]" : "").append('\n');
                }
            }
        }
        Files.writeString(out, sb.toString());
    }

    // ------------------------------------------------ podswietlanie ofert

    private static void highlight(MinecraftClient mc, GenericContainerScreen scr, DrawContext ctx) {
        AutoRynekConfig cfg = AutoRynekClient.CONFIG;
        if (cfg.watchlist.isEmpty()) return;
        String kw = cfg.settings.marketTitleKeyword;
        if (kw != null && !kw.isBlank()
                && !scr.getTitle().getString().toLowerCase(Locale.ROOT).contains(kw.toLowerCase(Locale.ROOT))) {
            return;
        }
        HandledScreenAccessor acc = (HandledScreenAccessor) scr;
        GenericContainerScreenHandler h = scr.getScreenHandler();
        int limit = Math.min(h.getRows() * 9, h.slots.size());
        for (int i = 0; i < limit; i++) {
            Slot sl = h.getSlot(i);
            ItemStack st = sl.getStack();
            if (st.isEmpty()) continue;
            for (WatchedItem w : cfg.watchlist) {
                if (w.buyPrice <= 0 || !w.key.matches(st)) continue;
                long price = PriceParser.parse(st);
                int count = Math.max(1, st.getCount());
                if (price > 0 && (double) price / count <= w.buyPrice) { // cena skupu jest za sztuke
                    int x = acc.autorynek$getX() + sl.x;
                    int y = acc.autorynek$getY() + sl.y;
                    ctx.fill(x, y, x + 16, y + 16, 0x9955FF55);
                }
                break;
            }
        }
    }

    private static void msg(MinecraftClient mc, String text) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[AutoRynek] " + text), true);
    }
}
