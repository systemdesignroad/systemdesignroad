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

enum Denomination {
    ONE(100, false), TWO(200, false), FIVE(500, false), TEN(1000, false),
    TWENTY(2000, true), FIFTY(5000, true), HUNDRED(10000, true);
    final long paise;
    final boolean note;
    Denomination(long paise, boolean note) { this.paise = paise; this.note = note; }
}

record Product(String name, long pricePaise) { }

final class Slot {
    private final String code;
    private Product product;
    private int count;
    private boolean faulty;

    Slot(String code, Product product, int count) {
        this.code = code; this.product = product; this.count = count;
    }

    String code() { return code; }
    Product product() { return product; }
    int count() { return count; }
    boolean canSell() { return count > 0 && !faulty; }

    void takeOne() {
        if (count == 0) throw new IllegalStateException("slot " + code + " is empty");   // (1)
        count--;
    }

    void markFaulty() { faulty = true; }
    void restock(Product p, int n) { product = p; count = n; faulty = false; }   // (2)
}

final class Inventory {
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    void add(Slot s) { slots.put(s.code(), s); }
    Optional<Slot> slot(String code) { return Optional.ofNullable(slots.get(code)); }
}

final class CashBox {
    private final Map<Denomination, Integer> held = new EnumMap<>(Denomination.class);

    void deposit(List<Denomination> money) {
        for (Denomination d : money) held.merge(d, 1, Integer::sum);
    }

    void payOut(List<Denomination> coins) {
        for (Denomination d : coins) {
            int left = held.getOrDefault(d, 0) - 1;
            if (left < 0) throw new IllegalStateException("no " + d + " left");
            held.put(d, left);
        }
    }

    // Exact change from the coins actually held (plus `extra`, the customer's coins).
    Optional<List<Denomination>> planChange(long amount, List<Denomination> extra) {
        Map<Denomination, Integer> avail = new EnumMap<>(held);
        for (Denomination d : extra) avail.merge(d, 1, Integer::sum);   // (1)
        List<Denomination> coins = new ArrayList<>();
        for (Denomination d : Denomination.values()) if (!d.note) coins.add(0, d);   // (2)
        List<Denomination> out = new ArrayList<>();
        return fill(amount, coins, 0, avail, out) ? Optional.of(out) : Optional.empty();
    }

    private static boolean fill(long left, List<Denomination> coins, int i,
            Map<Denomination, Integer> avail, List<Denomination> out) {
        if (left == 0) return true;
        if (i == coins.size()) return false;
        Denomination d = coins.get(i);
        int most = (int) Math.min(avail.getOrDefault(d, 0), left / d.paise);
        for (int k = most; k >= 0; k--) {                 // (3) as many as fit, then fewer
            for (int j = 0; j < k; j++) out.add(d);
            if (fill(left - k * d.paise, coins, i + 1, avail, out)) return true;
            for (int j = 0; j < k; j++) out.remove(out.size() - 1);   // (4) undo, try fewer
        }
        return false;
    }

    List<Denomination> collectNotes() {
        List<Denomination> notes = new ArrayList<>();
        for (Denomination d : Denomination.values()) {
            if (!d.note) continue;
            for (int i = 0; i < held.getOrDefault(d, 0); i++) notes.add(d);
            held.put(d, 0);
        }
        return notes;
    }
}

interface Display { void show(String message); }
interface Dispenser { void startDrop(String slotCode); }      // (1) returns at once
interface ReturnTray { void give(List<Denomination> money); }

interface Payment {
    void complete(VendingMachine m);
    void refund(VendingMachine m);
}

final class CashPayment implements Payment {
    private final List<Denomination> inserted;
    private final List<Denomination> change;

    CashPayment(List<Denomination> inserted, List<Denomination> change) {
        this.inserted = List.copyOf(inserted);    // (2) a snapshot, not the live escrow list
        this.change = List.copyOf(change);
    }

    public void complete(VendingMachine m) {
        m.cashBox().deposit(inserted);
        m.cashBox().payOut(change);
        m.tray().give(change);
    }

    public void refund(VendingMachine m) { m.tray().give(inserted); }
}

abstract class MachineState {
    void insert(VendingMachine m, Denomination d) {   // (1) by default: hand it back
        m.tray().give(List.of(d));
        m.display().show("Please wait");
    }
    void select(VendingMachine m, String code) { m.display().show("Please wait"); }
    void cancel(VendingMachine m) { }
    void dropFinished(VendingMachine m, boolean ok) { }
    void serviceKey(VendingMachine m) { m.display().show("Please wait"); }
    abstract String name();
}

final class IdleState extends MachineState {
    void insert(VendingMachine m, Denomination d) {
        m.escrow().add(d);
        m.display().show("Balance ₹" + m.balance() / 100);
        m.setState(new HasMoneyState());
    }

