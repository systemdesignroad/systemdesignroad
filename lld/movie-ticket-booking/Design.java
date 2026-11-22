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

enum SeatType { REGULAR, PREMIUM, RECLINER }

record Seat(String id, SeatType type) { }

record Screen(String id, List<Seat> seats) {
    Screen {
        seats = List.copyOf(seats);
    }
    Optional<Seat> seat(String seatId) {
        return seats.stream().filter(s -> s.id().equals(seatId)).findFirst();
    }
}

record Movie(String id, String title, Duration length) { }

record Show(String id, Movie movie, Screen screen, LocalDateTime start) { }

record Hold(String id, String userId, String showId, List<String> seatIds,
            Instant expiresAt) { }

enum BookingStatus { CONFIRMED, CANCELLED }

record Booking(String id, String holdId, String userId, String showId,
               List<String> seatIds, long amount, String paymentId,
               BookingStatus status) {
    Booking cancelled() {
        return new Booking(id, holdId, userId, showId, seatIds, amount, paymentId,
                           BookingStatus.CANCELLED);
    }
}

class BookingException extends RuntimeException {
    BookingException(String message) { super(message); }
}
class SeatsUnavailableException extends BookingException {
    SeatsUnavailableException(String m) { super(m); }
}
class HoldExpiredException extends BookingException {
    HoldExpiredException(String m) { super(m); }
}
class PaymentFailedException extends BookingException {
    PaymentFailedException(String m) { super(m); }
}

final class ShowSeats {
    private record Claim(String holdId, Instant until, boolean booked) {
        boolean blocks(Instant now) { return booked || until.isAfter(now); }   // (1)
    }

    private final Set<String> seatIds;
    private final Map<String, Claim> claims = new HashMap<>();

    ShowSeats(Screen screen) {
        Set<String> ids = new LinkedHashSet<>();
        for (Seat s : screen.seats()) ids.add(s.id());
        this.seatIds = Collections.unmodifiableSet(ids);
    }

    synchronized boolean tryHold(String holdId, List<String> ids, Instant now,
                                 Instant until) {
        for (String id : ids) {
            if (!seatIds.contains(id))
                throw new IllegalArgumentException("no seat " + id);
            Claim c = claims.get(id);
            if (c != null && c.blocks(now)) return false;                   // (2)
        }
        for (String id : ids) claims.put(id, new Claim(holdId, until, false));
        return true;
    }

    synchronized boolean extend(String holdId, List<String> ids, Instant now,
                                Instant until) {
        if (!ownsAll(holdId, ids, now)) return false;
        for (String id : ids) claims.put(id, new Claim(holdId, until, false));
        return true;
    }

    synchronized boolean book(String holdId, List<String> ids, Instant now) {
        if (!ownsAll(holdId, ids, now)) return false;
        for (String id : ids) claims.put(id, new Claim(holdId, Instant.MAX, true));
        return true;
    }

    synchronized void release(String holdId, List<String> ids) {
        for (String id : ids) {
            Claim c = claims.get(id);
            if (c != null && c.holdId().equals(holdId)) claims.remove(id);
        }
    }

    synchronized void cancel(String holdId, List<String> ids) {
        for (String id : ids) {
            Claim c = claims.get(id);
            if (c != null && c.holdId().equals(holdId) && c.booked()) claims.remove(id);
        }
    }

    synchronized List<String> free(Instant now) {
        List<String> out = new ArrayList<>();
        for (String id : seatIds) {
            Claim c = claims.get(id);
            if (c == null || !c.blocks(now)) out.add(id);
        }
        return out;
    }

    private boolean ownsAll(String holdId, List<String> ids, Instant now) {
        for (String id : ids) {
            Claim c = claims.get(id);
            if (c == null || !c.holdId().equals(holdId) || c.booked()
                    || !c.until().isAfter(now))
                return false;                                               // (3)
        }
        return true;
    }
}

interface PricingStrategy {
    long price(Show show, Seat seat);                                       // paise
}

final class SeatTypePricing implements PricingStrategy {
    private final Map<SeatType, Long> prices;
    SeatTypePricing(Map<SeatType, Long> prices) { this.prices = Map.copyOf(prices); }
    public long price(Show show, Seat seat) { return prices.get(seat.type()); }
}

record PaymentResult(boolean ok, String paymentId) { }

interface PaymentGateway {
    PaymentResult charge(String idempotencyKey, long amount);
    void refund(String paymentId);
}

interface BookingListener {
    void booked(Booking booking);
}

final class BookingService {
    private enum HoldState { HELD, PAYING, BOOKED, CLOSED }

    private final Map<String, Show> shows = new ConcurrentHashMap<>();
    private final Map<String, ShowSeats> seats = new ConcurrentHashMap<>();
    private final Map<String, Hold> holds = new ConcurrentHashMap<>();
    private final Map<String, HoldState> state = new ConcurrentHashMap<>();
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final Map<String, Booking> byHold = new ConcurrentHashMap<>();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
    private final List<BookingListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private final Clock clock;
    private final PricingStrategy pricing;
    private final PaymentGateway gateway;
    private final Duration holdFor, payFor;

