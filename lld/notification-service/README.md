# Design a notification system

Email, SMS and push per person and per kind: templates that refuse 'Hi {name}', one shared retry loop, rules that send, drop or hold, quiet hours in the user's zone, a daily cap, and duplicate events.

**Read the full walkthrough:** [https://systemdesignroad.in/lld/notification-service/read/](https://systemdesignroad.in/lld/notification-service/read/)
**Practise it stage by stage:** [https://systemdesignroad.in/lld/notification-service/](https://systemdesignroad.in/lld/notification-service/)

Level: Medium · Topics: Template Method, Strategy, Rules, Concurrency

## Run it

Java 21 or newer:

```
javac *.java && java Main
```

`Main.java` reproduces every output quoted in the walkthrough.

The walkthrough explains how to get here: the requirements, the entities, three versions of the class diagram, the patterns, the flows, the concurrency bug this design avoids (with a test), and the follow-up questions.
