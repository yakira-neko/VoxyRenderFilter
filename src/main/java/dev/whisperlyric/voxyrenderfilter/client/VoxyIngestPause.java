package dev.whisperlyric.voxyrenderfilter.client;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global ingest pause switch. Set to true before deleting cache so voxy's
 * {@code VoxyInstance.isIngestEnabled} (intercepted by a mixin) returns false,
 * preventing new data writes during the deletion window; otherwise freshly
 * deleted sections would be immediately resurrected by ingest.
 */
public final class VoxyIngestPause {

    private static final AtomicBoolean PAUSED = new AtomicBoolean(false);

    private VoxyIngestPause() {
    }

    public static boolean isPaused() {
        return PAUSED.get();
    }

    public static void setPaused(boolean paused) {
        PAUSED.set(paused);
    }
}
