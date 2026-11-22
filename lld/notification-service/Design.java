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

enum Kind { SECURITY, ORDER, MARKETING }                  // (1) urgency decides the rules
enum ChannelType { EMAIL, SMS, PUSH }

record User(String id, String name, ZoneId zone, String email, String phone) { }

final class Preferences {
    private final Map<Kind, Set<ChannelType>> wanted = new EnumMap<>(Kind.class);

    Preferences() {
        wanted.put(Kind.SECURITY, EnumSet.of(ChannelType.SMS));
        wanted.put(Kind.ORDER, EnumSet.of(ChannelType.PUSH, ChannelType.EMAIL));
        wanted.put(Kind.MARKETING, EnumSet.of(ChannelType.EMAIL));
    }

    synchronized Set<ChannelType> channelsFor(Kind k) {
        return EnumSet.copyOf(wanted.get(k));
    }

    synchronized void set(Kind k, Set<ChannelType> chosen) {
        if (k == Kind.SECURITY && chosen.isEmpty())       // (2) can't switch off security
            throw new IllegalArgumentException("security messages need a channel");
        wanted.put(k, chosen.isEmpty() ? EnumSet.noneOf(ChannelType.class)
                                       : EnumSet.copyOf(chosen));
    }
}

record Template(String id, Map<ChannelType, String> text) {
    String render(ChannelType ch, Map<String, String> vars) {
        String t = text.get(ch);
        if (t == null)
            throw new IllegalArgumentException(id + " has no " + ch + " version");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < t.length()) {
            int open = t.indexOf('{', i);
            if (open < 0) { out.append(t, i, t.length()); break; }
            int close = t.indexOf('}', open);
            String name = t.substring(open + 1, close);
            String v = vars.get(name);
            if (v == null)                                   // (1) never send "Hi {name}"
                throw new IllegalArgumentException("missing {" + name + "}");
            out.append(t, i, open).append(v);
            i = close + 1;
        }
        return out.toString();
    }
}

record Message(String id, User to, ChannelType channel, Kind kind, String body) { }

interface Gateway { void send(String address, String body) throws Exception; }

abstract class Channel {
    private final int attempts;
    private final List<String> log;

    Channel(int attempts, List<String> log) { this.attempts = attempts; this.log = log; }

    abstract ChannelType type();
    abstract String address(User u);                 // (1) the step each channel fills in
    abstract Gateway gateway();

    final boolean deliver(Message m) {                       // (2) the same steps for all
        String to = address(m.to());
        if (to == null) { log.add(m.id() + " " + type() + ": no address"); return false; }
        for (int i = 1; i <= attempts; i++) {
            try {
                gateway().send(to, m.body());
                log.add(m.id() + " " + type() + " to " + to + ": sent (try " + i + ")");
                return true;
            } catch (Exception e) {
                log.add(m.id() + " " + type() + ": try " + i + " failed, "
                        + e.getMessage());
            }
        }
        return false;
    }
}

final class EmailChannel extends Channel {
    private final Gateway g;
    EmailChannel(Gateway g, List<String> log) { super(3, log); this.g = g; }
    ChannelType type() { return ChannelType.EMAIL; }
    String address(User u) { return u.email(); }
    Gateway gateway() { return g; }
}

final class SmsChannel extends Channel {
    private final Gateway g;
    SmsChannel(Gateway g, List<String> log) { super(3, log); this.g = g; }
    ChannelType type() { return ChannelType.SMS; }
    String address(User u) { return u.phone(); }
    Gateway gateway() { return g; }
}

final class PushChannel extends Channel {
    private final Gateway g;
    PushChannel(Gateway g, List<String> log) { super(2, log); this.g = g; }
    ChannelType type() { return ChannelType.PUSH; }
    String address(User u) { return "device:" + u.id(); }
    Gateway gateway() { return g; }
}

sealed interface Decision permits Decision.Send, Decision.Drop, Decision.Later {
    record Send() implements Decision { }
    record Drop(String why) implements Decision { }
    record Later(Instant at, String why) implements Decision { }
}

record Request(String key, User user, Kind kind, String template,
               Map<String, String> vars) { }

interface Rule { Decision check(Request r, ChannelType ch, Instant now); }

