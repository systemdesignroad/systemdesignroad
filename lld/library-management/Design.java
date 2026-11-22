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

record Book(String isbn, String title, String author) { }

enum CopyStatus { AVAILABLE, ON_LOAN, ON_HOLD_SHELF, LOST }

final class BookCopy {
    private final String barcode;
    private final Book book;
    private CopyStatus status = CopyStatus.AVAILABLE;
    private Member heldFor;                         // (1) set only on the hold shelf
    private LocalDate holdUntil;

    BookCopy(String barcode, Book book) { this.barcode = barcode; this.book = book; }

    String barcode() { return barcode; }
    Book book() { return book; }
    CopyStatus status() { return status; }
    Member heldFor() { return heldFor; }
    LocalDate holdUntil() { return holdUntil; }

    void lend() { status = CopyStatus.ON_LOAN; heldFor = null; holdUntil = null; }
    void shelve() { status = CopyStatus.AVAILABLE; heldFor = null; holdUntil = null; }
    void holdFor(Member m, LocalDate until) {        // (2) one member, one deadline
        status = CopyStatus.ON_HOLD_SHELF; heldFor = m; holdUntil = until;
    }
    void markLost() { status = CopyStatus.LOST; heldFor = null; holdUntil = null; }
}

interface LoanPolicy {
    String name();
    int maxLoans();
    int loanDays();
    long fineFor(long daysLate);                            // paise
}

record StandardPolicy(String name, int maxLoans, int loanDays,
                      long finePerDay, long fineCap) implements LoanPolicy {
    static final StandardPolicy STUDENT =
        new StandardPolicy("Student", 3, 14, 1_000, 30_000);    // ₹10 a day, ₹300 cap
    static final StandardPolicy FACULTY =
        new StandardPolicy("Faculty", 10, 30, 500, 30_000);     // ₹5 a day, ₹300 cap

    public long fineFor(long daysLate) {
        return Math.min(Math.max(0, daysLate) * finePerDay, fineCap);   // (1) capped
    }
}

final class Member {
    static final long BORROW_BLOCK_AT = 10_000;              // ₹100 owed stops borrowing
    private final String id;
    private final String name;
    private final LoanPolicy policy;
    private final List<Loan> loans = new ArrayList<>();
    private long finesOwed;

    Member(String id, String name, LoanPolicy policy) {
        this.id = id; this.name = name; this.policy = policy;
    }

    String id() { return id; }
    String name() { return name; }
    LoanPolicy policy() { return policy; }
    synchronized long finesOwed() { return finesOwed; }
    synchronized List<Loan> loans() { return List.copyOf(loans); }

    synchronized Optional<String> whyCantBorrow(LocalDate today) {   // (2) a reason
        if (finesOwed >= BORROW_BLOCK_AT)
            return Optional.of("fines of " + Money.of(finesOwed));
        if (loans.stream().anyMatch(l -> l.isOverdue(today)))
            return Optional.of("an overdue book");
        if (loans.size() >= policy.maxLoans())
            return Optional.of("the limit of " + policy.maxLoans() + " books");
        return Optional.empty();
    }

    synchronized void addLoan(Loan l) { loans.add(l); }
    synchronized void endLoan(Loan l, long fine) { loans.remove(l); finesOwed += fine; }
    synchronized void charge(long paise) { finesOwed += paise; }
    synchronized long pay(long paise) {
        long paid = Math.min(paise, finesOwed);
        finesOwed -= paid;
        return paid;
    }
}

final class Money {
    static String of(long paise) {
        return paise % 100 == 0 ? String.format("₹%,d", paise / 100)
                                : String.format("₹%,d.%02d", paise / 100, paise % 100);
    }
}

final class Loan {
    private final BookCopy copy;
    private final Member member;
    private final LocalDate borrowed;
    private LocalDate due;
    private int renewals;

    Loan(BookCopy copy, Member member, LocalDate borrowed) {
        this.copy = copy; this.member = member; this.borrowed = borrowed;
        this.due = borrowed.plusDays(member.policy().loanDays());
    }

    BookCopy copy() { return copy; }
    Member member() { return member; }
    LocalDate due() { return due; }
    int renewals() { return renewals; }

    boolean isOverdue(LocalDate today) { return today.isAfter(due); }

    long fineIfReturned(LocalDate today) {
        long late = ChronoUnit.DAYS.between(due, today);    // (1) negative if early
        return member.policy().fineFor(late);
    }

    void renew(LocalDate today) {
        due = today.plusDays(member.policy().loanDays());    // (2) from today
        renewals++;
    }
}

final class Title {
    private final Book book;
    private final List<BookCopy> copies = new ArrayList<>();
    private final Deque<Member> waiting = new ArrayDeque<>();
    final ReentrantLock lock = new ReentrantLock();          // (1) guards everything here

