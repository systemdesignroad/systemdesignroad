import java.time.*;
import java.util.*;

public class Main {
    static void say(Result r) {
        System.out.println(switch (r) {
            case Result.Done d -> "ok: " + d.what();
            case Result.Refused n -> "no: " + n.why();
        });
    }

    public static void main(String[] args) {
        FakeClock clock = new FakeClock(LocalDate.of(2026, 3, 2));
        Notifier notices =
            (m, msg) -> System.out.println("  notice to " + m.name() + ": " + msg);
        Catalogue cat = new Catalogue();
        Library lib = new Library(cat, clock, notices);

        Book ddia = new Book("978-1449373320", "Designing Data-Intensive Applications",
                             "Martin Kleppmann");
        Book clean = new Book("978-0132350884", "Clean Code", "Robert C. Martin");
        Book prag = new Book("978-0135957059", "The Pragmatic Programmer",
                             "Hunt and Thomas");
        Book refac = new Book("978-0134757599", "Refactoring", "Martin Fowler");
        for (Book b : List.of(ddia, clean, prag, refac)) cat.addBook(b);
        lib.addCopy(new BookCopy("DD-1", ddia));
        lib.addCopy(new BookCopy("CC-1", clean));
        lib.addCopy(new BookCopy("CC-2", clean));
        lib.addCopy(new BookCopy("PP-1", prag));
        lib.addCopy(new BookCopy("RF-1", refac));

        Member asha = new Member("m1", "Asha", StandardPolicy.STUDENT);
        Member ben = new Member("m2", "Ben", StandardPolicy.STUDENT);
        Member chitra = new Member("m3", "Chitra", StandardPolicy.FACULTY);

        System.out.println("== search");
        System.out.println(cat.search("martin").stream().map(Book::title).toList());
        System.out.println(cat.search("clean martin").stream().map(Book::title).toList());

        System.out.println("== one copy, two people waiting");
        say(lib.borrow(asha, "DD-1"));
        say(lib.placeHold(ben, ddia.isbn()));
        say(lib.placeHold(chitra, ddia.isbn()));
        say(lib.placeHold(ben, clean.isbn()));
        say(lib.renew(asha, "DD-1"));
        clock.advance(19);
        say(lib.giveBack("DD-1"));
        say(lib.borrow(chitra, "DD-1"));
        clock.advance(4);
        lib.expireHolds().forEach(System.out::println);
        say(lib.borrow(chitra, "DD-1"));
        System.out.println("Asha owes " + Money.of(asha.finesOwed()));

        System.out.println("== limits and fines");
        say(lib.borrow(ben, "CC-1"));
        say(lib.borrow(ben, "CC-2"));
        say(lib.borrow(ben, "PP-1"));
        say(lib.borrow(ben, "RF-1"));
        clock.advance(25);
        say(lib.borrow(ben, "RF-1"));
        say(lib.renew(ben, "PP-1"));
        say(lib.giveBack("CC-1"));
        say(lib.giveBack("CC-2"));
        say(lib.giveBack("PP-1"));
        say(lib.borrow(ben, "RF-1"));
        System.out.println("Ben pays " + Money.of(lib.pay(ben, 25_000))
                           + ", owes " + Money.of(ben.finesOwed()));
        say(lib.borrow(ben, "RF-1"));

        System.out.println("== renewing");
        for (int i = 0; i < 3; i++) {
            clock.advance(10);
            say(lib.renew(ben, "RF-1"));
        }

        System.out.println("== lost, and a new copy");
        say(lib.placeHold(asha, ddia.isbn()));
        say(lib.reportLost("DD-1", 450_000));
        say(lib.addCopy(new BookCopy("DD-2", ddia)));
    }
}
