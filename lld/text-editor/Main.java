public class Main {
    static void show(String label, Editor e) {
        String t = e.text();
        System.out.println(label + ": \"" + t.substring(0, e.cursor()) + "|"
                           + t.substring(e.cursor()) + "\"" + (e.isDirty() ? " *" : ""));
    }

    public static void main(String[] args) {
        Editor e = new Editor();
        System.out.println("== typing, one character at a time");
        for (char c : "Meet at noon".toCharArray()) e.type(String.valueOf(c));
        show("typed", e);
        e.undo();
        show("undo", e);
        e.undo();
        show("undo", e);
        e.redo();
        show("redo", e);

        System.out.println("== editing in the middle");
        e.redo();
        e.moveTo(5);
        for (char c : "me ".toCharArray()) e.type(String.valueOf(c));
        show("typed at 5", e);
        e.backspace();
        e.backspace();
        show("2 backspaces", e);
        e.undo();
        show("undo", e);
        e.undo();
        e.undo();
        show("undo x2", e);

        System.out.println("== selection, and redo cleared");
        e.select(8, 12);
        System.out.println("selected: \"" + e.selection() + "\"");
        e.type("3pm");
        show("replaced", e);
        e.undo();
        show("undo", e);
        e.type("!");
        show("typed", e);
        System.out.println("redo now? " + e.redo());

        System.out.println("== replace all, one step");
        Editor doc = new Editor();
        doc.type("cat sat on the cat mat");
        doc.markSaved();
        show("saved", doc);
        doc.replaceAll("cat", "dog");
        show("replaced", doc);
        doc.undo();
        show("undo", doc);

        System.out.println("== snapshots");
        Snapshot s = doc.snapshot();
        doc.moveTo(doc.text().length());
        doc.type(" today");
        System.out.println("snapshot still: \"" + s.text() + "\"; editor: \""
                           + doc.text() + "\"");
    }
}