    Title(Book book) { this.book = book; }

    Book book() { return book; }
    List<BookCopy> copies() { return copies; }
    Deque<Member> waiting() { return waiting; }

    Optional<BookCopy> anyAvailable() {
        return copies.stream().filter(c -> c.status() == CopyStatus.AVAILABLE)
                     .findFirst();
    }

    long count(CopyStatus s) {
        return copies.stream().filter(c -> c.status() == s).count();
    }

    boolean isWaiting(Member m) { return waiting.contains(m); }
}

final class Catalogue {
    private final Map<String, Title> byIsbn = new ConcurrentHashMap<>();
    private final Map<String, BookCopy> byBarcode = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> wordIndex = new ConcurrentHashMap<>();

    Title addBook(Book b) {
        Title t = byIsbn.computeIfAbsent(b.isbn(), k -> new Title(b));
        for (String w : words(b.title() + " " + b.author()))
            wordIndex.computeIfAbsent(w, k -> ConcurrentHashMap.newKeySet())
                     .add(b.isbn());
        return t;
    }

    void addCopy(BookCopy c) {
        if (byBarcode.putIfAbsent(c.barcode(), c) != null)
            throw new IllegalArgumentException("barcode in use: " + c.barcode());
        Title t = title(c.book().isbn());
        t.lock.lock();
        try { t.copies().add(c); } finally { t.lock.unlock(); }
    }

    Title title(String isbn) {
        Title t = byIsbn.get(isbn);
        if (t == null) throw new IllegalArgumentException("no book " + isbn);
        return t;
    }

    BookCopy copy(String barcode) {
        BookCopy c = byBarcode.get(barcode);
        if (c == null) throw new IllegalArgumentException("no copy " + barcode);
        return c;
    }

    List<Book> search(String query) {                       // (1) every word must match
        List<String> ws = words(query);
        if (ws.isEmpty()) return List.of();
        Set<String> hits = null;
        for (String w : ws) {
            Set<String> s = wordIndex.getOrDefault(w, Set.of());
            if (hits == null) hits = new HashSet<>(s); else hits.retainAll(s);
        }
        return hits.stream().map(i -> byIsbn.get(i).book())
                   .sorted(Comparator.comparing(Book::title)).toList();
    }

    Collection<Title> titles() { return byIsbn.values(); }

    private static List<String> words(String s) {
        return Arrays.stream(s.toLowerCase().split("[^a-z0-9]+"))
                     .filter(w -> !w.isBlank()).toList();
    }
}

interface Clock { LocalDate today(); }

interface Notifier { void tell(Member m, String message); }

sealed interface Result permits Result.Done, Result.Refused {
    record Done(String what) implements Result { }
    record Refused(String why) implements Result { }
    static Result done(String w) { return new Done(w); }
    static Result no(String w) { return new Refused(w); }
}

final class Library {
    static final int HOLD_SHELF_DAYS = 3;
    static final int MAX_RENEWALS = 2;
    private final Catalogue catalogue;
    private final Clock clock;
    private final Notifier notifier;
    private final Map<BookCopy, Loan> activeLoans = new ConcurrentHashMap<>();

    Library(Catalogue catalogue, Clock clock, Notifier notifier) {
        this.catalogue = catalogue; this.clock = clock; this.notifier = notifier;
    }

    Result borrow(Member m, String barcode) {
        BookCopy c = catalogue.copy(barcode);
        Title t = catalogue.title(c.book().isbn());
        LocalDate today = clock.today();
        t.lock.lock();                                       // (1) one title at a time
        try {
            Optional<String> why = m.whyCantBorrow(today);
            if (why.isPresent()) return Result.no(m.name() + " has " + why.get());
            switch (c.status()) {
                case ON_LOAN, LOST -> { return Result.no(barcode + " is " + c.status()); }
                case ON_HOLD_SHELF -> {
                    if (c.heldFor() != m)            // (2) set aside for someone else
                        return Result.no(barcode + " is on hold for "
                                         + c.heldFor().name());
                }
                case AVAILABLE -> { }
            }
            c.lend();
            Loan loan = new Loan(c, m, today);
            activeLoans.put(c, loan);
            m.addLoan(loan);
            return Result.done(m.name() + " borrowed " + c.book().title()
                               + ", due " + loan.due());
        } finally {
            t.lock.unlock();
        }
    }

