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

record Message(long offset, String key, String body) { }

interface Subscriber {
    String name();
    void handle(Message m) throws Exception;                 // (1) may be slow, may throw
}

interface RetryPolicy {
    int maxAttempts();
    long delayMillis(int failedAttempts);                    // wait before the next try
}

record Backoff(int maxAttempts, long firstDelayMillis) implements RetryPolicy {
    public long delayMillis(int failedAttempts) {
        return firstDelayMillis << (failedAttempts - 1);     // (2) 100, 200, 400 ...
    }
}

interface Sleeper { void sleep(long millis) throws InterruptedException; }

final class Topic {
    private final String name;
    private final Message[] ring;
    private long next;                            // the offset the next message gets
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition arrived = lock.newCondition();

    Topic(String name, int capacity) {
        this.name = name;
        this.ring = new Message[capacity];
    }

    String name() { return name; }

    long publish(String key, String body) {
        lock.lock();
        try {
            long offset = next++;                            // (1) one order for everyone
            ring[(int) (offset % ring.length)] = new Message(offset, key, body);
            arrived.signalAll();
            return offset;
        } finally {
            lock.unlock();
        }
    }

    // The message at `offset`, waiting up to `waitMillis` for it to be published.
    // If it has already been overwritten, the oldest message still kept instead.
    Message read(long offset, long waitMillis) throws InterruptedException {
        lock.lock();
        try {
            long nanos = TimeUnit.MILLISECONDS.toNanos(waitMillis);
            while (offset >= next) {                         // (2) a loop, not an if
                if (nanos <= 0) return null;
                nanos = arrived.awaitNanos(nanos);
            }
            long oldest = Math.max(0, next - ring.length);
            long at = Math.max(offset, oldest);       // (3) fell behind: skip ahead
            return ring[(int) (at % ring.length)];
        } finally {
            lock.unlock();
        }
    }

    long nextOffset() {
        lock.lock();
        try { return next; } finally { lock.unlock(); }
    }
}

record DeadLetter(String subscriber, Message message, String error) { }

final class DeadLetters {
    private final List<DeadLetter> letters = new CopyOnWriteArrayList<>();
    void add(String subscriber, Message m, Exception e) {
        letters.add(new DeadLetter(subscriber, m, String.valueOf(e.getMessage())));
    }
    List<DeadLetter> all() { return List.copyOf(letters); }
}

final class Subscription implements Runnable {
    private final Topic topic;
    private final Subscriber subscriber;
    private final RetryPolicy retry;
    private final DeadLetters deadLetters;
    private final Sleeper sleeper;
    private volatile long offset;                            // (1) next one to deliver
    private final AtomicLong requested = new AtomicLong(-1); // a seek, waiting to apply
    private volatile boolean running = true;
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();

    Subscription(Topic topic, Subscriber subscriber, long from, RetryPolicy retry,
                 DeadLetters deadLetters, Sleeper sleeper) {
        this.topic = topic; this.subscriber = subscriber; this.offset = from;
        this.retry = retry; this.deadLetters = deadLetters; this.sleeper = sleeper;
    }

    public void run() {
        try {
            while (running) {
                long seekTo = requested.getAndSet(-1);
                if (seekTo >= 0) offset = seekTo;
                Message m = topic.read(offset, 50);
                if (m == null) continue;                     // nothing new yet
                if (m.offset() > offset) skipped.addAndGet(m.offset() - offset);
                deliver(m);
                offset = m.offset() + 1;                     // (2) move on only after
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deliver(Message m) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                subscriber.handle(m);
                return;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (attempt >= retry.maxAttempts()) {          // (3) give up on this one
                    deadLetters.add(subscriber.name(), m, e);
                    return;
                }
                retries.incrementAndGet();
                sleeper.sleep(retry.delayMillis(attempt));
            }
        }
    }

    void seek(long to) { requested.set(to); }               // (4) replay from here
    void stop() { running = false; }
    long offset() { return offset; }
    long skipped() { return skipped.get(); }
    long retries() { return retries.get(); }
    String name() { return subscriber.name(); }
}

final class Broker {
    private final Map<String, Topic> topics = new ConcurrentHashMap<>();
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private final DeadLetters deadLetters = new DeadLetters();
    private final Sleeper sleeper;

    Broker(Sleeper sleeper) { this.sleeper = sleeper; }

    Topic createTopic(String name, int capacity) {
        Topic t = new Topic(name, capacity);
        if (topics.putIfAbsent(name, t) != null)
            throw new IllegalArgumentException("topic exists: " + name);
        return t;
    }

    long publish(String topic, String key, String body) {
        return topic(topic).publish(key, body);              // (1) returns at once
    }

    Subscription subscribe(String topic, Subscriber s, boolean fromStart, RetryPolicy r) {
        Topic t = topic(topic);
        long from = fromStart ? 0 : t.nextOffset();
        Subscription sub = new Subscription(t, s, from, r, deadLetters, sleeper);
        subscriptions.add(sub);
        workers.add(Thread.ofVirtual().name("sub-" + s.name()).start(sub));   // (2)
        return sub;
    }

    Topic topic(String name) {
        Topic t = topics.get(name);
        if (t == null) throw new IllegalArgumentException("no topic " + name);
        return t;
    }

    DeadLetters deadLetters() { return deadLetters; }

    void shutdown() throws InterruptedException {
        for (Subscription s : subscriptions) s.stop();
        for (Thread w : workers) w.join();
    }
}
