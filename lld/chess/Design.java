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

enum Color {
    WHITE, BLACK;
    Color other() { return this == WHITE ? BLACK : WHITE; }
}

record Square(int file, int rank) {                     // (1) both 0..7; a1 is (0, 0)
    private static final Square[] ALL = new Square[64];
    static {
        for (int i = 0; i < 64; i++) ALL[i] = new Square(i % 8, i / 8);
    }
    static Square at(int file, int rank) {
        return file < 0 || file > 7 || rank < 0 || rank > 7 ? null : ALL[rank * 8 + file];
    }
    static Square of(String name) {
        return at(name.charAt(0) - 'a', name.charAt(1) - '1');
    }
    static Square index(int i) { return ALL[i]; }
    int index() { return rank * 8 + file; }
    Square plus(int df, int dr) { return at(file + df, rank + dr); }
    @Override public String toString() { return "" + (char) ('a' + file) + (rank + 1); }
}

enum MoveType { NORMAL, DOUBLE_PUSH, EN_PASSANT, CASTLE, PROMOTION }

record Move(Square from, Square to, Piece piece, Piece captured, MoveType type,
            Piece promotion) {
    String uci() {
        String promo = promotion == null ? ""
            : String.valueOf(Character.toLowerCase(promotion.letter()));
        return "" + from + to + promo;
    }
    @Override public String toString() { return uci(); }
}

record Undo(int castling, Square enPassant, int halfmove) { }

abstract sealed class Piece permits Slider, Stepper, Pawn {
    final Color color;
    Piece(Color color) { this.color = color; }

    abstract char letter();                                       // (1)
    abstract void moves(Board b, Square from, List<Move> out);    // (2)
    abstract boolean attacks(Board b, Square from, Square target);

    protected char mark(char c) {
        return color == Color.WHITE ? Character.toUpperCase(c) : c;
    }

    protected void addIfFree(Board b, Square from, Square to, List<Move> out) {
        Piece there = b.at(to);
        if (there == null)
            out.add(new Move(from, to, this, null, MoveType.NORMAL, null));
        else if (there.color != color)
            out.add(new Move(from, to, this, there, MoveType.NORMAL, null));
    }
}

abstract sealed class Slider extends Piece permits Rook, Bishop, Queen {
    Slider(Color c) { super(c); }
    abstract int[][] directions();

    void moves(Board b, Square from, List<Move> out) {
        for (int[] d : directions()) {
            for (Square s = from.plus(d[0], d[1]); s != null; s = s.plus(d[0], d[1])) {
                addIfFree(b, from, s, out);
                if (b.at(s) != null) break;                      // (3)
            }
        }
    }

    boolean attacks(Board b, Square from, Square target) {
        for (int[] d : directions()) {
            for (Square s = from.plus(d[0], d[1]); s != null; s = s.plus(d[0], d[1])) {
                if (s.equals(target)) return true;
                if (b.at(s) != null) break;
            }
        }
        return false;
    }
}

abstract sealed class Stepper extends Piece permits Knight, King {
    Stepper(Color c) { super(c); }
    abstract int[][] steps();

    void moves(Board b, Square from, List<Move> out) {
        for (int[] d : steps()) {
            Square s = from.plus(d[0], d[1]);
            if (s != null) addIfFree(b, from, s, out);
        }
    }

    boolean attacks(Board b, Square from, Square target) {
        for (int[] d : steps())
            if (target.equals(from.plus(d[0], d[1]))) return true;
        return false;
    }
}

final class Rook extends Slider {
    private static final int[][] D = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
    Rook(Color c) { super(c); }
    int[][] directions() { return D; }
    char letter() { return mark('r'); }
}

final class Bishop extends Slider {
    private static final int[][] D = { {1, 1}, {1, -1}, {-1, 1}, {-1, -1} };
    Bishop(Color c) { super(c); }
    int[][] directions() { return D; }
    char letter() { return mark('b'); }
}

final class Queen extends Slider {
    private static final int[][] D = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1} };
    Queen(Color c) { super(c); }
    int[][] directions() { return D; }
    char letter() { return mark('q'); }
}

final class Knight extends Stepper {
    private static final int[][] D = {
        {1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2} };
    Knight(Color c) { super(c); }
    int[][] steps() { return D; }
    char letter() { return mark('n'); }
}

