# Design a logging framework

Levels, named loggers in a tree, appenders and formatters, a request ID on every line, and an async writer that never makes a customer wait for the disk.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/logging-framework/read/](https://systemdesignroad.in/lld/logging-framework/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/logging-framework/](https://systemdesignroad.in/lld/logging-framework/)

Level: Easy · Topics: Strategy, Decorator, Concurrency, Performance

## Run it

Java 21 or newer:

```
javac Design.java
```

The design compiles on its own; the walkthrough shows it running, step by step.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
