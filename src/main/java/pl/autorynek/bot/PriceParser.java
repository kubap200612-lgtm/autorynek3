package pl.autorynek.bot;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import pl.autorynek.AutoRynekClient;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Wyciaga cene oferty z opisu (lore) przedmiotu w GUI rynku. */
public final class PriceParser {
    private static final Pattern FALLBACK = Pattern.compile("([0-9][0-9 .,]*)\\s*([kKmMbB])?\\s*\\$");
    private static final Pattern FALLBACK_PREFIX = Pattern.compile("\\$\\s*([0-9][0-9 .,]*)\\s*([kKmMbB])?");
    private static String cachedSource;
    private static Pattern cachedPattern;

    private PriceParser() {}

    /** @return cena lub 0, gdy nie udalo sie jej odczytac */
    public static long parse(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return 0;
        Pattern main = mainPattern();
        for (Pattern p : new Pattern[]{main, FALLBACK, FALLBACK_PREFIX}) {
            for (Text line : lore.lines()) {
                long v = tryLine(p, line.getString());
                if (v > 0) return v;
            }
        }
        return 0;
    }

    private static Pattern mainPattern() {
        String src = AutoRynekClient.CONFIG.settings.pricePattern;
        if (src == null) src = "";
        if (!src.equals(cachedSource)) {
            cachedSource = src;
            try {
                cachedPattern = Pattern.compile(src);
            } catch (PatternSyntaxException e) {
                cachedPattern = Pattern.compile("(?!)"); // nigdy nie pasuje
            }
        }
        return cachedPattern;
    }

    private static long tryLine(Pattern p, String line) {
        Matcher m = p.matcher(line.replace((char) 160, ' '));
        if (!m.find() || m.groupCount() < 1) return 0;
        try {
            return toNumber(m.group(1), m.groupCount() >= 2 ? m.group(2) : null);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static long toNumber(String num, String suffix) {
        String n = num.trim().replace(" ", "").replaceAll("[.,]+$", "");
        double mult = switch (suffix == null ? "" : suffix.toLowerCase(Locale.ROOT)) {
            case "k" -> 1_000d;
            case "m" -> 1_000_000d;
            case "b" -> 1_000_000_000d;
            default -> 1d;
        };
        if (n.matches("\\d{1,3}([.,]\\d{3})+")) {
            n = n.replaceAll("[.,]", "");   // 1.500 / 1,500 -> 1500
        } else {
            n = n.replace(',', '.');        // 1,5 -> 1.5
        }
        return Math.round(Double.parseDouble(n) * mult);
    }
}
