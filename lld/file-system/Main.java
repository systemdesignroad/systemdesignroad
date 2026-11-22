import java.util.*;

public class Main {
    static void tryIt(String label, Runnable r) {
        try { r.run(); System.out.println("ok: " + label); }
        catch (FsException e) { System.out.println("no: " + e.getMessage()); }
    }

    public static void main(String[] args) {
        FileSystem fs = new FileSystem();

        System.out.println("== paths");
        for (String p : List.of("/home/asha/./notes", "//home//asha/",
                                "/home/asha/../ben", "/.."))
            System.out.println(p + " -> " + Path.of(p));

        System.out.println("== making things");
        tryIt("mkdir /home/asha/photos", () -> fs.mkdir("/home/asha/photos", false));
        tryIt("mkdir -p /home/asha/photos", () -> fs.mkdir("/home/asha/photos", true));
        tryIt("mkdir /home/ben", () -> fs.mkdir("/home/ben", false));
        fs.createFile("/home/asha/notes.txt", "buy milk\n");
        fs.createFile("/home/asha/photos/goa.jpg", "x".repeat(2400));
        fs.createFile("/home/asha/photos/beach.jpg", "x".repeat(1800));
        fs.createFile("/home/ben/todo.txt", "call Asha\n");
        tryIt("create /home/asha/notes.txt again",
              () -> fs.createFile("/home/asha/notes.txt", ""));
        tryIt("create /home/asha/notes.txt/x",
              () -> fs.createFile("/home/asha/notes.txt/x", ""));
        fs.append("/home/asha/notes.txt", "pay rent\n");
        System.out.print(fs.read("/home/asha/notes.txt"));
        System.out.println("ls /home/asha: " + fs.ls("/home/asha"));
        System.out.println("size /home: " + fs.size("/home") + ", /home/asha/photos: "
                           + fs.size("/home/asha/photos"));

        System.out.println("== finding");
        System.out.println("*.txt: " + fs.find("/", Rules.extension("txt")));
        System.out.println("*.jpg over 2,000: "
                + fs.find("/home", Rules.extension("jpg").and(Rules.largerThan(2000))));

        System.out.println("== moving");
        tryIt("mv /home/asha/photos /home/ben",
              () -> fs.move("/home/asha/photos", "/home/ben"));
        System.out.println("ls /home/ben: " + fs.ls("/home/ben"));
        tryIt("mv /home/ben/todo.txt /home/ben/tasks.txt",
              () -> fs.move("/home/ben/todo.txt", "/home/ben/tasks.txt"));
        tryIt("mv /home /home/ben/photos", () -> fs.move("/home", "/home/ben/photos"));
        tryIt("mv /home/ben /home/ben", () -> fs.move("/home/ben", "/home/ben"));
        tryIt("mv /home/asha/notes.txt /home/ben/tasks.txt",
              () -> fs.move("/home/asha/notes.txt", "/home/ben/tasks.txt"));

        System.out.println("== deleting");
        tryIt("rm /home/ben/photos", () -> fs.delete("/home/ben/photos", false));
        tryIt("rm -r /home/ben/photos", () -> fs.delete("/home/ben/photos", true));
        tryIt("rm /", () -> fs.delete("/", true));
        System.out.println("size /home: " + fs.size("/home"));
        List<String> tree = new ArrayList<>();
        fs.walk("/", n -> tree.add(n.path().isRoot() ? "/"
                : n.path() + (n instanceof Directory ? "/" : "")));
        System.out.println(tree);
    }
}
