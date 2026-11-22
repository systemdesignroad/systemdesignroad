import java.util.*;

public class Main {
    static String show(Commit c) {
        return switch (c) {
            case Commit.Done d -> "committed (" + d.written()
                                  + (d.written() == 1 ? " change)" : " changes)");
            case Commit.Conflict x -> "conflict on " + x.key() + ": try again";
        };
    }

    static String val(Session s, String key) { return s.get(key).orElse("(none)"); }

    public static void main(String[] args) {
        Storage store = new InMemoryStorage();
        Session s = new Session(store);

        System.out.println("== no transaction");
        s.set("a", "10");
        System.out.println("a = " + val(s, "a"));
        s.delete("a");
        System.out.println("after delete, a = " + val(s, "a"));

        System.out.println("== nested transactions");
        s.set("a", "10");
        s.begin();
        s.set("a", "20");
        s.begin();
        s.set("a", "30");
        System.out.println("depth " + s.depth() + ": a = " + val(s, "a"));
        s.rollback();
        System.out.println("rolled back inner, depth " + s.depth()
                           + ": a = " + val(s, "a"));
        System.out.println("another client sees a = " + val(new Session(store), "a"));
        System.out.println(show(s.commit()) + "; a = " + val(s, "a"));
        s.begin();
        s.set("b", "5");
        s.delete("a");
        System.out.println("inside: a = " + val(s, "a") + ", b = " + val(s, "b"));
        s.rollback();
        System.out.println("rolled back: a = " + val(s, "a") + ", b = " + val(s, "b"));
        try { s.commit(); }
        catch (IllegalStateException e) { System.out.println("no: " + e.getMessage()); }

        System.out.println("== two clients, one balance");
        s.set("balance", "100");
        Session asha = new Session(store), ben = new Session(store);
        asha.begin(); ben.begin();
        int a = Integer.parseInt(val(asha, "balance"));
        int b = Integer.parseInt(val(ben, "balance"));
        System.out.println("Asha reads " + a + ", Ben reads " + b);
        asha.set("balance", String.valueOf(a + 50));
        ben.set("balance", String.valueOf(b - 30));
        System.out.println("Asha adds 50: " + show(asha.commit()));
        System.out.println("Ben takes 30: " + show(ben.commit()));
        System.out.println("balance = " + val(s, "balance"));
        ben.begin();
        b = Integer.parseInt(val(ben, "balance"));
        ben.set("balance", String.valueOf(b - 30));
        System.out.println("Ben again, reads " + b + ": " + show(ben.commit()));
        System.out.println("balance = " + val(s, "balance"));

        System.out.println("== a key that didn't exist");
        asha.begin();
        System.out.println("Asha: is 'lock' free? " + asha.get("lock").isEmpty());
        s.set("lock", "ben");
        asha.set("lock", "asha");
        System.out.println("Asha takes it: " + show(asha.commit())
                           + "; lock = " + val(s, "lock"));
    }
}
