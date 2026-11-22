# Design a rate limiter (LLD)

The class-level version: four algorithms behind one interface, per-key state with no global lock, safe eviction of idle keys, and combined limits.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/api-rate-limiter/read/](https://systemdesignroad.in/lld/api-rate-limiter/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/api-rate-limiter/](https://systemdesignroad.in/lld/api-rate-limiter/)

Level: Medium · Topics: Strategy, Algorithms, Concurrency, Memory

## Run it

Java 21 or newer:

```
javac Design.java
```

The design compiles on its own; the walkthrough shows it running, step by step.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
