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

final class GapBuffer {
    private char[] buf = new char[16];
    private int gapStart = 0, gapEnd = 16;                  // (1) the gap is [start, end)

    int length() { return buf.length - (gapEnd - gapStart); }

    private void moveGapTo(int pos) {                        // (2) only near the edit
        if (pos < gapStart) {
            int n = gapStart - pos;
            System.arraycopy(buf, pos, buf, gapEnd - n, n);
            gapStart -= n; gapEnd -= n;
        } else if (pos > gapStart) {
            int n = pos - gapStart;
            System.arraycopy(buf, gapEnd, buf, gapStart, n);
            gapStart += n; gapEnd += n;
        }
    }

    private void ensureGap(int need) {
        if (gapEnd - gapStart >= need) return;
        int tail = buf.length - gapEnd;
        char[] bigger = new char[Math.max(buf.length * 2, length() + need + 16)];
        System.arraycopy(buf, 0, bigger, 0, gapStart);
        System.arraycopy(buf, gapEnd, bigger, bigger.length - tail, tail);
        gapEnd = bigger.length - tail;
        buf = bigger;
    }

    void insert(int pos, String s) {
        moveGapTo(pos);
        ensureGap(s.length());
        s.getChars(0, s.length(), buf, gapStart);
        gapStart += s.length();
    }

    String delete(int pos, int n) {                         // (3) returns what it removed
        moveGapTo(pos);
        String removed = new String(buf, gapEnd, n);
        gapEnd += n;
        return removed;
    }

    String text() {
        return new String(buf, 0, gapStart)
             + new String(buf, gapEnd, buf.length - gapEnd);
    }
}

interface Edit {
    void apply(GapBuffer b);
    void revert(GapBuffer b);
    int cursorAfter();                                       // (1) where the cursor goes
    int cursorBefore();
    default Optional<Edit> merge(Edit next) { return Optional.empty(); }
}

record Insert(int pos, String text) implements Edit {
    public void apply(GapBuffer b) { b.insert(pos, text); }
    public void revert(GapBuffer b) { b.delete(pos, text.length()); }
    public int cursorAfter() { return pos + text.length(); }
    public int cursorBefore() { return pos; }

    public Optional<Edit> merge(Edit next) {              // (2) typing a word is one step
        if (!(next instanceof Insert n) || n.pos != pos + text.length())
            return Optional.empty();
        boolean newWord = text.endsWith(" ") && !n.text.startsWith(" ");
        return newWord ? Optional.empty() : Optional.of(new Insert(pos, text + n.text));
    }
}

record Delete(int pos, String removed, int cursorWas) implements Edit {
    public void apply(GapBuffer b) { b.delete(pos, removed.length()); }
    public void revert(GapBuffer b) { b.insert(pos, removed); }
    public int cursorAfter() { return pos; }
    public int cursorBefore() { return cursorWas; }
}

record Group(String name, List<Edit> parts) implements Edit {  // (3) many edits, one step
    public void apply(GapBuffer b) { parts.forEach(e -> e.apply(b)); }
    public void revert(GapBuffer b) {
        for (int i = parts.size() - 1; i >= 0; i--) parts.get(i).revert(b);
    }
    public int cursorAfter() { return parts.get(parts.size() - 1).cursorAfter(); }
    public int cursorBefore() { return parts.get(0).cursorBefore(); }
}

final class History {
    private final Deque<Edit> undo = new ArrayDeque<>();
    private final Deque<Edit> redo = new ArrayDeque<>();
    private boolean sealed = true;                      // (1) next edit starts a new step

    void record(Edit e) {
        redo.clear();                                 // (2) a new edit ends the redo path
        if (!sealed && !undo.isEmpty()) {
            Optional<Edit> merged = undo.peek().merge(e);
            if (merged.isPresent()) { undo.pop(); undo.push(merged.get()); return; }
        }
        undo.push(e);
        sealed = false;
    }

    void seal() { sealed = true; }                         // cursor moved, paused, saved…
    Optional<Edit> popUndo() { sealed = true; return pollTo(undo, redo); }
    Optional<Edit> popRedo() { sealed = true; return pollTo(redo, undo); }
    int steps() { return undo.size(); }
    Edit top() { return undo.peek(); }

