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

record Player(String name, char mark) { }

record Move(int row, int col, Player player) { }

enum GameState { IN_PROGRESS, WON, DRAW }

enum MoveResult { OK, GAME_OVER, NOT_YOUR_TURN, OUTSIDE_BOARD, CELL_TAKEN }

interface BoardView {
    int size();
    boolean inside(int row, int col);
    boolean isEmpty(int row, int col);
    char at(int row, int col);                        // (1) 0 means empty
}

final class Board implements BoardView {
    private final int size;
    private final char[][] cells;
    private int filled;

    Board(int size) {
        if (size < 3) throw new IllegalArgumentException("size must be at least 3");
        this.size = size;
        this.cells = new char[size][size];
    }

    public int size() { return size; }
    public boolean inside(int r, int c) {
        return r >= 0 && r < size && c >= 0 && c < size;
    }
    public boolean isEmpty(int r, int c) { return cells[r][c] == 0; }
    public char at(int r, int c) { return cells[r][c]; }

    void place(int r, int c, char mark) {
        if (!isEmpty(r, c)) throw new IllegalStateException("cell taken");   // (2)
        cells[r][c] = mark;
        filled++;
    }

    void clear(int r, int c) {
        if (isEmpty(r, c)) return;
        cells[r][c] = 0;
        filled--;
    }

    boolean isFull() { return filled == size * size; }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) sb.append(isEmpty(r, c) ? '.' : cells[r][c]);
            if (r < size - 1) sb.append('\n');
        }
        return sb.toString();
    }
}

interface WinRule {
    boolean isWinningMove(BoardView board, Move move);  // (1) move is already placed
    default void undo(Move move) { }                    // (2) for rules with state
}

final class LineCounter implements WinRule {
    private final int n;
    private final Map<Character, int[]> rows = new HashMap<>();
    private final Map<Character, int[]> cols = new HashMap<>();
    private final Map<Character, int[]> diags = new HashMap<>(); // (3) [0] main, [1] anti

    LineCounter(int n) { this.n = n; }

    public boolean isWinningMove(BoardView board, Move m) {
        return add(m, +1);
    }

    public void undo(Move m) { add(m, -1); }

    private boolean add(Move m, int delta) {
        char k = m.player().mark();
        int[] row = rows.computeIfAbsent(k, x -> new int[n]);
        int[] col = cols.computeIfAbsent(k, x -> new int[n]);
        int[] diag = diags.computeIfAbsent(k, x -> new int[2]);
        row[m.row()] += delta;
        col[m.col()] += delta;
        if (m.row() == m.col()) diag[0] += delta;
        if (m.row() + m.col() == n - 1) diag[1] += delta;     // (4) the other diagonal
        return row[m.row()] == n || col[m.col()] == n || diag[0] == n || diag[1] == n;
    }
}

interface GameListener {
    default void moved(Game game, Move move) { }
    default void finished(Game game, GameState state, Player winner) { }
}

final class Game {
    private final Board board;
    private final List<Player> players;
    private final WinRule rule;
    private final Deque<Move> history = new ArrayDeque<>();
    private final List<GameListener> listeners = new CopyOnWriteArrayList<>();
    private int turn;
    private GameState state = GameState.IN_PROGRESS;
    private Player winner;

    Game(int size, List<Player> players, WinRule rule) {
        if (players.size() < 2) throw new IllegalArgumentException("need two players");
        long marks = players.stream().map(Player::mark).distinct().count();
        if (marks != players.size())
            throw new IllegalArgumentException("marks must differ");
        this.board = new Board(size);
        this.players = List.copyOf(players);
        this.rule = rule;
    }

    static Game classic(Player x, Player o) {
        return new Game(3, List.of(x, o), new LineCounter(3));
    }

    void subscribe(GameListener l) { listeners.add(l); }

