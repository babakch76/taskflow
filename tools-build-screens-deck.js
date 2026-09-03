// Rebuilds TaskFlow_Screens.pptx from screenshots taken off the real build.
//
// Every image is a capture of the running app against a live backend. Nothing
// here is a mock-up, and nothing is reused from the pre-pivot v1 deck.
//
// Re-shot after the v2 UI pass (phases 1 to 6), which changed enough that the
// previous deck no longer showed the app that exists: the board's row grammar,
// the two-screen create flow, the busy pass, the starter chores a new household
// is offered, and the appearance toggle are all new since the last capture.
//
// Usage: node tools-build-screens-deck.js <shots-dir> <out.pptx>
const pptxgen = require("pptxgenjs");
const path = require("path");

const SHOTS = process.argv[2];
const OUT = process.argv[3];

// The app's own palette, so the deck and the screenshots agree.
const VIOLET = "7C3AED";
const INK = "1A1B2E";
const MUTED = "6B6F80";
const PAPER = "FFFFFF";
const CARD = "F4F5FA";

const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE"; // 13.3 x 7.5 - must be set before any slide
pres.author = "TaskFlow";
pres.title = "TaskFlow - screens from the built app";

const PHONE_H = 5.0;
const PHONE_W = PHONE_H * (1080 / 2400); // 2.25 - never distort the capture
const CARD_W = 2.5;
const CARD_H = 5.4;
const CARD_Y = 1.3;
const XS = [0.825, 3.875, 6.925, 9.975];

function shot(name) {
  return path.join(SHOTS, name);
}

// --- Title ----------------------------------------------------------------
const title = pres.addSlide();
title.background = { color: INK };
title.addText("TaskFlow", {
  isTextBox: true, x: 0.9, y: 1.9, w: 7.2, h: 1.0,
  fontSize: 54, bold: true, color: PAPER, fontFace: "Calibri", margin: 0,
});
title.addText("The household chore board, screen by screen", {
  isTextBox: true, x: 0.9, y: 2.95, w: 7.2, h: 0.6,
  fontSize: 20, color: "CADCFC", fontFace: "Calibri", margin: 0,
});
title.addText(
  "Captured on the emulator at 360dp against a running backend on 3 September 2026, " +
  "after the v2 UI pass. Every screen here is the app as it actually builds and runs.",
  {
    isTextBox: true, x: 0.9, y: 3.75, w: 6.9, h: 1.0,
    fontSize: 14, color: "9AA0B4", fontFace: "Calibri", margin: 0, lineSpacingMultiple: 1.3,
  },
);
title.addImage({
  path: shot("04-board.png"),
  x: 9.6, y: 0.85, w: PHONE_W + 0.35, h: PHONE_H + 0.78,
  shadow: { type: "outer", angle: 90, offset: 6, blur: 18, color: "000000", opacity: 0.45 },
});
title.addNotes(
  "Re-shot after v2 UI phases 1-6. The household is seeded: three flatmates, " +
  "four chores across all three schedule types, three one-off tasks, two " +
  "completed cycles a week apart, one overdue one-off, one person away.",
);

// --- Content slides -------------------------------------------------------
function contentSlide(heading, standfirst, items) {
  const s = pres.addSlide();
  s.background = { color: PAPER };
  s.addText(heading, {
    isTextBox: true, x: 0.8, y: 0.32, w: 11.7, h: 0.55,
    fontSize: 34, bold: true, color: INK, fontFace: "Calibri", margin: 0,
  });
  s.addText(standfirst, {
    isTextBox: true, x: 0.83, y: 0.86, w: 11.7, h: 0.36,
    fontSize: 13, color: MUTED, fontFace: "Calibri", margin: 0,
  });

  // Centre the row when a slide carries fewer than four cards.
  const span = items.length * CARD_W + (items.length - 1) * 0.55;
  const left = (13.3 - span) / 2;

  items.forEach((item, i) => {
    const x = items.length === 4 ? XS[i] : left + i * (CARD_W + 0.55);
    s.addShape(pres.ShapeType.roundRect, {
      x, y: CARD_Y, w: CARD_W, h: CARD_H,
      fill: { color: CARD }, line: { color: "E4E6F0", width: 1 }, rectRadius: 0.12,
    });
    s.addImage({
      path: shot(item.file),
      x: x + (CARD_W - PHONE_W) / 2, y: CARD_Y + 0.18, w: PHONE_W, h: PHONE_H,
      shadow: { type: "outer", angle: 90, offset: 3, blur: 10, color: "1A1B2E", opacity: 0.22 },
    });
    s.addText(item.label, {
      isTextBox: true, x, y: CARD_Y + CARD_H + 0.12, w: CARD_W, h: 0.28,
      fontSize: 12, bold: true, color: INK, fontFace: "Calibri", align: "center", margin: 0,
    });
    s.addText(item.note, {
      isTextBox: true, x: x - 0.12, y: CARD_Y + CARD_H + 0.4, w: CARD_W + 0.24, h: 0.42,
      fontSize: 10, color: MUTED, fontFace: "Calibri", align: "center", margin: 0,
    });
  });
  return s;
}

