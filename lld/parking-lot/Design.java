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

enum SpotSize { SMALL, MEDIUM, LARGE }   // (1) order matters: smallest to largest

enum VehicleType {
    BIKE(SpotSize.SMALL), CAR(SpotSize.MEDIUM), TRUCK(SpotSize.LARGE);
    private final SpotSize needs;
    VehicleType(SpotSize needs) { this.needs = needs; }
    SpotSize needs() { return needs; }
    boolean fits(SpotSize spot) { return spot.compareTo(needs) >= 0; }
}

record Vehicle(String plate, VehicleType type) { }

enum PaymentMode { CASH, CARD, UPI }

class ParkingSpot {
    private final String id;
    private final SpotSize size;
    private final AtomicReference<Vehicle> occupant = new AtomicReference<>();   // (1) null means free

    ParkingSpot(String id, SpotSize size) { this.id = id; this.size = size; }

    boolean tryOccupy(Vehicle v) { return occupant.compareAndSet(null, v); }   // (2) only succeeds if still free
    void release() { occupant.set(null); }
    boolean isFree() { return occupant.get() == null; }
    SpotSize size() { return size; }
    String id() { return id; }
}

interface FloorListener { void freeSpotsChanged(int level, SpotSize size, int free); }

class ParkingFloor {
    private final int level;
    private final Map<SpotSize, List<ParkingSpot>> bySize = new EnumMap<>(SpotSize.class);
    private final Map<SpotSize, AtomicInteger> free = new EnumMap<>(SpotSize.class);
    private final List<FloorListener> listeners = new CopyOnWriteArrayList<>();   // (1) safe to loop over while others subscribe

    ParkingFloor(int level, List<ParkingSpot> spots) {
        this.level = level;
        for (SpotSize s : SpotSize.values()) {
            bySize.put(s, new ArrayList<>());
            free.put(s, new AtomicInteger());
        }
        for (ParkingSpot sp : spots) {
            bySize.get(sp.size()).add(sp);
            free.get(sp.size()).incrementAndGet();
        }
    }

    Optional<ParkingSpot> claim(Vehicle v, SpotSize size) {
        if (free.get(size).get() == 0) return Optional.empty();   // (2) quick skip when this size is full
        for (ParkingSpot sp : bySize.get(size)) {
            if (sp.isFree() && sp.tryOccupy(v)) {                  // (3) isFree() is a cheap filter; tryOccupy() decides
                changed(size, free.get(size).decrementAndGet());
                return Optional.of(sp);
            }
        }
        return Optional.empty();
    }

    void release(ParkingSpot sp) {
        sp.release();
        changed(sp.size(), free.get(sp.size()).incrementAndGet());
    }

    int freeCount(SpotSize s) { return free.get(s).get(); }
    void subscribe(FloorListener l) { listeners.add(l); }
    private void changed(SpotSize s, int n) {
        for (FloorListener l : listeners) l.freeSpotsChanged(level, s, n);
    }
}

record SpotAssignment(ParkingFloor floor, ParkingSpot spot) { }

record Ticket(String id, Vehicle vehicle, ParkingSpot spot,
              ParkingFloor floor, Instant entry) { }

interface SpotAllocationStrategy {
    Optional<SpotAssignment> allocate(List<ParkingFloor> floors, Vehicle v);
}

class BestFitLowestFloor implements SpotAllocationStrategy {
    public Optional<SpotAssignment> allocate(List<ParkingFloor> floors, Vehicle v) {
        for (SpotSize size : SpotSize.values()) {        // (1) smallest size first: best fit
            if (!v.type().fits(size)) continue;
            for (ParkingFloor f : floors) {              // (2) then lowest floor first
                Optional<ParkingSpot> sp = f.claim(v, size);
                if (sp.isPresent()) return Optional.of(new SpotAssignment(f, sp.get()));
            }
        }
        return Optional.empty();
    }
}

interface PricingStrategy { long fee(Ticket t, Instant exit); }

