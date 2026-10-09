// What the replica and Aura's Query tool have in common, as Bombadil sees
// it: one extractor over the shared data-testid / role / aria-label hooks,
// properties built from it, and action generators that never produce a
// click outside [data-testid="query-app"].
//
// This module is imported by replica.ts and aura.ts. Bombadil requires a
// top-level specification to export only properties and action generators,
// so the builders live here and the two files choose among them.

import { always, eventually, next, now, type Formula } from "@antithesishq/bombadil";
import {
  actions,
  extract,
  getFingerprint,
  registerCustomAction,
  type ActionTemplate,
  type Fingerprint,
} from "@antithesishq/bombadil/browser";

export type Target = "replica" | "aura";

// ---------------------------------------------------------------- extractor

type StatementStatus = "success" | "error" | "aborted" | "other";

type FrameFacts = {
  frameId: string | null;
  command: string;
  hasResultsPanel: boolean;
  statusBarTexts: string[];
  spinnerCount: number;
  frameErrorCount: number;
  frameErrorCodes: string[];
  errorIconCount: number;
  abortedIconCount: number;
  statementStatuses: StatementStatus[];
  viewSelectors: { selectedCount: number; selectedDisabledCount: number; tabCount: number }[];
};

type ClickTarget = { fingerprint: Fingerprint; point: { x: number; y: number } };

type PageFacts = {
  path: string;
  queryAppPresent: boolean;
  overlayOpen: boolean;
  editorPresent: boolean;
  editorFocused: boolean;
  editorText: string;
  backendName: string | null;
  backendAttribute: string | null;
  frames: FrameFacts[];
  runTargets: ClickTarget[];
  editorTargets: ClickTarget[];
  viewTabTargets: ClickTarget[];
  removeTargets: ClickTarget[];
};

const emptyPage: PageFacts = {
  path: "",
  queryAppPresent: false,
  overlayOpen: false,
  editorPresent: false,
  editorFocused: false,
  editorText: "",
  backendName: null,
  backendAttribute: null,
  frames: [],
  runTargets: [],
  editorTargets: [],
  viewTabTargets: [],
  removeTargets: [],
};

const editorSelector = '[role="textbox"][aria-label="Cypher Editor"]';

function readEditorText(editor: Element | null): string {
  if (!editor) return "";
  if (editor instanceof HTMLTextAreaElement) return editor.value;
  // CodeMirror: the placeholder is a contenteditable=false child of the
  // content element, and is not text the user typed.
  const copy = editor.cloneNode(true) as Element;
  copy.querySelectorAll('[contenteditable="false"], .cm-placeholder').forEach((node) => node.remove());
  return copy.textContent ?? "";
}

function statementStatus(row: Element): StatementStatus {
  if (row.querySelector('[data-testid="error-icon"]')) return "error";
  if (row.querySelector('[data-testid="aborted-icon"]')) return "aborted";
  if (row.querySelector('[data-testid="success-icon"]')) return "success";
  return "other";
}

// A point to click, only if the element is drawn, in the viewport, and is
// what is actually under that point: an overlay, a menu or a tour covering
// it means no click is offered.
function clickTarget(window: Window, document: Document, queryApp: Element, element: Element): ClickTarget | null {
  const style = window.getComputedStyle(element);
  if (style.display === "none" || style.visibility === "hidden") return null;
  const rectangle = element.getBoundingClientRect();
  if (rectangle.width <= 0 || rectangle.height <= 0) return null;
  const point = { x: rectangle.left + rectangle.width / 2, y: rectangle.top + rectangle.height / 2 };
  if (point.x < 0 || point.y < 0 || point.x > window.innerWidth || point.y > window.innerHeight) return null;
  const elementAtPoint = document.elementFromPoint(point.x, point.y);
  if (!elementAtPoint) return null;
  if (!queryApp.contains(elementAtPoint)) return null;
  if (!(element === elementAtPoint || element.contains(elementAtPoint))) return null;
  return { fingerprint: getFingerprint(element), point };
}