contentSlide(
  "Getting in",
  "One household, so signing in lands straight on its board. The group list is one Back away.",
  [
    { file: "01-login.png", label: "Sign in", note: "The app's violet, not the wallpaper's" },
    { file: "02-register.png", label: "Create an account", note: "Scrolls clear of the keyboard" },
    { file: "03-dashboard.png", label: "My Groups", note: "Reached by Back from the board" },
    { file: "13-dashboard-menu.png", label: "The person's menu", note: "Quiet hours and appearance: both per person" },
  ],
);

contentSlide(
  "The board is the screen",
  "Yours, Others, Done this cycle. Grouped by whose it is, not by status. Overdue is amber, never red.",
  [
    { file: "04-board.png", label: "The board", note: "Three lines a row: what, when, whose" },
    { file: "05-board-done.png", label: "Done this cycle", note: "Completions sink, with who and when" },
    { file: "06-occurrence-detail.png", label: "A chore, opened", note: "The done-line, the schedule, whose turn" },
    { file: "07-chore-history.png", label: "One chore's history", note: "Two dates, no verdict on either" },
  ],
);

contentSlide(
  "Adding a chore asks two questions",
  "How often, then everything that answer implies. Nothing about days is asked of a one-off.",
  [
    { file: "08-create-step1.png", label: "Step 1: how often", note: "Four short answers, no schedule jargon" },
    { file: "09-create-tooltip.png", label: "The explanation, on ask", note: "Behind a '?', so the choice stays short" },
    { file: "10-create-step2.png", label: "Step 2: repeating", note: "Interval, then the order of turns" },
    { file: "12-create-onetime.png", label: "Step 2: one-off", note: "One person, one date, no rotation" },
  ],
);

contentSlide(
  "Changing it, and removing it",
  "Editing is the same two screens, prefilled. A schedule change re-dates the open turn rather than stranding it.",
  [
    { file: "21-edit-step1.png", label: "Edit, prefilled", note: "The one kind it cannot become is explained" },
    { file: "22-edit-step2.png", label: "The same step 2", note: "Rotation and done-line as they stand" },
    { file: "23-edit-delete.png", label: "Delete lives here", note: "At the end of editing, not on the row" },
    { file: "24-delete-confirm.png", label: "What delete costs", note: "Names the history it takes with it" },
  ],
);

contentSlide(
  "Busy, away, and whose turn",
  "The only two exits from a turn besides doing it. Neither cancels what you owe.",
  [
    { file: "25-pass-confirm.png", label: "Passing it on", note: "Says who is told, and that nobody else is" },
    { file: "26-after-pass.png", label: "After the pass", note: "It moves, and it comes back next cycle" },
    { file: "20-away-dialog.png", label: "Going away", note: "States the rule before you commit" },
    { file: "16-members.png", label: "The household", note: "Away is shown; a pass never is" },
  ],
);

contentSlide(
  "What has happened",
  "A record the household can point at. It counts, it does not rank, and it names no one as late.",
  [
    { file: "19-group-history.png", label: "What's been done", note: "Counts, zeroes included, days away noted" },
    { file: "17-activity.png", label: "Activity", note: "Every group-visible change, in words" },
    { file: "18-group-menu.png", label: "The group's menu", note: "Inviting, the record, and going away" },
    { file: "14-quiet-hours.png", label: "Quiet hours", note: "Held, not dropped: nothing is lost" },
  ],
);

contentSlide(
  "A household that has just started",
  "An empty board teaches nothing, so a new group is offered chores to argue with rather than a blank page.",
  [
    { file: "28-create-group.png", label: "Naming the household", note: "Two fields, one of them optional" },
    { file: "29-starters.png", label: "Five to start with", note: "Renameable, because names are local" },
    { file: "30-starters-deselected.png", label: "Take one off", note: "The count follows what you actually chose" },
    { file: "31-empty-board.png", label: "Or skip entirely", note: "The empty board says what to do next" },
  ],
);

contentSlide(
  "Light or dark, the household's choice",
  "One setting per phone. The overdue amber is picked from the theme, so it stays legible in either.",
  [
    { file: "15-appearance.png", label: "Appearance", note: "System, light or dark; kept on the device" },
    { file: "27-dark-board.png", label: "The board in dark", note: "Amber still amber; still nothing red" },
  ],
);

pres.writeFile({ fileName: OUT }).then(() => console.log("wrote", OUT));
