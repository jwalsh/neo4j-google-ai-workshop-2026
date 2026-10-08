# The checklist the progress targets read. Org headings `*** TODO name` with
# `- [ ]` boxes under them.
CHECKLIST ?= README.org
# A Cypher file for `gmake cypher`. One statement per file when cypher-shell
# is missing and the Query API is used instead.
FILE      ?=
SHELL := bash
# pipefail: without it `curl ... | jq` exits 0 when curl could not connect,
# and ping reports a dead database as a pass.
.SHELLFLAGS := -o pipefail -c

# The workshop's public datasets. `gmake data/<name>` fetches one.
DATASET_URL ?= https://storage.googleapis.com/neo4j-datasets/hands-on-lab

.DEFAULT_GOAL := help
.DELETE_ON_ERROR:

.PHONY: help deps todos agenda status ping cypher guard-env

# .env holds the Aura credentials and is loaded by direnv. A recipe that needs
# them loads the file itself too, so the target works in a shell direnv has
# not touched. Values already in the environment are overwritten by the file.
LOAD_ENV = set -a; [ -f .env ] && . ./.env; set +a

# The Query API host is the URI's host: neo4j+s://abc.databases.neo4j.io
# is served at https://abc.databases.neo4j.io/db/<database>/query/v2.
QUERY_API_URL = https://$${NEO4J_URI\#*://}/db/$${NEO4J_DATABASE:-neo4j}/query/v2

# Runs the statement on stdin. cypher-shell when it is installed, else the
# Query API through curl, with the password passed on stdin and not in argv.
# The API answers 200 with an errors array when a statement fails, so jq is
# what turns a failed statement into a failed recipe.
RUN_CYPHER = if command -v cypher-shell >/dev/null; then \
	    cypher-shell -a "$$NEO4J_URI" -u "$$NEO4J_USERNAME" -p "$$NEO4J_PASSWORD" -d "$${NEO4J_DATABASE:-neo4j}" --format plain; \
	  else \
	    jq -Rs '{statement: .}' \
	    | curl -sS --fail-with-body -K <(printf 'user = "%s:%s"\n' "$$NEO4J_USERNAME" "$$NEO4J_PASSWORD") \
	        -H 'Content-Type: application/json' -H 'Accept: application/json' -d @- "$(QUERY_API_URL)" \
	    | jq -c 'if (.errors // []) | length > 0 then (.errors | tostring + "\n" | halt_error(1)) else .data end'; \
	  fi

REQUIRE_NEO4J = for name in NEO4J_URI NEO4J_USERNAME NEO4J_PASSWORD; do \
	    [ -n "$${!name}" ] || { echo "$$name is not set. gmake .env, then fill it in." >&2; exit 1; }; \
	  done

# A real file target, so it runs once: a second `gmake data/x` finds the file
# and does nothing. The transfer lands in a .part file and is renamed when
# whole, so a failed download never leaves a short file that looks done.
# data/ is ignored by git.
#   gmake data/Apple_10-K_2025_2_pages.pdf    Lab 6's two pages of a 10-K
#   gmake data/form13-2023-05-11.csv          Lab 4's day of Form 13 filings
data/%:
	@mkdir -p $(@D)
	curl -fsSL --retry 3 -o $@.part $(DATASET_URL)/$*
	mv $@.part $@

help: ## Show available targets
	@grep -E '^[a-zA-Z0-9_.-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "%-12s %s\n", $$1, $$2}'

.env: ## Copy .env.template to .env (ignored), for the Aura credentials
	@cp -n .env.template .env && echo ".env written. Fill it in, then direnv allow."

deps: ## Show the tools the labs may want and which are installed
	@for tool in gcloud cypher-shell python3 uv jq curl direnv; do \
	  if tool_path=$$(command -v $$tool); then printf '%-13s %s\n' $$tool "$$tool_path"; \
	  else printf '%-13s missing\n' $$tool; fi; \
	done
	@command -v cypher-shell >/dev/null || echo "cypher-shell is optional: ping and cypher fall back to the Query API over curl."

todos: ## List the lectures and labs not yet ticked
	@awk '/^\*\*\* / { heading = $$0; sub(/^\*\*\* (TODO|DONE) /, "", heading) } \
	     /^- \[ \] / { item = $$0; sub(/^- \[ \] /, "", item); printf "%-32s %s\n", heading, item }' $(CHECKLIST)

agenda: ## List the assignments with how many boxes are ticked
	@awk 'function flush() { if (heading != "") printf "%-5s %d/%d  %s\n", state, ticked, total, heading } \
	     /^\*\*\* (TODO|DONE) / { flush(); state = $$2; heading = $$0; sub(/^\*\*\* (TODO|DONE) /, "", heading); ticked = 0; total = 0 } \
	     /^- \[[ Xx-]\] / { total++ } /^- \[[Xx]\] / { ticked++ } \
	     END { flush() }' $(CHECKLIST)

status: ## Where things stand: git, .env, which variables are set (never their values), gcloud
	@echo "branch $$(git branch --show-current) at $$(git rev-parse --short HEAD), $$(git status --short | wc -l | tr -d ' ') uncommitted"
	@[ -f .env ] && echo ".env present" || echo ".env absent: gmake .env"
	@$(LOAD_ENV); for name in NEO4J_URI NEO4J_USERNAME NEO4J_PASSWORD NEO4J_DATABASE GOOGLE_CLOUD_PROJECT GOOGLE_CLOUD_LOCATION; do \
	  [ -n "$${!name}" ] && printf '%-22s set\n' $$name || printf '%-22s unset\n' $$name; \
	done
	@if command -v gcloud >/dev/null; then \
	  echo "gcloud account $$(gcloud config get-value account 2>/dev/null), project $$(gcloud config get-value project 2>/dev/null)"; fi
	@$(MAKE) --no-print-directory agenda

ping: ## RETURN 1 against the database in .env
	@$(LOAD_ENV); $(REQUIRE_NEO4J); echo 'RETURN 1 AS ok' | $(RUN_CYPHER)

cypher: ## Run a Cypher file against the database in .env (FILE=labs/lab-04-query.cypher)
	@[ -n "$(FILE)" ] || { echo "FILE= is required." >&2; exit 1; }
	@$(LOAD_ENV); $(REQUIRE_NEO4J); $(RUN_CYPHER) < "$(FILE)"

# .gitignore keeps .env out of `git add .`; this is for `git add -f`, and for
# a commit already made. Run it before a push.
guard-env: ## Fail if .env is tracked, staged, or in a local branch's history
	@tracked=$$(git ls-files -- .env .envrc.local); \
	committed=$$(git log --branches --format='%h %s' -- .env .envrc.local | head -5); \
	if [ -n "$$tracked$$committed" ]; then \
	  echo ".env holds credentials and is not for committing." >&2; \
	  if [ -n "$$tracked" ]; then echo "in the index (git rm --cached takes it out): $$tracked" >&2; fi; \
	  if [ -n "$$committed" ]; then echo "in history:" >&2; echo "$$committed" | sed 's/^/  /' >&2; fi; \
	  exit 1; \
	fi; \
	echo "guard-env: .env is not tracked, staged or in history"
