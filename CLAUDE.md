# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Attendee notes for a one-day hands-on workshop: "AI Workshop with Neo4j and
Google" (Boston AI Developers Group via AICamp, Google Cambridge, 2026-10-08).
The labs run in a browser against Aura and the Google Cloud console, so
there is nothing to build or test here: the repository is org-mode prose, the
images it links to, and a Makefile of helpers.

The lab instructions are not in this repository. They are in
`neo4j-partners/hands-on-lab-neo4j-and-google`, cloned beside this one under
the ghq root; each lab is `assignment-N-*/lab-N-*/docs/README.md` there. Read
them, do not copy them in.

## Commands

`gmake help` lists the targets. `gmake agenda` and `gmake todos` report
progress from the checklist in `README.org`. `gmake status` shows git state
and which connection variables are set, never their values. `gmake .env`
starts `.env` from `.env.template`; `gmake ping` and
`gmake cypher FILE=labs/x.cypher` run against the instance it names, through
cypher-shell or, without it, Aura's Query API (one statement per file).
`gmake guard-env` fails if `.env` is tracked or in history; run it before a
push.

The Clojure tooling is the core; the Makefile's curl path stays as the check
that needs no JVM. `bb tasks` lists it: `bb ping`, `bb databases`,
`bb counts`, `bb cypher labs/x.cypher` (any number of statements),
`bb datasets`, `bb peek OBJECT [lines]`, `bb test`, `bb nrepl`. Each task
starts `clojure -M:run ...`; babashka is the runner, not the runtime, because
the drivers are JVM libraries. A single test: `clojure -M:test -v
workshop.env-test/parse-dotenv-test`.

## Layout

- `README.org` is the index: event details, the assignment/lab checklist
  copied from the class page, Materials (the lab repository and the dataset
  URLs), and a list of links into `notes/`.
- `notes/` holds one org file per session or topic. Images a note links to go
  in `notes/img/` and are committed alongside it.
- `labs/` holds the owner's own queries and scripts, one file per lab.
- `src/workshop/` is the Clojure core: `env` reads `.env`, `neo4j` wraps the
  official Java driver (`query` returns rows as maps), `gcp` wraps Google's
  Cloud Storage client (unauthenticated by default: the datasets are
  public), `cli` is what `bb` calls. `dev/user.clj` is the REPL namespace;
  `.dir-locals.el` makes `cider-jack-in` start in it.
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

The lab checklist in `README.org` uses org TODO headings per assignment and
checkboxes per lecture or lab. Tick the checkbox; flip the heading to DONE
only when every box under it is ticked.

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
- `gmake`, not `make`.
