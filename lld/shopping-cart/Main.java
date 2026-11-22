import java.util.*;

public class Main {
    public static void main(String[] args) {
        Product shirt = new Product("TS", "T-shirt", "clothing", 50_000);
        Product jeans = new Product("JN", "Jeans", "clothing", 150_000);
        Product socks = new Product("SK", "Socks", "clothing", 20_000);
        Product mug = new Product("MG", "Mug", "home", 30_000);
        Pricer pricer = new Pricer(
            List.of(new CategoryPercent("clothing", 20), new BuyXGetYFree("SK", 2, 1)),
            List.of(new SpendAndSave(200_000, 20_000), new Coupon("SAVE10", 10, 15_000)));

        System.out.println("== a full cart");
        Cart cart = new Cart();
        cart.add(shirt, 2);
        cart.add(jeans, 1);
        cart.add(socks, 3);
        cart.add(mug, 1);
        cart.applyCoupon("save10");
        Receipt r = pricer.price(cart);
        System.out.println(r.print());

        System.out.println("== what each line really cost");
        for (Line l : r.lines())
            System.out.printf("%-8s paid %9s (share of cart savings %s)%n",
                    l.product.name(), Money.of(l.paid()), Money.of(l.cartShare));
        System.out.println("lines add up to "
                + Money.of(r.lines().stream().mapToLong(Line::paid).sum()));

        System.out.println("== without the jeans, no coupon");
        Cart small = new Cart();
        small.add(shirt, 2);
        small.add(socks, 2);
        small.add(mug, 1);
        System.out.println(pricer.price(small).print());

        System.out.println("== the last pair of jeans");
        Inventory stock = new Inventory();
        stock.put("TS", 10); stock.put("JN", 1); stock.put("SK", 10); stock.put("MG", 5);
        Checkout checkout = new Checkout(pricer, stock);
        for (String who : List.of("Asha", "Ben")) {
            Cart c = new Cart();
            c.add(shirt, 1);
            c.add(jeans, 1);
            System.out.println(who + ": " + switch (checkout.placeOrder(c)) {
                case Placed.Ok ok ->
                    ok.order().id() + ", " + Money.of(ok.order().receipt().total());
                case Placed.Refused no -> "refused, " + no.why();
            });
        }
        System.out.println("left: T-shirts " + stock.left("TS")
                           + ", jeans " + stock.left("JN"));
    }
}