final class King extends Stepper {
    private static final int[][] D = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1} };
    King(Color c) { super(c); }
    int[][] steps() { return D; }
    char letter() { return mark('k'); }

    @Override void moves(Board b, Square from, List<Move> out) {
        super.moves(b, from, out);
        int rank = color == Color.WHITE ? 0 : 7;
        if (!from.equals(Square.at(4, rank)) || b.isAttacked(from, color.other())) return;
        if (b.canCastle(color, true) && empty(b, rank, 5, 6)
                && safe(b, rank, 5, 6))                           // (4)
            out.add(new Move(from, Square.at(6, rank), this, null,
                             MoveType.CASTLE, null));
        if (b.canCastle(color, false) && empty(b, rank, 1, 3)
                && safe(b, rank, 2, 3))
            out.add(new Move(from, Square.at(2, rank), this, null,
                             MoveType.CASTLE, null));
    }

    private boolean empty(Board b, int rank, int lo, int hi) {
        for (int f = lo; f <= hi; f++) if (b.at(Square.at(f, rank)) != null) return false;
        return true;
    }

    private boolean safe(Board b, int rank, int lo, int hi) {
        for (int f = lo; f <= hi; f++)
            if (b.isAttacked(Square.at(f, rank), color.other())) return false;
        return true;
    }
}

final class Pawn extends Piece {
    Pawn(Color c) { super(c); }
    char letter() { return mark('p'); }
    private int dir() { return color == Color.WHITE ? 1 : -1; }

    void moves(Board b, Square from, List<Move> out) {
        int last = color == Color.WHITE ? 7 : 0, start = color == Color.WHITE ? 1 : 6;
        Square one = from.plus(0, dir());
        if (one != null && b.at(one) == null) {
            add(from, one, null, out, one.rank() == last);
            Square two = from.plus(0, 2 * dir());
            if (from.rank() == start && b.at(two) == null)
                out.add(new Move(from, two, this, null, MoveType.DOUBLE_PUSH, null));
        }
        for (int df : new int[] { -1, 1 }) {
            Square s = from.plus(df, dir());
            if (s == null) continue;
            Piece there = b.at(s);
            if (there != null && there.color != color)
                add(from, s, there, out, s.rank() == last);
            else if (s.equals(b.enPassant()))                     // (5)
                out.add(new Move(from, s, this, b.at(Square.at(s.file(), from.rank())),
                                 MoveType.EN_PASSANT, null));
        }
    }

    private void add(Square from, Square to, Piece captured, List<Move> out,
                     boolean promote) {
        if (!promote) {
            out.add(new Move(from, to, this, captured, MoveType.NORMAL, null));
            return;
        }
        for (Piece p : List.of(new Queen(color), new Rook(color), new Bishop(color),
                               new Knight(color)))
            out.add(new Move(from, to, this, captured, MoveType.PROMOTION, p));
    }

    boolean attacks(Board b, Square from, Square target) {
        return target.rank() == from.rank() + dir()
            && Math.abs(target.file() - from.file()) == 1;
    }
}

final class Board {
    private static final int WK = 1, WQ = 2, BK = 4, BQ = 8;
    private final Piece[] squares = new Piece[64];
    private Color turn = Color.WHITE;
    private int castling;                                         // (1)
    private Square enPassant;
    private int halfmove;

    Piece at(Square s) { return squares[s.index()]; }
    Color turn() { return turn; }
    Square enPassant() { return enPassant; }
    int halfmove() { return halfmove; }

    boolean canCastle(Color c, boolean kingSide) {
        int bit = c == Color.WHITE ? (kingSide ? WK : WQ) : (kingSide ? BK : BQ);
        return (castling & bit) != 0;
    }

    boolean isAttacked(Square target, Color by) {
        for (int i = 0; i < 64; i++) {
            Piece p = squares[i];
            if (p != null && p.color == by && p.attacks(this, Square.index(i), target))
                return true;
        }
        return false;
    }

    Square kingOf(Color c) {
        for (int i = 0; i < 64; i++)
            if (squares[i] instanceof King k && k.color == c) return Square.index(i);
        throw new IllegalStateException("no " + c + " king");
    }

    boolean inCheck(Color c) { return isAttacked(kingOf(c), c.other()); }

