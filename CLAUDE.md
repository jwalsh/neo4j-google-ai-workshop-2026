# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Attendee notes for a one-day hands-on workshop: "AI Workshop with Neo4j and
Google" (Boston AI Developers Group via AICamp, Google Cambridge, 2026-10-08).
The labs run in a browser against Aura and the Google Cloud console. The
repository is the notes in org-mode, the images and diagrams they link to,
and Clojure tooling that does as much of each lab as can be done by code:
`bb test` runs its tests, `bb labs` and `bb audit` say where things stand.

The lab instructions are not in this repository. They are in
`neo4j-partners/hands-on-lab-neo4j-and-google`, cloned beside this one under
the ghq root; each lab is `assignment-N-*/lab-N-*/docs/README.md` there. Read
them, do not copy them in.

## Commands

`gmake help` lists the targets. `gmake agenda` and `gmake todos` report
progress from the checklist in `notes/assignments.org`. `gmake status` shows git state
and which connection variables are set, never their values. `gmake .env`
starts `.env` from `.env.template`; `gmake ping` and
`gmake cypher FILE=labs/x.cypher` run against the instance it names, through
cypher-shell or, without it, Aura's Query API (one statement per file).
`gmake data/<name>` fetches a workshop dataset into `data/` as a real file
target: it runs once, and a failed transfer leaves nothing behind.
`gmake guard-env` fails if `.env` is tracked or in history; run it before a
push.

