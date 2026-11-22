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

record Location(double x, double y) {                       // km on a city grid
    double kmTo(Location o) { return Math.hypot(x - o.x, y - o.y); }
}

final class Money {
    static String of(long paise) {
        return paise % 100 == 0 ? String.format("₹%,d", paise / 100)
                                : String.format("₹%,d.%02d", paise / 100, paise % 100);
    }
}

record MenuItem(String id, String name, long price) { }

final class Restaurant {
    private final String id;
    private final String name;
    private final Location at;
    private final Map<String, MenuItem> menu = new LinkedHashMap<>();
    private volatile boolean open = true;

    Restaurant(String id, String name, Location at) {
        this.id = id; this.name = name; this.at = at;
    }

    String id() { return id; }
    String name() { return name; }
    Location at() { return at; }
    boolean isOpen() { return open; }
    void setOpen(boolean o) { open = o; }

    synchronized void put(MenuItem i) { menu.put(i.id(), i); }   // (1) add, or new price
    synchronized Optional<MenuItem> item(String id) {
        return Optional.ofNullable(menu.get(id));
    }
}

record OrderLine(String name, long unitPrice, int qty) {    // (1) a copy, not a link
    long total() { return unitPrice * qty; }
}

final class Cart {
    private Restaurant restaurant;
    private final Map<MenuItem, Integer> items = new LinkedHashMap<>();

    Optional<String> add(Restaurant r, String itemId, int qty) {
        if (restaurant != null && restaurant != r)          // (2) one restaurant per cart
            return Optional.of("your cart has food from " + restaurant.name());
        Optional<MenuItem> item = r.item(itemId);
        if (item.isEmpty()) return Optional.of(itemId + " isn't on the menu");
        restaurant = r;
        items.merge(item.get(), qty, Integer::sum);
        return Optional.empty();
    }

    Restaurant restaurant() { return restaurant; }
    boolean isEmpty() { return items.isEmpty(); }

    List<OrderLine> lines() {
        List<OrderLine> out = new ArrayList<>();
        items.forEach((i, q) -> out.add(new OrderLine(i.name(), i.price(), q)));
        return out;
    }
}

record Bill(long food, long delivery, long discount) {
    long total() { return food + delivery - discount; }
    @Override public String toString() {
        return "food " + Money.of(food) + " + delivery " + Money.of(delivery)
             + (discount > 0 ? " - " + Money.of(discount) : "")
             + " = " + Money.of(total());
    }
}

interface Pricing { Bill bill(List<OrderLine> lines, double km); }

record StandardPricing(long freeAbove, long base, long perKm) implements Pricing {
    public Bill bill(List<OrderLine> lines, double km) {
        long food = lines.stream().mapToLong(OrderLine::total).sum();
        long fee = food >= freeAbove ? 0 : base + Math.round(Math.ceil(km) * perKm);
        return new Bill(food, fee, 0);                       // (1) whole km, rounded up
    }
}

enum Actor { CUSTOMER, RESTAURANT, RIDER, SYSTEM }

enum OrderStatus { PLACED, ACCEPTED, READY, PICKED_UP, DELIVERED, REJECTED, CANCELLED }

interface OrderListener { void changed(Order o, OrderStatus from, OrderStatus to); }

final class Order {
    // (1) each move, and the only actor allowed to make it
    private static final Map<OrderStatus, Map<OrderStatus, Actor>> MOVES = Map.of(
        OrderStatus.PLACED, Map.of(OrderStatus.ACCEPTED, Actor.RESTAURANT,
                                   OrderStatus.REJECTED, Actor.RESTAURANT,
                                   OrderStatus.CANCELLED, Actor.CUSTOMER),
        OrderStatus.ACCEPTED, Map.of(OrderStatus.READY, Actor.RESTAURANT,
                                     OrderStatus.CANCELLED, Actor.SYSTEM),
        OrderStatus.READY, Map.of(OrderStatus.PICKED_UP, Actor.RIDER),
        OrderStatus.PICKED_UP, Map.of(OrderStatus.DELIVERED, Actor.RIDER));

    private final String id;
    private final String customer;
    private final Restaurant restaurant;
    private final Location dropAt;
    private final List<OrderLine> lines;
    private final Bill bill;
    private OrderStatus status = OrderStatus.PLACED;
    private Rider rider;
    private boolean waiting;

