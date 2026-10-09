// Bombadil specification for Aura's own Query tool, built from the same
// module as the replica's. What differs is said in query-tool.ts beside
// each property: the real page has no hook this experiment knows for a
// running statement or for a single statement that failed.
//
// The action generators are the boundary. Nothing here can click outside
// [data-testid="query-app"]: no default `clicks`, no navigation, no key
// presses (Tab would walk focus out of the tool). Within it the only
// things clicked are Run, a frame's Graph/Table/Raw tabs and a frame's
// "Remove frame". Text reaches the editor through one custom action.
//
// Run by src/query_ui/bombadil.clj (clojure -M:bombadil aura), which starts
// a headless browser on a copy of the lab profile.

import { weighted } from "@antithesishq/bombadil/browser";
import * as queryTool from "./query-tool.ts";

export {
  noUncaughtExceptions,
  noUnhandledPromiseRejections,
  noConsoleErrors,
} from "@antithesishq/bombadil/browser/defaults/properties";

export const staysInQueryTool = queryTool.staysInQueryTool;
export const editorEmptyAfterRun = queryTool.editorEmptyAfterRun;
export const newestFrameFirst = queryTool.newestFrameFirst;
export const framesLeaveOnlyByRemove = queryTool.framesLeaveOnlyByRemove;
export const everyFrameHasOneStatus = queryTool.everyFrameHasOneStatus("aura");
export const statusLineOnlyForSuccess = queryTool.statusLineOnlyForSuccess("aura");
export const oneViewSelectedPerFrame = queryTool.oneViewSelectedPerFrame;
export const abortedOnlyAfterAnError = queryTool.abortedOnlyAfterAnError("aura");

// Reads, two failures, two multi-statement runs, and one write with its
// own undo. The write is confined to a label nothing in the labs uses.
const statements = [
  "RETURN 1 AS ok",
  "MATCH (n) RETURN count(n) AS nodes",
  "MATCH (m:Manager) RETURN m.managerName AS name LIMIT 3",
  "MATCH (m:Manager)-[o:OWNS]->(c:Company) RETURN m, o, c LIMIT 5",
  "RETURN 1/0 AS impossible",
  "this is not cypher",
  "RETURN 1 AS first; RETURN 2 AS second",
  "RETURN 1 AS first; RETURN 1/0 AS impossible; RETURN 3 AS third",
  "CREATE (:BombadilScratch {createdAt: timestamp()})",
  "MATCH (s:BombadilScratch) DETACH DELETE s",
];

export const exploration = weighted([
  [40, queryTool.enterStatement(statements)],
  [40, queryTool.clickRun],
  [20, queryTool.clickViewTab],
  [10, queryTool.clickRemoveFrame],
  [1, queryTool.waitWhenBlocked],
]);
