// Rebuilds TaskFlow_Screens.pptx from screenshots taken off the real build.
// Every image in the previous deck was a pre-pivot v1 mock-up; none is reused.
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
pres.layout = "LAYOUT_WIDE"; // 13.3 x 7.5 — must be set before any slide
pres.author = "TaskFlow";
pres.title = "TaskFlow — screens from the built app";

const PHONE_H = 5.0;
const PHONE_W = PHONE_H * (1080 / 2400); // 2.25 — never distort the capture
const CARD_W = 2.5;
const CARD_H = 5.4;
const CARD_Y = 1.3;
const XS = [0.825, 3.875, 6.925, 9.975];

function shot(name) {
  return path.join(SHOTS, name);
}

// ─── Title ───────────────────────────────────────────────────────────────
const title = pres.addSlide();
title.background = { color: INK };
title.addText("TaskFlow", {
  isTextBox: true, x: 0.9, y: 1.9, w: 7.2, h: 1.0,
  fontSize: 54, bold: true, color: PAPER, fontFace: "Calibri", margin: 0,
});
title.addText("The household chore board — screens from the built app", {
  isTextBox: true, x: 0.9, y: 2.95, w: 7.2, h: 0.6,
  fontSize: 20, color: "CADCFC", fontFace: "Calibri", margin: 0,
});
title.addText(
  "Captured on the emulator against a running backend on 2 September 2026. " +
  "Every screen here is the app as it actually builds and runs — no mock-ups.",
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
title.addNotes("Deck rebuilt for phase 4b. The previous version showed the pre-pivot v1 app.");

// ─── Content slides ──────────────────────────────────────────────────────
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
    { file: "01-login.png", label: "Sign in", note: "The violet scheme, not the wallpaper's" },
    { file: "02-register.png", label: "Create an account", note: "Scrolls clear of the keyboard" },
    { file: "03-dashboard.png", label: "My Groups", note: "Reached by Back from the board" },
    { file: "12-dashboard-menu.png", label: "Dashboard menu", note: "Quiet hours lives here — it is per person" },
  ],
);

contentSlide(
  "The board is the screen",
  "Yours, Others, Done — grouped by whose it is, not by status. Overdue is amber, never red.",
  [
    { file: "04-board.png", label: "The board", note: "One overdue row in amber; done rows sink" },
    { file: "05-occurrence-detail.png", label: "A chore, opened", note: "The done-line, the schedule, whose turn" },
    { file: "07-create-repeats.png", label: "Add — repeats", note: "Schedule and rotation order" },
    { file: "08-create-onetime.png", label: "Add — one time", note: "Same form, one question decides" },
  ],
);

contentSlide(
  "Busy, away, and whose turn",
  "The only two exits from an assigned chore besides doing it. Neither cancels the turn you owe.",
  [
    { file: "14-away-dialog.png", label: "Going away", note: "States the rule before you commit" },
    { file: "15-away-banner.png", label: "While you are away", note: "Visible on your own board, with a way back" },
    { file: "16-after-pass.png", label: "After a busy pass", note: "It moves on; it comes back to you next cycle" },
    { file: "09-members.png", label: "The household", note: "Away is shown, never hidden" },
  ],
);

contentSlide(
  "What has happened",
  "A record the household can point at. It counts, it does not rank, and it names no one as late.",
  [
    { file: "06-chore-history.png", label: "One chore's history", note: "Two dates, no verdict on either" },
    { file: "11-group-history.png", label: "What's been done", note: "Join order, zeroes included, days away noted" },
    { file: "10-activity.png", label: "Activity", note: "Every group-visible change, in words" },
    { file: "13-quiet-hours.png", label: "Quiet hours", note: "Held, not dropped — nothing is lost" },
  ],
);

pres.writeFile({ fileName: OUT }).then(() => console.log("wrote", OUT));
