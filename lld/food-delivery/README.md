# Design a food delivery app

Customer, restaurant and rider all move one order. A state table with owners, orders that keep the prices they were placed at, delivery fees, atomic rider claims and a waiting line nobody falls out of.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/food-delivery/read/](https://systemdesignroad.in/lld/food-delivery/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/food-delivery/](https://systemdesignroad.in/lld/food-delivery/)

Level: Medium · Topics: State, Strategy, Observer, Concurrency

## Run it

Java 21 or newer:

```
javac *.java && java Main
```

`Main.java` reproduces every output quoted in the walkthrough.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