    Order(String id, String customer, Restaurant r, Location dropAt,
          List<OrderLine> lines, Bill bill) {
        this.id = id; this.customer = customer; this.restaurant = r; this.dropAt = dropAt;
        this.lines = List.copyOf(lines); this.bill = bill;
    }

    String id() { return id; }
    String customer() { return customer; }
    Restaurant restaurant() { return restaurant; }
    Location dropAt() { return dropAt; }
    List<OrderLine> lines() { return lines; }
    Bill bill() { return bill; }
    synchronized OrderStatus status() { return status; }
    synchronized Rider rider() { return rider; }
    synchronized void setRider(Rider r) { rider = r; }
    synchronized boolean startWaiting() {
        boolean was = waiting;
        waiting = true;
        return !was;
    }
    synchronized void stopWaiting() { waiting = false; }
    synchronized boolean needsRider() {
        return rider == null
            && (status == OrderStatus.ACCEPTED || status == OrderStatus.READY);
    }

    // Moves the order if this actor may make this move from where it is now.
    synchronized Optional<OrderStatus> move(OrderStatus to, Actor by) {   // (2) in turn
        Actor allowed = MOVES.getOrDefault(status, Map.of()).get(to);
        if (allowed != by) return Optional.empty();
        OrderStatus from = status;
        status = to;
        return Optional.of(from);
    }
}

final class Rider {
    private final String id;
    private final String name;
    private volatile Location at;
    private final AtomicReference<Order> current = new AtomicReference<>();
    private final AtomicInteger delivered = new AtomicInteger();

    Rider(String id, String name, Location at) {
        this.id = id; this.name = name; this.at = at;
    }

    String id() { return id; }
    String name() { return name; }
    Location at() { return at; }
    void moveTo(Location l) { at = l; }
    boolean isFree() { return current.get() == null; }
    int deliveredToday() { return delivered.get(); }

    boolean claim(Order o) { return current.compareAndSet(null, o); }   // (1) one winner
    void release(Order o, boolean done) {
        if (current.compareAndSet(o, null) && done) delivered.incrementAndGet();
    }
}

interface RiderAssigner {
    // Riders to try, best first. The service claims the first one it can.
    List<Rider> candidates(Order o, Collection<Rider> riders);
}

record NearestFirst(double maxKm) implements RiderAssigner {
    public List<Rider> candidates(Order o, Collection<Rider> riders) {
        Location pickup = o.restaurant().at();
        return riders.stream()
            .filter(r -> r.isFree() && r.at().kmTo(pickup) <= maxKm)   // (2) a hint only
            .sorted(Comparator.comparingDouble((Rider r) -> r.at().kmTo(pickup))
                              .thenComparing(Rider::id))
            .toList();
    }
}

sealed interface Result permits Result.Done, Result.Refused {
    record Done(String what) implements Result { }
    record Refused(String why) implements Result { }
    static Result done(String w) { return new Done(w); }
    static Result no(String w) { return new Refused(w); }
}

final class DeliveryService {
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Rider> riders = new ConcurrentHashMap<>();
    private final List<OrderListener> listeners = new CopyOnWriteArrayList<>();
    private final Queue<Order> waiting = new ConcurrentLinkedQueue<>();   // no rider yet
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Pricing pricing;
    private final RiderAssigner assigner;

    DeliveryService(Pricing pricing, RiderAssigner assigner) {
        this.pricing = pricing; this.assigner = assigner;
    }

    void addRider(Rider r) { riders.put(r.id(), r); }
    void listen(OrderListener l) { listeners.add(l); }
    Order order(String id) { return orders.get(id); }
    Collection<Order> all() { return orders.values(); }
    Collection<Rider> riders() { return riders.values(); }

    Result place(String customer, Cart cart, Location dropAt) {
        if (cart.isEmpty()) return Result.no("the cart is empty");
        Restaurant r = cart.restaurant();
        if (!r.isOpen()) return Result.no(r.name() + " is closed");
        List<OrderLine> lines = cart.lines();               // (1) prices copied now
        Bill bill = pricing.bill(lines, r.at().kmTo(dropAt));
        String id = "O" + nextId.getAndIncrement();
        Order o = new Order(id, customer, r, dropAt, lines, bill);
        orders.put(o.id(), o);
        return Result.done(o.id() + " placed: " + bill);
    }

