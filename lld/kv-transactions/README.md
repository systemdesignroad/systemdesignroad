# Design a key-value store with transactions

SET, GET, DELETE, then nested BEGIN, COMMIT and ROLLBACK. Layers of changes instead of copies, deletes that stay deleted, and two clients reading the same balance: the lost update and optimistic concurrency.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/kv-transactions/read/](https://systemdesignroad.in/lld/kv-transactions/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/kv-transactions/](https://systemdesignroad.in/lld/kv-transactions/)

Level: Medium · Topics: Transactions, Stacks, Concurrency, Versions

## Run it

Java 21 or newer:

```
javac *.java && java Main
```

`Main.java` reproduces every output quoted in the walkthrough.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
