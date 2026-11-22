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

enum RoomType {
    STANDARD(4_000_00), DELUXE(6_500_00), SUITE(12_000_00);  // a night, in paise
    final long baseRate;
    RoomType(long baseRate) { this.baseRate = baseRate; }
}

record Stay(LocalDate checkIn, LocalDate checkOut) {
    Stay {
        if (!checkOut.isAfter(checkIn))                      // (1) at least one night
            throw new IllegalArgumentException("check-out must be after check-in");
    }
    long nights() { return ChronoUnit.DAYS.between(checkIn, checkOut); }
    boolean overlaps(Stay o) {                               // (2) check-out day is free
        return checkIn.isBefore(o.checkOut) && o.checkIn.isBefore(checkOut);
    }
    @Override public String toString() { return checkIn + " to " + checkOut; }
}

record Guest(String id, String name) { }

enum Status { HELD, CONFIRMED, CHECKED_IN, CHECKED_OUT, CANCELLED, EXPIRED }

final class Booking {
    private final String id;
    private final Guest guest;
    private final Room room;
    private final Stay stay;
    private final long price;
    private final LocalDateTime heldUntil;
    private Status status = Status.HELD;
    private long refund;

    Booking(String id, Guest guest, Room room, Stay stay, long price,
            LocalDateTime heldUntil) {
        this.id = id; this.guest = guest; this.room = room; this.stay = stay;
        this.price = price; this.heldUntil = heldUntil;
    }

    String id() { return id; }
    Guest guest() { return guest; }
    Room room() { return room; }
    Stay stay() { return stay; }
    long price() { return price; }
    Status status() { return status; }
    long refund() { return refund; }

    // Does this booking stop anyone else having the room for these nights?
    boolean blocks(LocalDateTime now) {              // (1) an old hold blocks nothing
        return switch (status) {
            case HELD -> now.isBefore(heldUntil);
            case CONFIRMED, CHECKED_IN -> true;
            case CHECKED_OUT, CANCELLED, EXPIRED -> false;
        };
    }

    boolean holdExpired(LocalDateTime now) {
        return status == Status.HELD && !now.isBefore(heldUntil);
    }

    void moveTo(Status next) {                               // (2) only the allowed moves
        boolean ok = switch (status) {
            case HELD -> next == Status.CONFIRMED || next == Status.EXPIRED
                         || next == Status.CANCELLED;
            case CONFIRMED -> next == Status.CHECKED_IN || next == Status.CANCELLED;
            case CHECKED_IN -> next == Status.CHECKED_OUT;
            case CHECKED_OUT, CANCELLED, EXPIRED -> false;
        };
        if (!ok) throw new IllegalStateException(id + ": " + status + " -> " + next);
        status = next;
    }

    void refunded(long paise) { refund = paise; }
}

final class Room {
    private final String number;
    private final RoomType type;
    private final NavigableMap<LocalDate, Booking> byCheckIn = new TreeMap<>();

    Room(String number, RoomType type) { this.number = number; this.type = type; }

    String number() { return number; }
    RoomType type() { return type; }

    // Caller holds the lock for this room type.
    boolean isFree(Stay s, LocalDateTime now) {
        // Only bookings that start before we leave can overlap.
        var before = byCheckIn.headMap(s.checkOut(), false).descendingMap();
        for (Booking b : before.values()) {
            if (!b.blocks(now)) continue;                    // (1) ignore expired holds
            if (b.stay().overlaps(s)) return false;
            if (!b.stay().checkOut().isAfter(s.checkIn())) break;   // (2) earlier: done
        }
        return true;
    }

    void add(Booking b, LocalDateTime now) {
        Booking old = byCheckIn.put(b.stay().checkIn(), b);
        if (old != null && old.blocks(now))          // (3) replaces only dead ones
            throw new IllegalStateException("two bookings start " + b.stay().checkIn());
    }

    Collection<Booking> calendar() { return byCheckIn.values(); }
}

interface PricingStrategy { long price(RoomType type, Stay stay); }

record WeekendPricing(int weekendPercent) implements PricingStrategy {
    public long price(RoomType type, Stay stay) {
        long total = 0;
        for (LocalDate d = stay.checkIn(); d.isBefore(stay.checkOut());
             d = d.plusDays(1)) {
            DayOfWeek w = d.getDayOfWeek();
            boolean weekend = w == DayOfWeek.FRIDAY || w == DayOfWeek.SATURDAY;
            total += weekend ? type.baseRate * (100 + weekendPercent) / 100
                             : type.baseRate;
        }
        return total;                                        // (1) one night at a time
    }
}

interface CancellationPolicy { long refund(Booking b, LocalDateTime now); }

record FreeUntil(int hoursBefore, int lateRefundPercent) implements CancellationPolicy {
    static final LocalTime CHECK_IN_TIME = LocalTime.of(14, 0);
    public long refund(Booking b, LocalDateTime now) {
        LocalDateTime arrive = b.stay().checkIn().atTime(CHECK_IN_TIME);
        if (now.isBefore(arrive.minusHours(hoursBefore))) return b.price();
        return b.price() * lateRefundPercent / 100;          // (2) late: part back
    }
}

interface Clock { LocalDateTime now(); }

final class Money {
    static String of(long paise) {
        return paise % 100 == 0 ? String.format("₹%,d", paise / 100)
                                : String.format("₹%,d.%02d", paise / 100, paise % 100);
    }
}

sealed interface Result permits Result.Done, Result.Refused {
    record Done(String what, Booking booking) implements Result { }
    record Refused(String why) implements Result { }
    static Result done(String w, Booking b) { return new Done(w, b); }
    static Result no(String w) { return new Refused(w); }
}

