package pl.autorynek.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import pl.autorynek.AutoRynekClient;
import pl.autorynek.bot.MarketBot;
import pl.autorynek.data.AutoRynekConfig;
import pl.autorynek.data.Stats;
import pl.autorynek.data.WatchedItem;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Hud {
    private record Line(String text, int color) {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, GOLD = 0xFFFFAA00,
            GREEN = 0xFF55FF55, RED = 0xFFFF5555, AQUA = 0xFF55FFFF;

    private Hud() {}

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;

        AutoRynekConfig cfg = AutoRynekClient.CONFIG;
        MarketBot bot = AutoRynekClient.BOT;
        Stats st = cfg.stats;
        List<Line> lines = new ArrayList<>();

        String state = switch (bot.getMode()) {
            case OFF -> "WYŁĄCZONY";
            case ANALYSIS -> "ANALIZA RYNKU";
            case BUYING -> "SKUPOWANIE";
        };
        lines.add(new Line("AutoRynek [" + state + "]", bot.isRunning() ? GREEN : RED));

        // --- skupowane przedmioty ---
        lines.add(new Line("Skupowane (L = dodaj):", GOLD));
        if (cfg.watchlist.isEmpty()) {
            lines.add(new Line("  (pusto)", GRAY));
        }
        int shown = 0;
        for (WatchedItem w : cfg.watchlist) {
            if (shown >= 8) {
                lines.add(new Line("  ... +" + (cfg.watchlist.size() - shown) + " więcej", GRAY));
                break;
            }
            shown++;
            String price;
            if (!w.analyzed) {
                price = "czeka na analizę rynku";
            } else if (w.buyPrice > 0) {
                price = "kup do " + money(w.buyPrice) + " (rynek " + money(w.marketPrice) + ", marża " + money(w.margin) + ")";
            } else {
                price = "brak ofert na rynku";
            }
            lines.add(new Line("  " + shown + ". " + w.label + " - " + price, WHITE));
        }

        // --- pieniadze ---
        lines.add(new Line("Wydano: " + money(st.spent) + " | Zakupów: " + st.purchases, WHITE));
        lines.add(new Line("Przychód (szac.): " + money(st.expectedRevenue) + " | Zysk: " + money(st.profit()),
                st.profit() >= 0 ? GREEN : RED));

        // --- historia ---
        lines.add(new Line("Historia zakupów:", GOLD));
        if (st.history.isEmpty()) {
            lines.add(new Line("  (brak)", GRAY));
        } else {
            for (int i = st.history.size() - 1, n = 0; i >= 0 && n < 4; i--, n++) {
                Stats.HistoryEntry e = st.history.get(i);
                lines.add(new Line("  " + TIME.format(Instant.ofEpochMilli(e.time())) + " " + e.item() + " za " + money(e.price()), GRAY));
            }
        }

        // --- konsola ---
        lines.add(new Line("Konsola:", GOLD));
        lines.add(new Line("  Odświeżanie: " + st.lastRefreshMs + " ms | Potwierdzenie: " + st.lastConfirmMs + " ms", AQUA));
        lines.add(new Line("  Próby zakupu: " + st.attempts + " | Resety rynku: " + st.marketResets, AQUA));
        int skip = Math.max(0, st.console.size() - 4);
        int i = 0;
        for (String s : st.console) {
            if (i++ < skip) continue;
            lines.add(new Line("  " + s, GRAY));
        }

        TextRenderer tr = mc.textRenderer;
        int width = 0;
        for (Line l : lines) width = Math.max(width, tr.getWidth(l.text()));
        int x = 6, y = 6;
        ctx.fill(x - 3, y - 3, x + width + 3, y + lines.size() * 10 + 1, 0x90000000);
        for (Line l : lines) {
            ctx.drawText(tr, l.text(), x, y, l.color(), true);
            y += 10;
        }
    }

    private static String money(long v) {
        return String.format(Locale.ROOT, "%,d", v).replace(',', ' ') + "$";
    }
}