    synchronized MoveResult play(Player p, int row, int col) {
        if (state != GameState.IN_PROGRESS) return MoveResult.GAME_OVER;   // (1)
        if (!p.equals(players.get(turn))) return MoveResult.NOT_YOUR_TURN;
        if (!board.inside(row, col)) return MoveResult.OUTSIDE_BOARD;
        if (!board.isEmpty(row, col)) return MoveResult.CELL_TAKEN;

        Move move = new Move(row, col, p);
        board.place(row, col, p.mark());
        history.push(move);
        if (rule.isWinningMove(board, move)) {
            state = GameState.WON;
            winner = p;
        } else if (board.isFull()) {
            state = GameState.DRAW;
        } else {
            turn = (turn + 1) % players.size();                           // (2)
        }
        for (GameListener l : listeners) l.moved(this, move);
        if (state != GameState.IN_PROGRESS)
            for (GameListener l : listeners) l.finished(this, state, winner);
        return MoveResult.OK;
    }

    synchronized boolean undo() {
        if (history.isEmpty()) return false;
        Move last = history.pop();
        board.clear(last.row(), last.col());
        rule.undo(last);                                                  // (3)
        state = GameState.IN_PROGRESS;
        winner = null;
        turn = players.indexOf(last.player());                            // (4)
        return true;
    }

    synchronized GameState state() { return state; }
    synchronized Player winner() { return winner; }
    synchronized Player current() { return players.get(turn); }
    synchronized List<Move> moves() {
        List<Move> out = new ArrayList<>(history);
        Collections.reverse(out);
        return out;
    }
    BoardView board() { return board; }
    List<Player> players() { return players; }
    @Override public synchronized String toString() { return board.toString(); }
}

interface MoveChooser {
    int[] choose(BoardView board, char me);           // (1) a read-only view: can't cheat
}

final class RandomChooser implements MoveChooser {
    private final Random random;
    RandomChooser(Random random) { this.random = random; }

    public int[] choose(BoardView b, char me) {
        List<int[]> free = new ArrayList<>();
        for (int r = 0; r < b.size(); r++)
            for (int c = 0; c < b.size(); c++)
                if (b.isEmpty(r, c)) free.add(new int[] { r, c });
        return free.get(random.nextInt(free.size()));
    }
}

final class Match {
    private final Game game;
    private final Map<Player, MoveChooser> choosers;

    Match(Game game, Map<Player, MoveChooser> choosers) {
        this.game = game;
        this.choosers = Map.copyOf(choosers);
    }

    Player playOut() {
        while (game.state() == GameState.IN_PROGRESS) {
            Player p = game.current();
            int[] cell = choosers.get(p).choose(game.board(), p.mark());
            MoveResult r = game.play(p, cell[0], cell[1]);
            if (r != MoveResult.OK) throw new IllegalStateException(p.name() + ": " + r);
        }
        return game.winner();                                             // (2)
    }
}

final class Scoreboard implements GameListener {
    private final Map<String, Integer> wins = new TreeMap<>();
    private int draws;

    public synchronized void finished(Game g, GameState s, Player winner) {
        if (s == GameState.DRAW) draws++;
        else wins.merge(winner.name(), 1, Integer::sum);
    }

    @Override public synchronized String toString() {
        return "wins=" + wins + ", draws=" + draws;
    }
}

class Demo {
    public static void main(String[] args) {
        Player asha = new Player("Asha", 'X'), ravi = new Player("Ravi", 'O');
        Game g = Game.classic(asha, ravi);
        System.out.println("X (1,1): " + g.play(asha, 1, 1));
        System.out.println("X again: " + g.play(asha, 0, 0));
        System.out.println("O (1,1): " + g.play(ravi, 1, 1));
        System.out.println("O (3,0): " + g.play(ravi, 3, 0));
        g.play(ravi, 0, 0);
        g.play(asha, 0, 2);
        g.play(ravi, 1, 0);
        System.out.println("X (2,0): " + g.play(asha, 2, 0));
        System.out.println(g.state() + ", winner " + g.winner().name());
        System.out.println(g);
        System.out.println("O (2,2): " + g.play(ravi, 2, 2));
        g.undo();
        System.out.println("after undo: " + g.state() + ", "
            + g.current().name() + " to play");

        Scoreboard score = new Scoreboard();
        Random random = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            Game m = Game.classic(asha, ravi);
            m.subscribe(score);
            new Match(m, Map.of(asha, new RandomChooser(random),
                                ravi, new RandomChooser(random))).playOut();
        }
        System.out.println("10,000 random games: " + score);
    }
}
