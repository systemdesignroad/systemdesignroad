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

record Path(List<String> parts) {
    static final Path ROOT = new Path(List.of());

    static Path of(String text) {
        if (!text.startsWith("/")) throw new FsException("paths start with /: " + text);
        Deque<String> out = new ArrayDeque<>();
        for (String p : text.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;   // (1) "//" and "." add nothing
            if (p.equals("..")) { if (!out.isEmpty()) out.removeLast(); continue; }
            out.addLast(p);
        }
        return new Path(List.copyOf(out));
    }

    boolean isRoot() { return parts.isEmpty(); }
    String name() { return isRoot() ? "/" : parts.get(parts.size() - 1); }
    Path parent() { return new Path(parts.subList(0, Math.max(0, parts.size() - 1))); }
    Path child(String name) {
        List<String> p = new ArrayList<>(parts);
        p.add(name);
        return new Path(List.copyOf(p));
    }
    boolean isInside(Path other) {                   // (2) the same path, or below it
        return parts.size() >= other.parts.size()
            && parts.subList(0, other.parts.size()).equals(other.parts);
    }
    @Override public String toString() { return "/" + String.join("/", parts); }
}

final class FsException extends RuntimeException {
    FsException(String message) { super(message); }
}

sealed abstract class Node permits FileNode, Directory {
    private String name;
    private Directory parent;                                // (1) null only for the root

    Node(String name, Directory parent) { this.name = name; this.parent = parent; }

    String name() { return name; }
    Directory parent() { return parent; }
    void reattach(String newName, Directory newParent) {
        name = newName;
        parent = newParent;
    }

    Path path() {
        Deque<String> parts = new ArrayDeque<>();
        for (Node n = this; n.parent != null; n = n.parent) parts.addFirst(n.name);
        return new Path(List.copyOf(parts));
    }

    abstract long size();                            // (2) same question, both kinds
}

final class FileNode extends Node {
    private final StringBuilder content = new StringBuilder();

    FileNode(String name, Directory parent) { super(name, parent); }

    synchronized void write(String text) { content.setLength(0); content.append(text); }
    synchronized void append(String text) { content.append(text); }
    synchronized String read() { return content.toString(); }
    synchronized long size() { return content.length(); }
}

final class Directory extends Node {
    private final NavigableMap<String, Node> children = new TreeMap<>();   // (3) sorted

    Directory(String name, Directory parent) { super(name, parent); }

    Node child(String name) { return children.get(name); }
    Collection<Node> children() { return children.values(); }
    void add(Node n) { children.put(n.name(), n); }
    void remove(String name) { children.remove(name); }
    boolean isEmpty() { return children.isEmpty(); }

    long size() {
        long total = 0;
        for (Node n : children.values()) total += n.size();  // (4) files, folders alike
        return total;
    }
}

final class FileSystem {
    private final Directory root = new Directory("", null);
    private final ReentrantReadWriteLock tree = new ReentrantReadWriteLock();   // (1)
    private final Lock read = tree.readLock(), write = tree.writeLock();

    private Node find(Path p) {                              // caller holds a tree lock
        Node n = root;
        for (String part : p.parts()) {
            if (!(n instanceof Directory d))
                throw new FsException(n.path() + " is a file, not a directory");
            n = d.child(part);
            if (n == null) throw new FsException("no such file or directory: " + p);
        }
        return n;
    }

    private Directory dir(Path p) {
        if (find(p) instanceof Directory d) return d;
        throw new FsException("not a directory: " + p);
    }

    private FileNode file(Path p) {
        if (find(p) instanceof FileNode f) return f;
        throw new FsException("is a directory: " + p);
    }

    void mkdir(String path, boolean parents) {
        Path p = Path.of(path);
        write.lock();
        try {
            Directory d = root;
            for (int i = 0; i < p.parts().size(); i++) {
                String part = p.parts().get(i);
                boolean last = i == p.parts().size() - 1;
                Node next = d.child(part);
                if (next == null) {
                    if (!last && !parents)                   // (2) like mkdir without -p
                        throw new FsException("no such directory: "
                                              + d.path().child(part));
                    Directory made = new Directory(part, d);
                    d.add(made);
                    next = made;
                } else if (last && !parents) {
                    throw new FsException("already exists: " + p);
                }
                if (!(next instanceof Directory nd))
                    throw new FsException(next.path() + " is a file");
                d = nd;
            }
        } finally {
            write.unlock();
        }
    }

