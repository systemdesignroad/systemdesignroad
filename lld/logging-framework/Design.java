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

enum Level {
    TRACE, DEBUG, INFO, WARN, ERROR;
    boolean atLeast(Level other) { return compareTo(other) >= 0; }
}

record LogEvent(long time, Level level, String logger, String thread,
                String message, Map<String, String> context, Throwable error) { }

interface Clock { long now(); }

final class LogContext {
    private static final ThreadLocal<Map<String, String>> CURRENT =
        ThreadLocal.withInitial(LinkedHashMap::new);

    interface Scope extends AutoCloseable { void close(); }       // no checked exception

    static Scope with(String key, String value) {                // (1) try-with-resources
        Map<String, String> m = CURRENT.get();
        String before = m.put(key, value);
        return () -> { if (before == null) m.remove(key); else m.put(key, before); };
    }

    static Map<String, String> snapshot() {                     // (2) a copy, not the map
        Map<String, String> m = CURRENT.get();
        return m.isEmpty() ? Map.of()
                           : Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }
}

interface Formatter { String format(LogEvent e); }

final class PatternFormatter implements Formatter {
    public String format(LogEvent e) {
        StringBuilder sb = new StringBuilder()
            .append(String.format("%06d", e.time() % 1_000_000)).append(' ')
            .append(String.format("%-5s", e.level())).append(' ')
            .append('[').append(e.thread()).append("] ")
            .append(e.logger());
        if (!e.context().isEmpty()) sb.append(' ').append(e.context());
        sb.append(" - ").append(e.message());
        if (e.error() != null) sb.append(" | ").append(e.error());
        return sb.toString();
    }
}

interface Appender {
    void append(LogEvent e);
    default void close() { }
}

final class StreamAppender implements Appender {
    private final PrintStream out;
    private final Formatter formatter;
    StreamAppender(PrintStream out, Formatter formatter) {
        this.out = out; this.formatter = formatter;
    }
    public void append(LogEvent e) {
        String line = formatter.format(e);                   // (1) build outside the lock
        synchronized (this) { out.println(line); }         // (2) one whole line at a time
    }
}

final class MemoryAppender implements Appender {
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Formatter formatter;
    MemoryAppender(Formatter formatter) { this.formatter = formatter; }
    public void append(LogEvent e) { lines.add(formatter.format(e)); }
    List<String> lines() { return List.copyOf(lines); }
}

final class Logger {
    private final String name;
    private final Logger parent;
    private final Clock clock;
    private volatile Level level;                              // (1) null: ask the parent
    private volatile boolean additive = true;
    private final List<Appender> appenders = new CopyOnWriteArrayList<>();

    Logger(String name, Logger parent, Clock clock) {
        this.name = name; this.parent = parent; this.clock = clock;
    }

    String name() { return name; }
    void setLevel(Level l) { level = l; }
    void setAdditive(boolean a) { additive = a; }
    void addAppender(Appender a) { appenders.add(a); }

    Level effectiveLevel() {
        for (Logger l = this; l != null; l = l.parent)
            if (l.level != null) return l.level;
        return Level.INFO;
    }

    boolean isEnabled(Level l) { return l.atLeast(effectiveLevel()); }

    void log(Level l, Supplier<String> message, Throwable error) {
        if (!isEnabled(l)) return;                         // (2) before building anything
        LogEvent e = new LogEvent(clock.now(), l, name, Thread.currentThread().getName(),
                                  message.get(), LogContext.snapshot(), error);
        for (Logger lg = this; lg != null; lg = lg.additive ? lg.parent : null)
            for (Appender a : lg.appenders) a.append(e);         // (3) up the tree
    }

    void debug(Supplier<String> m) { log(Level.DEBUG, m, null); }
    void info(String m) { log(Level.INFO, () -> m, null); }
    void warn(String m) { log(Level.WARN, () -> m, null); }
    void error(String m, Throwable t) { log(Level.ERROR, () -> m, t); }
}

final class LogManager {
    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();
    private final Logger root;
    private final Clock clock;

    LogManager(Clock clock) {
        this.clock = clock;
        this.root = new Logger("root", null, clock);
        root.setLevel(Level.INFO);
    }

    Logger root() { return root; }

    Logger getLogger(String name) {
        Logger found = loggers.get(name);
        if (found != null) return found;
        int dot = name.lastIndexOf('.');
        Logger parent = dot < 0 ? root : getLogger(name.substring(0, dot));   // (1)
        return loggers.computeIfAbsent(name, n -> new Logger(n, parent, clock)); // (2)
    }
}

enum WhenFull { BLOCK, DROP_NEWEST, DROP_BELOW_WARN }

final class AsyncAppender implements Appender {
    private static final LogEvent STOP =
        new LogEvent(0, Level.ERROR, "", "", "", Map.of(), null);
    private final Appender target;
    private final BlockingQueue<LogEvent> queue;
    private final WhenFull whenFull;
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;

    AsyncAppender(Appender target, int capacity, WhenFull whenFull) {
        this.target = target;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.whenFull = whenFull;
        this.worker = Thread.ofPlatform().daemon().name("log-writer").start(this::drain);
    }

    public void append(LogEvent e) {
        if (queue.offer(e)) return;                              // (1) the usual case
        boolean keep = whenFull == WhenFull.BLOCK
            || (whenFull == WhenFull.DROP_BELOW_WARN && e.level().atLeast(Level.WARN));
        if (!keep) { dropped.incrementAndGet(); return; }       // (2) counted, not hidden
        try {
            queue.put(e);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            dropped.incrementAndGet();
        }
    }

    private void drain() {
        try {
            while (true) {
                LogEvent e = queue.take();
                if (e == STOP) return;
                target.append(e);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public void close() {                                        // (3) flush, then stop
        try {
            queue.put(STOP);
            worker.join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        target.close();
    }

    long dropped() { return dropped.get(); }
}