    void select(VendingMachine m, String code) {
        m.inventory().slot(code).ifPresentOrElse(
            s -> m.display().show(s.product().name() + ": ₹"
                    + s.product().pricePaise() / 100 + ". Insert money"),
            () -> m.display().show("No such item"));
    }

    void serviceKey(VendingMachine m) {
        m.display().show("Out of service");
        m.setState(new OutOfServiceState());
    }

    String name() { return "Idle"; }
}

final class HasMoneyState extends MachineState {
    void insert(VendingMachine m, Denomination d) {
        m.escrow().add(d);
        m.display().show("Balance ₹" + m.balance() / 100);
    }

    void select(VendingMachine m, String code) {
        Optional<Slot> found = m.inventory().slot(code);
        if (found.isEmpty()) { m.display().show("No such item"); return; }
        Slot slot = found.get();
        if (!slot.canSell()) {
            m.display().show("Sold out. Choose another or cancel");
            return;
        }
        long price = slot.product().pricePaise();
        long owed = m.balance() - price;
        if (owed < 0) {
            m.display().show("Insert ₹" + (-owed / 100) + " more");
            return;
        }
        Optional<List<Denomination>> change = m.cashBox().planChange(owed, m.escrow());
        if (change.isEmpty()) {
            m.display().show("Can't make change. Insert exact money or cancel");   // (1)
            return;
        }
        m.beginSale(slot, new CashPayment(m.escrow(), change.get()));
    }

    void cancel(VendingMachine m) {
        m.tray().give(List.copyOf(m.escrow()));    // (2) exactly what went in
        m.escrow().clear();
        m.display().show("Cancelled");
        m.setState(new IdleState());
    }

    void serviceKey(VendingMachine m) { cancel(m); m.setState(new OutOfServiceState()); }

    String name() { return "HasMoney"; }
}

final class DispensingState extends MachineState {
    private final Slot slot;
    private final Payment payment;

    DispensingState(Slot slot, Payment payment) {
        this.slot = slot;
        this.payment = payment;
    }

    void dropFinished(VendingMachine m, boolean ok) {
        if (ok) {
            slot.takeOne();
            payment.complete(m);                  // (1) money moves only now
            m.display().show("Enjoy your " + slot.product().name());
        } else {
            slot.markFaulty();
            payment.refund(m);
            m.display().show("Sorry, that didn't drop. Refunded");
        }
        m.escrow().clear();
        m.setState(new IdleState());
    }

    String name() { return "Dispensing"; }
}

final class OutOfServiceState extends MachineState {
    void insert(VendingMachine m, Denomination d) {
        m.tray().give(List.of(d));
        m.display().show("Out of service");
    }
    void select(VendingMachine m, String code) { m.display().show("Out of service"); }
    void serviceKey(VendingMachine m) {
        m.display().show("Ready");
        m.setState(new IdleState());
    }
    String name() { return "OutOfService"; }
}

final class VendingMachine {
    private final Inventory inventory;
    private final CashBox cashBox;
    private final Display display;
    private final Dispenser dispenser;
    private final ReturnTray tray;
    private final List<Denomination> escrow = new ArrayList<>();
    private MachineState state = new IdleState();

    VendingMachine(Inventory inventory, CashBox cashBox, Display display,
                   Dispenser dispenser, ReturnTray tray) {
        this.inventory = inventory; this.cashBox = cashBox; this.display = display;
        this.dispenser = dispenser; this.tray = tray;
    }

    // events from the hardware: one at a time
    public synchronized void insert(Denomination d) { state.insert(this, d); }
    public synchronized void select(String code) { state.select(this, code); }
    public synchronized void cancel() { state.cancel(this); }
    public synchronized void dropFinished(boolean ok) { state.dropFinished(this, ok); }
    public synchronized void serviceKey() { state.serviceKey(this); }

    // staff only, and only while out of service
    public synchronized void restock(String code, Product p, int n) {
        requireService();
        inventory.slot(code).orElseThrow().restock(p, n);
    }
    public synchronized void loadCoins(List<Denomination> coins) {
        requireService();
        cashBox.deposit(coins);
    }
    public synchronized List<Denomination> collectNotes() {
        requireService();
        return cashBox.collectNotes();
    }
    private void requireService() {
        if (!(state instanceof OutOfServiceState))
            throw new IllegalStateException("turn the service key first");
    }

    // used by the states
    void setState(MachineState s) { state = s; }
    void beginSale(Slot slot, Payment p) {
        setState(new DispensingState(slot, p));   // (1) switch first...
        display.show("Dispensing " + slot.product().name());
        dispenser.startDrop(slot.code());         // (2) ...then start the motor
    }
    long balance() { return escrow.stream().mapToLong(d -> d.paise).sum(); }
    List<Denomination> escrow() { return escrow; }
    Inventory inventory() { return inventory; }
    CashBox cashBox() { return cashBox; }
    Display display() { return display; }
    ReturnTray tray() { return tray; }
    synchronized String stateName() { return state.name(); }
}
