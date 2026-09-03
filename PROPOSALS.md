# Proposed changes, and what to know before building them

Raised by Babak on 2026-09-04, on handing the project over. **Nothing here is
implemented.** This is a brief for whoever picks it up.

Each item records what was asked for, **what already exists** (checked in the
code, not remembered), the design questions that have to be answered before
writing anything, and an opinion. The opinions are arguable; the "what exists"
sections are not, and reading them first will save building something twice.

Three things to flag before the list:

1. **Two of these are already built.** Members *can* leave a household, and a
   household *is* deleted when the last member leaves. Item 6 explains what is
   genuinely missing, which is narrower and sharper than "we cannot leave
   groups".
2. **One collides with a hard constraint.** The bar chart in item 5 is the kind
   of comparison the spec forbids the app from drawing. That does not kill it,
   but it cannot be built as described without a decision that overrides
   constraint 4 explicitly and in writing.
3. **Item 3 is not just a preference.** Babak's instinct about the rotation
   order being editable turns out to point at a live hole: editing a rotation
   currently voids a debt, and editing is open to everybody. See below.

---

## 1. Collapsible YOURS and OTHERS

**Asked for.** Keep the split, which works, but let either section collapse so
the board reads shorter.

**What exists.** `GroupDetailScreen` builds a `sections` list of
`"YOURS" to open.filter { ... }` and `"OTHERS" to ...`, rendered as sticky-ish
headers over rows. There is no collapse state anywhere.

**Design questions.**

- **Is the state remembered, and where?** A collapse that resets every time the
  screen opens is a fidget, not a setting. It is per person and per device, like
  the appearance toggle, so `SharedPreferences` beside `Appearance` and
  `LocalHints` is the consistent home. Per *household*, if a person has two.
- **What does a collapsed header say?** It has to carry the count, or collapsing
  hides work silently: `OTHERS (4)`. Note this is the one place a number on the
  board is *not* a constraint-4 problem, because it counts rows on screen rather
  than scoring a person. B-11 already put a count on the Board tab for the same
  reason.
- **Can YOURS collapse too?** Probably yes, for symmetry, but it is the section
  the app exists to show. Consider defaulting OTHERS collapsed and YOURS open
  rather than offering both and defaulting to neither.
- **What happens to DONE THIS CYCLE?** It is arguably the best candidate of the
  three for collapsing by default, and was not mentioned.

**Opinion.** Worth doing, and the cheapest item on the list. Default OTHERS
collapsed on a board over some length rather than always, so a three-chore
household never meets the feature at all.

**Size.** Small. One state holder, a clickable header, and the count.

---

## 2. Swipe between tabs, and the row swipe feels wrong

**Asked for.** Two things, and they are in tension, which is the important part.

- Let a horizontal swipe move between Board, Activity and Members.
- The swipe *on a row* feels awkward: it does not need to travel far, the row
  springs back, and the action happens anyway.

**What exists.** Rows are wrapped in `SwipeToDismissBox` with
`confirmValueChange`, offered only on your own open occurrence rows. Past a
threshold it fires the pass and the row returns to place. Tabs are a plain
`TabRow` with no pager.

**The conflict.** Both gestures are horizontal drags on the same surface. If
rows keep their swipe and a pager is added, every drag has to be adjudicated:
the row claims it, the pager gets what is left, and the failure mode is a person
trying to change tabs on a busy board and passing a chore instead. That is worse
than either problem it solves.

**Why the row gesture feels wrong**, specifically: `SwipeToDismissBox` is built
for *dismissal*, so its affordance promises the row will leave. Here the row
comes back and something else happens instead. The gesture says "throw this
away" and the app means "hand this over". The spring-back is the app apologising
for a metaphor it borrowed and does not honour.

**Three ways out, pick one deliberately.**

| Option | What it costs |
|---|---|
| **Pager for tabs, drop the row swipe.** The detail sheet already carries Pass and the checkbox already marks done, so nothing becomes unreachable. | Loses a shortcut some testers may have learned. The one-time "Swipe a row for actions" hint goes too. |
| **Keep the row swipe, no pager.** Tabs stay tapped. | The tab bar is three items at the top of a phone screen; tapping is not a hardship. Cheapest, and changes nothing. |
| **Both, with the row gesture redesigned** so it reveals a stationary action behind the row rather than moving it, and only claims the gesture after a longer press. | Most work, most risk, and gesture contention on a scrolling list is exactly where Compose gets fiddly. |

**Opinion.** The first. The row swipe is the weaker of the two: it duplicates
something already available two taps away, its metaphor is borrowed, and Babak's
own reaction to it is the clearest evidence available that it does not read
right. Tab paging is a gesture people expect from every other app they use.
Worth noting in the report either way: a swipe that fires without completing is
a real finding about affordance, and it was found by using the thing.

**Size.** Option one is small. Option three is not.

---

## 3. A rotation's order should not be editable

**Asked for.** Once a repeating chore is set up with an order of people, that
order should change only through passing or going away, not through Edit.

