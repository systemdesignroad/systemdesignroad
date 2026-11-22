# Pull requests to awesome lists (Week 1)

This file stays local, so don't upload it to the repo.

Open each one from **your own GitHub account**. On the list's README, click the pencil icon. GitHub forks the repo for you. Paste the line, then "Propose changes" → "Create pull request".

Repo links below assume `https://github.com/systemdesignroad/systemdesignroad`. If the repo has a different name, change them.

Rules of thumb:
- Send one PR per list, and open no more than two a day.
- Don't send a second PR to a list that rejected the first.
- Keep the wording plain. Maintainers close anything that reads like an ad.

---

## 1. prasadgujar/low-level-design-primer (best fit, LLD)

File: `solutions.md`. It's a table where each problem row has `[Solution-1] [Solution-2]` links. Add the new link as the next `Solution-N` on **existing rows only**. Don't add new rows.

| Row in their table | Add |
|---|---|
| Design an online hotel booking system | `[Solution-3](https://systemdesignroad.in/lld/hotel-booking/read/)` |
| Design Tic Tac Toe | `[Solution-3](https://systemdesignroad.in/lld/tic-tac-toe/read/)` |
| Design a Vending Machine | `[Solution-2](https://systemdesignroad.in/lld/vending-machine/read/)` |
| Design Elevator | `[Solution-4](https://systemdesignroad.in/lld/elevator/read/)` |
| Movie Ticket Booking | `[Solution-2](https://systemdesignroad.in/lld/movie-ticket-booking/read/)` |
| Uber Eats/ Door dash/ Swiggy | `[Solution-2](https://systemdesignroad.in/lld/food-delivery/read/)` |

**Title:** Add Java solutions for hotel booking, tic-tac-toe, vending machine, elevator, movie tickets, food delivery

**Body:**
> Adds one more solution link to six existing rows. Each one is a step-by-step walkthrough (requirements, entities, class diagram, design patterns, concurrency) with complete Java code that compiles and runs. The source is also on GitHub: https://github.com/systemdesignroad/systemdesignroad/tree/main/lld
>
> I wrote these. Happy to trim it to fewer rows if you prefer.

---

## 2. binhnguyennus/awesome-scalability (system design)

File: `README.md`, section `## Interview`. Their CONTRIBUTING asks for one link per PR, added at the bottom of the category. Add it as a sub-item under the first bullet ("Designing Large-Scale Systems"), after the "How NOT to design Netflix..." / "API Best Practices" lines:

```
	* [System Design Interview Walkthroughs: 23 Problems Worked from Requirements to Deep Dives](https://systemdesignroad.in/system-design-interview-questions/)
```

(Indented with one tab, like its neighbours.)

**Title:** Add System Design Road interview walkthroughs

**Body:**
> Adds one link under Interview. It's a free collection of 23 system design problems, among them a rate limiter, Kafka, Google Docs, ride hailing, a payment system and a metrics/alerting system. Each one goes from requirements and estimates to the API, the staged architecture, and deep dives on the parts that break at scale. No sign-up. I'm the author.

---

## 3. checkcheckzz/system-design-interview (system design + OOD)

File: `README.md`.

**3a.** Under `### Object Oriented Design` → `#### Useful Websites`, add below the sourcemaking line:

```
* [Low-Level Design Problems with Java Code](https://systemdesignroad.in/lld-interview-questions/)
```

**3b.** Optional, in the same PR: under `**Design a Google document system**` → `Reference:`, add:

```
* [Design Google Docs: OT vs CRDT walkthrough](https://systemdesignroad.in/practice/google-docs/read/)
```

**Title:** Add LLD problem set and a Google Docs design reference

**Body:**
> Two additions. The first is a free set of 22 low-level design problems (parking lot, elevator, splitwise, a text editor, a meeting scheduler and more), each with a class diagram and Java code that compiles. The second is a walkthrough for the "Design a Google document system" question, covering operational transformation vs CRDTs, presence, and storage. I'm the author of both.

---

## 4. ashishps1/awesome-low-level-design (big audience, lower odds)

File: `README.md`, section `## Additional resources`. Add at the bottom:

```
- [System Design Road - LLD Problems with Java Code](https://systemdesignroad.in/lld/)
```

**Title:** Add System Design Road LLD problems to Additional resources

**Body:**
> Adds a free resource to Additional resources: 22 LLD interview problems, each with requirements, a UML class diagram, design patterns, concurrency issues, and compiled Java code (source: https://github.com/systemdesignroad/systemdesignroad/tree/main/lld). I'm the author.

Note: this list mostly links to the maintainer's own site, so it may not be merged. Send it last.

---

## Skip for now
- **donnemartin/system-design-primer**: it doesn't take links to external sites, and the PR would be closed.
- **ashishps1/awesome-system-design-resources**: every problem links to the maintainer's own articles, and it has no section for outside resources.
- **DopplerHQ/awesome-interview-questions**: it's organised by language and framework, and it has no system design section.

## Other places that aren't PRs (also Week 1)
- Add the site URL to your GitHub profile (Settings → Profile → Website) and to the repo's "About" box. Put these topics on the repo: `system-design`, `low-level-design`, `java`, `interview-preparation`, `lld`, `design-patterns`.
- LinkedIn and Dev.to/Hashnode: post one walkthrough at a time (start with Google Docs or Kafka). Set the canonical URL to the site.