    BookingService(Clock clock, PricingStrategy pricing, PaymentGateway gateway,
                   Duration holdFor, Duration payFor) {
        this.clock = clock;
        this.pricing = pricing;
        this.gateway = gateway;
        this.holdFor = holdFor;
        this.payFor = payFor;
    }

    void addShow(Show show) {
        shows.put(show.id(), show);
        seats.put(show.id(), new ShowSeats(show.screen()));
    }

    void subscribe(BookingListener l) { listeners.add(l); }

    List<String> freeSeats(String showId) {
        return seatsOf(showId).free(clock.instant());
    }

    Hold hold(String userId, String showId, List<String> seatIds) {
        if (seatIds.isEmpty() || seatIds.size() > 10)
            throw new IllegalArgumentException("pick 1 to 10 seats");
        if (new HashSet<>(seatIds).size() != seatIds.size())
            throw new IllegalArgumentException("same seat twice");
        Instant now = clock.instant();
        Hold h = new Hold("h" + ids.incrementAndGet(), userId, showId,
                          List.copyOf(seatIds), now.plus(holdFor));
        if (!seatsOf(showId).tryHold(h.id(), h.seatIds(), now, h.expiresAt()))
            throw new SeatsUnavailableException(
                "some of " + seatIds + " were just taken");
        holds.put(h.id(), h);
        state.put(h.id(), HoldState.HELD);
        return h;
    }

    Booking confirm(String holdId, String userId) {
        Hold h = holds.get(holdId);
        if (h == null || !h.userId().equals(userId))
            throw new BookingException("no such hold");
        Booking done = byHold.get(holdId);
        if (done != null) return done;                                     // (1)
        if (!state.replace(holdId, HoldState.HELD, HoldState.PAYING))      // (2)
            throw new BookingException("payment already in progress, or hold closed");

        ShowSeats ss = seatsOf(h.showId());
        Instant now = clock.instant();
        if (!ss.extend(holdId, h.seatIds(), now, now.plus(payFor))) {      // (3)
            state.put(holdId, HoldState.CLOSED);
            throw new HoldExpiredException("hold " + holdId + " expired");
        }
        long amount = priceOf(h);
        String key = holdId + "/" + attempts.getOrDefault(holdId, 1);
        PaymentResult paid = gateway.charge(key, amount);                  // (4)
        if (!paid.ok()) {
            attempts.merge(holdId, 2, (a, b) -> a + 1);
            state.put(holdId, HoldState.HELD);
            throw new PaymentFailedException("payment declined; seats still held");
        }
        if (!ss.book(holdId, h.seatIds(), clock.instant())) {              // (5)
            gateway.refund(paid.paymentId());
            state.put(holdId, HoldState.CLOSED);
            throw new HoldExpiredException(
                "payment arrived after the hold ended; refunded");
        }
        Booking b = new Booking("b" + ids.incrementAndGet(), holdId, userId, h.showId(),
                                h.seatIds(), amount, paid.paymentId(),
                                BookingStatus.CONFIRMED);
        bookings.put(b.id(), b);
        byHold.put(holdId, b);
        state.put(holdId, HoldState.BOOKED);
        for (BookingListener l : listeners) l.booked(b);
        return b;
    }

    void release(String holdId) {
        Hold h = holds.get(holdId);
        if (h == null) return;
        if (state.replace(holdId, HoldState.HELD, HoldState.CLOSED))
            seatsOf(h.showId()).release(holdId, h.seatIds());
    }

    Booking cancel(String bookingId, String userId) {
        Booking b = bookings.get(bookingId);
        if (b == null || !b.userId().equals(userId))
            throw new BookingException("no such booking");
        Show show = shows.get(b.showId());
        if (!clock.instant().isBefore(show.start().atZone(clock.getZone()).toInstant()))
            throw new BookingException("the show has started");
        Booking c = b.cancelled();
        if (!bookings.replace(bookingId, b, c))                            // (6)
            throw new BookingException("already cancelled");
        gateway.refund(b.paymentId());
        seatsOf(b.showId()).cancel(b.holdId(), b.seatIds());
        return c;
    }

    long priceOf(Hold h) {
        Show show = shows.get(h.showId());
        long total = 0;
        for (String id : h.seatIds())
            total += pricing.price(show, show.screen().seat(id).get());
        return total;
    }

    Optional<Booking> booking(String id) { return Optional.ofNullable(bookings.get(id)); }

    private ShowSeats seatsOf(String showId) {
        ShowSeats ss = seats.get(showId);
        if (ss == null) throw new IllegalArgumentException("no show " + showId);
        return ss;
    }
}