**What exists.** `UpdateChore` accepts `rotation` from any member, compares it
with `sameOrder`, and records the word `rotation` in the activity diff if it
changed. So it is currently editable by anybody, and the household is told
*that* it changed but not *how*.

**This is pointing at a real hole.** `debtsAfter` drops any debt whose owner is
no longer in the chore's rotation. That is deliberate (cases 3.3 and 3.4: a debt
belongs to a person, and dies with their place in the rotation) and it is
correct for somebody who has left the household. But combined with an editable
rotation and no permissions, it means:

> Anybody who owes a turn can remove themselves from that chore's rotation, and
> the debt is silently gone. Two taps, and the only trace is the word
> "rotation" in the feed.

That is worth fixing regardless of what is decided about reordering, and it is
the strongest argument for the proposal.

**But "immutable" is too strong**, because households change. People move in and
out, and a rotation that cannot be edited cannot accept a new housemate or
release one who has gone. So the useful question is not *whether* it changes but
*which* changes are legitimate:

| Change | Legitimate? |
|---|---|
| Add a person who has joined the household | Yes, clearly |
| Remove a person who has left | Yes, and see item 6 |
| **Remove a person who owes a turn on this chore** | This is the hole. Their debt should survive, or the removal should say out loud that it is cancelling one |
| **Reorder the people already in it** | This is what Babak wants to stop |

**Opinion.** He is right about the risk and half right about the remedy.

- **Fix the hole properly:** a debt should not vanish because a rotation was
  edited. Either keep it (a debt is owed to the household, not to a slot) or
  make the confirmation say what is being cancelled and who owed it, in the way
  chore deletion already names what it destroys.
- **On reordering, prefer attribution to prohibition.** The app has no
  permissions anywhere and removed the one role it had, on the grounds that
  chore editing belongs to everybody. Making the rotation the single exception
  cuts against that. The consistent move is the one the app already makes for
  chore deletion, which is equally destructive and equally open: allow it, and
  announce it *with the diff* rather than the bare word "rotation", so a
  reordering that moves somebody to the back is visible to the people it
  affects.
- **If you do lock it anyway**, lock only reordering, keep add and remove, and
  say why on the screen. A disabled control with no explanation is the thing
  phase 4 already had to fix once.

**Worth asking a real household**, because this is a question about trust rather
than mechanics, and the answer probably differs between a house of friends and a
house of strangers.

**Size.** The debt fix is small and contained. The diff broadcast is small. A
lock is small. Deciding is the expensive part.

---

## 4. A chart for "What's been done"

**Asked for.** A new screen: a bar chart per person showing what was *scheduled*
for them in a grey hollow bar, and how much they actually *did* in a solid
colour. Babak is explicitly unsure and expects it may come from an interviewee or
a beta user rather than from him.

**What exists.** `GET /groups/:id/history` returns per-person counts over this
week, this month or three months. The screen shows counts, zeroes included, days
away noted, and a line explaining that covering counts for the coverer.
`history.go` carries the no-ranking rule in a comment and `history_test.go`
asserts that `late`, `overdue`, `missed`, `days_late` and `streak` appear nowhere
in the payload.

### This is the item to be careful with

**It collides with constraint 4**, which the spec states as: *"No points, ranks,
streaks, percentages, or comparisons drawn by the app. The data is presented;
conclusions are the household's business."*

A grouped bar chart of people side by side is a comparison drawn by the app.
Numbers in a list can be read without ranking them; bars cannot — the eye ranks
them before the label is read, and a short bar next to a long one is an
accusation the app has made on somebody's behalf. Adding a "done against
scheduled" ratio makes it a percentage in all but name, which is named in the
constraint directly.

**And the measure does not exist yet.** "What was scheduled for a person" is not
a quantity this data model has. Under completion-anchored rotation a chore has
exactly **one** live occurrence, so nothing is scheduled in advance for anybody.
You would have to define the denominator, and every available definition is a
judgement:

- occurrences assigned to X that reached their due date? Punishes whoever holds
  the slow chores.
- turns X was assigned at any point? A passed chore counts against the passer
  and the receiver both.
- turns X *would* have had under an unbroken rotation? Now the app is modelling a
  counterfactual and comparing people against it.

The denominator is where the bias lives, and a bar chart hides the choice behind
something that looks like a measurement.

**Ways to honour the impulse without breaking the rule.** The impulse is
reasonable: "is this working?" is a fair question.

- **One person at a time, over time.** Your own bars, across weeks. Trend without
  comparison, and nobody else's bar to sit next to.
- **The household as one bar.** What the house set out to do against what it did,
  no names. This answers "is the arrangement working?" which is probably the real
  question, and it is the only version with no target.
- **Keep it out of the app.** In the report, a researcher comparing participants
  is the entire point. The constraint binds the product, not the analysis.

**Opinion.** Do not build it as described. Park it as a **hypothesis to test**,
which is what Babak is already proposing by waiting for a user to raise it. If
users do ask for it, that is a finding worth reporting **even if you decline to
build it** — "four of six participants asked for a comparison the design
deliberately withholds" is a more interesting result than a chart, and it is
exactly the tension the constraint was written to create.

