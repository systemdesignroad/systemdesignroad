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

enum Direction {
    UP, DOWN;
    Direction opposite() { return this == UP ? DOWN : UP; }
}

enum CarState { IDLE, MOVING, DOORS_OPEN, OUT_OF_SERVICE }

interface CarListener { void doorsOpened(ElevatorCar car, int floor, Direction leaving); }

final class ElevatorCar {
    static final int DOOR_TICKS = 2;
    private final int id, minFloor, maxFloor;
    private final CarListener listener;
    private final TreeSet<Integer> carStops = new TreeSet<>();    // (1) pressed inside
    private final TreeSet<Integer> upCalls = new TreeSet<>();     // (2) floor buttons, going up
    private final TreeSet<Integer> downCalls = new TreeSet<>();
    private int floor;
    private Direction dir = Direction.UP;
    private CarState state = CarState.IDLE;
    private int doorTicks;
    private boolean draining;
    private int travelled;

    ElevatorCar(int id, int minFloor, int maxFloor, int startFloor,
                CarListener listener) {
        this.id = id; this.minFloor = minFloor; this.maxFloor = maxFloor;
        this.floor = startFloor; this.listener = listener;
    }

    void addCarStop(int f) {
        if (f < minFloor || f > maxFloor)
            throw new IllegalArgumentException("no floor " + f);
        if (state == CarState.OUT_OF_SERVICE) return;
        if (f == floor && state == CarState.DOORS_OPEN) return;    // (3) already here, doors open
        carStops.add(f);
    }

    private TreeSet<Integer> calls(Direction d) {
        return d == Direction.UP ? upCalls : downCalls;
    }
    void assignHallCall(int f, Direction d) { calls(d).add(f); }
    void cancelHallCall(int f, Direction d) { calls(d).remove(f); }

    boolean accepting() { return state != CarState.OUT_OF_SERVICE && !draining; }
    boolean busy() {
        return !carStops.isEmpty() || !upCalls.isEmpty() || !downCalls.isEmpty();
    }
    int stopCount() { return carStops.size() + upCalls.size() + downCalls.size(); }

    void tick() {
        switch (state) {
            case OUT_OF_SERVICE -> { }
            case DOORS_OPEN -> {
                if (--doorTicks > 0) return;                         // (1) still open
                state = busy() ? CarState.MOVING
                      : draining ? CarState.OUT_OF_SERVICE : CarState.IDLE;
            }
            case IDLE, MOVING -> {
                if (!busy()) {
                    state = draining ? CarState.OUT_OF_SERVICE : CarState.IDLE;
                    return;
                }
                if (serveHere()) return;                             // (2) open doors instead of moving
                if (!anyAhead(dir)) dir = dir.opposite();            // (3) LOOK: turn at the last stop
                floor += dir == Direction.UP ? 1 : -1;               // (4) the only line that moves
                travelled++;
                state = CarState.MOVING;
            }
        }
    }

    private boolean serveHere() {
        boolean turning = !anyAhead(dir);                            // (1) nothing further this way
        boolean stop = carStops.contains(floor)
            || calls(dir).contains(floor)
            || (turning && (upCalls.contains(floor) || downCalls.contains(floor)));
        if (!stop) return false;
        carStops.remove(floor);
        boolean sameWay = calls(dir).remove(floor);
        if (!sameWay && turning && calls(dir.opposite()).remove(floor)) {
            dir = dir.opposite();                                    // (2) answer the other button
        }
        state = CarState.DOORS_OPEN;
        doorTicks = DOOR_TICKS;
        listener.doorsOpened(this, floor, dir);
        return true;
    }

    private boolean anyAhead(Direction d) {
        for (TreeSet<Integer> s : List.of(carStops, upCalls, downCalls)) {
            Integer next = d == Direction.UP ? s.higher(floor) : s.lower(floor);   // (3)
            if (next != null) return true;
        }
        return false;
    }

    // Hand back hall calls so another car can take them; finish car stops, then stop.
    List<HallPress> startMaintenance() {
        draining = true;
        List<HallPress> back = new ArrayList<>();
        for (int f : upCalls) back.add(new HallPress(f, Direction.UP));
        for (int f : downCalls) back.add(new HallPress(f, Direction.DOWN));
        upCalls.clear();
        downCalls.clear();
        return back;
    }

    void endMaintenance() {
        draining = false;
        if (state == CarState.OUT_OF_SERVICE) state = CarState.IDLE;
    }

    // the farthest stop in the current direction, or the current floor if none
    int farthestAhead() {
        Integer far = null;
        for (TreeSet<Integer> s : List.of(carStops, upCalls, downCalls)) {
            if (s.isEmpty()) continue;
            int f = dir == Direction.UP ? s.last() : s.first();
            if (far == null || (dir == Direction.UP ? f > far : f < far)) far = f;
        }
        return far == null ? floor : far;
    }

    int id() { return id; }
    int floor() { return floor; }
    Direction direction() { return dir; }
    CarState state() { return state; }
    int travelled() { return travelled; }
}

