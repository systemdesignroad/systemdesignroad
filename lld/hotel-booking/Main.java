import java.time.*;
import java.util.*;

public class Main {
    static String say(Result r) {
        String line = switch (r) {
            case Result.Done d -> "ok: " + d.what();
            case Result.Refused n -> "no: " + n.why();
        };
        System.out.println(line);
        return r instanceof Result.Done d ? d.booking().id() : null;
    }

    static Stay stay(int from, int to) {
        return new Stay(LocalDate.of(2026, 3, from), LocalDate.of(2026, 3, to));
    }

    public static void main(String[] args) {
        FakeClock clock = new FakeClock(LocalDateTime.of(2026, 3, 2, 10, 0));
        Hotel h = new Hotel(new WeekendPricing(25), new FreeUntil(48, 50), clock);
        h.addRoom("101", RoomType.STANDARD);
        h.addRoom("102", RoomType.STANDARD);
        h.addRoom("201", RoomType.DELUXE);
        h.addRoom("301", RoomType.SUITE);
        Guest asha = new Guest("g1", "Asha"), ben = new Guest("g2", "Ben");
        Guest chitra = new Guest("g3", "Chitra"), dev = new Guest("g4", "Dev");

        System.out.println("== prices");
        System.out.println("deluxe, Thu 12 to Sun 15: "
                + Money.of(h.quote(RoomType.DELUXE, stay(12, 15))));
        System.out.println("standard rooms free 12 to 15: "
                + h.available(RoomType.STANDARD, stay(12, 15)));

        System.out.println("== the last deluxe room");
        String b1 = say(h.hold(asha, RoomType.DELUXE, stay(12, 15)));
        say(h.hold(ben, RoomType.DELUXE, stay(14, 16)));
        String b2 = say(h.hold(ben, RoomType.DELUXE, stay(15, 17)));
        say(h.confirm(b1));
        say(h.confirm(b1));
        say(h.confirm(b2));

        System.out.println("== a hold that runs out");
        String b3 = say(h.hold(chitra, RoomType.SUITE, stay(20, 22)));
        say(h.hold(dev, RoomType.SUITE, stay(21, 23)));
        clock.advance(Duration.ofMinutes(11));
        String b4 = say(h.hold(dev, RoomType.SUITE, stay(21, 23)));
        say(h.confirm(b3));
        say(h.confirm(b4));

        System.out.println("== cancelling");
        say(h.cancel(b2));
        clock.advance(Duration.ofDays(9).plusHours(10));
        System.out.println("now " + clock.now());
        say(h.cancel(b1));
        say(h.cancel(b1));

        System.out.println("== arriving and leaving");
        say(h.checkIn(b4));
        clock.advance(Duration.ofDays(10));
        say(h.checkIn(b4));
        clock.advance(Duration.ofDays(2));
        say(h.checkOut(b4));
        say(h.cancel(b4));
        try { stay(15, 15); }
        catch (IllegalArgumentException e) {
            System.out.println("no: " + e.getMessage());
        }
    }
}
