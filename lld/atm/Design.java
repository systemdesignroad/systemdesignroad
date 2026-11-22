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

enum Note {
    FIVE_HUNDRED(50_000), TWO_HUNDRED(20_000), HUNDRED(10_000);   // (1) largest first
    final long paise;
    Note(long paise) { this.paise = paise; }
}

record Card(String number) {
    String masked() { return "XXXX-" + number.substring(number.length() - 4); }
}

enum PinResult { OK, WRONG, BLOCKED }

enum HoldResult { APPROVED, INSUFFICIENT_FUNDS, OVER_DAILY_LIMIT, CANCELLED, UNKNOWN }

enum CashOutcome { TAKEN, JAMMED, NOT_TAKEN }

interface Bank {
    PinResult verifyPin(Card card, String pin);
    long available(Card card);
    HoldResult hold(String txnId, Card card, long amount);  // (1) same id, same answer
    boolean confirm(String txnId);                         // (2) false: try later
    boolean reverse(String txnId);
}

interface Screen { void show(String text); }
interface CardSlot { void eject(); void retain(); }
interface NoteFeeder { void present(Map<Note, Integer> notes); }  // (3) answers later

final class Cassettes {
    static final int MAX_NOTES = 40;
    private final Map<Note, Integer> count = new EnumMap<>(Note.class);

    void load(Note n, int k) { count.merge(n, k, Integer::sum); }
    int count(Note n) { return count.getOrDefault(n, 0); }

    long total() {
        long t = 0;
        for (var e : count.entrySet()) t += e.getKey().paise * e.getValue();
        return t;
    }

    void remove(Map<Note, Integer> plan) {
        for (var e : plan.entrySet()) {
            int left = count(e.getKey()) - e.getValue();
            if (left < 0) throw new IllegalStateException("not enough " + e.getKey());
            count.put(e.getKey(), left);
        }
    }

    // Which notes make exactly `amount`, from what's really loaded? Empty if impossible.
    Optional<Map<Note, Integer>> plan(long amount) {
        Map<Note, Integer> out = new EnumMap<>(Note.class);
        return fill(amount, 0, MAX_NOTES, out) ? Optional.of(out) : Optional.empty();
    }

    private boolean fill(long left, int i, int notesLeft, Map<Note, Integer> out) {
        if (left == 0) return true;
        if (i == Note.values().length || notesLeft == 0) return false;
        Note n = Note.values()[i];
        int most = (int) Math.min(Math.min(count(n), left / n.paise), notesLeft);
        for (int k = most; k >= 0; k--) {          // (1) as many as fit, then fewer
            if (k > 0) out.put(n, k); else out.remove(n);
            if (fill(left - k * n.paise, i + 1, notesLeft - k, out)) return true;
        }
        out.remove(n);
        return false;
    }
}

enum TxnStatus { REQUESTED, HELD, DISPENSED, CONFIRMED, CONFIRM_PENDING,
                 REVERSED, REVERSAL_PENDING }

record JournalEntry(String txnId, long amount, TxnStatus status, String note) { }

final class Journal {
    private final List<JournalEntry> entries = new ArrayList<>();

    void record(String txnId, long amount, TxnStatus status, String note) {
        entries.add(new JournalEntry(txnId, amount, status, note));   // (1) append only
    }

    Map<String, JournalEntry> latest() {
        Map<String, JournalEntry> last = new LinkedHashMap<>();
        for (JournalEntry e : entries) last.put(e.txnId(), e);
        return last;
    }

    List<JournalEntry> entries() { return List.copyOf(entries); }
}

final class Atm {
    static final long PER_WITHDRAWAL = 10_000_00;      // ₹10,000

    private AtmState state = new IdleState();
    private Card card;
    private boolean cardInSlot;
    private final Bank bank;
    private final Cassettes cassettes;
    private final Screen screen;
    private final CardSlot slot;
    private final NoteFeeder feeder;
    private final Journal journal = new Journal();
    private final Supplier<String> ids;
    private long retracted;

    Atm(Bank bank, Cassettes cassettes, Screen screen, CardSlot slot,
        NoteFeeder feeder, Supplier<String> ids) {
        this.bank = bank; this.cassettes = cassettes; this.screen = screen;
        this.slot = slot; this.feeder = feeder; this.ids = ids;
    }

    // Events from the hardware. One at a time, in order.
    synchronized void insertCard(Card c) { state.insertCard(this, c); }
    synchronized void enterPin(String pin) { state.enterPin(this, pin); }
    synchronized void withdraw(long amount) { state.withdraw(this, amount); }
    synchronized void balance() { state.balance(this); }
    synchronized void cancel() { state.cancel(this); }
    synchronized void timeout() { state.timeout(this); }
    synchronized void cashOutcome(CashOutcome o) { state.cashOutcome(this, o); }
    synchronized void serviceKey() { state.serviceKey(this); }

