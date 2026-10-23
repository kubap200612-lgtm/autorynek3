package pl.autorynek.data;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class Stats {
    public record HistoryEntry(long time, String item, long price, long marketPrice) {}

    // zapisywane do pliku
    public long spent;
    public long expectedRevenue;   // suma (cena rynkowa * 0.97) z chwili zakupu
    public int purchases;
    public List<HistoryEntry> history = new ArrayList<>();

    // tylko w pamieci (konsola w HUD)
    public transient int attempts;
    public transient int marketResets;
    public transient long lastRefreshMs;
    public transient long lastConfirmMs;
    public transient Deque<String> console = new ArrayDeque<>();

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public long profit() {
        return expectedRevenue - spent;
    }

    public void log(String msg) {
        if (console == null) console = new ArrayDeque<>();
        console.addLast(LocalTime.now().format(TIME) + " " + msg);
        while (console.size() > 6) console.removeFirst();
    }

    public void resetAll() {
        spent = 0;
        expectedRevenue = 0;
        purchases = 0;
        history.clear();
        attempts = 0;
        marketResets = 0;
        lastRefreshMs = 0;
        lastConfirmMs = 0;
    }
}
