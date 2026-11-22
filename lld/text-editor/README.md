# Design a text editor with undo and redo

Typing, selections, find and replace, and undo that works the way people expect: a gap buffer, reversible commands merged into words, groups for compound edits, and an autosave that never saves half an edit.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/text-editor/read/](https://systemdesignroad.in/lld/text-editor/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/text-editor/](https://systemdesignroad.in/lld/text-editor/)

Level: Medium · Topics: Command, Memento, Data structures, Concurrency

## Run it

Java 21 or newer:

```
javac *.java && java Main
```

`Main.java` reproduces every output quoted in the walkthrough.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
