package de.adraxah.blockoverview;

import java.util.*;

/** Minecraft publishes snapshots on its main thread. HTTP never touches Bukkit. */
public final class LiveStatus {
    public record Player(String name, UUID uuid) {}
    public record Snapshot(long observedAt, long processStartedAt, int online, int maxPlayers,
        int peakPlayers, String version, List<Player> players, Double tps) {
        public Snapshot { players = List.copyOf(players); }
    }
    private volatile Snapshot snapshot;
    private final PlayerUpdates playerUpdates = new PlayerUpdates();
    private volatile java.util.function.Supplier<ContentStore.Settings> settings;
    public synchronized void publish(Snapshot value) {
        snapshot = value;
        if (settings != null) playerUpdates.select(value, settings.get(), System.currentTimeMillis());
    }
    public Snapshot get() { return snapshot; }
    synchronized void configurePlayers(java.util.function.Supplier<ContentStore.Settings> value) {
        settings = value;
        playerUpdates.select(snapshot, value.get(), System.currentTimeMillis());
    }
    synchronized PlayerUpdates.View players(ContentStore.Settings value, long now) {
        return playerUpdates.select(snapshot, value, now);
    }
    synchronized List<Player> displayedPlayers() {
        if (settings == null) return List.of();
        PlayerUpdates.View view = players(settings.get(), System.currentTimeMillis());
        return view.available() ? view.snapshot().players() : List.of();
    }

    /** Observed tick rate, at most 60 seconds of history; no NMS/server fork dependency. */
    public static final class TickRate {
        private final ArrayDeque<Long> times = new ArrayDeque<>();
        public void tick(long nanos) {
            times.addLast(nanos);
            while (times.size() > 2 && (times.peekFirst() < nanos - 60_000_000_000L || times.size() > 4096)) times.removeFirst();
        }
        public Double value() {
            if (times.size() < 2) return null;
            long elapsed = times.peekLast() - times.peekFirst();
            if (elapsed < 5_000_000_000L) return null;
            return Math.round(Math.min(20.0, (times.size() - 1) * 1_000_000_000.0 / elapsed) * 100.0) / 100.0;
        }
    }
}
