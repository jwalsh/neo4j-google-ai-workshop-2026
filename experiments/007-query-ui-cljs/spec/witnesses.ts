// The replica's exploration with witnesses in place of properties. Every
// export below should be reported as violated; one that is not names a
// state the exploration did not reach in that run.
//
//   bombadil browser test --headless --time-limit 30s \
//     --output-path scratch/bombadil/witnesses http://127.0.0.1:8378/ spec/witnesses.ts

import { witnesses } from "./query-tool.ts";

export { exploration } from "./replica.ts";

export const runWasClicked = witnesses["runWasClicked"]!;
export const runWasClickedWithText = witnesses["runWasClickedWithText"]!;
export const removeWasClicked = witnesses["removeWasClicked"]!;
export const aFrameWasRunning = witnesses["aFrameWasRunning"]!;
export const aStatusLineWasShown = witnesses["aStatusLineWasShown"]!;
export const aSingleStatementFailed = witnesses["aSingleStatementFailed"]!;
export const aStatementTableWasShown = witnesses["aStatementTableWasShown"]!;
export const aStatementWasAborted = witnesses["aStatementWasAborted"]!;
export const threeFramesAtOnce = witnesses["threeFramesAtOnce"]!;