class HourlyPricing implements PricingStrategy {
    private final Map<SpotSize, Long> perHour;   // paise per hour
    private final Duration grace;
    HourlyPricing(Map<SpotSize, Long> perHour, Duration grace) {
        this.perHour = Map.copyOf(perHour);
        this.grace = grace;
    }

    public long fee(Ticket t, Instant exit) {
        Duration stay = Duration.between(t.entry(), exit);
        if (stay.compareTo(grace) <= 0) return 0;     // (1) free for the first few minutes
        long hours = (stay.toMinutes() + 59) / 60;    // (2) round up to whole hours
        return hours * perHour.get(t.spot().size());
    }
}

interface PaymentProcessor { boolean charge(long amountPaise, PaymentMode mode); }
record Receipt(String ticketId, String plate, Duration stay,
               long amountPaise, PaymentMode mode) { }

class LotFullException extends RuntimeException {
    LotFullException(VehicleType t) { super("No free spot for a " + t); }
}

class ParkingLot {
    private final List<ParkingFloor> floors;
    private final SpotAllocationStrategy allocation;
    private final PricingStrategy pricing;
    private final PaymentProcessor payments;
    private final Clock clock;
    private final Map<String, Ticket> activeByTicket = new ConcurrentHashMap<>();
    private final Map<String, Ticket> activeByPlate = new ConcurrentHashMap<>();

    ParkingLot(List<ParkingFloor> floors, SpotAllocationStrategy allocation,
               PricingStrategy pricing, PaymentProcessor payments, Clock clock) {   // (1) everything handed in: easy to test and to change
        this.floors = List.copyOf(floors); this.allocation = allocation;
        this.pricing = pricing; this.payments = payments; this.clock = clock;
    }

    Ticket park(Vehicle v) {
        if (activeByPlate.containsKey(v.plate()))
            throw new IllegalStateException(v.plate() + " is already parked");
        SpotAssignment a = allocation.allocate(floors, v)
            .orElseThrow(() -> new LotFullException(v.type()));
        String id = UUID.randomUUID().toString();
        Ticket t = new Ticket(id, v, a.spot(), a.floor(), clock.instant());
        if (activeByPlate.putIfAbsent(v.plate(), t) != null) {   // (2) the same plate at two gates at once
            a.floor().release(a.spot());
            throw new IllegalStateException(v.plate() + " is already parked");
        }
        activeByTicket.put(t.id(), t);
        return t;
    }

    Optional<Ticket> activeTicketFor(String plate) {
        return Optional.ofNullable(activeByPlate.get(plate));
    }

    Receipt unpark(String ticketId, PaymentMode mode) {
        Ticket t = activeByTicket.remove(ticketId);   // (3) claim the ticket first: only one exit can get it
        if (t == null)
            throw new IllegalArgumentException("Unknown or already used ticket");
        Instant exit = clock.instant();
        long fee = pricing.fee(t, exit);
        if (fee > 0 && !payments.charge(fee, mode)) {
            activeByTicket.put(ticketId, t);           // (4) payment failed: give the ticket back
            throw new IllegalStateException("Payment failed, please try again");
        }
        activeByPlate.remove(t.vehicle().plate(), t);
        t.floor().release(t.spot());
        Duration stay = Duration.between(t.entry(), exit);
        return new Receipt(t.id(), t.vehicle().plate(), stay, fee, mode);
    }
}

class EntryGate {
    private final String id; private final ParkingLot lot;
    EntryGate(String id, ParkingLot lot) { this.id = id; this.lot = lot; }
    Ticket arrived(Vehicle v) { return lot.park(v); }
}

class ExitGate {
    private final String id; private final ParkingLot lot;
    ExitGate(String id, ParkingLot lot) { this.id = id; this.lot = lot; }
    Receipt leaving(String ticketId, PaymentMode mode) {
        return lot.unpark(ticketId, mode);
    }
}

class DisplayBoard implements FloorListener {
    private final Map<SpotSize, Integer> shown = new ConcurrentHashMap<>();
    public void freeSpotsChanged(int level, SpotSize size, int free) {
        shown.put(size, free);
        System.out.println("  [board F" + level + "] " + size + " free: " + free);
    }
}