    Result giveBack(String barcode) {
        BookCopy c = catalogue.copy(barcode);
        Title t = catalogue.title(c.book().isbn());
        LocalDate today = clock.today();
        t.lock.lock();
        try {
            Loan loan = activeLoans.remove(c);
            if (loan == null) return Result.no(barcode + " isn't on loan");
            long fine = loan.fineIfReturned(today);
            loan.member().endLoan(loan, fine);
            String next = passOn(t, c, today);               // (1) waiting list first
            return Result.done(loan.member().name() + " returned " + c.book().title()
                    + (fine > 0 ? ", fine " + Money.of(fine) : "") + "; " + next);
        } finally {
            t.lock.unlock();
        }
    }

    // Caller holds t.lock. The copy goes to the first person waiting, or to the shelf.
    private String passOn(Title t, BookCopy c, LocalDate today) {
        Member next = t.waiting().pollFirst();
        if (next == null) { c.shelve(); return "back on the shelf"; }
        LocalDate until = today.plusDays(HOLD_SHELF_DAYS);
        c.holdFor(next, until);
        notifier.tell(next, c.book().title() + " is ready until " + until);
        return "held for " + next.name();
    }

    Result placeHold(Member m, String isbn) {
        Title t = catalogue.title(isbn);
        t.lock.lock();                                       // (2) same lock as giveBack
        try {
            if (t.anyAvailable().isPresent())
                return Result.no("a copy of " + t.book().title() + " is on the shelf");
            if (t.isWaiting(m)) return Result.no(m.name() + " is already waiting");
            boolean hasIt = m.loans().stream()
                             .anyMatch(l -> l.copy().book().isbn().equals(isbn));
            if (hasIt) return Result.no(m.name() + " already has a copy");
            t.waiting().addLast(m);
            return Result.done(m.name() + " is number " + t.waiting().size()
                               + " for " + t.book().title());
        } finally {
            t.lock.unlock();
        }
    }

    Result renew(Member m, String barcode) {
        BookCopy c = catalogue.copy(barcode);
        Title t = catalogue.title(c.book().isbn());
        LocalDate today = clock.today();
        t.lock.lock();
        try {
            Loan loan = activeLoans.get(c);
            if (loan == null || loan.member() != m)
                return Result.no(m.name() + " hasn't got it");
            if (loan.isOverdue(today)) return Result.no("it's overdue; return it first");
            if (!t.waiting().isEmpty())                      // (1) someone else needs it
                return Result.no(t.waiting().size() + " waiting for it");
            if (loan.renewals() >= MAX_RENEWALS)
                return Result.no("renewed twice already");
            loan.renew(today);
            return Result.done("due " + loan.due());
        } finally {
            t.lock.unlock();
        }
    }

    // Run once a day. A copy nobody collected goes to the next person, or the shelf.
    List<String> expireHolds() {
        LocalDate today = clock.today();
        List<String> done = new ArrayList<>();
        for (Title t : catalogue.titles()) {
            t.lock.lock();
            try {
                for (BookCopy c : t.copies()) {
                    if (c.status() != CopyStatus.ON_HOLD_SHELF) continue;
                    if (!today.isAfter(c.holdUntil())) continue;
                    Member missed = c.heldFor();
                    notifier.tell(missed,
                                  "your hold on " + t.book().title() + " ran out");
                    done.add(missed.name() + " missed " + c.barcode() + "; "
                             + passOn(t, c, today));
                }
            } finally {
                t.lock.unlock();
            }
        }
        return done;
    }

    Result reportLost(String barcode, long replacementPaise) {
        BookCopy c = catalogue.copy(barcode);
        Title t = catalogue.title(c.book().isbn());
        t.lock.lock();
        try {
            Loan loan = activeLoans.remove(c);
            if (loan == null) return Result.no(barcode + " isn't on loan");
            loan.member().endLoan(loan, replacementPaise);   // (2) charged, loan closed
            c.markLost();
            return Result.done(loan.member().name() + " owes "
                               + Money.of(replacementPaise));
        } finally {
            t.lock.unlock();
        }
    }

    Result addCopy(BookCopy c) {                     // (3) new copies serve holds too
        catalogue.addCopy(c);
        Title t = catalogue.title(c.book().isbn());
        t.lock.lock();
        try {
            if (c.status() == CopyStatus.AVAILABLE && !t.waiting().isEmpty())
                return Result.done(c.barcode() + " " + passOn(t, c, clock.today()));
            return Result.done(c.barcode() + " on the shelf");
        } finally {
            t.lock.unlock();
        }
    }

    long pay(Member m, long paise) { return m.pay(paise); }

    Optional<Loan> loanOf(String barcode) {
        return Optional.ofNullable(activeLoans.get(catalogue.copy(barcode)));
    }
}

final class FakeClock implements Clock {
    private LocalDate today;
    FakeClock(LocalDate start) { today = start; }
    public synchronized LocalDate today() { return today; }
    synchronized void advance(int days) { today = today.plusDays(days); }
}
