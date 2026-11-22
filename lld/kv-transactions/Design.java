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

record Entry(String value, long version) { }

sealed interface Commit permits Commit.Done, Commit.Conflict {
    record Done(int written) implements Commit { }
    record Conflict(String key) implements Commit { }   // (1) what we read has changed
}

interface Storage {
    Optional<Entry> read(String key);
    // Apply all writes at once, but only if every key read is still at the version seen.
    Commit commit(Map<String, Optional<String>> writes, Map<String, Long> reads);
}

final class InMemoryStorage implements Storage {
    private final Map<String, Entry> data = new HashMap<>();
    private final Lock lock = new ReentrantLock();
    private long nextVersion = 1;

    public Optional<Entry> read(String key) {
        lock.lock();
        try { return Optional.ofNullable(data.get(key)); } finally { lock.unlock(); }
    }

    public Commit commit(Map<String, Optional<String>> writes, Map<String, Long> reads) {
        lock.lock();                                // (1) check and write together
        try {
            for (var r : reads.entrySet()) {
                Entry now = data.get(r.getKey());
                long seen = r.getValue(), current = now == null ? 0 : now.version();
                if (seen != current) return new Commit.Conflict(r.getKey());
            }
            long v = nextVersion++;                         // (2) one version per commit
            for (var w : writes.entrySet()) {
                String key = w.getKey();
                Optional<String> value = w.getValue();
                if (value.isPresent()) data.put(key, new Entry(value.get(), v));
                else if (data.containsKey(key)) data.put(key, new Entry(null, v));  // (3)
            }
            return new Commit.Done(writes.size());
        } finally {
            lock.unlock();
        }
    }
}

final class Layer {
    // Optional.empty() means "deleted in this layer".
    final Map<String, Optional<String>> writes = new LinkedHashMap<>();   // (1) in order
}

final class Session {
    private final Storage storage;
    private final Deque<Layer> layers = new ArrayDeque<>();     // innermost first
    private final Map<String, Long> reads = new HashMap<>();    // for the outermost

    Session(Storage storage) { this.storage = storage; }

    boolean inTransaction() { return !layers.isEmpty(); }
    int depth() { return layers.size(); }

    Optional<String> get(String key) {
        for (Layer l : layers)                               // (1) newest change wins
            if (l.writes.containsKey(key)) return l.writes.get(key);
        Optional<Entry> e = storage.read(key);
        if (inTransaction())
            reads.putIfAbsent(key, e.map(Entry::version).orElse(0L));   // (2) remember
        return e.map(Entry::value).filter(Objects::nonNull);
    }

    void set(String key, String value) { write(key, Optional.of(value)); }
    void delete(String key) { write(key, Optional.empty()); }

    private void write(String key, Optional<String> value) {
        if (inTransaction()) { layers.peekFirst().writes.put(key, value); return; }
        storage.commit(Map.of(key, value), Map.of());   // (3) no transaction: at once
    }

    void begin() { layers.addFirst(new Layer()); }

    void rollback() {
        if (layers.isEmpty()) throw new IllegalStateException("no transaction");
        layers.removeFirst();                                // (4) just forget this layer
        if (layers.isEmpty()) reads.clear();
    }

    Commit commit() {
        if (layers.isEmpty()) throw new IllegalStateException("no transaction");
        Layer top = layers.removeFirst();
        if (!layers.isEmpty()) {                     // (1) nested: fold into parent
            layers.peekFirst().writes.putAll(top.writes);
            return new Commit.Done(top.writes.size());
        }
        Map<String, Long> seen = new HashMap<>(reads);
        reads.clear();
        return storage.commit(top.writes, seen);             // (2) all or nothing
    }
}