**Size.** The household-level version is a day. The per-person comparison is not
a size question, it is a decision.

---

## 5. Removing a member

**Asked for.** The owner should be able to remove members. Babak flagged that he
is unsure and wants it reviewed.

**What exists.** Nothing. There is no route: the only member-scoped delete is
`DELETE /groups/{group_id}/members/me`, which is a person removing *themselves*.

**Why it is genuinely needed.** Someone moves out and stops opening the app.
They will never leave the household themselves, and meanwhile the rotation keeps
handing them turns that nobody does. The board fills with a phantom, and the only
workaround is editing them out of every chore's rotation one at a time.

**But "the owner" is the wrong frame.** The app has no permissions. The one role
it had, Manager, was removed precisely because gating chore editing contradicted
the spec. Reintroducing hierarchy for this one action would make the owner an
administrator of a household of peers, which is not what the rest of the design
says a household is.

**The consistent alternative** is the one the app already uses for the other
destructive, unpermissioned action. Deleting a chore is open to every member,
destroys history, and is handled by naming exactly what will be lost in the
confirmation. Removal could work the same way: **any member may remove a member,
the confirmation says what happens to their turns and their record, and the feed
names who did it.** Attribution instead of permission.

**Questions that must be answered before writing it.**

- **What happens to their open turns?** They cannot vanish; somebody has to do
  the bathroom. Reassign by ordinary rotation, presumably, but that is a
  decision.
- **What happens to their history?** It should stay. "maya did this" is a fact
  about the past, and deleting it rewrites a record the household relies on. The
  member list would need to render a name for somebody no longer in it.
- **What happens to debts they owed?** Item 3's hole, in a different coat.
- **Can somebody remove the owner?** If any member can remove any member, then
  yes, unless it is excluded — and the owner is the one member with a special
  case already (they cannot leave while others remain).
- **Can they rejoin?** Invites still exist, so presumably yes, arriving as a new
  member with old history attached to their user id.

**Opinion.** Build it, and prefer any-member-with-attribution over owner-only.
If it does end up owner-only, that is a deliberate reintroduction of hierarchy
and should be argued in the report rather than slipped in, because it changes
what the app says a household is.

---

## 6. Leaving and deleting a household

**Asked for.** The ability for members to leave a group, and the ability to
delete groups.

**Both are partly built already, so here is the exact state.**

| | Works today? |
|---|---|
| An ordinary member leaves | **Yes.** "Leave group" in the group's overflow menu, `DELETE /members/me`, with a confirmation |
| The last member leaves | **Yes**, and the household is deleted with it, cascading its chores, occurrences, tasks and activity |
| The **owner** leaves while others remain | **No.** `409`, deliberately: ownership transfer was out of scope |
| Anybody deletes a household that still has members | **No.** There is no delete-group route at all |

Three tests pin the working behaviour: `TestLeaveGroupRemovesMembership`,
`TestLeaveGroupRejectsOwnerWhileOthersRemain` and
`TestLeaveGroupDeletesGroupWhenLastMemberLeaves`.

### The real gap, which is sharper than it sounds

**An owner cannot get out of a household.** They cannot leave while anybody else
is in it, and there is no way to delete it. So the person who created the
household is the one person permanently stuck in it. If they move out, their only
options are to remove everybody else first, which item 5 says is not possible
either, or to abandon the account.

That is not a missing nicety, it is a trap, and it is reachable by the most
ordinary sequence in the domain: the person who set up the flat's chore board
moves out of the flat.

**Three ways to fix it**, roughly in order of how much they respect the rest of
the design:

1. **Ownership transfer.** The owner nominates a successor and leaves. Fits a
   household of peers, and needs one endpoint and a picker.
2. **Automatic succession.** The owner leaves and ownership passes to the
   longest-standing remaining member, with the feed saying so. No picker, no
   decision to make, and nobody is asked to volunteer.
3. **Explicit deletion.** The owner dissolves the household for everybody. The
   most destructive option, and the confirmation would have to name what it
   destroys for people who are not in the room.

**Opinion.** Option 2, with option 1 if a picker is cheap. Both are small.
Option 3 solves a different problem — "we are done with this household" — and if
you want that, it should be separate from and additional to letting an owner
leave, not a substitute for it.

**Size.** Small, and this is the item I would do first. It is the only one on the
list that is a defect rather than an improvement.

---

## Suggested order

1. **Owner cannot leave** (item 6). A trap, reachable normally, and small.
2. **The debt hole in item 3.** A rule the app claims to enforce and does not.
3. **Removing a member** (item 5), which items 3 and 6 both lean on.
4. **Collapsible sections** (item 1). Cheap, visible, low risk.
5. **Tabs and the row swipe** (item 2). Decide the gesture question before
   writing anything.
6. **The chart** (item 4). Not until a user asks for it, and not as described
   without overriding constraint 4 on the record.