final class Hotel {
    static final Duration HOLD_FOR = Duration.ofMinutes(10);
    private final Map<RoomType, List<Room>> rooms = new EnumMap<>(RoomType.class);
    private final Map<RoomType, ReentrantLock> locks = new EnumMap<>(RoomType.class);
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final PricingStrategy pricing;
    private final CancellationPolicy cancellation;
    private final Clock clock;

    Hotel(PricingStrategy pricing, CancellationPolicy cancellation, Clock clock) {
        this.pricing = pricing; this.cancellation = cancellation; this.clock = clock;
        for (RoomType t : RoomType.values()) {
            rooms.put(t, new ArrayList<>());
            locks.put(t, new ReentrantLock());               // (1) a lock per type
        }
    }

    void addRoom(String number, RoomType type) {
        ReentrantLock lock = locks.get(type);
        lock.lock();
        try { rooms.get(type).add(new Room(number, type)); } finally { lock.unlock(); }
    }

    // How many rooms of this type are free for the whole stay?
    int available(RoomType type, Stay stay) {
        ReentrantLock lock = locks.get(type);
        lock.lock();
        try {
            LocalDateTime now = clock.now();
            return (int) rooms.get(type).stream()
                              .filter(r -> r.isFree(stay, now)).count();
        } finally {
            lock.unlock();
        }
    }

    long quote(RoomType type, Stay stay) { return pricing.price(type, stay); }

    Result hold(Guest g, RoomType type, Stay stay) {
        ReentrantLock lock = locks.get(type);
        lock.lock();                                 // (1) check and claim together
        try {
            LocalDateTime now = clock.now();
            Optional<Room> free = rooms.get(type).stream()
                                       .filter(r -> r.isFree(stay, now)).findFirst();
            if (free.isEmpty()) return Result.no("no " + type + " free for " + stay);
            Booking b = new Booking("B" + nextId.getAndIncrement(), g, free.get(), stay,
                                    pricing.price(type, stay), now.plus(HOLD_FOR));
            free.get().add(b, now);
            bookings.put(b.id(), b);
            return Result.done(b.id() + ": room " + free.get().number() + " held for "
                               + g.name() + " until " + now.plus(HOLD_FOR).toLocalTime()
                               + ", " + Money.of(b.price()), b);
        } finally {
            lock.unlock();
        }
    }

    Result confirm(String bookingId) {
        Booking b = find(bookingId);
        ReentrantLock lock = locks.get(b.room().type());
        lock.lock();
        try {
            if (b.holdExpired(clock.now())) {        // (2) too late: the room is gone
                b.moveTo(Status.EXPIRED);
                return Result.no(b.id() + ": hold expired");
            }
            if (b.status() == Status.CONFIRMED)      // (3) a retry changes nothing
                return Result.done(b.id() + ": already confirmed", b);
            if (b.status() != Status.HELD) return Result.no(b.id() + " is " + b.status());
            b.moveTo(Status.CONFIRMED);
            return Result.done(b.id() + ": confirmed, room " + b.room().number(), b);
        } finally {
            lock.unlock();
        }
    }

    Result cancel(String bookingId) {
        Booking b = find(bookingId);
        ReentrantLock lock = locks.get(b.room().type());
        lock.lock();
        try {
            LocalDateTime now = clock.now();
            switch (b.status()) {
                case HELD -> {
                    if (b.holdExpired(now)) { b.moveTo(Status.EXPIRED); break; }
                    b.moveTo(Status.CANCELLED);      // (1) never paid, nothing back
                }
                case CONFIRMED -> {
                    b.refunded(cancellation.refund(b, now));
                    b.moveTo(Status.CANCELLED);
                    return Result.done(b.id() + ": cancelled, refund "
                                       + Money.of(b.refund()), b);
                }
                default -> { return Result.no(b.id() + " is " + b.status()); }
            }
            return Result.done(b.id() + ": " + b.status(), b);
        } finally {
            lock.unlock();
        }
    }

    Result checkIn(String bookingId) {
        Booking b = find(bookingId);
        ReentrantLock lock = locks.get(b.room().type());
        lock.lock();
        try {
            LocalDate today = clock.now().toLocalDate();
            if (b.status() != Status.CONFIRMED)
                return Result.no(b.id() + " is " + b.status());
            if (!today.equals(b.stay().checkIn()))           // (2) not early, not late
                return Result.no(b.id() + ": check-in is on " + b.stay().checkIn());
            b.moveTo(Status.CHECKED_IN);
            return Result.done(b.guest().name() + " is in room " + b.room().number(), b);
        } finally {
            lock.unlock();
        }
    }

    Result checkOut(String bookingId) {
        Booking b = find(bookingId);
        ReentrantLock lock = locks.get(b.room().type());
        lock.lock();
        try {
            if (b.status() != Status.CHECKED_IN)
                return Result.no(b.id() + " is " + b.status());
            b.moveTo(Status.CHECKED_OUT);
            return Result.done(b.guest().name() + " left room " + b.room().number(), b);
        } finally {
            lock.unlock();
        }
    }

    Booking find(String id) {
        Booking b = bookings.get(id);
        if (b == null) throw new IllegalArgumentException("no booking " + id);
        return b;
    }

    Collection<Booking> all() { return bookings.values(); }
    List<Room> roomsOf(RoomType t) { return rooms.get(t); }
}

final class FakeClock implements Clock {
    private LocalDateTime now;
    FakeClock(LocalDateTime start) { now = start; }
    public synchronized LocalDateTime now() { return now; }
    synchronized void advance(Duration d) { now = now.plus(d); }
}
