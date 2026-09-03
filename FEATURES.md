# TaskFlow: what the app does

A reference for the app **as built and deployed**, not as planned. Written
2026-09-04 against commit `496005a`, with every claim checked against the code
or driven on a device rather than taken from an earlier document.

Two companion documents, deliberately separate:

- [`taskflow_feature_spec_2.md`](taskflow_feature_spec_2.md) is the *spec*: what
  was asked for. Where this document and the spec disagree, the app follows this
  one and [`BACKLOG.md`](BACKLOG.md) records why.
- [`V2-HANDOFF.md`](V2-HANDOFF.md) is the *working state*: what is deployed,
  what is half-done, what will cost the next person hours.

---

## 1. What it is

A chore board for a household that shares one. Three or four people, a handful
of recurring chores, and the question the app exists to answer: **whose turn is
it, and has it been done?**

It is not a task manager with a household theme. The difference shows in what it
refuses to do, which is set out in section 8.

---

## 2. The board

The app's home screen after signing in. If you belong to exactly one household
it opens straight onto that household's board; the group list is one Back away.

Rows are grouped by **whose it is**, not by status:

| Section | What is in it |
|---|---|
| **YOURS** | Open rows assigned to you |
| **OTHERS** | Open rows assigned to anybody else |
| **DONE THIS CYCLE** | Completed rows, with who did it and when |

Each row is three lines: **what it is**, **how it recurs and when it is wanted**,
and **whose turn it is**. For example:

```
Clean the bathroom
weekly
Due Thu 10 Sep
maya covered your last turn, so it's yours again
```

That fourth line appears only when there is something to explain. See section 4.

**Overdue is amber, never red**, and never a day count, a badge or a percentage.
The amber follows whichever line carries the date, and is picked from the theme
so it stays legible in light and dark.

**Anyone can tick anything.** "I just did it myself" is the common reality, so
the board records who actually did it rather than fighting over who was meant
to. Completion is one tap, undoable for **ten minutes** and only by the person
who ticked it.

**Tabs are Board, Activity and Members.** There is no calendar; see
[`calendar_decision_record.md`](calendar_decision_record.md) for why it was
removed rather than fixed.

---

## 3. Chores, and the four ways they come round

A chore is added in **two questions**. The first is *how often*, and the answer
decides what the second screen even asks:

| Answer | What it means | What the second screen asks |
|---|---|---|
| **Every so many days** | Counted from the last time it was done | The interval, and the order of turns |
| **A fixed day of the week or month** | A calendar commitment: "Thursdays" | Which day, and the order of turns |
| **As needed** | No date at all; you can see it needs doing | Just the order of turns |
| **A one-time thing** | Given to one person once | Who, and an optional date |

Each option has a circled **?** that explains it on tap, so the choice itself
stays short.

Every chore may carry a **done-line**: one sentence agreed once, saying what
counts as finished, so it is not argued every time. *"Sink, loo and shower
screen, and the bin out."*

**Editing is the same two screens, prefilled.** Changing a schedule re-dates the
open turn rather than stranding it on a date the new schedule would never
produce. The one change a chore cannot make is becoming a one-time thing, and
the screen says so instead of silently disabling the option.

**Deleting** lives at the end of editing, not on the row, and its confirmation
names what goes with it: the chore, whoever's turn it currently is, and the
history of every time it was done.

### Dates

- **Interval**: the next turn is due N days after the **last completion**, so
  doing it late moves the next turn with it. Doing it *early* also pulls the
  schedule earlier; that is deliberate and BACKLOG's B-14 records the three
  reasons, including the cost.
- **Fixed date**: the pattern holds. A chore done early still comes round on its
  own day, and a missed day rolls forward with the same person still holding it.
- A chore may also have a **needed-by time** ("before 7am").

---

## 4. Whose turn: the turn rule

One rule covers three things the app treats as identical: a housemate quietly
doing someone else's chore, a **busy pass**, and the overflowing bin somebody
else finally emptied. All three are a **cover**.

> A chore done by anybody other than the person whose turn it was counts as
> **the doer's** turn. The next one goes back to the person who owed it, and the
> rotation then resumes **after the doer**.

So a cover is not a favour that vanishes and not a debt that compounds. It is a
swap: two people exchange places for one cycle.

**Every passer owes one.** If A passes to B and B passes on to C, both A and B
owe a turn, repaid oldest first. A person is only ever owed **one** turn per
chore, however many times they passed it.

**Away holds a debt, it does not cancel or park it.** Someone away is out of
every rotation, so the turn passes to the next available person and the debt
waits for their return.

**When everyone is busy**, the chore has nowhere to go. It returns to whoever
asked first, dated no later than the day it would next have come round on, and
that person picks the day. If nobody picks, that date stands and the chore
stands with it. Asking again is refused with what to do instead, rather than
lapping the household forever.

**A debt belongs to a person, not a position.** Reordering a rotation does not
move it; leaving the rotation voids it, and nothing is said about the turn that
was not taken.

---

## 5. The two ways out of a turn, besides doing it

Neither cancels what you owe. These are the **only** two.

### Busy pass

Swipe your own row, or use the button in the row's detail. It goes to the next
person in the rotation, and the confirmation says who will be told and that
**nobody else is**.