    void createFile(String path, String text) {
        Path p = Path.of(path);
        write.lock();
        try {
            Directory d = dir(p.parent());
            if (d.child(p.name()) != null) throw new FsException("already exists: " + p);
            FileNode f = new FileNode(p.name(), d);
            f.write(text);
            d.add(f);
        } finally {
            write.unlock();
        }
    }

    void write(String path, String text) { withFile(path, f -> f.write(text)); }
    void append(String path, String text) { withFile(path, f -> f.append(text)); }

    String read(String path) {
        read.lock();
        try { return file(Path.of(path)).read(); } finally { read.unlock(); }
    }

    private void withFile(String path, Consumer<FileNode> change) {
        read.lock();                                 // (1) the tree isn't changing
        try { change.accept(file(Path.of(path))); } finally { read.unlock(); }
    }

    List<String> ls(String path) {
        read.lock();
        try {
            Node n = find(Path.of(path));
            if (n instanceof FileNode) return List.of(n.name());
            List<String> out = new ArrayList<>();
            for (Node c : ((Directory) n).children())
                out.add(c instanceof Directory ? c.name() + "/" : c.name());   // (2)
            return out;
        } finally {
            read.unlock();
        }
    }

    long size(String path) {
        read.lock();
        try { return find(Path.of(path)).size(); } finally { read.unlock(); }
    }

    void delete(String path, boolean recursive) {
        Path p = Path.of(path);
        if (p.isRoot()) throw new FsException("can't delete /");
        write.lock();
        try {
            Node n = find(p);
            if (n instanceof Directory d && !d.isEmpty() && !recursive)   // (1) rm -r
                throw new FsException("directory not empty: " + p);
            n.parent().remove(n.name());
        } finally {
            write.unlock();
        }
    }

    void move(String from, String to) {
        Path src = Path.of(from), dst = Path.of(to);
        if (src.isRoot()) throw new FsException("can't move /");
        write.lock();                                // (2) check and move together
        try {
            Node n = find(src);
            Node there = dst.isRoot() ? root : findOrNull(dst);
            Path target = there instanceof Directory ? dst.child(src.name()) : dst;
            if (target.isInside(src))                        // (3) not into itself
                throw new FsException("can't move " + src + " inside itself");
            Directory newParent = dir(target.parent());
            if (newParent.child(target.name()) != null)
                throw new FsException("already exists: " + target);
            n.parent().remove(n.name());
            n.reattach(target.name(), newParent);
            newParent.add(n);
        } finally {
            write.unlock();
        }
    }

    private Node findOrNull(Path p) {
        try { return find(p); } catch (FsException e) { return null; }
    }

    List<Path> find(String under, Predicate<Node> rule) {
        read.lock();
        try {
            List<Path> out = new ArrayList<>();
            walk(find(Path.of(under)), n -> { if (rule.test(n)) out.add(n.path()); });
            return out;
        } finally {
            read.unlock();
        }
    }

    void walk(String under, Consumer<Node> visit) {
        read.lock();
        try { walk(find(Path.of(under)), visit); } finally { read.unlock(); }
    }

    private void walk(Node n, Consumer<Node> visit) {        // (1) depth first, sorted
        visit.accept(n);
        if (n instanceof Directory d) for (Node c : d.children()) walk(c, visit);
    }

    Directory rootForChecks() { return root; }
}

final class Rules {
    static Predicate<Node> isFile() { return n -> n instanceof FileNode; }
    static Predicate<Node> extension(String ext) {
        return isFile().and(n -> n.name().endsWith("." + ext));
    }
    static Predicate<Node> largerThan(long chars) { return n -> n.size() > chars; }
    static Predicate<Node> nameContains(String s) { return n -> n.name().contains(s); }
}