The Clojure tooling is the core; the Makefile's curl path stays as the check
that needs no JVM. `bb tasks` lists it: `bb ping`, `bb databases`,
`bb counts`, `bb cypher labs/x.cypher` (any number of statements),
`bb datasets`, `bb peek OBJECT [lines]`, `bb form13 [view]`, `bb test`,
`bb repl`, and
`bb redact IN.png OUT.png` (cover emails and `.env` secrets in a
screenshot), `bb status` (every connection check once) with `bb dashboard` (pages on
127.0.0.1:8377, loopback only: `/` an overview of every source in
`agents/registry.edn`, `/map`, `/data`, `/ask`, `/traces`, and `/config`
for the connection checks), `bb ask [--agent ID] QUESTION` (a local model chooses one of an agent's
Cypher templates; `agents/registry.edn` ties each agent to its own Neo4j),
`bb local` (an embedded Neo4j on localhost:7688 for the filings),
`bb local-papers` and `bb papers-load` (a second one on 7689 for the papers),
`bb mbta fetch|network`, `bb local-mbta` and `bb mbta-load` (a fourth on 7691
for the MBTA's rail network from its GTFS feed, kept in the XDG cache),
`bb local-books` and `bb books-load` (a third on 7690 for a shelf of books;
`bb books catalog|fetch|outline|index` fill the cache it reads (`index` is each book's back-of-book index, as concepts); the library's
catalog and PDFs stay in the XDG cache and are never committed),
`bb annotate where|list|add BOOK PAGE TEXT|merge DIR|load` (notes on those
books, one file each under `$XDG_DATA_HOME/workshop-books/annotations`, never
committed; a subagent or worktree that must not write the owner's notes gets
its own `BOOKS_ANNOTATIONS` and `WORKSHOP_AUTHOR`, merged back afterwards), and `bb papers where|list|fetch|add DIR|index` (arXiv papers a `.bib`
names or a directory holds, kept in the XDG cache; this one runs in babashka itself, from `bb/`).
`bb ports [show|claim SERVICE...|release]` asks portclaim (`PORTCLAIM` names
the registry; no address is written in this repository) who holds this
checkout's ports. In a git worktree `bb ports claim books` takes a slot of
ten ports between 11000 and 11999, the one a checksum of the branch name
picks or the next free one, and writes the ignored `.env.ports`, which
`workshop.env` and the `local-*` tasks read, so a worktree runs its own
servers; the main checkout keeps 8377 and 7688-7691. Never hardcode a port
in new code: read `.env.ports`.
`WORKSHOP_ENV=local-graph.env` points every command at the local server in
place of `.env`. `OLLAMA_URL` and `OLLAMA_MODEL` choose the model; the
default is this machine. Do not run a model on this machine unless asked:
set `OLLAMA_URL` to a host with room. Each task
starts `clojure -M:run ...`; babashka is the runner, not the runtime, because
the drivers are JVM libraries. A single test: `clojure -M:test -v
workshop.env-test/parse-dotenv-test`.

## Layout

- `README.org` is about exploring graphs from a REPL, not about the day: how
  to run it, the four ways in, the graphs, the model's part, then a short
  section on the workshop (event, Materials) and a list of links into
  `notes/`. The assignment/lab checklist copied from the class page is
  `notes/assignments.org`; the list of experiments is
  `experiments/README.org`.
- `notes/` holds one org file per session or topic. Images a note links to go
  in `notes/img/` and are committed alongside it.
- `labs/` holds the queries run, one file per lab, or a directory of
  numbered files when a lab has several steps (`labs/lab-04/`). A statement
  taken from the lab says so in its header.
- `src/workshop/` is the Clojure core: `env` reads `.env`, `neo4j` wraps the
  official Java driver (`query` returns rows as maps), `gcp` wraps Google's
  Cloud Storage client (unauthenticated by default: the datasets are
  public), `form13` explores Lab 4's file in memory and checks a load
  against it; `status` holds one check per dependency and `dashboard` renders
  them; `telemetry` holds spans in memory for `/traces`; `ollama` is the
  one model client; `agent` answers a question by choosing a template;
  `papers` reads the paper cache; `ports` is this checkout's ports and their
claims in portclaim; `annotations` is the store of notes on books
and its projection into the books graph; `cli` is what `bb` calls. `dev/user.clj` is the REPL namespace;
  `.dir-locals.el` makes `cider-jack-in` start in it. `workshop.el` at the
  root is the Emacs side: `workshop-repl` lays out a page beside the REPL,
  `workshop-graph` switches graphs by evaluating `(user/use-env! FILE)`. A
  new data source gets an entry in its `workshop-graphs`. It also defines
  `workshop-annotate` and `workshop-annotations` (notes on books, through
`user/annotate!`) and `org-babel-execute:cypher`: a `#+begin_src cypher :graph NAME` block in an
  org file runs through that REPL (`user/table`), as in
  `notes/transit-queries.org`. `dev/explore.clj` is
  `(comment ...)` blocks per lab, evaluated a form at a time; a new lab
  gets a block there.
- `prompts/` holds every prompt a lab has a person type into a model, one
  file each, named `lab-NN-[order-]what-it-does.txt`, indexed in
  `prompts/README.org`. Code reads the prompt from the file. Queries are in
  `labs/`, prompts are here, and both are committed: they are what makes a
  lab repeatable.
- `resources/` is git-ignored and holds other people's material kept for
  reference: the lab's step images, slides. Nothing in it is committed. A
  slide worth keeping is redrawn as mermaid in `notes/diagrams/` and the
  note says which slide it came from.
- `src/workshop/labs.clj` is each lab as a step returning
  `{:lab :status :evidence :how}`; `src/workshop/audit.clj` checks the notes
  against the repository and the database. Both read only.
  `dev/labs_explore.clj` is the page to evaluate. `.meta/audit-YYYY-MM-DD.md`
  is a dated run of the audit, and its numbered discrepancies are a to-do
  list, not a record.
- `experiments/NNN-name/` is for exploration in anything else (Python, say).
  What survives is rewritten into `src/workshop/` in Clojure, ClojureScript
  or babashka.
- `.meta/README.md` says where things stand and what was never verified;
  `.meta/findings.md` takes one numbered entry per thing that bit. Read the
  first on arrival and keep its "Never verified" list honest.

A new note is not done until `README.org` links to it under "Notes", with a
one-line description of what is in it.

## The shape of the workshop

The nine labs map onto one architecture, all of it in Google Cloud; read
`notes/architecture.org` before filing anything by lab number. The mapping
below is a reading of the lab titles, not something the slide states.

- Cloud Storage holds source data, loaded into Neo4j AuraDB Professional.
- Around the database: Query (Lab 4), Bloom (Lab 5), Document Intelligence
  (Lab 6), an Aura Agent (Lab 9), and Graph Analytics (no lab).
- Gemini Enterprise holds a Neo4j Agent (Labs 7 and 8) that talks to the
  database and to a Gemini Enterprise app.
- Labs 1 to 3 are sign-in, deploy and connect.

The lab checklist in `notes/assignments.org` uses org TODO headings per assignment and
checkboxes per lecture or lab. Tick the checkbox; flip the heading to DONE
only when every box under it is ticked.

## Lab credentials are not secrets

The owner's rule, stated more than once on 2026-10-08: these are timeboxed
lab credentials and pedagogy beats security here. Read them, print them,
type them into forms, pass them to tools and hosted apps the lab needs.
Do not mask them in output, do not warn about them, do not stop to ask.

What still holds, and it is the whole list: they are not committed (`.env`
and `resources/` stay ignored, `gmake guard-env` before a commit), and a
screenshot that goes into the repository is redacted first. The owner's own
Google account is a different matter and stays out of lab work.

## Conventions

- Org-mode, not Markdown, for everything except this file and `.meta/`.
- Notes record what the source said and say which source ("From the slide
  ...", "From the class page ..."). Observations that are not in the source
  are stated as such, e.g. "No lab is named for Graph Analytics".
- Commits are conventional, scoped by area, and small: `docs: ...` for the
  README, `docs(notes): ...` for a note. An image and the note that links to
  it may be separate commits.
- `.gitignore` already excludes `.env`, `.envrc.local`, `*.dump` and `/data/`.
  Aura credentials, database dumps and workshop datasets stay local; code in
  `labs/` reads connection details from the environment.
- Take screenshots as the work goes: the Cloud console, Aura, and any Emacs
  session. The lab environment expires, and the images are what is left.
  Every screenshot goes through `bb redact IN OUT` and is then looked at
  before it is added to `notes/img/`; a note links it, a lab's own note
  when it belongs to a lab, `notes/screens.org` otherwise.
- `gmake`, not `make`.
