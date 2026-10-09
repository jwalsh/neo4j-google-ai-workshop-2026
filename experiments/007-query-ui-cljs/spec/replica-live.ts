// The replica on its live back end: the same specification with a longer
// bound on how long a frame may run. The instance answered in 0.4 to 4
// seconds per statement from here, the server runs four at a time, and
// the page gives up on a run after 30 seconds.
//
//   bombadil browser test --headless --time-limit 45s \
//     --output-path scratch/bombadil/replica-live http://127.0.0.1:8379/ spec/replica-live.ts

import * as queryTool from "./query-tool.ts";

export {
  noHttpErrorCodes,
  noUncaughtExceptions,
  noUnhandledPromiseRejections,
  noConsoleErrors,
  editorEmptyAfterRun,
  newestFrameFirst,
  framesLeaveOnlyByRemove,
  runAddsExactlyOneFrame,
  everyFrameHasOneStatus,
  statusLineOnlyForSuccess,
  errorFramesNameTheError,
  oneViewSelectedPerFrame,
  abortedOnlyAfterAnError,
  backendIsStated,
  exploration,
} from "./replica.ts";

export const frameStopsRunning = queryTool.frameStopsRunningWithin(40);
