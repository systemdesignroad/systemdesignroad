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

interface Cache<K, V> {
    Optional<V> get(K key);
    void put(K key, V value);
    boolean remove(K key);
    int size();
}

interface EvictionListener<K, V> { void evicted(K key, V value); }

final class Node<K, V> {
    final K key;              // (1) never changes; needed to clean the map on eviction
    V value;                  // (2) changes when put() updates an existing key
    Node<K, V> prev, next;
    Node(K key, V value) { this.key = key; this.value = value; }
}

final class DoublyLinkedList<K, V> {
    private final Node<K, V> head = new Node<>(null, null);   // (1) sentinel before the most recent
    private final Node<K, V> tail = new Node<>(null, null);   // (2) sentinel after the least recent
    private int size;

    DoublyLinkedList() { head.next = tail; tail.prev = head; }

    void addFirst(Node<K, V> n) {
        n.prev = head;
        n.next = head.next;          // (3) the old first node (or tail, if empty)
        head.next.prev = n;
        head.next = n;
        size++;
    }

    void unlink(Node<K, V> n) {
        n.prev.next = n.next;        // (4) neighbours skip over n
        n.next.prev = n.prev;
        n.prev = null;               // (5) n no longer points into the list
        n.next = null;
        size--;
    }

    void moveToFront(Node<K, V> n) { unlink(n); addFirst(n); }

    Node<K, V> removeLast() {
        if (size == 0) return null;
        Node<K, V> last = tail.prev;
        unlink(last);
        return last;
    }

    int size() { return size; }
}

final class LRUCache<K, V> implements Cache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final DoublyLinkedList<K, V> order = new DoublyLinkedList<>();
    private final EvictionListener<K, V> listener;
    private final ReentrantLock lock = new ReentrantLock();

    LRUCache(int capacity) { this(capacity, (k, v) -> { }); }   // (1) no listener: do nothing

    LRUCache(int capacity, EvictionListener<K, V> listener) {
        if (capacity <= 0)
            throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 4 / 3 + 1);           // (2) sized so it never resizes
        this.listener = Objects.requireNonNull(listener);
    }

    public Optional<V> get(K key) {
        Objects.requireNonNull(key);
        lock.lock();
        try {
            Node<K, V> n = map.get(key);
            if (n == null) return Optional.empty();
            order.moveToFront(n);
            return Optional.of(n.value);
        } finally { lock.unlock(); }                               // (3) always released
    }

    public void put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        Node<K, V> victim = null;
        lock.lock();
        try {
            Node<K, V> n = map.get(key);
            if (n != null) {                     // (1) existing key: update, no eviction
                n.value = value;
                order.moveToFront(n);
                return;
            }
            if (map.size() == capacity) {        // (2) full: make room first
                victim = order.removeLast();
                map.remove(victim.key);
            }
            n = new Node<>(key, value);
            order.addFirst(n);
            map.put(key, n);
        } finally { lock.unlock(); }
        if (victim != null) listener.evicted(victim.key, victim.value);   // (3) outside the lock
    }

    public boolean remove(K key) {
        lock.lock();
        try {
            Node<K, V> n = map.remove(key);
            if (n == null) return false;
            order.unlink(n);
            return true;
        } finally { lock.unlock(); }
    }

    public int size() {
        lock.lock();
        try { return map.size(); } finally { lock.unlock(); }
    }
}
