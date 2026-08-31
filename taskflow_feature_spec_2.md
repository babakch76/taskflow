# TaskFlow v1 — Feature Specification
**Mechanics** = the rules and interactions as implemented. **Dynamics** = how it plays out between housemates over time.
For evidence behind each decision see taskflow_features_v1.md. This document is the build contract.

---

## Shared model (read first)

- **Group** — a household. Members, invite links, join/leave. (Already built server-side.)
- **Chore** — a definition, not a to-do: name · optional one-line "what done means" · schedule type · optional "needed by" time · ordered rotation list (any subset of members, min 1).
- **Schedule types (pick one at creation):**
  1. **Interval** — every 1/2/3/4/5/6 days, weekly, monthly; next due = last completion + N (done late shifts the schedule with reality).
  2. **Fixed-date** — specific weekdays or month-days ("Tuesdays", "the 1st"), for chores whose deadline the world sets; a missed date rolls into the next one, same assignee.
  3. **As-needed** — rotates with no date: one standing occurrence always exists ("Trash — Marco's turn"), completed whenever reality demands it; completion advances the turn and spawns the next standing occurrence. No due dates, hence no due reminders — only turn-start. There is deliberately no "it's needed now" button: flipping someone's chore to due is a nag with a disguise on.
  4. **One-off** — no recurrence; assigned at creation by the creator.
- **Occurrence** — one cycle of a chore, assigned to one member. This is what appears on the board and what gets marked done.
- **Occurrence states:** `open → done` (records done_by, done_at). An open occurrence **never expires and never disappears** — it stays with its assignee, day after day, until completed or passed. There is no "missed" state anywhere in the system; a chore can only be *not yet done*.
- **Member statuses:** `active` · `busy` (per-occurrence pass, see F5) · `away` (out of all rotations, see F5). Statuses are visible to the group in the app; none of them sends a chat-style announcement.

The metaphor throughout is the hallway whiteboard, with one upgrade: entries don't wash off. A chore stays written next to your name until you do it. Accountability comes from persistence, not from badges.

---

## F1 — The Board (group home screen)

**Mechanics**
- One screen per group listing current occurrences in three sections: **Yours** · **Others** · **Done this cycle**.
- Each row: chore name, assignee, due date, state; member status (busy pass available / away) reflected where relevant. Past-due and still open → subtle visual (dimmed/amber dot) and the due date shown as-is. Never red, never a count of days late, never a badge — the date is information enough.
- Marking done is one tap from the row. **Any member can mark any occurrence done** — done_by records who actually did it, which may differ from the assignee. Both names are kept (assigned_to, done_by).
- Undo within 10 minutes by the person who marked it.
- **One-off tasks** (a bill, a repair, a delivery): created by any member, assigned at creation by the creator — to themselves or someone else — with the usual group diff notification. No self-claim pool, no unassigned tasks: everything on the board always has exactly one name on it.
- Pull to refresh; board is the app's default screen after login.

**Dynamics**
Answers "what still needs doing and whose is it" in one glance — the top two survey priorities. Because anyone can mark done, the common reality of "I just did it myself" is recorded instead of fought; the assignee's blank cell and the doer's mark both end up in history without anyone saying a word.

---

## F2 — Rotation: completion-anchored, persistent

**Mechanics**
- Two schedule types at chore creation:
  - **Interval** ("every N days"): next occurrence is due N days after the last *completion* — done late, the whole schedule shifts with reality (the bathroom needs cleaning 4 days after it was last cleaned, not on Tuesdays regardless).
  - **Fixed-date** ("Tuesdays", "1st of the month"): due dates come from the calendar because the world sets them (bin collection).
- **Rotation advances on completion, never on the calendar.** An undone chore stays assigned to the same person:
  - Interval chores: it simply remains open on their row, due date in the past, until done.
  - Fixed-date chores: a missed date rolls into the next date, same assignee — you keep the chore until you've actually done it once.
