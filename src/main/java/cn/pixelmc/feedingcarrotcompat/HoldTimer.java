package cn.pixelmc.feedingcarrotcompat;

/** Tick-based gate: packet spam cannot advance time, and silence expires a hold. */
final class HoldTimer {
    static final int MAX_SILENCE = 10;
    final long started;
    final int duration;
    long lastPulse;

    HoldTimer(long now, int duration) {
        this.started = this.lastPulse = now;
        this.duration = Math.max(20, duration);
    }

    boolean expired(long now) { return now - lastPulse > MAX_SILENCE; }
    boolean pulse(long now) {
        if (expired(now)) return false;
        lastPulse = now;
        return now - started >= duration;
    }
    int elapsed(long now) { return (int) Math.min(duration, now - started); }
}
