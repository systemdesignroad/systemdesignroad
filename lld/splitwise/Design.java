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

record User(String id, String name) {
    @Override public String toString() { return name; }
}

final class Money {
    private Money() { }
    static String format(long paise) {
        String sign = paise < 0 ? "-" : "";
        long p = Math.abs(paise);
        String rupees = sign + "₹" + (p / 100);
        return p % 100 == 0 ? rupees : rupees + String.format(".%02d", p % 100);
    }
}

final class Shares {
    private Shares() { }

    // Splits total in proportion to the weights, in whole paise, adding up exactly.
    static Map<User, Long> byWeight(long total, Map<User, Long> weights) {
        long sum = 0;
        for (long w : weights.values()) {
            if (w < 0) throw new IllegalArgumentException("negative weight");
            sum += w;
        }
        if (sum == 0) throw new IllegalArgumentException("nobody to split between");
        Map<User, Long> out = new LinkedHashMap<>();
        List<User> order = new ArrayList<>(weights.keySet());
        Map<User, Long> rest = new HashMap<>();
        long given = 0;
        for (User u : order) {
            long exact = total * weights.get(u);                  // (1)
            out.put(u, exact / sum);
            rest.put(u, exact % sum);
            given += exact / sum;
        }
        order.sort((a, b) -> Long.compare(rest.get(b), rest.get(a)));   // (2)
        for (int i = 0; i < total - given; i++) out.merge(order.get(i), 1L, Long::sum);
        return out;
    }
}

sealed interface Split permits EqualSplit, ExactSplit, PercentSplit, ShareSplit {
    Map<User, Long> amounts(long total);                    // (1) always adds up to total
}

record EqualSplit(List<User> users) implements Split {
    public Map<User, Long> amounts(long total) {
        Map<User, Long> w = new LinkedHashMap<>();
        for (User u : users) w.put(u, 1L);
        return Shares.byWeight(total, w);
    }
}

record ExactSplit(Map<User, Long> exact) implements Split {
    public Map<User, Long> amounts(long total) {
        long sum = exact.values().stream().mapToLong(Long::longValue).sum();
        if (sum != total)
            throw new IllegalArgumentException("exact amounts add up to "
                + Money.format(sum) + ", not " + Money.format(total));
        return new LinkedHashMap<>(exact);
    }
}

record PercentSplit(Map<User, Long> basisPoints) implements Split {   // (2) 12.5% = 1250
    public Map<User, Long> amounts(long total) {
        long sum = basisPoints.values().stream().mapToLong(Long::longValue).sum();
        if (sum != 10_000)
            throw new IllegalArgumentException("percentages must add to 100");
        return Shares.byWeight(total, basisPoints);
    }
}

record ShareSplit(Map<User, Long> shares) implements Split {
    public Map<User, Long> amounts(long total) {
        return Shares.byWeight(total, shares);
    }
}

final class Ledger {
    private final Map<User, Map<User, Long>> owes = new HashMap<>();   // (1) a owes b

    void record(User debtor, User creditor, long amount) {
        if (debtor.equals(creditor) || amount == 0) return;
        add(debtor, creditor, amount);
        add(creditor, debtor, -amount);                                // (2)
    }

    private void add(User a, User b, long amount) {
        Map<User, Long> row = owes.computeIfAbsent(a, x -> new HashMap<>());
        long v = row.getOrDefault(b, 0L) + amount;
        if (v == 0) row.remove(b); else row.put(b, v);
    }

    long owes(User a, User b) {
        return owes.getOrDefault(a, Map.of()).getOrDefault(b, 0L);
    }

    Map<User, Long> net() {                                           // (3)
        Map<User, Long> net = new HashMap<>();
        owes.forEach((a, row) -> row.forEach((b, v) -> net.merge(a, -v, Long::sum)));
        net.values().removeIf(v -> v == 0);
        return net;
    }
}

record Expense(String id, String description, User paidBy, long amount,
               Map<User, Long> owed) { }

record Transfer(User from, User to, long amount) {
    @Override public String toString() {
        return from + " pays " + to + " " + Money.format(amount);
    }
}

interface DebtSimplifier {
    List<Transfer> settle(Map<User, Long> net);
}