- **The unified turn rule:** an occurrence completed by its assignee advances rotation normally. An occurrence completed by anyone else, or passed via busy (F5), **counts as the doer's turn: the next occurrence is assigned back to the original assignee, and rotation then continues after the doer.** One rule covers busy passes, voluntary covers, and the overflowing bin — a standing turn survives until its owner completes one; nobody's patience can be waited out.
- A member going `away` is lifted out of all rotations; on return they re-enter at their old position. A member leaving the group is removed permanently; order closes the gap.
- Editing a chore (schedule, rotation, done-line) is open to any member and **notifies the whole group with a diff** ("Sara changed Kitchen: weekly → every 3 days"). Transparency instead of an approval flow.

**Dynamics**
Nobody escapes a turn by waiting it out — the chore follows you until it's done, visibly but quietly. Skipping doesn't stall anyone else's schedule and doesn't trigger any alarm; it just means your row on the board doesn't clear. That standing state, not a notification, is the accountability mechanism.

## F3 — Reminders (the schedule speaks; people never do)

**Mechanics**
- Exactly two push notifications per occurrence, **to the assignee only**: (1) when their turn starts; (2) approaching needed-by (default: 3h before, or 09:00 that day if no time set). As-needed chores get (1) only — there is no date to remind against.
- **Quiet hours, default 21:00–09:00, per user.** A reminder that would land inside quiet hours is delivered at the next allowed moment. (Direct consequence of the 11pm finding: a reminder you can't act on is a reminder you'll forget.)
- No member can trigger a notification to another member about a chore. There is no "remind them" button. Lateness is never pushed to anyone — it is only visible on the board to whoever looks.
- One private notification to the receiver when an occurrence is passed to them (F5). No group notification for passes.
- Group-wide notifications exist only for: member joined/left, chore created/edited.
- For an occurrence that stays open past due (persistence, F2): at most one further reminder per 48h to the assignee, always inside allowed hours. Persistent, never escalating — same tone on day one and day five.

**Dynamics**
The app absorbs the nag role that currently costs a relationship tax — people in the interviews would rather redo work silently than remind someone. The assignee gets pressure from a clock, not a person; flatmates get to stay flatmates.

## F4 — "What done means" (one line, agreed once)

**Mechanics**
- Optional free-text line on every chore, ≤140 chars, shown on the occurrence detail. Set at creation, editable like any chore field (edit → group diff notification).
- The chore's frequency lives next to it and is subject to the same visibility.

**Dynamics**
Moves the standards fight (mopping weekly vs monthly; "clean" vs "disinfected") from every occurrence to a single setup conversation. The 140-char limit is deliberate: this is a treaty, not a manual.

## F5 — Busy and Away (the two honest exits)

Two statuses, chosen in the app, no chat message required. They are the *only* ways an assigned chore leaves you without being done.

**Busy — "pass this one" (still living at home, temporarily overloaded)**
- On any open occurrence of yours, tap **Busy — pass it**. The occurrence moves to the next member in rotation. If it was already due, the new assignee's due date becomes tomorrow (earliest convenience); otherwise it keeps its date.
- The receiver gets one quiet notification ("Kitchen passed to you, due tomorrow"). **No group broadcast.** The board simply shows the new name.
- **The debt rule:** the next occurrence of that chore is assigned back to the passer, and rotation then continues to the person *after* the coverer — the cover counted as the coverer's turn. Net effect: passing is an automatic one-cycle swap with whoever is next. Declaring busy defers your turn; it never deletes it.
- The receiver can also be busy and pass onward. The chain can wrap all the way around; the occurrence always has exactly one owner. No approvals, no reasons, no caps, no penalties.

**Away — "I'm not at the house" (physically absent for a period)**
- Set an away period (open-ended or dated). You are removed from all rotations for its duration and re-inserted at your old position on return. No turns are owed back.
- Away is **not for residents who come and go** — if you're sleeping at home, you're busy, not away. The app states this rule at the toggle; it cannot enforce it, so it makes the status impossible to hide instead: away shows on the member list and on every screen where your name would appear in a rotation, and your history cells for the period are marked *away* (distinct from blank).

