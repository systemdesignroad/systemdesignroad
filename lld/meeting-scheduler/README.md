# Design a meeting scheduler

Calendars, rooms and invitations: instants shown in each person's zone, weekly series as iterators, rooms that can't be double-booked and people who can, and a free hour for Kolkata and London.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/meeting-scheduler/read/](https://systemdesignroad.in/lld/meeting-scheduler/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/meeting-scheduler/](https://systemdesignroad.in/lld/meeting-scheduler/)

Level: Medium · Topics: Iterator, Intervals, Time zones, Concurrency

## Run it

Java 21 or newer:

```
javac *.java && java Main
```

`Main.java` reproduces every output quoted in the walkthrough.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
