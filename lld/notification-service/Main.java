import java.time.*;
import java.util.*;

public class Main {
    static Notifier n;
    static User asha;

    static void send(String key, Kind kind, String tpl, String... kv) {
        Map<String, String> vars = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) vars.put(kv[i], kv[i + 1]);
        try {
            for (String line : n.notify(new Request(key, asha, kind, tpl, vars)))
                System.out.println(line);
        } catch (IllegalArgumentException e) {
            System.out.println("no: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        Instant[] now = {
            LocalDateTime.of(2026, 11, 23, 22, 30).atZone(ist).toInstant() };
        List<String> log = new ArrayList<>();
        int[] smsCalls = { 0 };
        Gateway email = (to, body) -> { };
        Gateway sms = (to, body) -> {                        // fails twice, then works
            if (++smsCalls[0] <= 2) throw new Exception("provider timeout");
        };
        Gateway push = (to, body) -> { };
        n = new Notifier(
            List.of(new EmailChannel(email, log), new SmsChannel(sms, log),
                    new PushChannel(push, log)),
            List.of(new QuietHours(), new DailyCap(2)), () -> now[0]);
        n.addTemplate(new Template("otp", Map.of(ChannelType.SMS,
                "{code} is your login code. Don't share it.")));
        n.addTemplate(new Template("shipped", Map.of(
                ChannelType.PUSH, "Order {order} is on its way",
                ChannelType.EMAIL, "Hi {name}, order {order} has shipped.")));
        n.addTemplate(new Template("sale", Map.of(ChannelType.EMAIL,
                "Hi {name}, 20% off today")));
        asha = new User("u1", "Asha", ist, "asha@example.com", "+91 98xxxx1234");

        System.out.println("== 10:30 pm: a login code, an order, a sale email");
        send("otp-77", Kind.SECURITY, "otp", "code", "482915");
        send("ship-5521", Kind.ORDER, "shipped", "name", "Asha", "order", "5521");
        send("sale-1", Kind.MARKETING, "sale", "name", "Asha");
        log.forEach(l -> System.out.println("  " + l));
        log.clear();

        System.out.println("== the order event arrives again");
        send("ship-5521", Kind.ORDER, "shipped", "name", "Asha", "order", "5521");

        System.out.println("== next morning, 8:05 am");
        now[0] = now[0].plus(Duration.ofMinutes(9 * 60 + 35));
        n.runDue().forEach(System.out::println);
        send("sale-2", Kind.MARKETING, "sale", "name", "Asha");
        send("sale-3", Kind.MARKETING, "sale", "name", "Asha");

        System.out.println("== preferences");
        n.prefs(asha).set(Kind.MARKETING, Set.of());
        send("sale-4", Kind.MARKETING, "sale", "name", "Asha");
        try {
            n.prefs(asha).set(Kind.SECURITY, Set.of());
        } catch (IllegalArgumentException e) {
            System.out.println("no: " + e.getMessage());
        }
        send("ship-9", Kind.ORDER, "shipped", "name", "Asha");
        send("ship-9", Kind.ORDER, "shipped", "name", "Asha", "order", "9");
    }
}
