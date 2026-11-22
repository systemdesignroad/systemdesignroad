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

final class Money {
    static String of(long paise) {
        String sign = paise < 0 ? "-" : "";
        long p = Math.abs(paise);
        return sign + (p % 100 == 0 ? String.format("₹%,d", p / 100)
                                    : String.format("₹%,d.%02d", p / 100, p % 100));
    }
}

record Product(String sku, String name, String category, long price) { }

final class Cart {
    private final Map<Product, Integer> items = new LinkedHashMap<>();  // (1) as added
    private final Set<String> coupons = new LinkedHashSet<>();

    void add(Product p, int qty) {
        if (qty <= 0) throw new IllegalArgumentException("quantity must be positive");
        items.merge(p, qty, Integer::sum);
    }
    void remove(Product p) { items.remove(p); }
    void applyCoupon(String code) { coupons.add(code.toUpperCase()); }

    Map<Product, Integer> items() { return Collections.unmodifiableMap(items); }
    Set<String> coupons() { return Collections.unmodifiableSet(coupons); }
}

final class Line {
    final Product product;
    final int qty;
    long saving;                                             // (1) the best item discount
    String savingLabel = "";
    long cartShare;                                           // set at the end

    Line(Product product, int qty) { this.product = product; this.qty = qty; }
    long gross() { return product.price() * qty; }
    long net() { return gross() - saving; }
    long paid() { return net() - cartShare; }
}

record Saving(String label, long amount) { }

interface ItemDiscount {                                     // (2) looks at one line
    Optional<Saving> on(Line line);
}

interface CartDiscount {                                 // (3) looks at the running total
    Optional<Saving> on(long runningTotal, Set<String> coupons);
}

record CategoryPercent(String category, int percent) implements ItemDiscount {
    public Optional<Saving> on(Line l) {
        if (!l.product.category().equals(category)) return Optional.empty();
        return Optional.of(new Saving(percent + "% off " + category,
                                      l.gross() * percent / 100));
    }
}

record BuyXGetYFree(String sku, int buy, int free) implements ItemDiscount {
    public Optional<Saving> on(Line l) {
        if (!l.product.sku().equals(sku)) return Optional.empty();
        long freeUnits = (long) (l.qty / (buy + free)) * free;   // (1) whole groups only
        if (freeUnits == 0) return Optional.empty();
        return Optional.of(new Saving("buy " + buy + " get " + free + " free",
                                      freeUnits * l.product.price()));
    }
}

record SpendAndSave(long threshold, long off) implements CartDiscount {
    public Optional<Saving> on(long total, Set<String> coupons) {
        if (total < threshold) return Optional.empty();
        String label = Money.of(off) + " off over " + Money.of(threshold);
        return Optional.of(new Saving(label, off));
    }
}

record Coupon(String code, int percent, long cap) implements CartDiscount {
    public Optional<Saving> on(long total, Set<String> coupons) {
        if (!coupons.contains(code)) return Optional.empty();
        return Optional.of(new Saving(code + ": " + percent + "% up to " + Money.of(cap),
                                      Math.min(total * percent / 100, cap)));
    }
}

record Receipt(List<Line> lines, List<Saving> cartSavings, long gross, long total) {
    String print() {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            sb.append(String.format("%-12s x%d %9s",
                                    l.product.name(), l.qty, Money.of(l.gross())));
            if (l.saving > 0)
                sb.append(String.format("  -%s %s", Money.of(l.saving), l.savingLabel));
            sb.append('\n');
        }
        for (Saving s : cartSavings)
            sb.append(String.format("%-27s -%s%n", s.label(), Money.of(s.amount())));
        sb.append(String.format("%-27s %s", "Total", Money.of(total)));
        return sb.toString();
    }
}

final class Pricer {
    private final List<ItemDiscount> itemDiscounts;
    private final List<CartDiscount> cartDiscounts;

    Pricer(List<ItemDiscount> items, List<CartDiscount> cart) {
        this.itemDiscounts = List.copyOf(items); this.cartDiscounts = List.copyOf(cart);
    }