    private static Optional<Edit> pollTo(Deque<Edit> from, Deque<Edit> to) {
        Edit e = from.poll();
        if (e != null) to.push(e);
        return Optional.ofNullable(e);
    }
}

final class Snapshot {
    private final String text;                               // (1) immutable, opaque
    private final int cursor;
    Snapshot(String text, int cursor) { this.text = text; this.cursor = cursor; }
    String text() { return text; }
    int cursor() { return cursor; }
}

final class Editor {
    private final GapBuffer text = new GapBuffer();
    private final History history = new History();
    private int cursor;
    private int anchor = -1;                                 // (1) selection start, or -1
    private int savedSteps = 0;
    private Edit savedTop = null;

    synchronized void type(String s) {
        if (!hasSelection()) { run(new Insert(cursor, s)); return; }
        int from = Math.min(anchor, cursor), to = Math.max(anchor, cursor);
        Edit replace = new Group("replace selection", List.of(   // (2) one step, not two
            new Delete(from, text.text().substring(from, to), cursor),
            new Insert(from, s)));
        anchor = -1;
        history.seal();
        run(replace);
        history.seal();
    }

    synchronized void backspace() {
        if (hasSelection()) { deleteSelection(); return; }
        if (cursor == 0) return;
        history.seal();                                      // (3) deleting isn't typing
        run(new Delete(cursor - 1, text.text().substring(cursor - 1, cursor), cursor));
        history.seal();
    }

    synchronized void moveTo(int pos) {
        cursor = Math.max(0, Math.min(pos, text.length()));
        anchor = -1;
        history.seal();                                    // (4) moving ends a typing run
    }

    synchronized void select(int from, int to) {
        anchor = Math.max(0, Math.min(from, text.length()));
        cursor = Math.max(0, Math.min(to, text.length()));
        history.seal();
    }

    synchronized void replaceAll(String find, String with) {
        if (find.isEmpty()) return;
        List<Edit> parts = new ArrayList<>();
        String now = text.text();
        int shift = 0;
        for (int i = now.indexOf(find); i >= 0;
             i = now.indexOf(find, i + find.length())) {
            int at = i + shift;                            // (5) earlier changes moved it
            parts.add(new Delete(at, find, at));
            parts.add(new Insert(at, with));
            shift += with.length() - find.length();
        }
        if (parts.isEmpty()) return;
        history.seal();
        run(new Group("replace all", parts));
        history.seal();
    }

    synchronized void perform(Edit e) {                     // (6) any new command
        history.seal();
        anchor = -1;
        run(e);
        history.seal();
    }

    synchronized boolean undo() {
        Optional<Edit> e = history.popUndo();
        e.ifPresent(x -> { x.revert(text); cursor = x.cursorBefore(); anchor = -1; });
        return e.isPresent();
    }

    synchronized boolean redo() {
        Optional<Edit> e = history.popRedo();
        e.ifPresent(x -> { x.apply(text); cursor = x.cursorAfter(); anchor = -1; });
        return e.isPresent();
    }

    synchronized Snapshot snapshot() { return new Snapshot(text.text(), cursor); }  // (1)

    synchronized void markSaved() {
        history.seal();
        savedSteps = history.steps();
        savedTop = history.top();
    }

    synchronized boolean isDirty() {                       // (2) same step as when saved?
        return history.steps() != savedSteps || history.top() != savedTop;
    }

    synchronized String text() { return text.text(); }
    synchronized int cursor() { return cursor; }
    synchronized String selection() {
        return hasSelection() ? text.text().substring(Math.min(anchor, cursor),
                                                      Math.max(anchor, cursor)) : "";
    }

    private boolean hasSelection() { return anchor >= 0 && anchor != cursor; }

    private void deleteSelection() {
        int from = Math.min(anchor, cursor), to = Math.max(anchor, cursor);
        history.seal();
        run(new Delete(from, text.text().substring(from, to), cursor));
        history.seal();
        anchor = -1;
    }

    private void run(Edit e) {
        e.apply(text);
        cursor = e.cursorAfter();
        history.record(e);
    }
}