    List<Move> legalMoves() {
        List<Move> pseudo = new ArrayList<>(), legal = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            Piece p = squares[i];
            if (p != null && p.color == turn) p.moves(this, Square.index(i), pseudo);
        }
        Color me = turn;
        for (Move m : pseudo) {                                   // (2)
            Undo u = make(m);
            if (!inCheck(me)) legal.add(m);
            unmake(m, u);
        }
        return legal;
    }

    Undo make(Move m) {
        Undo u = new Undo(castling, enPassant, halfmove);
        squares[m.from().index()] = null;
        if (m.type() == MoveType.EN_PASSANT)
            squares[Square.at(m.to().file(), m.from().rank()).index()] = null;
        squares[m.to().index()] = m.promotion() != null ? m.promotion() : m.piece();
        if (m.type() == MoveType.CASTLE) {                        // (3)
            int r = m.from().rank();
            boolean kingSide = m.to().file() == 6;
            Square rf = Square.at(kingSide ? 7 : 0, r);
            Square rt = Square.at(kingSide ? 5 : 3, r);
            squares[rt.index()] = squares[rf.index()];
            squares[rf.index()] = null;
        }
        enPassant = m.type() == MoveType.DOUBLE_PUSH
            ? Square.at(m.from().file(), (m.from().rank() + m.to().rank()) / 2) : null;
        castling &= ~lostRights(m.from()) & ~lostRights(m.to());  // (4)
        halfmove = m.piece() instanceof Pawn || m.captured() != null ? 0 : halfmove + 1;
        turn = turn.other();
        return u;
    }

    void unmake(Move m, Undo u) {
        turn = turn.other();
        squares[m.from().index()] = m.piece();
        squares[m.to().index()] = null;
        if (m.type() == MoveType.EN_PASSANT)
            squares[Square.at(m.to().file(), m.from().rank()).index()] = m.captured();
        else squares[m.to().index()] = m.captured();
        if (m.type() == MoveType.CASTLE) {
            int r = m.from().rank();
            boolean kingSide = m.to().file() == 6;
            Square rf = Square.at(kingSide ? 7 : 0, r);
            Square rt = Square.at(kingSide ? 5 : 3, r);
            squares[rf.index()] = squares[rt.index()];
            squares[rt.index()] = null;
        }
        castling = u.castling();
        enPassant = u.enPassant();
        halfmove = u.halfmove();
    }

    private static int lostRights(Square s) {
        return switch (s.toString()) {
            case "e1" -> WK | WQ;
            case "h1" -> WK;
            case "a1" -> WQ;
            case "e8" -> BK | BQ;
            case "h8" -> BK;
            case "a8" -> BQ;
            default -> 0;
        };
    }

    String key() {                                                // (5)
        StringBuilder sb = new StringBuilder(72);
        for (Piece p : squares) sb.append(p == null ? '.' : p.letter());
        return sb.append(turn.ordinal()).append(castling).append(enPassant).toString();
    }

    static Board fromFen(String fen) {
        String[] f = fen.split(" ");
        Board b = new Board();
        String[] rows = f[0].split("/");
        for (int r = 0; r < 8; r++) {
            int file = 0;
            for (char ch : rows[7 - r].toCharArray()) {
                if (Character.isDigit(ch)) { file += ch - '0'; continue; }
                Color c = Character.isUpperCase(ch) ? Color.WHITE : Color.BLACK;
                Square sq = Square.at(file++, r);
                b.squares[sq.index()] = switch (Character.toLowerCase(ch)) {
                    case 'k' -> new King(c);
                    case 'q' -> new Queen(c);
                    case 'r' -> new Rook(c);
                    case 'b' -> new Bishop(c);
                    case 'n' -> new Knight(c);
                    default -> new Pawn(c);
                };
            }
        }
        b.turn = f[1].equals("w") ? Color.WHITE : Color.BLACK;
        for (char ch : f[2].toCharArray())
            b.castling |= switch (ch) {
                case 'K' -> WK;
                case 'Q' -> WQ;
                case 'k' -> BK;
                case 'q' -> BQ;
                default -> 0;
            };
        b.enPassant = f[3].equals("-") ? null : Square.of(f[3]);
        b.halfmove = f.length > 4 ? Integer.parseInt(f[4]) : 0;
        return b;
    }

    static Board start() {
        return fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 7; r >= 0; r--) {
            for (int f = 0; f < 8; f++) {
                Piece p = at(Square.at(f, r));
                sb.append(p == null ? '.' : p.letter());
            }
            if (r > 0) sb.append('\n');
        }
        return sb.toString();
    }
}

