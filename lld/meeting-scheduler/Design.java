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

record Person(String name, ZoneId zone, LocalTime workStart, LocalTime workEnd) { }

record Slot(Instant start, Instant end) implements Comparable<Slot> {
    Slot {
        if (!end.isAfter(start))
            throw new IllegalArgumentException("a slot must have length");
    }
    boolean overlaps(Slot o) {                           // (1) touching isn't overlapping
        return start.isBefore(o.end) && o.start.isBefore(end);
    }
    Duration length() { return Duration.between(start, end); }
    public int compareTo(Slot o) { return start.compareTo(o.start); }

    String in(ZoneId z) {
        var day = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm");
        var time = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        return day.format(start.atZone(z)) + "–" + time.format(end.atZone(z));
    }
}

interface Repeat {
    Iterator<Slot> from(Slot first);                         // (1) lazily, maybe forever
}

record Once() implements Repeat {
    public Iterator<Slot> from(Slot first) { return List.of(first).iterator(); }
}

record Weekly(int times, ZoneId zone) implements Repeat {    // times <= 0: no end
    public Iterator<Slot> from(Slot first) {
        ZonedDateTime s0 = first.start().atZone(zone), e0 = first.end().atZone(zone);
        return new Iterator<>() {
            int n = 0;
            public boolean hasNext() { return times <= 0 || n < times; }
            public Slot next() {                          // (2) same local time each week
                long w = n++;
                return new Slot(s0.plusWeeks(w).toInstant(), e0.plusWeeks(w).toInstant());
            }
        };
    }
}

enum Answer { PENDING, ACCEPTED, DECLINED }

record Room(String name, int seats) { }

final class Meeting {
    final String id, title;
    final Person organizer;
    final Room room;
    final Slot first;
    final Repeat repeat;
    private final Map<Person, Answer> answers = new LinkedHashMap<>();
    private final Set<Instant> cancelled = ConcurrentHashMap.newKeySet();

    Meeting(String id, String title, Person organizer, List<Person> guests, Room room,
            Slot first, Repeat repeat) {
        this.id = id; this.title = title; this.organizer = organizer; this.room = room;
        this.first = first; this.repeat = repeat;
        answers.put(organizer, Answer.ACCEPTED);
        for (Person p : guests) answers.putIfAbsent(p, Answer.PENDING);
    }

    synchronized Answer answerOf(Person p) { return answers.get(p); }
    synchronized void answer(Person p, Answer a) {
        if (!answers.containsKey(p))
            throw new IllegalArgumentException(p.name() + " isn't invited");
        answers.put(p, a);
    }
    synchronized Set<Person> people() { return new LinkedHashSet<>(answers.keySet()); }
    void cancel(Instant occurrenceStart) { cancelled.add(occurrenceStart); }

    // Every occurrence that starts before `until` and isn't cancelled.
    List<Slot> occurrences(Instant from, Instant until) {
        List<Slot> out = new ArrayList<>();
        for (Iterator<Slot> it = repeat.from(first); it.hasNext(); ) {
            Slot s = it.next();
            if (!s.start().isBefore(until)) break;         // (1) stop: the rest are later
            if (s.end().isAfter(from) && !cancelled.contains(s.start())) out.add(s);
        }
        return out;
    }
}

sealed interface Booked permits Booked.Ok, Booked.Refused {
    record Ok(Meeting meeting, List<String> warnings) implements Booked { }
    record Refused(String why) implements Booked { }
}

