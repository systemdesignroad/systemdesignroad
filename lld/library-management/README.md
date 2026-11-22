# Design a library management system

Books and their copies, loans with fines by member type, renewals, and waiting lists where a returned copy goes to the first person in line. Plus the race between a return and a new hold.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/library-management/read/](https://systemdesignroad.in/lld/library-management/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/library-management/](https://systemdesignroad.in/lld/library-management/)

Level: Medium · Topics: Entities, Strategy, Waiting lists, Concurrency

## Run it

Java 21 or newer:

```
javac *.java && java Main
```

`Main.java` reproduces every output quoted in the walkthrough.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