    Result accept(String orderId) {
        Order o = orders.get(orderId);
        if (!move(o, OrderStatus.ACCEPTED, Actor.RESTAURANT))
            return Result.no(o.id() + " is " + o.status());
        return Result.done(o.id() + " accepted; " + assignRider(o));   // (2) rider goes
    }

    Result reject(String orderId) {
        Order o = orders.get(orderId);
        if (!move(o, OrderStatus.REJECTED, Actor.RESTAURANT))
            return Result.no(o.id() + " is " + o.status());
        return Result.done(o.id() + " rejected; refund " + Money.of(o.bill().total()));
    }

    String assignRider(Order o) {
        for (Rider r : assigner.candidates(o, riders.values())) {
            if (r.claim(o)) {                        // (1) lost the race? next one
                o.setRider(r);
                double km = r.at().kmTo(o.restaurant().at());
                return r.name() + " is " + String.format("%.1f", km) + " km away";
            }
        }
        if (o.startWaiting()) waiting.add(o);                // (2) not forgotten
        return "no rider free yet";
    }

    // Called whenever a rider becomes free: oldest waiting order first.
    private String serveWaiting() {
        for (int n = waiting.size(); n > 0; n--) {
            Order o = waiting.poll();
            if (o == null) break;
            o.stopWaiting();
            if (!o.needsRider()) continue;                   // cancelled, or got one
            assignRider(o);
            if (o.rider() != null) return "; " + o.id() + " goes to " + o.rider().name();
        }
        return "";
    }

    Result ready(String orderId) {
        Order o = orders.get(orderId);
        if (!move(o, OrderStatus.READY, Actor.RESTAURANT))
            return Result.no(o.id() + " is " + o.status());
        if (o.rider() == null) return Result.done(o.id() + " ready; " + assignRider(o));
        return Result.done(o.id() + " ready for " + o.rider().name());
    }

    Result pickUp(String orderId, String riderId) {
        Order o = orders.get(orderId);
        if (o.rider() == null || !o.rider().id().equals(riderId)) {   // (3) theirs only
            return Result.no(riderId + " isn't the rider for " + o.id());
        }
        if (!move(o, OrderStatus.PICKED_UP, Actor.RIDER))
            return Result.no(o.id() + " is " + o.status());
        return Result.done(o.rider().name() + " picked up " + o.id());
    }

    Result deliver(String orderId, String riderId) {
        Order o = orders.get(orderId);
        if (o.rider() == null || !o.rider().id().equals(riderId))
            return Result.no(riderId + " isn't the rider for " + o.id());
        if (!move(o, OrderStatus.DELIVERED, Actor.RIDER))
            return Result.no(o.id() + " is " + o.status());
        o.rider().moveTo(o.dropAt());
        o.rider().release(o, true);                          // (4) free for the next one
        return Result.done(o.id() + " delivered by " + o.rider().name() + serveWaiting());
    }

    Result cancelByCustomer(String orderId) {
        Order o = orders.get(orderId);
        if (!move(o, OrderStatus.CANCELLED, Actor.CUSTOMER))  // (5) only before cooking
            return Result.no("can't cancel: " + o.id() + " is " + o.status());
        return Result.done(o.id() + " cancelled; refund " + Money.of(o.bill().total()));
    }

    Result cancelBySystem(String orderId, String why) {
        Order o = orders.get(orderId);
        if (!move(o, OrderStatus.CANCELLED, Actor.SYSTEM))
            return Result.no(o.id() + " is " + o.status());
        Rider r = o.rider();
        if (r != null) r.release(o, false);
        return Result.done(o.id() + " cancelled (" + why + "); refund "
                           + Money.of(o.bill().total())
                           + (r != null ? serveWaiting() : ""));
    }

    private boolean move(Order o, OrderStatus to, Actor by) {
        Optional<OrderStatus> from = o.move(to, by);
        if (from.isEmpty()) return false;
        for (OrderListener l : listeners) l.changed(o, from.get(), to);   // (6) after
        return true;
    }
}
