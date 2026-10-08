# Findings

What bit, and what was done about it. Numbered so they can be cited: a number
is never reused and a new entry takes the next one. Each was hit, not
reasoned to.

## Driving the lab from an agent

The labs are written for a person in a browser. On 2026-10-08 they were
driven instead by an agent attached over the DevTools protocol to a Chromium
with an empty profile, so that the lab's Google account never met the
owner's. These are what that cost.

1. **Two hands on one browser garble input.** The owner typed in the window
   while the agent was typing the email; the field came out with stray
   characters and Google answered "Couldn't find this account". One driver at
   a time. Read a field back before submitting it.
2. **A failed sign-in counts.** After that one bad email the next page
   carried "Too many failed attempts" beside the Employee ID prompt. It still
   let the right answer through, but there is not much room for retries on a
   lab account.
3. **Lab 1 leaves out a page.** Between the Employee ID and the console,
   Google shows "Welcome to your new account" with terms to accept. The lab
   text goes straight from Employee ID to "Click Console". The agent stopped
   there and the owner clicked it: accepting terms is a person's click.
4. **"Manage on provider" failed the first time.** The first click produced
   "We were unable to authenticate you with the provider. Please navigate to
   the provider directly." Closing the dialog and clicking again opened
   `console.neo4j.io/gcp-marketplace-registration/v2?token=...` as it should.
   Do not take the dialog's advice: going to the provider directly skips the
   marketplace token, which is what ties the Aura organization to the lab's
   subscription. The "leaving Google Cloud" dialog the lab describes did not
   appear on either click.
5. **A tab opened by the page is the lab's next step.** Each click on "Manage
   on provider" opened its own tab, so there were two Aura sign-up tabs. One
   was closed; check which tab is the live one before closing any.
6. **Aura asks things the lab does not mention.** After sign-up there is an
   onboarding survey ("Define your goal") before the tier choice.
7. **A download set over DevTools does not outlive the connection.** The
   agent asked the browser to save downloads to its own directory, then
   disconnected; the credentials file went to the default `~/Downloads`
   anyway, because the owner clicked "Download" in a later moment. Look in
   both places.
8. **A screenshot of the instance-created page holds the password.** It is on
   screen in clear. The shot taken there was deleted. Screenshots for
   replication are taken before and after that page, not on it.

14. **Two things are called Query.** The lab says Connect, then Query: the
    Query in the instance's Connect menu. A locator for a menu item named
    Query found the sidebar's Query first, which opens the tool with no
    instance connected. Scope a locator to the menu that was just opened.
15. **Clicking the query editor does not focus it.** The editor is
    CodeMirror; a click landed on its placeholder and the keystrokes went
    nowhere, twice, with no error. Calling `focus()` on `.cm-content` and
    then typing worked. Read the editor back before pressing Run.

16. **Read back the end of the editor, not the start.** Lab 4's last
    statement went in with a stray `t` after its semicolon, which the Query
    tool ran as a second statement and failed. The check before Run looked
    only at the first words. Where the `t` came from is not known. The real
    statement had already succeeded, so nothing was lost.
17. **Bloom's search was reported as undrivable, and the fault was the
    driver's.** Four attempts failed and the entry first written here blamed
    Bloom. The cause: clicks were placed by converting screenshot
    coordinates to page coordinates with the wrong factor, so two attempts
    clicked above the search bar, and the other two used Tab and Enter,
    which do not pick a suggestion. Screenshots here are 2x the page, and
    the image shown to the agent is scaled again: page = shown x 0.6. What
    worked: ask the page for the position of each visible text and click
    that, one suggestion at a time (`driver/bloom.py`). The owner's
    reference images of the lab's steps showed what the list should look
    like, which is what exposed that it had never opened. A tool that
    "does not respond" is first a question about where the click went.
18. **The account's email is in the header of every Query and Bloom
    screenshot.** "User: Aura (...)" sits on the instance bar. Each image
    kept in `notes/img/` has that span painted over before it is added. At
    first that was a rectangle at fixed coordinates, which holds only while
    the layout does. `bb redact` replaced it: tesseract finds the words,
    and anything that reads as an email or holds a value from `.env` is
    covered, then the output is read again to check nothing is left. It
    catches what OCR can read and no more, so the image is still looked at.
22. **Aura has no "Document Intelligence" for this account.** Lab 6 starts
    from that sidebar entry, a private preview "enabled for your account".
    The lab's own images show an organization "New Organization" with a
    project "Google Cloud marketplace project" (Lab 5's images) or
    "Vocareum" and "GCP marketplace project" (Lab 6's), with usage billed
    in dollars. This account has one organization, "test", one project,
    "New project", on a trial, and the sidebar ends at Dashboards. The
    tool's direct path, `/tools/document-intelligence`, redirects to
    Instances. Read with finding 13: the sign-up seems to
    have made a fresh trial organization and not the marketplace one the
    preview is attached to. Not resolved.
23. **A navigation that never fires "load" hangs the driver.** Going from
    "All organizations" back to the project timed out waiting for the load
    event, and the next script blocked on the half-navigated page. Navigate
    with `wait_until="commit"` and a short default timeout.

## Tooling

9. **`curl | jq` passed a dead database.** `gmake ping` printed curl's "Could
   not resolve host" and exited 0, because the pipeline's status was jq's.
   `.SHELLFLAGS := -o pipefail -c` in the Makefile. The wait loop built on
   ping had broken out on its first, false, success.
10. **Aura's credentials file is not safe to source as written.**
    `AURA_INSTANCENAME=My instance` has a space and no quotes; bash ran
    `instance` as a command. Quote any value with a space when it goes into
    `.env`. The six names in the file are exactly those in `.env.template`.
11. **The file says to wait, and means it.** Its one comment is "Wait a few
    minutes before connecting". The hostname did not resolve in DNS for the
    first minutes after the page showed the credentials.
12. **The local resolver remembered that the host did not exist.** Pinging
    before the instance was up cached the miss in the local network's resolver:
    public resolvers answered while `curl` still could not resolve the name.
    `dig @8.8.8.8 <host>` told the two apart, `dscacheutil -flushcache`
    cleared it. Do not poll a hostname that is not expected to exist yet;
    watch the console's status and ping once it says RUNNING.
13. **The marketplace path gave a trial.** The instance came up labelled
    "Trial - 14 days left", although the listing says the organization has
    purchased. Nothing asked for a card. Whether the usage reaches the
    marketplace subscription is not something this session can see.
19. **The Query API reports a failed statement with HTTP 200.** The body
    carries an `errors` array beside an empty `data`. `gmake cypher` piped
    it through `jq '.data // .'`, printed the empty data and exited 0, for a
    statement that had failed. The jq filter now fails the recipe when
    `errors` is not empty. Same shape as finding 9, one layer up: the
    transport succeeded and the question was whether the statement did.
20. **Lab 4 says "full year" and loads the day file.** The text of "More
    Performant Load" introduces `form13-2023.csv`, a million rows, and all
    three of its statements name `form13-2023-05-11.csv`. The statements
    were run as written. The result is the day's 7,381 holdings, keyed.
21. **`gds.version()` exists and refuses.** It fails with "Aura Graph
    Analytics is versionless": the namespace is there on AuraDB
    Professional, the plugin is not. Graph algorithms mean a Graph
    Analytics session, which is what Bloom starts in Lab 5.
