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

record Player(String name) { }

record Jump(int from, int to) {
    Jump {
        if (from == to) throw new IllegalArgumentException("a jump must move: " + from);
    }
    boolean isLadder() { return to > from; }
}

final class Board {
    private final int last;
    private final Map<Integer, Jump> jumps = new HashMap<>();   // (1) by start square

    Board(int last, List<Jump> list) {
        if (last < 2) throw new IllegalArgumentException("board too small");
        this.last = last;
        for (Jump j : list) {
            if (j.from() < 1 || j.from() >= last || j.to() < 1 || j.to() > last)
                throw new IllegalArgumentException("off the board: " + j);   // (2)
            if (jumps.putIfAbsent(j.from(), j) != null)
                throw new IllegalArgumentException("two jumps start at " + j.from());
        }
        for (Jump j : list)
            if (jumps.containsKey(j.to()))
                throw new IllegalArgumentException("jump lands on a jump: " + j);  // (3)
    }

    int last() { return last; }
    Optional<Jump> jumpAt(int square) { return Optional.ofNullable(jumps.get(square)); }
    int resolve(int square) {
        Jump j = jumps.get(square);
        return j == null ? square : j.to();
    }
    Collection<Jump> jumps() {
        return Collections.unmodifiableCollection(jumps.values());
    }
}

interface Dice { int roll(); }

final class RandomDice implements Dice {
    private final int count;
    private final RandomGenerator random;
    RandomDice(int count, RandomGenerator random) {
        this.count = count;
        this.random = random;
    }
    public int roll() {
        int sum = 0;
        for (int i = 0; i < count; i++) sum += 1 + random.nextInt(6);
        return sum;
    }
}

final class ScriptedDice implements Dice {
    private final Deque<Integer> rolls = new ArrayDeque<>();
    ScriptedDice(int... values) { for (int v : values) rolls.add(v); }
    public int roll() {
        Integer v = rolls.poll();
        if (v == null) throw new IllegalStateException("script ran out");
        return v;
    }
}

interface FinishRule {
    int land(int from, int roll, int last);   // (1) where the move stops, before jumps
}

final class ExactFinish implements FinishRule {
    public int land(int from, int roll, int last) {
        int to = from + roll;
        return to > last ? from : to;                 // (2) overshoot: don't move
    }
}

final class BounceBack implements FinishRule {
    public int land(int from, int roll, int last) {
        int to = from + roll;
        return to > last ? last - (to - last) : to;   // (3) 98 + 5: up to 100, back to 97
    }
}

interface TurnRule {
    boolean rollsAgain(int roll, int rollsThisTurn);
}

final class OneRollPerTurn implements TurnRule {
    public boolean rollsAgain(int roll, int rollsThisTurn) { return false; }
}

record Turn(Player player, int roll, int from, int landed, int to, boolean won) {
    boolean jumped() { return landed != to; }
    @Override public String toString() {
        String s = player.name() + " rolls " + roll + ": " + from + " -> " + landed;
        if (jumped()) s += (to > landed ? " ladder to " : " snake to ") + to;
        return won ? s + ", wins" : s;
    }
}

interface GameListener {
    void turnTaken(Turn turn);
}

final class Game {
    private final Board board;
    private final Dice dice;
    private final FinishRule finish;
    private final TurnRule turns;
    private final Deque<Player> order;
    private final Map<Player, Integer> position = new HashMap<>();
    private final List<GameListener> listeners = new CopyOnWriteArrayList<>();
    private Player winner;
    private int rollsThisTurn;

    Game(Board board, List<Player> players, Dice dice, FinishRule finish,
         TurnRule turns) {
        if (players.size() < 2) throw new IllegalArgumentException("need two players");
        if (new HashSet<>(players).size() != players.size())
            throw new IllegalArgumentException("duplicate player");
        this.board = board;
        this.dice = dice;
        this.finish = finish;
        this.turns = turns;
        this.order = new ArrayDeque<>(players);
        for (Player p : players) position.put(p, 0);                       // (1)
    }

    void subscribe(GameListener l) { listeners.add(l); }

    synchronized Optional<Turn> takeTurn(Player p) {
        if (winner != null || !p.equals(order.peekFirst())) return Optional.empty();
        int roll = dice.roll();
        int from = position.get(p);
        int landed = finish.land(from, roll, board.last());
        int to = board.resolve(landed);
        position.put(p, to);
        boolean won = to == board.last();
        rollsThisTurn++;
        if (won) {
            winner = p;
        } else if (!turns.rollsAgain(roll, rollsThisTurn)) {
            order.addLast(order.pollFirst());                               // (2)
            rollsThisTurn = 0;
        }
        Turn t = new Turn(p, roll, from, landed, to, won);
        for (GameListener l : listeners) l.turnTaken(t);
        return Optional.of(t);
    }

    synchronized Player current() { return order.peekFirst(); }
    synchronized Optional<Player> winner() { return Optional.ofNullable(winner); }
    synchronized int positionOf(Player p) { return position.get(p); }
    Board board() { return board; }
}

class Demo {
    public static void main(String[] args) {
        Board board = new Board(100, List.of(
            new Jump(4, 25), new Jump(13, 46), new Jump(33, 49), new Jump(42, 63),
            new Jump(50, 69), new Jump(62, 81), new Jump(74, 92),
            new Jump(27, 5), new Jump(40, 3), new Jump(43, 18), new Jump(54, 31),
            new Jump(66, 45), new Jump(76, 58), new Jump(89, 53), new Jump(99, 41)));
        Player meera = new Player("Meera"), kabir = new Player("Kabir");
        Game g = new Game(board, List.of(meera, kabir),
            new ScriptedDice(4, 6, 2, 5, 6, 2), new ExactFinish(), new OneRollPerTurn());
        g.subscribe(System.out::println);
        for (int i = 0; i < 6; i++) g.takeTurn(g.current());
        System.out.println("Kabir out of turn: " + g.takeTurn(kabir).isPresent());
    }
}
