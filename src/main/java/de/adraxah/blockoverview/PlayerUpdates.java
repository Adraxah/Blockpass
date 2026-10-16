package de.adraxah.blockoverview;

import java.time.*;
import java.util.Objects;

/** One shared player snapshot for every visitor. TPS and uptime remain independent. */
final class PlayerUpdates {
    record Window(long start, long end) {}
    record View(LiveStatus.Snapshot snapshot, boolean paused, Long resumesAt, boolean available) {}
    private LiveStatus.Snapshot displayed;
    private Window heldWindow;
    private String schedule;

    static boolean fresh(LiveStatus.Snapshot value, long now) {
        return value != null && now >= value.observedAt() && now - value.observedAt() < 15000;
    }

    static Window window(ContentStore.Settings settings, long now) {
        if (!settings.playerPauseEnabled()) return null;
        LocalTime start = LocalTime.parse(settings.playerPauseStart()), end = LocalTime.parse(settings.playerPauseEnd());
        LocalDate today = Instant.ofEpochMilli(now).atZone(ContentStore.ZONE).toLocalDate();
        for (LocalDate day : new LocalDate[]{today.minusDays(1), today}) {
            long a = day.atTime(start).atZone(ContentStore.ZONE).toInstant().toEpochMilli();
            long b = (end.isAfter(start) ? day : day.plusDays(1)).atTime(end).atZone(ContentStore.ZONE).toInstant().toEpochMilli();
            if (now >= a && now < b) return new Window(a, b);
        }
        return null;
    }

    synchronized View select(LiveStatus.Snapshot latest, ContentStore.Settings settings, long now) {
        Window window = window(settings, now);
        String key = settings.playerPauseEnabled() + "/" + settings.playerPauseStart() + "/" + settings.playerPauseEnd();
        boolean changed = !Objects.equals(schedule, key);
        if (window == null) {
            displayed = latest;
        } else if (changed || !window.equals(heldWindow)) {
            // On a normal boundary keep the last sample before the pause. On startup or
            // a changed schedule, capture a fresh baseline once instead of inventing data.
            if (changed || !fresh(displayed, window.start())) displayed = fresh(latest, now) ? latest : null;
        } else if (displayed == null && fresh(latest, now)) {
            displayed = latest;
        }
        schedule = key; heldWindow = window;
        boolean available = window != null ? displayed != null : fresh(displayed, now);
        return new View(displayed, window != null, window == null ? null : window.end(), available);
    }
}
