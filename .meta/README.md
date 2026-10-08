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
environment, so the oracle here is the lab environment itself: a query is
right when Aura returns what the lab says it should. Nothing local can stand
in for that.

The lab content and slides are Neo4j's and Google's. Notes and the owner's
queries go in; copies of their materials do not. One slide image is in
`notes/img/`; ask before adding another.

## Where things stand (2026-10-08)

- The workshop is in progress. Labs 1 to 4 are done: the lab account is
  signed in to a Chromium with its own empty profile, one AuraDB
  Professional instance is RUNNING in us-east1 with its credentials in
  `.env`, and the database holds Lab 4's keyed load: 2,251 companies, 28
  managers, 7,381 OWNS, two node-key constraints. `bb form13 verify` checks
  that against the file. The lectures are unticked: slides, and the owner's.
- Lab 5 is half done. Bloom opened and "Show me a graph" drew a scene; the
  search and the Degree Centrality steps were not driven (finding 17) and
  are the owner's to click.
- The lab account, the project and the instance expire with the lab, about
  five hours from 16:23 EDT. After that `.env` holds dead values and
  `gmake ping` fails; that is expected, not a regression.
- The lab repository is `neo4j-partners/hands-on-lab-neo4j-and-google`,
  cloned beside this one by ghq and not forked: it is read, not changed.
- Tooling was scaffolded during the session from the shape of
  `babashka-workshop-spectre`, less its babashka and Emacs parts: `Makefile`,
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
- `workshop.form13/load!` has only taken its already-loaded branch: the
  load itself was run from the console. The files it would run are the
  ones that were run there.
- `workshop.form13/value-scale` is an inference from the numbers.
- `experiments/001-msa-similarity` shows the Python and the Clojure agree
  with each other. Neither was compared with the notebook it came from.
- `cider-jack-in` was not run in a live Emacs. Checked in batch: the
  `.dir-locals.el` values are accepted as safe and applied, and
  `clojure -M:dev:nrepl` starts with CIDER's middleware.

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
