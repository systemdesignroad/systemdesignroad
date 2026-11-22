import java.io.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.function.*;
import java.util.stream.*;
import java.util.random.*;

interface Ticker { long nanos(); }

record Limit(long permits, long periodNanos) {
    Limit {
        if (permits <= 0 || periodNanos <= 0)
            throw new IllegalArgumentException("bad limit");
    }
    static Limit perSecond(long n) { return new Limit(n, 1_000_000_000L); }
    static Limit perMinute(long n) { return new Limit(n, 60_000_000_000L); }
}

record Decision(boolean allowed, long remaining, long retryAfterNanos) {
    static Decision allow(long remaining) { return new Decision(true, remaining, 0); }
    static Decision deny(long retryAfterNanos) {
        return new Decision(false, 0, retryAfterNanos);
    }
}

interface KeyState {
    Decision tryAcquire(long now);
    boolean idleSince(long now);                              // (1)
}

interface Algorithm {
    KeyState newState(Limit limit, long now);
}

final class FixedWindow implements Algorithm {
    public KeyState newState(Limit limit, long now) {
        return new KeyState() {
            private long windowStart = now - Math.floorMod(now, limit.periodNanos());
            private long count;

            public synchronized Decision tryAcquire(long t) {
                if (t - windowStart >= limit.periodNanos()) {
                    windowStart = t - Math.floorMod(t, limit.periodNanos());   // (2)
                    count = 0;
                }
                if (count < limit.permits())
                    return Decision.allow(limit.permits() - ++count);
                return Decision.deny(windowStart + limit.periodNanos() - t);
            }

            public synchronized boolean idleSince(long t) {
                return t - windowStart >= limit.periodNanos();
            }
        };
    }
}

final class SlidingLog implements Algorithm {
    public KeyState newState(Limit limit, long now) {
        return new KeyState() {
            private final ArrayDeque<Long> times = new ArrayDeque<>();

            public synchronized Decision tryAcquire(long t) {
                while (!times.isEmpty() && t - times.peekFirst() >= limit.periodNanos())
                    times.pollFirst();                                   // (3)
                if (times.size() < limit.permits()) {
                    times.addLast(t);
                    return Decision.allow(limit.permits() - times.size());
                }
                return Decision.deny(times.peekFirst() + limit.periodNanos() - t);
            }

            public synchronized boolean idleSince(long t) {
                return times.isEmpty() || t - times.peekLast() >= limit.periodNanos();
            }
        };
    }
}

final class SlidingCounter implements Algorithm {
    public KeyState newState(Limit limit, long now) {
        return new KeyState() {
            private final long p = limit.periodNanos();
            private long windowStart = now - Math.floorMod(now, p);
            private long current, previous;

            public synchronized Decision tryAcquire(long t) {
                long start = t - Math.floorMod(t, p);
                if (start != windowStart) {
                    previous = start - windowStart == p ? current : 0;   // (4)
                    current = 0;
                    windowStart = start;
                }
                double weight = 1.0 - (double) (t - windowStart) / p;   // (5)
                double estimate = previous * weight + current;
                if (estimate + 1 <= limit.permits()) {
                    current++;
                    return Decision.allow((long) (limit.permits() - estimate - 1));
                }
                return Decision.deny(Math.max(1, p / limit.permits()));
            }

            public synchronized boolean idleSince(long t) {
                return t - windowStart >= 2 * p;
            }
        };
    }
}

final class TokenBucket implements Algorithm {
    public KeyState newState(Limit limit, long now) {
        return new KeyState() {
            private final long cost = limit.periodNanos() / limit.permits();  // (6)
            private final long capacity = limit.periodNanos();
            private long full = now;                                       // (7)

            public synchronized Decision tryAcquire(long t) {
                long earliest = Math.max(full, t);
                long next = earliest + cost;
                if (next - t > capacity) return Decision.deny(next - t - capacity);
                full = next;
                return Decision.allow((capacity - (next - t)) / cost);
            }

            public synchronized boolean idleSince(long t) { return t >= full; }
        };
    }
}

final class RateLimiter {
    private final Algorithm algorithm;
    private final Function<String, Limit> limits;
    private final Ticker ticker;
    private final ConcurrentHashMap<String, KeyState> states = new ConcurrentHashMap<>();

    RateLimiter(Algorithm algorithm, Function<String, Limit> limits, Ticker ticker) {
        this.algorithm = algorithm;
        this.limits = limits;
        this.ticker = ticker;
    }

    Decision tryAcquire(String key) {
        long now = ticker.nanos();
        KeyState s = states.computeIfAbsent(key,
            k -> algorithm.newState(limits.apply(k), now));            // (1)
        return s.tryAcquire(now);
    }

    int evictIdle() {
        long now = ticker.nanos();
        int before = states.size();
        states.entrySet().removeIf(e -> e.getValue().idleSince(now)); // (2)
        return before - states.size();
    }

    int trackedKeys() { return states.size(); }
}