    // Resend confirmations and reversals the bank didn't receive. Safe to run any time.
    synchronized void retryPending() {
        for (JournalEntry e : journal.latest().values()) {
            if (e.status() == TxnStatus.CONFIRM_PENDING && bank.confirm(e.txnId()))
                journal.record(e.txnId(), e.amount(), TxnStatus.CONFIRMED, "retried");
            if (e.status() == TxnStatus.REVERSAL_PENDING && bank.reverse(e.txnId()))
                journal.record(e.txnId(), e.amount(), TxnStatus.REVERSED, "retried");
        }
    }

    // Used by the states.
    void setState(AtmState s) { state = s; }
    void startSession(Card c, boolean inSlot) { card = c; cardInSlot = inSlot; }
    void returnCard() {                    // card first, then cash
        if (!cardInSlot) return;
        slot.eject();
        cardInSlot = false;
        screen.show("Take your card");
    }
    void endSession(boolean keepCard) {
        if (keepCard) slot.retain(); else if (cardInSlot) slot.eject();
        card = null;
        cardInSlot = false;
        setState(new IdleState());
    }
    void addRetracted(long amount) { retracted += amount; }

    Card card() { return card; }
    Bank bank() { return bank; }
    Cassettes cassettes() { return cassettes; }
    Screen screen() { return screen; }
    CardSlot slot() { return slot; }
    NoteFeeder feeder() { return feeder; }
    Journal journal() { return journal; }
    String newTxnId() { return ids.get(); }
    String stateName() { return state.name(); }
    long retracted() { return retracted; }
}

abstract class AtmState {
    void insertCard(Atm atm, Card c) { atm.slot().eject(); }    // (1) slot is busy
    void enterPin(Atm atm, String pin) { }
    void withdraw(Atm atm, long amount) { atm.screen().show("Please wait"); }
    void balance(Atm atm) { atm.screen().show("Please wait"); }
    void cancel(Atm atm) { }
    void timeout(Atm atm) { }
    void cashOutcome(Atm atm, CashOutcome o) { }
    void serviceKey(Atm atm) { atm.screen().show("Please wait"); }
    abstract String name();
}

final class IdleState extends AtmState {
    void insertCard(Atm atm, Card c) {
        atm.startSession(c, true);
        atm.setState(new CardInsertedState());
        atm.screen().show("Enter PIN");
    }
    void serviceKey(Atm atm) { atm.setState(new OutOfServiceState()); }
    String name() { return "Idle"; }
}

final class CardInsertedState extends AtmState {
    void enterPin(Atm atm, String pin) {
        switch (atm.bank().verifyPin(atm.card(), pin)) {   // (1) the bank counts tries
            case OK -> {
                atm.setState(new MenuState());
                atm.screen().show("Choose a service");
            }
            case WRONG -> atm.screen().show("Wrong PIN. Try again");
            case BLOCKED -> {
                atm.screen().show("Card blocked. Please contact your bank");
                atm.endSession(true);                            // (2) keep the card
            }
        }
    }
    void cancel(Atm atm) { atm.endSession(false); }
    void timeout(Atm atm) { atm.endSession(false); }
    String name() { return "CardInserted"; }
}

final class OutOfServiceState extends AtmState {
    void insertCard(Atm atm, Card c) {
        atm.screen().show("Out of service");
        atm.slot().eject();
    }
    void serviceKey(Atm atm) { atm.setState(new IdleState()); }
    String name() { return "OutOfService"; }
}

final class MenuState extends AtmState {
    void withdraw(Atm atm, long amount) {
        if (amount <= 0 || amount % Note.HUNDRED.paise != 0) {
            atm.screen().show("Enter a multiple of ₹100");
            return;
        }
        if (amount > Atm.PER_WITHDRAWAL) {
            atm.screen().show("Up to ₹10,000 per withdrawal");
            return;
        }
        Optional<Map<Note, Integer>> plan = atm.cassettes().plan(amount);   // (1)
        if (plan.isEmpty()) {
            atm.screen().show("Can't give " + Rupees.of(amount) + ". Try another amount");
            return;
        }
        String txn = atm.newTxnId();
        atm.journal().record(txn, amount, TxnStatus.REQUESTED, atm.card().masked());
        HoldResult r = atm.bank().hold(txn, atm.card(), amount);            // (2)
        switch (r) {
            case APPROVED -> {
                atm.journal().record(txn, amount, TxnStatus.HELD, "");
                atm.returnCard();                                            // (3)
                atm.setState(new DispensingState(txn, amount, plan.get()));
                atm.feeder().present(plan.get());
            }
            case INSUFFICIENT_FUNDS -> atm.screen().show("Insufficient funds");
            case OVER_DAILY_LIMIT -> atm.screen().show("Daily limit reached");
            case CANCELLED, UNKNOWN -> {                                     // (4)
                Reversals.reverse(atm, txn, amount, "no answer from bank");
                atm.screen().show("Couldn't complete. You have not been charged");
            }
        }
    }

    void balance(Atm atm) {
        atm.screen().show("Available " + Rupees.of(atm.bank().available(atm.card())));
    }
    void cancel(Atm atm) { atm.endSession(false); }
    void timeout(Atm atm) { atm.endSession(false); }
    String name() { return "Menu"; }
}

