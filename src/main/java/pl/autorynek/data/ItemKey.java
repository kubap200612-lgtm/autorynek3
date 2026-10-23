package pl.autorynek.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import pl.autorynek.AutoRynekClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * "Odcisk" przedmiotu: minecraft id + wlasna nazwa + enchanty + KOMPONENTY.
 * Przedmiot z rynku jest uznany za ten sam tylko wtedy, gdy wszystko sie zgadza.
 *
 * Komponenty (custom_data, item_model, opis itd.) odrozniaja customowe przedmioty serwera.
 * Z porownania wylaczone sa: komponenty z listy settings.ignoredComponents (domyslnie wytrzymalosc)
 * oraz czesc opisu (lore) dopisana przez rynek (od linii zawierajacej settings.listingLoreMarkers).
 */
public record ItemKey(String id, String name, Map<String, Integer> enchants, String components) {

    private static final Map<ItemStack, ItemKey> CACHE = new WeakHashMap<>();

    public static ItemKey of(ItemStack stack) {
        ItemKey cached = CACHE.get(stack);
        if (cached != null) return cached;

        Text custom = stack.get(DataComponentTypes.CUSTOM_NAME);
        String name = custom == null ? null : custom.getString();
        Map<String, Integer> enchants = new TreeMap<>();
        collect(stack.get(DataComponentTypes.ENCHANTMENTS), enchants);
        collect(stack.get(DataComponentTypes.STORED_ENCHANTMENTS), enchants);
        String components = sha(String.join(";", parts(stack)), 8);

        ItemKey key = new ItemKey(idOf(stack), name, enchants, components);
        CACHE.put(stack, key);
        return key;
    }

    /** Czy podany przedmiot to dokladnie ten sam przedmiot co zapisany. */
    public boolean matches(ItemStack stack) {
        if (!Objects.equals(id, idOf(stack))) return false;
        ItemKey other = of(stack);
        if (!Objects.equals(name, other.name) || !Objects.equals(enchants, other.enchants)) return false;
        // components == null: stary wpis sprzed wprowadzenia komponentow - porownujemy tylko id/nazwe/enchanty
        return components == null || components.equals(other.components);
    }

    /** Opis do zrzutu GUI: liczba komponentow + skrot kazdego z nich (do wykrywania, co sie rozni miedzy ofertami). */
    public static String describe(ItemStack stack) {
        List<String> parts = parts(stack);
        return parts.size() + ": " + String.join(", ", parts);
    }

    private static String idOf(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private static void collect(ItemEnchantmentsComponent component, Map<String, Integer> out) {
        if (component == null) return;
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> e : component.getEnchantmentEntries()) {
            int level = e.getIntValue();
            e.getKey().getKey().ifPresent(k -> out.put(k.getValue().toString(), level));
        }
    }

    // ------------------------------------------------------------ komponenty

    /** Posortowana lista "typ=skrot_wartosci" dla wszystkich nieignorowanych komponentow przedmiotu. */
    private static List<String> parts(ItemStack stack) {
        DynamicOps<NbtElement> ops = nbtOps();
        ComponentMap map = stack.getComponents();
        List<String> out = new ArrayList<>();
        for (ComponentType<?> type : map.getTypes()) {
            Identifier typeId = Registries.DATA_COMPONENT_TYPE.getId(type);
            String id = typeId == null ? "?" : typeId.toString();
            if (isIgnored(id)) continue;
            String value = encode(map, type, ops);
            if (value != null) out.add(id + "=" + sha(value, 4));
        }
        Collections.sort(out);
        return out;
    }

    private static <T> String encode(ComponentMap map, ComponentType<T> type, DynamicOps<NbtElement> ops) {
        T value = map.get(type);
        if (value == null) return null;
        if (type == DataComponentTypes.LORE) return loreText((LoreComponent) value);
        Codec<T> codec = type.getCodec();
        if (codec == null) return String.valueOf(value);
        return codec.encodeStart(ops, value).result().map(Object::toString).orElseGet(() -> String.valueOf(value));
    }

    /** Tekst opisu bez czesci dopisanej przez rynek (cena, wystawiajacy, "Kliknij LEWYM..."). */
    private static String loreText(LoreComponent lore) {
        List<String> lines = new ArrayList<>();
        for (Text line : lore.lines()) lines.add(line.getString());

        List<String> markers = AutoRynekClient.CONFIG.settings.listingLoreMarkers;
        if (markers != null) {
            for (int i = 0; i < lines.size(); i++) {
                String low = lines.get(i).toLowerCase(Locale.ROOT);
                boolean hit = false;
                for (String m : markers) {
                    if (m != null && !m.isBlank() && low.contains(m.toLowerCase(Locale.ROOT))) hit = true;
                }
                if (hit) {
                    lines = new ArrayList<>(lines.subList(0, i));
                    break;
                }
            }
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        return String.join("\n", lines);
    }

    private static boolean isIgnored(String typeId) {
        List<String> ignored = AutoRynekClient.CONFIG.settings.ignoredComponents;
        return ignored != null && ignored.contains(typeId);
    }

    private static DynamicOps<NbtElement> nbtOps() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) return mc.world.getRegistryManager().getOps(NbtOps.INSTANCE);
        return NbtOps.INSTANCE;
    }

    private static String sha(String text, int bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bytes; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
