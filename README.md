# System Design Road

**Learn system design the way real systems grow: one server first, then whatever breaks next.**

🌐 **[systemdesignroad.in](https://systemdesignroad.in/)** · free, no sign-up, no ads

Most system design material lists components without saying why they're there, or shows a finished architecture and leaves you guessing how anyone got there. System Design Road follows one made-up app, Chirp, from a single rented server to fifty million users. Every lesson starts with the thing that broke.

## What's on the site

| | |
|---|---|
| 📚 [**The course**](https://systemdesignroad.in/what-happens-when-you-open-an-app/) | 37 lessons in eight parts, from DNS and databases to sharding, consensus, multi-region and payments, with real numbers and interactive diagrams |
| 🎮 [**The simulation**](https://systemdesignroad.in/simulation/) | Make Chirp's design decisions yourself, then watch real traffic test them |
| 🎯 [**Interview practice**](https://systemdesignroad.in/practice/) | 23 system design questions, practised stage by stage like a real 45-minute round |
| 🧩 [**Low-level design**](https://systemdesignroad.in/lld/) | OOP, SOLID, design patterns and concurrency, then 22 LLD problems with Java that compiles ([code in this repo](./lld)) |
| 🔥 [**Engineering blogs**](https://systemdesignroad.in/blogs/) | Famous outages retold in plain words, and what the engineers learned |

## Interview questions with answers

- [System design interview questions](https://systemdesignroad.in/system-design-interview-questions/): the questions asked most, plus 55 concept questions with short answers
- [LLD interview questions](https://systemdesignroad.in/lld-interview-questions/): LLD problems by difficulty, plus 45 questions on OOP, SOLID, patterns and concurrency

## System design interview walkthroughs

- [Design a URL shortener](https://systemdesignroad.in/practice/url-shortener/read/)
- [Design a rate limiter](https://systemdesignroad.in/practice/rate-limiter/read/)
- [Design a notification system](https://systemdesignroad.in/practice/notifications/read/)
- [Design a news feed](https://systemdesignroad.in/practice/news-feed/read/)
- [Design search autocomplete](https://systemdesignroad.in/practice/typeahead/read/)
- [Design a web crawler](https://systemdesignroad.in/practice/web-crawler/read/)
- [Design a ticket booking system](https://systemdesignroad.in/practice/ticket-booking/read/)
- [Design Instagram (photo sharing)](https://systemdesignroad.in/practice/instagram/read/)
- [Design a game leaderboard and top-K](https://systemdesignroad.in/practice/leaderboard/read/)
- [Design a video streaming platform like YouTube](https://systemdesignroad.in/practice/video-streaming/read/)
- [Design a chat app like WhatsApp](https://systemdesignroad.in/practice/chat/read/)
- [Design a distributed key-value store](https://systemdesignroad.in/practice/kv-store/read/)
- [Design a file sync service like Dropbox](https://systemdesignroad.in/practice/file-sync/read/)
- [Design a ride-hailing app like Uber](https://systemdesignroad.in/practice/ride-hailing/read/)
- [Design a payment system](https://systemdesignroad.in/practice/payment-system/read/)
- [Design a distributed job scheduler](https://systemdesignroad.in/practice/job-scheduler/read/)
- [Design an ad click aggregator](https://systemdesignroad.in/practice/ad-click-aggregator/read/)
- [Design a distributed cache like Redis](https://systemdesignroad.in/practice/distributed-cache/read/)
- [Design ChatGPT (LLM serving)](https://systemdesignroad.in/practice/chatgpt/read/)
- [Design Google Maps](https://systemdesignroad.in/practice/google-maps/read/)
- [Design a message queue like Kafka](https://systemdesignroad.in/practice/message-queue/read/)
- [Design a metrics and alerting system](https://systemdesignroad.in/practice/metrics-alerting/read/)
- [Design Google Docs (collaborative editing)](https://systemdesignroad.in/practice/google-docs/read/)

## Low-level design problems, with Java code in this repo

Every problem's design is in [`lld/`](./lld), and it compiles with Java 21.

| Walkthrough | Level | Code |
|---|---|---|
| [Design a parking lot](https://systemdesignroad.in/lld/parking-lot/read/) | Easy | [Java](./lld/parking-lot) |
| [Design tic-tac-toe](https://systemdesignroad.in/lld/tic-tac-toe/read/) | Easy | [Java](./lld/tic-tac-toe) |
| [Design snakes and ladders](https://systemdesignroad.in/lld/snakes-and-ladders/read/) | Easy | [Java](./lld/snakes-and-ladders) |
| [Design a logging framework](https://systemdesignroad.in/lld/logging-framework/read/) | Easy | [Java](./lld/logging-framework) |
| [Design an LRU cache](https://systemdesignroad.in/lld/lru-cache/read/) | Medium | [Java](./lld/lru-cache) |
| [Design a vending machine](https://systemdesignroad.in/lld/vending-machine/read/) | Medium | [Java](./lld/vending-machine) |
| [Design Splitwise](https://systemdesignroad.in/lld/splitwise/read/) | Medium | [Java](./lld/splitwise) |
| [Design a movie ticket booking system](https://systemdesignroad.in/lld/movie-ticket-booking/read/) | Medium | [Java](./lld/movie-ticket-booking) |
| [Design a rate limiter (LLD)](https://systemdesignroad.in/lld/api-rate-limiter/read/) | Medium | [Java](./lld/api-rate-limiter) |
| [Design an ATM](https://systemdesignroad.in/lld/atm/read/) | Medium | [Java](./lld/atm) |
| [Design a pub-sub message queue](https://systemdesignroad.in/lld/pub-sub/read/) | Medium | [Java](./lld/pub-sub) |
| [Design a library management system](https://systemdesignroad.in/lld/library-management/read/) | Medium | [Java](./lld/library-management) |
| [Design a hotel booking system](https://systemdesignroad.in/lld/hotel-booking/read/) | Medium | [Java](./lld/hotel-booking) |
| [Design a food delivery app](https://systemdesignroad.in/lld/food-delivery/read/) | Medium | [Java](./lld/food-delivery) |
| [Design an in-memory file system](https://systemdesignroad.in/lld/file-system/read/) | Medium | [Java](./lld/file-system) |
| [Design a key-value store with transactions](https://systemdesignroad.in/lld/kv-transactions/read/) | Medium | [Java](./lld/kv-transactions) |
| [Design a shopping cart with discounts](https://systemdesignroad.in/lld/shopping-cart/read/) | Medium | [Java](./lld/shopping-cart) |
| [Design a text editor with undo and redo](https://systemdesignroad.in/lld/text-editor/read/) | Medium | [Java](./lld/text-editor) |
| [Design a meeting scheduler](https://systemdesignroad.in/lld/meeting-scheduler/read/) | Medium | [Java](./lld/meeting-scheduler) |
| [Design a notification system](https://systemdesignroad.in/lld/notification-service/read/) | Medium | [Java](./lld/notification-service) |
| [Design an elevator system](https://systemdesignroad.in/lld/elevator/read/) | Hard | [Java](./lld/elevator) |
| [Design chess](https://systemdesignroad.in/lld/chess/read/) | Hard | [Java](./lld/chess) |

## Popular lessons

[Caching](https://systemdesignroad.in/caching/) · [Load Balancers](https://systemdesignroad.in/load-balancers/) · [Database Replication](https://systemdesignroad.in/replication/) · [Sharding](https://systemdesignroad.in/partitioning/) · [Consistent Hashing](https://systemdesignroad.in/consistent-hashing/) · [CAP and PACELC](https://systemdesignroad.in/cap-and-pacelc/) · [Message Queues vs Logs](https://systemdesignroad.in/queues-and-logs/) · [Rate Limiting](https://systemdesignroad.in/rate-limiting/) · [Back-of-the-Envelope Estimation](https://systemdesignroad.in/back-of-the-envelope-estimation/)

## Outages explained

- [CrowdStrike and the biggest IT outage in history](https://systemdesignroad.in/blogs/crowdstrike-2024/)
- [GitLab deleted its production database](https://systemdesignroad.in/blogs/gitlab-database-deletion/)
- [Roblox was down for 73 hours](https://systemdesignroad.in/blogs/roblox-73-hours/)
- [The race condition that took down DynamoDB](https://systemdesignroad.in/blogs/aws-dynamodb-dns-2025/)

---

Found a mistake or want a topic covered? Email **codesnap424@gmail.com**.
