# Design a hotel booking system

Book a room type for a range of nights: a calendar per room, check-out days that can be resold, holds that lapse while someone pays, weekend prices, refunds by timing, and the last room for overlapping nights.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/hotel-booking/read/](https://systemdesignroad.in/lld/hotel-booking/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/hotel-booking/](https://systemdesignroad.in/lld/hotel-booking/)

Level: Medium · Topics: Date ranges, State, Strategy, Concurrency

## Run it

Java 21 or newer:

```
javac *.java && java Main
```

`Main.java` reproduces every output quoted in the walkthrough.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