final class Reversals {
    static void reverse(Atm atm, String txn, long amount, String why) {
        boolean ok = atm.bank().reverse(txn);
        atm.journal().record(txn, amount,
            ok ? TxnStatus.REVERSED : TxnStatus.REVERSAL_PENDING, why);
    }
}

final class Rupees {
    static String of(long paise) {
        return String.format(Locale.ENGLISH, "₹%,d", paise / 100);
    }
}

final class DispensingState extends AtmState {
    private final String txn;
    private final long amount;
    private final Map<Note, Integer> plan;

    DispensingState(String txn, long amount, Map<Note, Integer> plan) {
        this.txn = txn; this.amount = amount; this.plan = new EnumMap<>(plan);
    }

    void cashOutcome(Atm atm, CashOutcome o) {
        switch (o) {
            case TAKEN -> {
                atm.cassettes().remove(plan);
                atm.journal().record(txn, amount, TxnStatus.DISPENSED, plan.toString());
                boolean ok = atm.bank().confirm(txn);                       // (1)
                atm.journal().record(txn, amount,
                    ok ? TxnStatus.CONFIRMED : TxnStatus.CONFIRM_PENDING, "");
                atm.screen().show("Thank you");
                atm.endSession(false);
            }
            case JAMMED -> {                                                // (2)
                Reversals.reverse(atm, txn, amount, "jam: no notes left the ATM");
                atm.screen().show("Sorry, no cash given. You have not been charged");
                atm.setState(new OutOfServiceState());
            }
            case NOT_TAKEN -> {                                             // (3)
                atm.cassettes().remove(plan);
                atm.addRetracted(amount);
                Reversals.reverse(atm, txn, amount, "not taken: retracted");
                atm.screen().show("Cash retracted. You have not been charged");
                atm.endSession(false);
            }
        }
    }

    void cancel(Atm atm) { atm.screen().show("Please take your cash"); }
    String name() { return "Dispensing"; }
}

final class InMemoryBank implements Bank {
    static final long DAILY_LIMIT = 25_000_00;              // ₹25,000

    static final class Account {
        long balance, held, withdrawnToday;
        int wrongPins;
        boolean blocked;
        final String pin;
        Account(long balance, String pin) { this.balance = balance; this.pin = pin; }
    }

    private enum HoldState { HELD, CONFIRMED, REVERSED }
    private static final class Hold {
        final Account account; final long amount; HoldState state;
        Hold(Account a, long amount, HoldState s) {
            account = a; this.amount = amount; state = s;
        }
    }

    private final Map<String, Account> byCard = new ConcurrentHashMap<>();
    private final Map<String, Hold> holds = new ConcurrentHashMap<>();

    void open(Account a, String... cardNumbers) {
        for (String n : cardNumbers) byCard.put(n, a);             // (1) joint accounts
    }
    Account account(Card c) { return byCard.get(c.number()); }

    public PinResult verifyPin(Card card, String pin) {
        Account a = account(card);
        synchronized (a) {
            if (a.blocked) return PinResult.BLOCKED;
            if (a.pin.equals(pin)) { a.wrongPins = 0; return PinResult.OK; }
            if (++a.wrongPins >= 3) { a.blocked = true; return PinResult.BLOCKED; }
            return PinResult.WRONG;
        }
    }

    public long available(Card card) {
        Account a = account(card);
        synchronized (a) { return a.balance - a.held; }
    }

    public HoldResult hold(String txnId, Card card, long amount) {
        Account a = account(card);
        synchronized (a) {                         // (2) check and hold together
            Hold seen = holds.get(txnId);
            if (seen != null) return answerFor(seen);
            if (a.balance - a.held < amount) return HoldResult.INSUFFICIENT_FUNDS;
            if (a.withdrawnToday + amount > DAILY_LIMIT)
                return HoldResult.OVER_DAILY_LIMIT;
            seen = holds.putIfAbsent(txnId, new Hold(a, amount, HoldState.HELD));
            if (seen != null) return answerFor(seen);      // (3) reversed meanwhile
            a.held += amount;
            a.withdrawnToday += amount;
            return HoldResult.APPROVED;
        }
    }

    private static HoldResult answerFor(Hold h) {
        return h.state == HoldState.REVERSED ? HoldResult.CANCELLED : HoldResult.APPROVED;
    }

    public boolean confirm(String txnId) {
        Hold h = holds.get(txnId);
        if (h == null || h.account == null) return false;
        synchronized (h.account) {
            if (h.state == HoldState.HELD) {
                h.account.held -= h.amount;
                h.account.balance -= h.amount;
                h.state = HoldState.CONFIRMED;
            }
            return h.state == HoldState.CONFIRMED;
        }
    }

    public boolean reverse(String txnId) {
        Hold h = holds.putIfAbsent(txnId, new Hold(null, 0, HoldState.REVERSED));  // (4)
        if (h == null) return true;
        if (h.account == null) return true;
        synchronized (h.account) {
            if (h.state == HoldState.HELD) {
                h.account.held -= h.amount;
                h.account.withdrawnToday -= h.amount;
                h.state = HoldState.REVERSED;
            }
            return h.state == HoldState.REVERSED;
        }
    }
}
