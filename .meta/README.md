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

- The workshop is in progress. Labs 1 to 3 are done: the lab account is
  signed in to a Chromium with its own empty profile, one AuraDB
  Professional instance is RUNNING in us-east1 with its credentials in
  `.env`, and `SHOW DATABASES` returns `neo4j` and `system` from the
  console's Query tool and from `gmake cypher` alike. The database is empty.
  The lecture in Assignment 1 is unticked: it is slides, and the owner's.
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

## Verified on 2026-10-08

- The Query API answers at `https://<host>/db/<database>/query/v2` with the
  host taken from `NEO4J_URI`, basic auth, and `{"statement": ...}`.
- The six variable names in `.env.template` are the six in the credentials
  file Aura hands out.
