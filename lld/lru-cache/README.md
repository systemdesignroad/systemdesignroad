# Design an LRU cache

A fixed-size cache that throws away whatever was used longest ago, in constant time, safely shared by many threads.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/lru-cache/read/](https://systemdesignroad.in/lld/lru-cache/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/lru-cache/](https://systemdesignroad.in/lld/lru-cache/)

Level: Medium · Topics: Data structures, Generics, Decorator, Concurrency

## Run it

Java 21 or newer:

```
javac Design.java
```

The design compiles on its own; the walkthrough shows it running, step by step.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
