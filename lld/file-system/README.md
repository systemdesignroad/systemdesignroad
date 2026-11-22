# Design an in-memory file system

Files and folders as one kind of node, paths with dots and slashes, mkdir -p, sizes at any depth, moves that never put a folder inside itself, search by any rule, and two moves that cross.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/file-system/read/](https://systemdesignroad.in/lld/file-system/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/file-system/](https://systemdesignroad.in/lld/file-system/)

Level: Medium · Topics: Composite, Trees, Strategy, Concurrency

## Run it

Java 21 or newer:

```
javac *.java && java Main
```

`Main.java` reproduces every output quoted in the walkthrough.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