const page = extract((state): PageFacts => {
  // An extractor that throws ends the run, so nothing here may.
  try {
    const document = state.document;
    const window = state.window;
    const queryApp = document.querySelector('[data-testid="query-app"]');
    if (!queryApp) return { ...emptyPage, path: window.location.pathname };

    const targetsFor = (selector: string): ClickTarget[] =>
      Array.from(queryApp.querySelectorAll(selector))
        .map((element) => clickTarget(window, document, queryApp, element))
        .filter((target): target is ClickTarget => target !== null);

    const editor = queryApp.querySelector(editorSelector);
    const frames = Array.from(queryApp.querySelectorAll('[data-testid="frame"]')).map(
      (frame): FrameFacts => ({
        frameId: frame.getAttribute("data-frame-id"),
        command: frame.querySelector('[data-testid="frame-command"]')?.textContent ?? "",
        hasResultsPanel: frame.querySelector('[data-testid="results-panel-content"]') !== null,
        statusBarTexts: Array.from(
          frame.querySelectorAll('[data-testid="simple-frame-status-bar-right"]'),
        ).map((statusBar) => (statusBar.textContent ?? "").trim()),
        spinnerCount: frame.querySelectorAll('[data-testid="frame-spinner"]').length,
        frameErrorCount: frame.querySelectorAll('[data-testid="frame-error"]').length,
        frameErrorCodes: Array.from(frame.querySelectorAll('[data-testid="frame-error"]')).map((frameError) =>
          (frameError.querySelector("strong")?.textContent ?? "").trim(),
        ),
        errorIconCount: frame.querySelectorAll('[data-testid="error-icon"]').length,
        abortedIconCount: frame.querySelectorAll('[data-testid="aborted-icon"]').length,
        statementStatuses: Array.from(frame.querySelectorAll('[data-testid="multi-statement"]')).map(
          statementStatus,
        ),
        viewSelectors: Array.from(
          frame.querySelectorAll('[role="tablist"][aria-label="Frame view selector"]'),
        ).map((selector) => {
          const tabs = Array.from(selector.querySelectorAll('[role="tab"]'));
          const selectedTabs = tabs.filter((tab) => tab.getAttribute("aria-selected") === "true");
          return {
            tabCount: tabs.length,
            selectedCount: selectedTabs.length,
            selectedDisabledCount: selectedTabs.filter(
              (tab) => tab.getAttribute("aria-disabled") === "true",
            ).length,
          };
        }),
      }),
    );

    return {
      path: window.location.pathname,
      queryAppPresent: true,
      overlayOpen:
        document.querySelector('[role="dialog"], [role="menu"], [role="listbox"], [aria-modal="true"]') !== null,
      editorPresent: editor !== null,
      editorFocused: editor !== null && document.activeElement === editor,
      editorText: readEditorText(editor),
      backendName: document.querySelector('[data-testid="backend-name"]')?.textContent ?? null,
      backendAttribute: queryApp.getAttribute("data-backend"),
      frames,
      runTargets: targetsFor('[data-testid="run-button"]'),
      editorTargets: targetsFor(editorSelector),
      viewTabTargets: targetsFor(
        '[data-testid="frame"] [role="tablist"][aria-label="Frame view selector"] [role="tab"]',
      ),
      removeTargets: targetsFor('[data-testid="frame"] button[aria-label="Remove frame"]'),
    };
  } catch {
    return emptyPage;
  }
});

type LastAction =
  | { kind: "none" }
  | { kind: "click"; testId: string | null; accessibleName: string | null }
  | { kind: "custom"; name: string }
  | { kind: "other" };

// Bombadil 0.7.8 types a fingerprint as { testId, accessibleName, ... } and
// that is what getFingerprint returns, but the fingerprint inside
// state.lastAction arrives as { test_id, accessible_name, ... } with null
// fields left out. Both spellings are read.
const lastAction = extract((state): LastAction => {
  const action = state.lastAction;
  if (action === null || action === undefined) return { kind: "none" };
  if (typeof action === "object" && "Click" in action) {
    const fingerprint = action.Click.fingerprint as unknown as Record<string, string | null | undefined>;
    return {
      kind: "click",
      testId: fingerprint["test_id"] ?? fingerprint["testId"] ?? null,
      accessibleName: fingerprint["accessible_name"] ?? fingerprint["accessibleName"] ?? null,
    };
  }
  if (typeof action === "object" && "Custom" in action) {
    return { kind: "custom", name: action.Custom.name };
  }
  return { kind: "other" };
});

const lastActionWasRun = () => {
  const action = lastAction.current;
  return action.kind === "click" && action.testId === "run-button";
};

const lastActionWasRemove = () => {
  const action = lastAction.current;
  return action.kind === "click" && action.accessibleName === "Remove frame";
};

const withoutWhitespace = (text: string) => text.replace(/\s+/g, "");

// --------------------------------------------------------------- properties

// After a click on Run, the editor is empty. True of a blank editor too.
export const editorEmptyAfterRun: Formula = always(
  () => !lastActionWasRun() || page.current.editorText.trim() === "",
);

