# Design a pub-sub message queue

Topics, publishers and subscribers, in memory: one order for everyone, slow and failing subscribers that can't hurt the rest, retries, dead letters, replay and consumer groups.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/pub-sub/read/](https://systemdesignroad.in/lld/pub-sub/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/pub-sub/](https://systemdesignroad.in/lld/pub-sub/)

Level: Medium · Topics: Observer, Producer-consumer, Concurrency, Retries

## Run it

Java 21 or newer:

```
javac Design.java
```

The design compiles on its own; the walkthrough shows it running, step by step.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