final class Scheduler {
    static final Duration HORIZON = Duration.ofDays(366);   // rooms checked a year ahead
    private final Map<Room, List<Meeting>> roomBookings = new ConcurrentHashMap<>();
    private final Map<Room, ReentrantLock> roomLocks = new ConcurrentHashMap<>();
    private final Map<Person, List<Meeting>> calendars = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    Booked book(String title, Person organizer, List<Person> guests, Room room,
                Slot first, Repeat repeat) {
        Meeting m = new Meeting("M" + nextId.getAndIncrement(), title, organizer, guests,
                                room, first, repeat);
        if (m.people().size() > room.seats())
            return new Booked.Refused(room.name() + " seats " + room.seats());
        Instant from = first.start(), until = from.plus(HORIZON);
        List<Slot> mine = m.occurrences(from, until);
        ReentrantLock lock = roomLocks.computeIfAbsent(room, r -> new ReentrantLock());
        lock.lock();                                        // (1) check and book together
        try {
            for (Meeting other : roomBookings.getOrDefault(room, List.of()))
                for (Slot o : other.occurrences(from, until))
                    for (Slot s : mine)
                        if (s.overlaps(o))                   // (2) rooms: a hard rule
                            return new Booked.Refused(room.name() + " is taken by "
                                    + other.title + " at " + o.in(organizer.zone()));
            roomBookings.computeIfAbsent(room, r -> new CopyOnWriteArrayList<>()).add(m);
        } finally {
            lock.unlock();
        }
        List<String> warnings = new ArrayList<>();
        for (Person p : m.people()) {
            for (Slot s : mine)
                if (busy(p, s.start(), s.end()).stream().anyMatch(b -> b.overlaps(s))) {
                    // (3) people: only a warning
                    warnings.add(p.name() + " is busy " + s.in(p.zone()));
                    break;
                }
            calendars.computeIfAbsent(p, k -> new CopyOnWriteArrayList<>()).add(m);
        }
        return new Booked.Ok(m, warnings);
    }

    List<Slot> busy(Person p, Instant from, Instant until) {
        List<Slot> out = new ArrayList<>();
        for (Meeting m : calendars.getOrDefault(p, List.of()))
            if (m.answerOf(p) != Answer.DECLINED)           // (1) declined frees the time
                out.addAll(m.occurrences(from, until));
        Collections.sort(out);
        return out;
    }

    void respond(Person p, Meeting m, Answer a) { m.answer(p, a); }

    List<Meeting> roomBookings(Room r) { return roomBookings.getOrDefault(r, List.of()); }

    List<Slot> freeSlots(List<Person> people, Duration length, LocalDate fromDay,
                         int days, ZoneId shownIn, int max) {
        List<Slot> found = new ArrayList<>();
        for (int d = 0; d < days && found.size() < max; d++) {
            LocalDate day = fromDay.plusDays(d);
            Instant start = Instant.MIN, end = Instant.MAX;
            for (Person p : people) {                      // (1) everyone's working hours
                Instant ws = day.atTime(p.workStart()).atZone(p.zone()).toInstant();
                Instant we = day.atTime(p.workEnd()).atZone(p.zone()).toInstant();
                if (ws.isAfter(start)) start = ws;
                if (we.isBefore(end)) end = we;
            }
            if (!end.isAfter(start)) continue;               // no overlap that day
            List<Slot> taken = new ArrayList<>();
            for (Person p : people) taken.addAll(busy(p, start, end));
            Instant cursor = start;
            for (Slot b : merge(taken)) {               // (2) the gaps between busy times
                if (b.start().isAfter(cursor))
                    addFits(found, cursor, b.start(), length, max);
                if (b.end().isAfter(cursor)) cursor = b.end();
            }
            addFits(found, cursor, end, length, max);
        }
        return found;
    }

    static List<Slot> merge(List<Slot> slots) {
        List<Slot> sorted = new ArrayList<>(slots);
        Collections.sort(sorted);
        List<Slot> out = new ArrayList<>();
        for (Slot s : sorted) {
            Slot last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && !s.start().isAfter(last.end())) {
                if (s.end().isAfter(last.end()))
                    out.set(out.size() - 1, new Slot(last.start(), s.end()));
            } else out.add(s);
        }
        return out;
    }

    private static void addFits(List<Slot> found, Instant from, Instant to,
                                Duration length, int max) {
        if (found.size() < max && !Duration.between(from, to).minus(length).isNegative())
            found.add(new Slot(from, from.plus(length)));   // (3) earliest start in it
    }
}