// Text in the editor, then Run: the top frame's command is that text.
export const newestFrameFirst: Formula = always(() => {
  const typedText = withoutWhitespace(page.current.editorText);
  return now(() => typedText !== "")
    .and(next(lastActionWasRun))
    .implies(next(() => withoutWhitespace(page.current.frames[0]?.command ?? "").includes(typedText)));
});

// The number of frames goes down only by a click on "Remove frame", and
// then by one.
export const framesLeaveOnlyByRemove: Formula = always(() => {
  const frameCountBefore = page.current.frames.length;
  return next(
    () =>
      page.current.frames.length >= frameCountBefore ||
      (lastActionWasRemove() && page.current.frames.length === frameCountBefore - 1),
  );
});

// Replica only. Aura caps the stream, so a Run there may add a frame and
// drop the oldest in one step.
export const runAddsExactlyOneFrame: Formula = always(() => {
  const frameCountBefore = page.current.frames.length;
  const editorHadText = page.current.editorText.trim() !== "";
  return next(lastActionWasRun).implies(
    next(() => page.current.frames.length === frameCountBefore + (editorHadText ? 1 : 0)),
  );
});

// Every frame with a results panel is in exactly one state. On the replica
// the states are running, success, error and the multi-statement table. On
// Aura only two of those have a hook the snapshot shows (the status line
// and the table), so the property there is "not both".
export function everyFrameHasOneStatus(target: Target): Formula {
  return always(() =>
    page.current.frames
      .filter((frame) => frame.hasResultsPanel)
      .every((frame) => {
        const statesShown =
          (frame.spinnerCount > 0 ? 1 : 0) +
          (frame.statusBarTexts.length > 0 ? 1 : 0) +
          (frame.frameErrorCount > 0 ? 1 : 0) +
          (frame.statementStatuses.length > 0 ? 1 : 0);
        return target === "replica" ? statesShown === 1 : statesShown <= 1;
      }),
  );
}

// A status line is one line, reads as the Query tool words it, and sits in
// a frame with no error, no aborted statement and no statement table.
export function statusLineOnlyForSuccess(target: Target): Formula {
  const wording =
    target === "replica"
      ? /^Started streaming \d+ records? after \d+ ms and completed after \d+ ms\.$/
      : /\d+ ms/;
  return always(() =>
    page.current.frames
      .filter((frame) => frame.statusBarTexts.length > 0)
      .every(
        (frame) =>
          frame.statusBarTexts.length === 1 &&
          wording.test(frame.statusBarTexts[0]!) &&
          frame.errorIconCount === 0 &&
          frame.abortedIconCount === 0 &&
          frame.frameErrorCount === 0 &&
          frame.statementStatuses.length === 0,
      ),
  );
}

// Replica only: it has the hook for a running statement. A frame that is
// running stops running within the bound, whatever its outcome. Five
// seconds suits the fixture, whose slowest answer takes 400 ms; the live
// back end needs more (README, Log 9).
export function frameStopsRunningWithin(seconds: number): Formula {
  return always(() => {
    const runningFrameIds = page.current.frames
      .filter((frame) => frame.spinnerCount > 0)
      .map((frame) => frame.frameId);
    return eventually(() =>
      page.current.frames.every((frame) => frame.spinnerCount === 0 || !runningFrameIds.includes(frame.frameId)),
    ).within(seconds, "seconds");
  });
}

// Replica only (frame-error is its hook): a failed statement says what
// failed. An error with no code is a frame that failed to explain itself.
export const errorFramesNameTheError: Formula = always(() =>
  page.current.frames.every((frame) => frame.frameErrorCodes.every((errorCode) => errorCode !== "")),
);

// Each view selector has exactly one selected tab, and it is not a
// disabled one.
export const oneViewSelectedPerFrame: Formula = always(() =>
  page.current.frames.every((frame) =>
    frame.viewSelectors.every(
      (selector) => selector.selectedCount === 1 && selector.selectedDisabledCount === 0,
    ),
  ),
);

// In the multi-statement table, a statement is aborted only after one that
// failed. On the replica, where every status has a hook, everything after
// the failure is aborted and nothing before it is.
export function abortedOnlyAfterAnError(target: Target): Formula {
  return always(() =>
    page.current.frames.every((frame) => {
      const firstError = frame.statementStatuses.indexOf("error");
      return frame.statementStatuses.every((status, position) => {
        if (status === "aborted") return firstError !== -1 && firstError < position;
        if (target === "replica") {
          if (status === "other") return false;
          if (firstError !== -1 && position > firstError) return false;
        }
        return true;
      });
    }),
  );
}