**Dynamics**
Both statuses remove the conversation nobody wants to have ("I can't this week, sorry, I know it's my turn…") and replace it with a state change the household can see. Busy costs you nothing but a delay — which is exactly why it's safe to use honestly. Away that isn't true is visible to everyone eating dinner next to you; social enforcement, zero mechanics.

## F6 — History (the whiteboard's memory)

**Mechanics**
- Two views, read-only:
  1. **Per chore:** a timeline of completions — who did it, when it was due, when it was done. Late completions are visible only as date arithmetic, never as a flag. *Away* periods are marked distinctly, so absence never reads as flaking.
  2. **Per person:** completions over a selectable window (this week / month / 3 months), counting done_by (so covering counts for the coverer).
- No points, ranks, streaks, percentages, or comparisons drawn by the app. The data is presented; conclusions are the household's business.

**Dynamics**
Serves the person whose work goes unnoticed without making them claim credit aloud, and gives the "who actually does more" argument a neutral record to point at instead of two memories. Kept deliberately quiet because it ranked last in the priority vote.

---

## Onboarding loop
Create or join a group (invite link — built) → add 3–5 chores with rotation (a starter list of common chores is offered, skippable) → land on the board. The board alone must be a complete, useful app; F2–F6 are additive.

## Hard constraints for the implementing agent (non-negotiable)
1. No peer ratings, likes, comments, or quality feedback of any kind.
2. No user-to-user reminder/nag actions; no notification ever names another member as late.
3. No red overdue states, late counters, or shame badges — dim/amber at most; expiry is silent.
4. No points, streaks, leaderboards, or gamification.
5. No calendar integration or free/busy scheduling in v1.
6. Chores/household scope only; no generic project-management concepts (no priorities, no subtasks, no comments threads).
7. Every group-visible change (chore create/edit, membership) is broadcast; passes notify only the receiver; nothing about individual lateness is ever pushed to anyone.
8. Busy and away are the only exits from an assigned chore besides completing it. No unassigned chores, no claim mechanics, no directed swap requests.


## Considered and rejected (keep in the report — non-goals read as maturity)

- **Self-claim task pool** — destroys "knowing who is responsible" (the #2 priority, 41 votes) and formalises the "we each do what's needed" arrangement the complaints come from. Everything is assigned, always.
- **Directed swap requests** — targeted asks are social pressure with an address on them, and reciprocal swaps need a debt ledger. The busy pass achieves the same outcome as an automatic, addressless one-cycle swap.
- **Minutes / difficulty per chore** — creates a new thing to argue about and turns the quiet history into a scoreboard; contribution tracking ranked last in the priority vote. Revisit in v2 at most, as a creation-time field only.
- **Shared-resource tags + conflict warnings** — a conflict warning needs to know when people intend to do chores, which means asking them to schedule in advance: the calendar feature the vote ranked 4th of 5. A tag without the warning is dead weight.
- **House-meeting slot picker** — generic scheduling inside a chore app; the group chat already does this. The underlying need (deciding emergent things together) is served by edit broadcasts and creator-assigned one-offs.
- **Broadcast handoff ("up for grabs")** — replaced by the deterministic busy pass: quieter (no group announcement, no social ask-moment), no nobody-accepts dead end, and the debt rule prevents gaming.

## Build order
1. Board (F1) on existing task/group endpoints — first screen after Login.
2. Chore definitions + rotation + occurrence spawning (F2) — new backend work.
3. Notifications (F3) — closes the existing backend TODO; includes quiet hours.
4. Done-line + edit-diff broadcasts (F4) — small.
5. Busy pass + away status (F5) — pass endpoint with debt-rule bookkeeping; away as a member attribute consulted by occurrence assignment.
6. History (F6) — read-only queries over occurrence records (ensure done_by/done_at/assigned_to are all persisted from step 1).