**A pass is private.** It writes no entry in the household's activity feed. That
is enforced in the data, not just in the wording: the pass and its undo both
leave the feed untouched, and the list of who has passed what is never sent to
anybody's phone.

The snackbar offers **Undo** for a mis-swipe. The server allows it for two
minutes, only to the person whose pass it was, and only while the chore is still
open. It also puts back a date the pass had moved, so passing an overdue chore
and taking it straight back cannot launder its deadline.

There is no cap on passing, no cooldown, no tally, and no notification to
anybody about somebody else's passing. That is a decision, not an omission; the
reasoning is in BACKLOG's Part 0 notes.

### Away

Declare yourself away, open-ended or until a date. You come out of every
rotation here and back in at the same place, and no turns are owed for the time
you were gone. **The household can see you are away** — unlike a pass, which is
the point of having both.

Your own board shows a quiet banner while you are away, with a way back.

---

## 6. Reminders

Scheduled **on the device**, not on the server, and covering every household you
belong to rather than only the one you have open.

**Quiet hours** are a property of the person, not the group: one window on your
account, applying to reminders from all of your households, and set from the
group-list menu. Anything due inside the window waits until it is over rather
than being dropped.

---

## 7. The record

**Activity** is the household's shared feed, in words: *"maya did a chore"*,
*"demo added a task"*, *"Moved it to Done"*. Every group-visible change appears;
a pass does not, by design.

**One chore's history** shows every time it has been done, with the date, the
date it was due, and who did it for whom. Away periods appear as *away*.

**What's been done** is the per-person record, over this week, this month, or
three months. It **counts, and does not rank**: no percentages, no averages, no
streaks, no ordering by who did most. Zeroes are shown as zeroes, days away are
noted, and the line under it says what the number means: *"Counted by who
actually did it, so covering for someone counts for you."*

---

## 8. What it deliberately does not do

Each of these was considered and refused. A reader who finds one missing should
know it was a decision.

- **No scores, percentages, points, streaks, leaderboards or rankings.** Not on
  the board, not in history, not anywhere.
- **No red, and no shaming of lateness.** Overdue is amber and stated once.
- **No counting of anybody's skips.** No "3×", no tally, no sort by it.
- **No escalating repayment.** Three skips owe three turns, one at a time, in the
  ordinary way.
- **No penalty, cap or cooldown on the busy pass**, and no telling the household
  about somebody else's.
- **No roles or permissions beyond ownership.** Chore editing is open to every
  member, so the manager role and its deadline gate were removed rather than
  kept.
- **No calendar view.** Under completion-anchored rotation a chore has one live
  occurrence, so a month grid has almost nothing to draw.
- **No multi-select or bulk actions.**

---

## 9. Joining, leaving, and a new household

A household is created by name, and joined either by **direct invite** to a
username or by a **shared code** that expires. Invites appear on the group list
with a badge.

**A new household is offered five starter chores** rather than an empty board:
*Trash* (as needed), *Bathroom* (every 4 days), *Kitchen floor* (weekly),
*Recycling* (Tuesdays) and *Hallway vacuum* (fortnightly). Each shows its
schedule underneath, so "as needed" and "Tuesdays" are met as facts about real
chores before the create flow ever asks about them. Each can be renamed, because
*Trash* and *Bins* are the same chore in different houses, and any can be
removed. "Something else" adds a blank row, and Skip leaves the board empty
with a line saying what to do next.

Leaving is available to any member; the owner cannot leave while others remain,
and the last member leaving deletes the household.

---

## 10. Appearance

**System, Light or Dark**, chosen from the group-list menu and stored **on the
device**: it is a property of this phone in this person's hand, not of the
account or the household, so it is not synced and not visible to anybody else.

---

## 11. Shape of the system

| Piece | What it is |
|---|---|
| **App** | Android, Kotlin, Jetpack Compose with Material 3. `minSdk` 26. |
| **Backend** | Go, one binary, SQLite in WAL mode. Migrations run on every start. |
| **Hosting** | One EC2 instance. Caddy terminates TLS on 443 and proxies to the app on `127.0.0.1:8080`, so the API is reachable only over HTTPS. |
| **Auth** | JWT bearer tokens; the middleware wraps every route, so even an unknown path answers `401`. |

Two data shapes sit side by side on the board and this is intentional:
**occurrences** are the turns of a recurring chore, and **tasks** are one-off
items, which predate the chore model and are the spec's one-off type. The board
shows both; the rotation rules apply only to the first.

The endpoint list lives in
[`task-manager-backend-GO/README.md`](task-manager-backend-GO/README.md), and the
screen-by-screen map in
[`TaskFlowApp-kotlin/README.md`](TaskFlowApp-kotlin/README.md).

### One thing in this repository is not the app

`index.html`, `app.js` and `index.css` at the root are a **pre-pivot web
prototype**, and they describe a different product: "Joint Task Management",
projects called *Website Redesign* and *API Integration*, a deadline calendar.
Nothing in the Android app or the Go backend uses them, and no build refers to
them. They are kept only because deleting somebody's earlier work is their call,
not the toolchain's. **Do not read them as documentation of TaskFlow.**