interface Dispatcher {
    Optional<ElevatorCar> choose(List<ElevatorCar> cars, int floor, Direction d);
}

final class NearestCarDispatcher implements Dispatcher {
    public Optional<ElevatorCar> choose(List<ElevatorCar> cars, int floor, Direction d) {
        return cars.stream()
            .filter(ElevatorCar::accepting)
            .min(Comparator.comparingInt(c -> cost(c, floor, d)));
    }

    static int cost(ElevatorCar c, int floor, Direction d) {
        int here = c.floor();
        if (!c.busy()) return Math.abs(here - floor);                  // (1) idle: just the distance
        boolean onTheWay = c.direction() == d
            && (d == Direction.UP ? floor >= here : floor <= here);
        int far = c.farthestAhead();
        int distance = onTheWay ? Math.abs(floor - here)               // (2) heading there already
            : Math.abs(far - here) + Math.abs(far - floor);            // (3) finish the run, come back
        return distance + 2 * c.stopCount();                           // (4) each stop costs door time
    }
}

final class RoundRobinDispatcher implements Dispatcher {
    private int next;
    public Optional<ElevatorCar> choose(List<ElevatorCar> cars, int floor, Direction d) {
        for (int i = 0; i < cars.size(); i++) {
            ElevatorCar c = cars.get((next + i) % cars.size());
            if (c.accepting()) {
                next = (next + i + 1) % cars.size();
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}

sealed interface Request permits HallPress, CarPress, Maintenance { }
record HallPress(int floor, Direction dir) implements Request { }
record CarPress(int carId, int floor) implements Request { }
record Maintenance(int carId, boolean on) implements Request { }

final class ElevatorSystem {
    private final int minFloor, maxFloor;
    private final List<ElevatorCar> cars = new ArrayList<>();
    private final Dispatcher dispatcher;
    private final Queue<Request> inbox = new ConcurrentLinkedQueue<>();   // (1) the only shared thing
    private final Map<String, ElevatorCar> assigned = new HashMap<>();   // (2) lit: "5UP" → car
    private final Deque<HallPress> waiting = new ArrayDeque<>();         // (3) no car free yet
    private final List<CarListener> listeners = new CopyOnWriteArrayList<>();
    private long now;

    ElevatorSystem(int minFloor, int maxFloor, int carCount, Dispatcher dispatcher) {
        this.minFloor = minFloor; this.maxFloor = maxFloor; this.dispatcher = dispatcher;
        for (int i = 0; i < carCount; i++)
            cars.add(new ElevatorCar(i, minFloor, maxFloor, minFloor, this::opened));
    }

    // any thread: buttons just leave a message
    public void pressHallButton(int floor, Direction d) {
        if (floor < minFloor || floor > maxFloor)
            throw new IllegalArgumentException("no floor " + floor);
        boolean offTheEnd = (floor == maxFloor && d == Direction.UP)
                         || (floor == minFloor && d == Direction.DOWN);
        if (offTheEnd)
            throw new IllegalArgumentException("no " + d + " button on floor " + floor);
        inbox.add(new HallPress(floor, d));
    }
    public void pressCarButton(int carId, int floor) {
        inbox.add(new CarPress(carId, floor));
    }
    public void setMaintenance(int carId, boolean on) {
        inbox.add(new Maintenance(carId, on));
    }
    public void addListener(CarListener l) { listeners.add(l); }

    // the controller thread only: one step of the whole building
    public void tick() {
        Request r;
        while ((r = inbox.poll()) != null) handle(r);
        for (int i = waiting.size(); i > 0; i--) assign(waiting.poll());   // (4) retry stuck calls
        for (ElevatorCar c : cars) c.tick();
        now++;
    }

    private void handle(Request r) {
        switch (r) {                                                       // (5) sealed: all cases
            case HallPress h -> assign(h);
            case CarPress c -> cars.get(c.carId()).addCarStop(c.floor());
            case Maintenance m -> {
                ElevatorCar car = cars.get(m.carId());
                if (!m.on()) { car.endMaintenance(); return; }
                for (HallPress h : car.startMaintenance()) {
                    assigned.remove(key(h.floor(), h.dir()));
                    assign(h);
                }
            }
        }
    }

    private void assign(HallPress h) {
        if (assigned.containsKey(key(h.floor(), h.dir()))) return;       // (6) already lit
        Optional<ElevatorCar> car = dispatcher.choose(cars, h.floor(), h.dir());
        if (car.isEmpty()) { waiting.add(h); return; }
        car.get().assignHallCall(h.floor(), h.dir());
        assigned.put(key(h.floor(), h.dir()), car.get());
    }

    private void opened(ElevatorCar car, int floor, Direction leaving) {
        ElevatorCar owner = assigned.remove(key(floor, leaving));        // (7) the light goes off
        if (owner != null && owner != car) owner.cancelHallCall(floor, leaving);
        for (CarListener l : listeners) l.doorsOpened(car, floor, leaving);
    }

    private static String key(int floor, Direction d) { return floor + d.name(); }
    List<ElevatorCar> cars() { return cars; }
    long now() { return now; }
}
