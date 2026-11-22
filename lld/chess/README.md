# Design chess

Pieces that really do need inheritance, legality that no piece can judge alone, castling, en passant and promotion, undo, draws, and rules proven by perft.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/chess/read/](https://systemdesignroad.in/lld/chess/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/chess/](https://systemdesignroad.in/lld/chess/)

Level: Hard · Topics: Inheritance, Command, Testing, Concurrency

## Run it

Java 21 or newer:

```
javac Design.java && java Demo
```

`Demo` runs a short game or scenario from the walkthrough.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
