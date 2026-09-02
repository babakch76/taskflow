# Decision record — why the Calendar tab was deleted

For the repo, next to `V2-HANDOFF.md`. Written so a future session (or a
groupmate) can read the reversal as a decision with reasons, not as drift.
Nothing in this file asks for new work; the last section is fenced off.

---

## The short version

The whiteboard the spec asks for **already exists — it is the Board tab.**
Yours / Others / Done, every chore with exactly one name on it, one glance.
That is "everything that needs doing, put together." The Calendar was a
different instrument answering a different question ("how are deadlines
spread across the month"), and for this app it was broken three ways.

## The three reasons

**1. It only showed one-off tasks.** `CalendarTab` read `state.tasks` and
never saw occurrences, so rotation chores — the app's entire subject — were
invisible on it. A household opening the month view saw the electrician
appointment and nothing else. That is worse than no calendar: it implies the
month is empty while the board is full.

**2. The data model cannot populate a month grid.** This is the deeper
reason, and it is a consequence of a *deliberate* design choice, not a gap.
Under completion-anchored rotation each chore has exactly **one live
occurrence**; the next one's date does not exist until this one is completed,
because next due = completion + N. Doing a chore late shifts its whole
schedule with reality — that is the point. So a forward-looking month can
show at most one dot per chore, zero for as-needed chores, and anything
further out is a guess about when people will finish things. The month view
made sense in v1, where tasks had fixed arbitrary deadlines scattered across
weeks. v2 abandoned fixed scattered deadlines on purpose.

**3. It was the one screen the handoff had to apologise for.** It survived
constraint 5 ("no calendar integration / free-busy scheduling") only under a
lenient reading, with a standing note telling the report to defend it.
Deleting it removed a misleading view and a grading liability in one commit.

## What was NOT lost

Nothing that "needs to be done" was hidden by the deletion. Every open
occurrence and one-off is on the Board with its due date on the row. The
Calendar duplicated a subset of that information in a layout the data could
not fill.

## If a forward-looking view is ever wanted (do not build unless asked)

The honest version for this data model is **not** a month grid. It is an
"up next" strip on the Board: each chore's current due date, plus at most one
*projected* next turn, visibly labelled as projected (it moves when reality
does). That fits the single-live-occurrence model. A month grid does not, and
re-adding one should be treated as a spec change, not a UI task.

Test the Board with real users first. The expected finding is that nobody
asks where the calendar went; if someone does, ask what question they were
trying to answer with it before reaching for a grid.
