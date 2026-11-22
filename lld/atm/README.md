# Design an ATM

Withdraw cash from an account that lives at a bank across a network: hold, dispense, confirm or reverse, with lost replies, jams, power cuts and joint accounts.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/atm/read/](https://systemdesignroad.in/lld/atm/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/atm/](https://systemdesignroad.in/lld/atm/)

Level: Medium · Topics: State, Remote calls, Idempotency, Concurrency

## Run it

Java 21 or newer:

```
javac Design.java
```

The design compiles on its own; the walkthrough shows it running, step by step.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