// Replica only: the page says which back end it is on, in two places that
// agree.
export const backendIsStated: Formula = always(
  () =>
    (page.current.backendName === "fixture" || page.current.backendName === "live") &&
    page.current.backendName === page.current.backendAttribute,
);

// Aura only: the test never leaves the Query tool.
export const staysInQueryTool: Formula = always(() => /\/query(\/|$)/.test(page.current.path));

// ---------------------------------------------------------------- witnesses

// Not properties of the page: each of these is expected to be VIOLATED.
// A violation shows that the run reached the state the real properties
// talk about, so that "no violations" above is not vacuous. They are
// exported by witnesses.ts only.
const anyFrame = (predicate: (frame: FrameFacts) => boolean) => page.current.frames.some(predicate);

export const witnesses: Record<string, Formula> = {
  runWasClicked: always(() => !lastActionWasRun()),
  runWasClickedWithText: always(() => {
    const editorHadText = page.current.editorText.trim() !== "";
    return next(() => !(editorHadText && lastActionWasRun()));
  }),
  removeWasClicked: always(() => !lastActionWasRemove()),
  aFrameWasRunning: always(() => !anyFrame((frame) => frame.spinnerCount > 0)),
  aStatusLineWasShown: always(() => !anyFrame((frame) => frame.statusBarTexts.length > 0)),
  aSingleStatementFailed: always(() => !anyFrame((frame) => frame.frameErrorCount > 0)),
  aStatementTableWasShown: always(() => !anyFrame((frame) => frame.statementStatuses.length > 0)),
  aStatementWasAborted: always(() => !anyFrame((frame) => frame.statementStatuses.includes("aborted"))),
  threeFramesAtOnce: always(() => page.current.frames.length < 3),
};

// ------------------------------------------------------------------ actions

const setEditorText = registerCustomAction(
  "setEditorText",
  async (document, _window, statementText: string) => {
    const editor = document.querySelector(`[data-testid="query-app"] ${editorSelector}`);
    if (!editor) throw new Error("no Cypher editor inside query-app");
    if (editor instanceof HTMLTextAreaElement) {
      editor.focus();
      editor.value = statementText;
      editor.dispatchEvent(new Event("input", { bubbles: true }));
      return;
    }
    // CodeMirror. A click does not focus it (finding 15); focus() does, and
    // insertText replaces the selection without key events.
    (editor as HTMLElement).focus();
    document.execCommand("selectAll", false);
    document.execCommand("insertText", false, statementText);
  },
);

// The generators offer nothing but "Wait" unless the page is the Query
// tool with nothing open over it.
const mayAct = () =>
  page.current.queryAppPresent && /\/query(\/|$)|^\/$/.test(page.current.path) && !page.current.overlayOpen;

const clicksOn = (targets: () => ClickTarget[]) =>
  actions((): ActionTemplate[] => (mayAct() ? targets().map((target) => ({ Click: target })) : []));

export const clickRun = clicksOn(() => page.current.runTargets);
export const clickEditor = clicksOn(() => page.current.editorTargets);
export const clickViewTab = clicksOn(() => page.current.viewTabTargets);
export const clickRemoveFrame = clicksOn(() => page.current.removeTargets);

export function enterStatement(statements: string[]) {
  return actions((): ActionTemplate[] =>
    mayAct() && page.current.editorPresent ? statements.map((statement) => setEditorText(statement)) : [],
  );
}

export const waitWhenBlocked = actions((): ActionTemplate[] => (mayAct() ? [] : ["Wait"]));

// Text into the focused editor, as key strokes. This stands in for
// Bombadil's default `inputs` generator, which also presses Backspace, Tab,
// Enter and Escape. With 0.7.8 here, five runs stalled straight after a
// PressKey: the driver timed out on its next evaluation, twice running on
// past the time limit (README, Log 7). So no key presses: Tab, Enter,
// Backspace and Escape are not explored.
export const typeIntoEditor = actions((): ActionTemplate[] => {
  if (!mayAct() || !page.current.editorFocused) return [];
  const delayMillis: [number, number] = [1, 10];
  return [
    { TypeText: { text: { Text: [1, 40] }, delayMillis } },
    { TypeText: { text: { Regexp: "(RETURN|MATCH|;| |\\d|'|\\(n\\)){1,8}" }, delayMillis } },
  ];
});
