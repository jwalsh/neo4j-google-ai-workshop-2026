# Start here

For someone, or some agent, arriving cold. `CLAUDE.md` has the conventions,
`README.org` the event and the lab checklist, `gmake help` the targets. This
directory holds only what those do not say.

| file | read it for |
|---|---|
| this one | where things stand, what is open, what was never checked |
| [findings.md](findings.md) | what bit, one entry each, with the fix |

## What this repo is for

The owner's notes and own queries from a three-hour cloud lab on Neo4j Aura
and Gemini Enterprise. The labs run in a browser against a hosted
environment, so the first oracle is the lab environment itself: a query is
right when Aura returns what the lab says it should. The second is the
source file: `workshop.form13` computes from the CSV what a correct load
holds, and that check runs against any server, including a local one
(`experiments/008-local-graph-dx/`).

The lab content and slides are Neo4j's and Google's. Notes and the owner's
queries go in; copies of their materials do not. A slide worth keeping is
redrawn as a mermaid file in `notes/diagrams/`, and the original goes in
`resources/`, which git ignores. The one slide image that was committed
early on was removed from the tree on 2026-10-08; it is still in history
(commit `232e0f4`).

## Where things stand (2026-10-08)

- The workshop is over. Labs 1 to 5 and 7 to 9 were done as written; the
  database held Lab 4's keyed load (2,251 companies, 28 managers, 7,381
  OWNS, two node keys), which `bb form13 verify` checks against the file.
  Of the lectures, Gemini Enterprise is ticked, on the owner's word; the
  rest are not.
- Lab 6 was blocked as written: this Aura account had no Document
  Intelligence (finding 22). Its result was reproduced through the LLM
  Graph Builder's API (`experiments/005-graph-builder-api/`), which wrote
  Document, Chunk and 117 entity nodes (two runs) into the same database.
  That subgraph is kept as EDN beside the experiment. Its box stays
  unticked.
- Lab 7 added the marketplace Neo4j Agent to a Gemini Enterprise app, Lab 8
  sent it three prompts, Lab 9 generated an Aura agent. The agent's export
  is `labs/lab-09/`; its second answer was wrong and the first partial
  (findings 26 and 27).
- `neo4j/query` now gives `IN TRANSACTIONS` an auto-commit session
  (finding 29). The relationship file was run that way against Aura once,
  on a loaded database. A load from empty through `form13/load!` was run
  only locally, by experiment 008.
- The lab account, the project and the instance expired at about 21:20 EDT
  on 2026-10-08. `.env` holds dead values and `gmake ping` fails; that is
  expected, not a regression. `examples/` has what the commands printed
  while it was up.
- The lab repository is `neo4j-partners/hands-on-lab-neo4j-and-google`,
  cloned beside this one by ghq and not forked: it is read, not changed.
- Tooling was scaffolded during the session from the shape of
  `babashka-workshop-spectre`: `Makefile`,
  `.env.template`, `.envrc`, `labs/`, this directory.
- `cypher-shell` is not installed on this machine. `gmake ping` and
  `gmake cypher` fall back to Aura's Query API over curl.
- The core tooling is Clojure in `src/workshop/`, run through `bb` tasks:
  the Neo4j Java driver over Bolt and Google's Cloud Storage client. The
  Storage client is unauthenticated, which is enough for the datasets.
- The dataset bucket holds more than the class page names: beside the two
  CSVs and the PDF there are `form10k.zip`, an importer model
  (`neo4j_importer_model_sec-edgar-forms13.json`) and `form13-raw/`, about
  31,000 raw filings. `bb datasets` lists the top level.

## Open

- "Cloud Environment" on the class page sits outside the three assignments.
  In the lab repository it is a directory of empty placeholders, so there is
  nothing to do under it.
- The lab repository calls the second assignment "Query and Explore" in its
  agenda and `assignment-2-moving-data` on disk. `README.org` follows the
  class page, "Moving Data".

## Never verified

- `gmake ping` and `gmake cypher` have run only through the Query API.
  `cypher-shell` is not installed, so that branch of the Makefile has never
  executed.
- The lab-to-component mapping in `notes/architecture.org` is a reading of
  the lab titles. The slide does not number anything.
- `workshop.gcp/project-storage` and `download-object` have never run. The
  first needs application default credentials for the lab account, which
  exists only in the driven browser; nothing on this machine can call the
  lab project's own APIs yet.
- `workshop.form13/load!` against Aura has only taken its already-loaded
  branch. Its loading branch ran on a local server (experiment 008), and
  the relationship file alone ran on Aura through `bb cypher`.
- Experiment 007's Bombadil properties ran against the replica, never the
  real Query tool. Its runner for the real page was written and not run.
- Experiment 006's larger model (gemma4:26b) timed out and was not retried.
- The Lab 9 agent was never called through its endpoint or as an MCP
  server; only the preview chat in the console.
- Whether the marketplace trial reached the lab's subscription.
- `workshop.form13/value-scale` is an inference from the numbers.
- `experiments/001-msa-similarity` shows the Python and the Clojure agree
  with each other. Neither was compared with the notebook it came from.
- `cider-connect-clj` ran in a terminal Emacs against experiment 008's
  nREPL, driven by keys sent through tmux (`notes/screens.org`). Nobody has
  sat at it, and nothing was tried against the root project's REPL.
- `cider-jack-in` was not run in a live Emacs. Checked in batch: the
  `.dir-locals.el` values are accepted as safe and applied, and
  `clojure -M:dev:repl` starts with CIDER's middleware.

## Verified on 2026-10-08

- The Query API answers at `https://<host>/db/<database>/query/v2` with the
  host taken from `NEO4J_URI`, basic auth, and `{"statement": ...}`.
- The six variable names in `.env.template` are the six in the credentials
  file Aura hands out.
- Through the Java driver, against the live instance: `bb ping`,
  `bb databases`, `bb counts` (empty), `bb cypher`, and `(q ...)` from
  `dev/user.clj`.
- The dataset bucket lists and reads with no credentials: `bb datasets`,
  `bb peek`.
- `workshop.neo4j/->clj` on a path of two nodes and a relationship.
- `bb form13` views, and `verify` agreeing with the database after Lab 4.
- `bb status` and `bb dashboard`, with everything up. No check has been
  seen to fail for real: the :down paths are untested against a dead
  instance or a closed browser.
- `bb redact` on three screenshots, one of which had the account email:
  covered, and nothing sensitive read back from the output. It has not met
  a password or a small or low-contrast rendering of the email.
- `LAB_EXPIRES_AT` in `.env` is typed in by hand from the lab's countdown
  and is good to a few minutes.