enum Status { ACTIVE, CHECK, CHECKMATE, STALEMATE, DRAW_FIFTY_MOVES, DRAW_REPETITION }

enum MoveResult { OK, GAME_OVER, NOT_YOUR_TURN, ILLEGAL }

final class Game {
    private record Played(Move move, Undo undo) { }

    private final Board board;
    private final Deque<Played> history = new ArrayDeque<>();
    private final Map<String, Integer> seen = new HashMap<>();
    private Status status = Status.ACTIVE;

    Game(Board board) {
        this.board = board;
        seen.merge(board.key(), 1, Integer::sum);
        status = evaluate();
    }

    synchronized MoveResult play(Color who, String uci) {
        if (isOver()) return MoveResult.GAME_OVER;
        if (who != board.turn()) return MoveResult.NOT_YOUR_TURN;
        Move chosen = null;
        for (Move m : board.legalMoves())                         // (1)
            if (m.uci().equals(uci)) chosen = m;
        if (chosen == null) return MoveResult.ILLEGAL;
        history.push(new Played(chosen, board.make(chosen)));
        int times = seen.merge(board.key(), 1, Integer::sum);
        status = times >= 3 ? Status.DRAW_REPETITION : evaluate();
        return MoveResult.OK;
    }

    synchronized boolean undo() {
        if (history.isEmpty()) return false;
        seen.merge(board.key(), -1, Integer::sum);
        Played p = history.pop();
        board.unmake(p.move(), p.undo());
        status = evaluate();
        return true;
    }

    private Status evaluate() {
        boolean check = board.inCheck(board.turn());
        if (board.legalMoves().isEmpty())
            return check ? Status.CHECKMATE : Status.STALEMATE;
        if (board.halfmove() >= 100) return Status.DRAW_FIFTY_MOVES;   // (2)
        return check ? Status.CHECK : Status.ACTIVE;
    }

    synchronized boolean isOver() {
        return status != Status.ACTIVE && status != Status.CHECK;
    }
    synchronized Status status() { return status; }
    synchronized Color turn() { return board.turn(); }
    synchronized String board() { return board.toString(); }
    synchronized List<String> legal() {
        return board.legalMoves().stream().map(Move::uci).sorted().toList();
    }
}

class Demo {
    public static void main(String[] args) {
        Game g = new Game(Board.start());
        System.out.println("e2e5: " + g.play(Color.WHITE, "e2e5"));
        System.out.println("black first: " + g.play(Color.BLACK, "e7e5"));
        g.play(Color.WHITE, "f2f3");
        g.play(Color.BLACK, "e7e5");
        g.play(Color.WHITE, "g2g4");
        System.out.println("d8h4: " + g.play(Color.BLACK, "d8h4") + " -> " + g.status());
        System.out.println(g.board());
        System.out.println("after mate: " + g.play(Color.WHITE, "a2a3"));
        g.undo();
        System.out.println("undo: " + g.status() + ", "
            + g.legal().size() + " legal moves");

        Game st = new Game(Board.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1"));
        System.out.println("stalemate position: " + st.status());
        Game ep = new Game(Board.fromFen("4k3/8/8/8/3p4/8/4P3/4K3 w - - 0 1"));
        ep.play(Color.WHITE, "e2e4");
        System.out.println("en passant d4e3 allowed: " + ep.legal().contains("d4e3"));
        Game rep = new Game(Board.start());
        String[] shuffle = { "g1f3", "g8f6", "f3g1", "f6g8",
                             "g1f3", "g8f6", "f3g1", "f6g8" };
        for (int i = 0; i < shuffle.length; i++)
            rep.play(i % 2 == 0 ? Color.WHITE : Color.BLACK, shuffle[i]);
        System.out.println("knights out and back twice: " + rep.status());
    }
}

final class Perft {
    static long count(Board b, int depth) {
        if (depth == 0) return 1;
        long n = 0;
        for (Move m : b.legalMoves()) {
            Undo u = b.make(m);
            n += count(b, depth - 1);
            b.unmake(m, u);
        }
        return n;
    }
}