final class GreedySimplifier implements DebtSimplifier {
    public List<Transfer> settle(Map<User, Long> net) {
        Comparator<Map.Entry<User, Long>> big = (x, y) -> {
            int c = Long.compare(Math.abs(y.getValue()), Math.abs(x.getValue()));
            return c != 0 ? c : x.getKey().id().compareTo(y.getKey().id());   // (1)
        };
        PriorityQueue<Map.Entry<User, Long>> owed = new PriorityQueue<>(big);
        PriorityQueue<Map.Entry<User, Long>> owing = new PriorityQueue<>(big);
        net.forEach((u, v) -> {
            if (v > 0) owed.add(Map.entry(u, v));
            else if (v < 0) owing.add(Map.entry(u, v));
        });
        List<Transfer> out = new ArrayList<>();
        while (!owed.isEmpty() && !owing.isEmpty()) {
            var c = owed.poll();
            var d = owing.poll();
            long amount = Math.min(c.getValue(), -d.getValue());       // (2)
            out.add(new Transfer(d.getKey(), c.getKey(), amount));
            if (c.getValue() > amount)
                owed.add(Map.entry(c.getKey(), c.getValue() - amount));
            if (-d.getValue() > amount)
                owing.add(Map.entry(d.getKey(), d.getValue() + amount));
        }
        return out;
    }
}

interface GroupListener {
    void expenseAdded(Group group, Expense expense);
}

final class Group {
    private final String name;
    private final Set<User> members;
    private final Map<String, Expense> expenses = new LinkedHashMap<>();
    private final Ledger ledger = new Ledger();
    private final List<GroupListener> listeners = new CopyOnWriteArrayList<>();

    Group(String name, Collection<User> members) {
        this.name = name;
        this.members = Set.copyOf(members);
    }

    void subscribe(GroupListener l) { listeners.add(l); }

    Expense addExpense(String id, String description, User paidBy, long amount,
                       Split split) {
        Expense e;
        synchronized (this) {
            Expense seen = expenses.get(id);
            if (seen != null) {                                        // (1)
                if (seen.amount() != amount || !seen.paidBy().equals(paidBy))
                    throw new IllegalStateException("expense id reused: " + id);
                return seen;
            }
            if (amount <= 0)
                throw new IllegalArgumentException("amount must be positive");
            check(paidBy);
            Map<User, Long> owed = split.amounts(amount);
            owed.keySet().forEach(this::check);
            e = new Expense(id, description, paidBy, amount,
                            Collections.unmodifiableMap(owed));             // (3)
            expenses.put(id, e);
            owed.forEach((u, share) -> ledger.record(u, paidBy, share));
        }
        for (GroupListener l : listeners) l.expenseAdded(this, e);    // (2)
        return e;
    }

    synchronized void settle(User from, User to, long amount) {
        check(from);
        check(to);
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        ledger.record(to, from, amount);                               // (4)
    }

    synchronized long owes(User a, User b) { return ledger.owes(a, b); }
    synchronized Map<User, Long> net() { return ledger.net(); }
    synchronized int expenseCount() { return expenses.size(); }
    String name() { return name; }

    List<Transfer> simplify(DebtSimplifier s) { return s.settle(net()); }

    private void check(User u) {
        if (!members.contains(u))
            throw new IllegalArgumentException(u + " is not in " + name);
    }
}

class Demo {
    public static void main(String[] args) {
        User aarav = new User("u1", "Aarav"), bhavna = new User("u2", "Bhavna"),
             chetan = new User("u3", "Chetan"), divya = new User("u4", "Divya");
        List<User> all = List.of(aarav, bhavna, chetan, divya);
        Group goa = new Group("Goa trip", all);

        goa.addExpense("e1", "Hotel", aarav, 12_000_00, new EqualSplit(all));
        Map<User, Long> dinner = new LinkedHashMap<>();
        dinner.put(aarav, 700_00L);
        dinner.put(bhavna, 600_00L);
        dinner.put(chetan, 900_00L);
        dinner.put(divya, 300_00L);
        goa.addExpense("e2", "Dinner", bhavna, 2_500_00, new ExactSplit(dinner));
        Map<User, Long> scuba = new LinkedHashMap<>();
        scuba.put(chetan, 40_00L);                                     // 40.00%
        scuba.put(divya, 30_00L);
        scuba.put(aarav, 30_00L);
        goa.addExpense("e3", "Scuba", chetan, 10_000_00, new PercentSplit(scuba));
        EqualSplit three = new EqualSplit(List.of(aarav, bhavna, divya));
        Expense cab = goa.addExpense("e4", "Cab", divya, 1_000_00, three);
        Expense again = goa.addExpense("e4", "Cab", divya, 1_000_00, three);

        System.out.println("cab: " + cab.owed());
        System.out.println("retry is the same expense: " + (again == cab)
            + ", expenses: " + goa.expenseCount());
        for (User x : all)
            for (User y : all) {
                long v = goa.owes(x, y);
                if (v > 0) System.out.println(x + " owes " + y + " " + Money.format(v));
            }
        System.out.println("greedy:");
        goa.simplify(new GreedySimplifier()).forEach(t -> System.out.println("  " + t));
        goa.settle(bhavna, aarav, 2_300_00);
        System.out.println("Bhavna owes Aarav after paying: "
            + Money.format(goa.owes(bhavna, aarav)));
    }
}
