# Design a movie ticket booking system

BookMyShow at the class level: seats per show, holds that expire, paying without holding a lock, double clicks, late payments and deadlocks.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/movie-ticket-booking/read/](https://systemdesignroad.in/lld/movie-ticket-booking/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/movie-ticket-booking/](https://systemdesignroad.in/lld/movie-ticket-booking/)

Level: Medium · Topics: Entities, Holds, Payments, Deadlock

## Run it

Java 21 or newer:

```
javac Design.java
```

The design compiles on its own; the walkthrough shows it running, step by step.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
