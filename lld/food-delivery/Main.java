import java.util.*;

public class Main {
    static void say(Result r) {
        System.out.println(switch (r) {
            case Result.Done d -> "ok: " + d.what();
            case Result.Refused n -> "no: " + n.why();
        });
    }

    public static void main(String[] args) {
        Restaurant biryani = new Restaurant("r1", "Biryani House", new Location(0, 0));
        biryani.put(new MenuItem("b1", "Chicken biryani", 24_000));
        biryani.put(new MenuItem("b2", "Raita", 4_000));
        Restaurant pizza = new Restaurant("r2", "Pizza Point", new Location(3, 4));
        pizza.put(new MenuItem("p1", "Margherita", 29_900));

        DeliveryService svc = new DeliveryService(new StandardPricing(50_000, 2_500, 800),
                                                  new NearestFirst(5));
        svc.addRider(new Rider("ravi", "Ravi", new Location(0.5, 0)));
        svc.addRider(new Rider("sita", "Sita", new Location(1, 1)));
        svc.addRider(new Rider("kabir", "Kabir", new Location(6, 6)));
        svc.listen((o, from, to) ->
            System.out.println("  " + o.id() + ": " + from + " -> " + to));
        Location asha = new Location(2, 1.5), ben = new Location(1, 2);

        System.out.println("== one cart, one restaurant");
        Cart c1 = new Cart();
        System.out.println(c1.add(biryani, "b1", 2).orElse("added"));
        System.out.println(c1.add(biryani, "b2", 1).orElse("added"));
        System.out.println(c1.add(pizza, "p1", 1).orElse("added"));

        System.out.println("== placing, accepting, a rider");
        say(svc.place("Asha", c1, asha));
        biryani.put(new MenuItem("b1", "Chicken biryani", 26_000));
        System.out.println("menu now " + Money.of(biryani.item("b1").get().price())
                + "; O1 still charges "
                + Money.of(svc.order("O1").lines().get(0).unitPrice()));
        say(svc.accept("O1"));
        Cart c2 = new Cart();
        c2.add(biryani, "b2", 2);
        say(svc.place("Ben", c2, ben));
        say(svc.accept("O2"));

        System.out.println("== nobody free nearby");
        Cart c3 = new Cart();
        c3.add(biryani, "b1", 1);
        say(svc.place("Asha", c3, asha));
        say(svc.accept("O3"));

        System.out.println("== cooking, pick-up, delivery");
        say(svc.pickUp("O1", "sita"));
        say(svc.cancelByCustomer("O1"));
        say(svc.ready("O1"));
        say(svc.pickUp("O1", "ravi"));
        say(svc.deliver("O1", "ravi"));
        say(svc.ready("O3"));
        say(svc.pickUp("O3", "ravi"));

        System.out.println("== cancelled, rejected, closed");
        Cart c4 = new Cart();
        c4.add(pizza, "p1", 1);
        say(svc.place("Asha", c4, asha));
        say(svc.cancelByCustomer("O4"));
        say(svc.accept("O4"));
        pizza.setOpen(false);
        Cart c5 = new Cart();
        c5.add(pizza, "p1", 2);
        say(svc.place("Ben", c5, ben));
        pizza.setOpen(true);
        say(svc.place("Ben", c5, ben));
        say(svc.reject("O5"));
    }
}