final class QuietHours implements Rule {                // (1) marketing waits for morning
    public Decision check(Request r, ChannelType ch, Instant now) {
        if (r.kind() != Kind.MARKETING) return new Decision.Send();
        ZonedDateTime local = now.atZone(r.user().zone());
        int h = local.getHour();
        if (h >= 8 && h < 21) return new Decision.Send();
        ZonedDateTime next8 = local.toLocalDate().plusDays(h >= 21 ? 1 : 0)
                                   .atTime(8, 0).atZone(r.user().zone());
        return new Decision.Later(next8.toInstant(), "quiet hours");
    }
}

final class DailyCap implements Rule {                    // (2) at most N marketing a day
    private final int max;
    private final Map<String, AtomicInteger> sent = new ConcurrentHashMap<>();
    DailyCap(int max) { this.max = max; }

    public Decision check(Request r, ChannelType ch, Instant now) {
        if (r.kind() != Kind.MARKETING) return new Decision.Send();
        String day = r.user().id() + "@" + now.atZone(r.user().zone()).toLocalDate();
        AtomicInteger n = sent.computeIfAbsent(day, k -> new AtomicInteger());
        if (n.incrementAndGet() <= max) return new Decision.Send();  // (3) count, check
        n.decrementAndGet();
        return new Decision.Drop("daily marketing cap of " + max);
    }
}

interface Clock { Instant now(); }

record Pending(Instant at, Request request, ChannelType channel) { }

final class Notifier {
    private final Map<String, Preferences> prefs = new ConcurrentHashMap<>();
    private final Map<String, Template> templates = new ConcurrentHashMap<>();
    private final Map<ChannelType, Channel> channels = new EnumMap<>(ChannelType.class);
    private final List<Rule> rules;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Queue<Pending> later =
        new PriorityBlockingQueue<>(16, Comparator.comparing(Pending::at));
    private final Clock clock;
    private final AtomicInteger nextId = new AtomicInteger(1);

    Notifier(List<Channel> chans, List<Rule> rules, Clock clock) {
        for (Channel c : chans) channels.put(c.type(), c);
        this.rules = List.copyOf(rules); this.clock = clock;
    }

    void addTemplate(Template t) { templates.put(t.id(), t); }
    Preferences prefs(User u) {
        return prefs.computeIfAbsent(u.id(), k -> new Preferences());
    }

    List<String> notify(Request r) {
        List<String> outcome = new ArrayList<>();
        if (!seen.add(r.key())) {                       // (1) one event, one notification
            outcome.add(r.key() + ": duplicate, ignored");
            return outcome;
        }
        try {
            Set<ChannelType> chans = prefs(r.user()).channelsFor(r.kind());
            if (chans.isEmpty())
                outcome.add(r.key() + ": " + r.user().name() + " opted out");
            for (ChannelType ch : chans) outcome.add(route(r, ch, clock.now()));
            return outcome;
        } catch (RuntimeException e) {
            seen.remove(r.key());                        // (2) a fixed retry can still go
            throw e;
        }
    }

    private String route(Request r, ChannelType ch, Instant now) {
        for (Rule rule : rules) {
            switch (rule.check(r, ch, now)) {
                case Decision.Drop d -> {
                    return r.key() + " " + ch + ": dropped, " + d.why();
                }
                case Decision.Later l -> {
                    later.add(new Pending(l.at(), r, ch));   // (3) try again then
                    return r.key() + " " + ch + ": held until "
                        + l.at().atZone(r.user().zone()).toLocalTime() + ", " + l.why();
                }
                case Decision.Send s -> { }
            }
        }
        Template t = templates.get(r.template());
        String body = t.render(ch, r.vars());
        String id = "N" + nextId.getAndIncrement();
        Message m = new Message(id, r.user(), ch, r.kind(), body);
        boolean ok = channels.get(ch).deliver(m);
        return r.key() + " " + ch + ": " + (ok ? "delivered" : "failed after retries");
    }

    // Called by a timer: send everything whose time has come.
    List<String> runDue() {
        List<String> out = new ArrayList<>();
        Instant now = clock.now();
        while (!later.isEmpty() && !later.peek().at().isAfter(now)) {
            Pending p = later.poll();
            out.add(route(p.request(), p.channel(), now));
        }
        return out;
    }
}
