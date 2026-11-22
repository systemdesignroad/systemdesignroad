import java.time.*;
import java.util.*;

public class Main {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata"), UK = ZoneId.of("Europe/London");

    static Slot at(int day, int h, int m, int minutes) {     // a day in Nov 2026, in IST
        Instant s = LocalDateTime.of(2026, 11, day, h, m).atZone(IST).toInstant();
        return new Slot(s, s.plus(Duration.ofMinutes(minutes)));
    }

    static Meeting say(Booked b) {
        switch (b) {
            case Booked.Ok ok -> {
                System.out.println("ok: " + ok.meeting().id + " " + ok.meeting().title
                        + (ok.warnings().isEmpty() ? ""
                           : " (warning: " + ok.warnings() + ")"));
                return ok.meeting();
            }
            case Booked.Refused no -> {
                System.out.println("no: " + no.why());
                return null;
            }
        }
    }

    public static void main(String[] args) {
        Person asha = new Person("Asha", IST, LocalTime.of(9, 0), LocalTime.of(18, 0));
        Person ben = new Person("Ben", UK, LocalTime.of(9, 0), LocalTime.of(17, 0));
        Person chitra = new Person("Chitra", IST, LocalTime.of(10, 0),
                                   LocalTime.of(19, 0));
        Person dev = new Person("Dev", IST, LocalTime.of(9, 0), LocalTime.of(18, 0));
        Person esha = new Person("Esha", IST, LocalTime.of(9, 0), LocalTime.of(18, 0));
        Room teal = new Room("Teal", 4), orange = new Room("Orange", 10);
        Scheduler cal = new Scheduler();

        System.out.println("== booking rooms");
        Meeting standup = say(cal.book("Standup", asha, List.of(chitra), teal,
                                       at(23, 10, 0, 30), new Weekly(0, IST)));
        say(cal.book("Design review", ben, List.of(asha), teal,
                     at(23, 15, 0, 60), new Once()));
        say(cal.book("1:1", chitra, List.of(dev), teal, at(30, 10, 15, 30), new Once()));
        say(cal.book("Planning", asha, List.of(ben, chitra, dev, esha), teal,
                     at(24, 11, 0, 60), new Once()));

        System.out.println("== people can be double-booked; rooms can't");
        Meeting sync = say(cal.book("Vendor sync", dev, List.of(asha), orange,
                                    at(23, 15, 30, 60), new Once()));
        cal.respond(asha, sync, Answer.DECLINED);
        System.out.println("Asha declines; her Monday afternoon: "
                + cal.busy(asha, at(23, 14, 0, 1).start(), at(23, 18, 0, 1).start())
                     .stream().map(s -> s.in(IST)).toList());

        System.out.println("== a time for Asha (Kolkata) and Ben (London)");
        for (Slot s : cal.freeSlots(List.of(asha, ben), Duration.ofHours(1),
                                    LocalDate.of(2026, 11, 23), 2, IST, 3))
            System.out.println(s.in(IST) + " IST = " + s.in(UK) + " London");

        System.out.println("== cancelling one standup");
        standup.cancel(at(30, 10, 0, 30).start());
        say(cal.book("1:1", chitra, List.of(dev), teal, at(30, 10, 15, 30), new Once()));
        Instant from = at(23, 0, 0, 1).start();
        System.out.println("standups in three weeks: " + standup.occurrences(from,
                from.plus(Duration.ofDays(21))).stream().map(s -> s.in(IST)).toList());
    }
}
