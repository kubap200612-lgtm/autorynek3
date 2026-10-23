package pl.autorynek.data;

/** Przedmiot z listy "skupowane" widoczny w HUD. */
public class WatchedItem {
    public ItemKey key;
    public String label;        // nazwa pokazywana w HUD
    public String searchQuery;  // tekst uzywany w /ah search <tekst>
    public long margin;         // marza (np. 100, 200, 500)
    public long marketPrice;    // najnizsza cena z ostatniej analizy
    public long buyPrice;       // maksymalna cena skupu = floor(marketPrice * 0.97) - margin
    public boolean analyzed;    // czy przedmiot przeszedl juz analize rynku (do tego czasu brak cen)

    public WatchedItem() {}

    public WatchedItem(ItemKey key, String label, String searchQuery, long margin) {
        this.key = key;
        this.label = label;
        this.searchQuery = searchQuery;
        this.margin = margin;
    }

    public void recalc(double tax) {
        buyPrice = marketPrice <= 0 ? 0 : Math.max(0, (long) Math.floor(marketPrice * tax) - margin);
    }
}
