// Bombadil specification for the replica (experiments/007-query-ui-cljs),
// on either back end. Every export is a property or an action generator.
//
//   bombadil browser test --headless --time-limit 60s \
//     --output-path scratch/bombadil/replica http://127.0.0.1:8378/ spec/replica.ts

import { weighted } from "@antithesishq/bombadil/browser";
import { scroll } from "@antithesishq/bombadil/browser/defaults/actions";
import * as queryTool from "./query-tool.ts";

export {
  noHttpErrorCodes,
  noUncaughtExceptions,
  noUnhandledPromiseRejections,
  noConsoleErrors,
} from "@antithesishq/bombadil/browser/defaults/properties";

export const editorEmptyAfterRun = queryTool.editorEmptyAfterRun;
export const newestFrameFirst = queryTool.newestFrameFirst;
export const framesLeaveOnlyByRemove = queryTool.framesLeaveOnlyByRemove;
export const runAddsExactlyOneFrame = queryTool.runAddsExactlyOneFrame;
export const everyFrameHasOneStatus = queryTool.everyFrameHasOneStatus("replica");
export const statusLineOnlyForSuccess = queryTool.statusLineOnlyForSuccess("replica");
export const frameStopsRunning = queryTool.frameStopsRunningWithin(5);
export const errorFramesNameTheError = queryTool.errorFramesNameTheError;
export const oneViewSelectedPerFrame = queryTool.oneViewSelectedPerFrame;
export const abortedOnlyAfterAnError = queryTool.abortedOnlyAfterAnError("replica");
export const backendIsStated = queryTool.backendIsStated;

// The fixture's statements (src/query_ui/backend.clj), text it has no
// answer for, and several statements at once.
const statements = [
  "RETURN 1 AS ok",
  "MATCH (n) RETURN count(n) AS nodes",
  "MATCH (n) UNWIND labels(n) AS label RETURN label, count(*) AS count ORDER BY label",
  "MATCH (m:Manager) RETURN m LIMIT 2",
  "MATCH (home {id: 'Home'})-[r]->(member) RETURN home.id AS source, 'Segment' AS sourceLabel, type(r) AS relationship, member.id AS target, 'Product' AS targetLabel",
  "CREATE (:Scratch {name: 'bombadil'})",
  "MATCH (s:Scratch) DETACH DELETE s",
  "CALL fixture.slow()",
  "RETURN 1/0 AS impossible",
  "this is not cypher",
  "RETURN 1 AS ok; MATCH (n) RETURN count(n) AS nodes",
  "RETURN 1 AS ok; RETURN 1/0 AS impossible; RETURN 1 AS ok;thi",
  "RETURN ';' AS semicolon",
  "   ",
  ";",
  ";;",
];

export const exploration = weighted([
  [40, queryTool.enterStatement(statements)],
  [40, queryTool.clickRun],
  [20, queryTool.clickViewTab],
  [10, queryTool.clickRemoveFrame],
  // A click on the textarea focuses it; typeIntoEditor then types.
  [10, queryTool.clickEditor],
  [20, queryTool.typeIntoEditor],
  [5, scroll],
  [1, queryTool.waitWhenBlocked],
]);