    Receipt price(Cart cart) {
        List<Line> lines = new ArrayList<>();
        for (var e : cart.items().entrySet()) {
            Line l = new Line(e.getKey(), e.getValue());
            for (ItemDiscount d : itemDiscounts)          // (1) best one, not all of them
                d.on(l).filter(s -> s.amount() > l.saving)
                       .ifPresent(s -> {
                           l.saving = s.amount();
                           l.savingLabel = s.label();
                       });
            lines.add(l);
        }
        long gross = lines.stream().mapToLong(Line::gross).sum();
        long afterItems = lines.stream().mapToLong(Line::net).sum();
        long running = afterItems;
        List<Saving> applied = new ArrayList<>();
        for (CartDiscount d : cartDiscounts) {        // (2) in order, each on what's left
            Optional<Saving> s = d.on(running, cart.coupons());
            if (s.isEmpty()) continue;
            long amount = Math.min(s.get().amount(), running);   // (3) never below zero
            applied.add(new Saving(s.get().label(), amount));
            running -= amount;
        }
        share(lines, afterItems - running);
        return new Receipt(lines, applied, gross, running);
    }

    // Split the cart-level savings across lines by their net price, to the paisa.
    static void share(List<Line> lines, long total) {
        long base = lines.stream().mapToLong(Line::net).sum();
        if (base == 0) return;
        long given = 0;
        long[] rem = new long[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            long exact = total * lines.get(i).net();    // (4) whole share, then leftovers
            lines.get(i).cartShare = exact / base;
            rem[i] = exact % base;
            given += lines.get(i).cartShare;
        }
        Integer[] order = new Integer[lines.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compare(rem[b], rem[a]));
        for (int k = 0; given < total; k++, given++) lines.get(order[k]).cartShare++;
    }
}

final class Inventory {
    private final Map<String, AtomicInteger> stock = new ConcurrentHashMap<>();

    void put(String sku, int n) { stock.put(sku, new AtomicInteger(n)); }
    int left(String sku) { return stock.getOrDefault(sku, new AtomicInteger()).get(); }

    // Take every quantity, or none of them.
    Optional<String> reserve(Map<String, Integer> want) {
        Map<String, Integer> taken = new LinkedHashMap<>();
        for (var e : want.entrySet()) {
            AtomicInteger s = stock.get(e.getKey());
            if (s == null || !take(s, e.getValue())) {
                taken.forEach((sku, n) -> stock.get(sku).addAndGet(n));   // (2) give back
                String sku = e.getKey();
                return Optional.of("only " + left(sku) + " of " + sku + " left");
            }
            taken.put(e.getKey(), e.getValue());
        }
        return Optional.empty();
    }

    private static boolean take(AtomicInteger s, int n) {
        while (true) {                                   // (1) check and take as one step
            int now = s.get();
            if (now < n) return false;
            if (s.compareAndSet(now, now - n)) return true;
        }
    }

    void release(Map<String, Integer> held) {
        held.forEach((sku, n) -> stock.get(sku).addAndGet(n));
    }
}

record Order(String id, Receipt receipt) { }

sealed interface Placed permits Placed.Ok, Placed.Refused {
    record Ok(Order order) implements Placed { }
    record Refused(String why) implements Placed { }
}

final class Checkout {
    private final Pricer pricer;
    private final Inventory inventory;
    private final AtomicInteger nextId = new AtomicInteger(1);

    Checkout(Pricer pricer, Inventory inventory) {
        this.pricer = pricer; this.inventory = inventory;
    }

    Placed placeOrder(Cart cart) {
        Map<String, Integer> want = new LinkedHashMap<>();
        cart.items().forEach((p, q) -> want.put(p.sku(), q));
        Optional<String> shortBy = inventory.reserve(want);   // (1) stock first
        if (shortBy.isPresent()) return new Placed.Refused(shortBy.get());
        Receipt r = pricer.price(cart);                       // (2) prices fixed now
        return new Placed.Ok(new Order("ORD-" + nextId.getAndIncrement(), r));
    }
}
