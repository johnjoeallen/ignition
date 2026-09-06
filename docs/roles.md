# Roles

Ignition has three roles, one console (`https://<event-domain>/` — no separate
hostname, no token per role), and a clean line between what each role can do
there.

!!! note "Login"
    The **prototype** signs everyone in with email + password on the console
    itself. The **intended model** is the organisation's own identity provider
    at the edge — one corporate identity, no Ignition password, no per-team
    account to manage; `git push` / `docker push` use a personal access token
    minted after that first sign-in. See [Exposure & access](exposure.md). The
    role split below is the same either way.

```mermaid
flowchart TB
    pa["Platform admin"]
    za["Team admin<br/>(one or more per team)"]
    dev["Team member / developer"]

    subgraph cp["ignition-control — one console, by role"]
        nodes["Nodes<br/>register · drain · capacity"]
        teams["Teams<br/>provision · place · move · destroy · roster"]
        teamconsole["Team console<br/>members · apps · issues/PRs/releases · runner · status"]
    end

    pa --> nodes
    pa --> teams
    za -->|"the only surface a team admin sees"| teamconsole
    dev -->|"same console, no member management"| teamconsole
    teamconsole -->|"proxied API (service token), or the member's own PAT"| forgejo["the team's Forgejo"]

    pa -. "never touches" .-> forgejo
    za -. "no node or Docker access" .-> nodes
```

## Platform admin

The person (or two) running the event. Signs into the **console** at
`https://<event-domain>/` with email + password, same as everyone else — what
they see and can do there is by role (`PLATFORM_ADMIN`), not by a separate
hostname or token. Every task is a console action — there is no CLI.

| Task | In the console |
|---|---|
| Register a host to run teams | **Nodes → Register** — endpoint (`local` / `ssh://…` / `tcp://…`), CPU, memory, optional labels |
| See node capacity / allocation | **Nodes** table (allocated vs. capacity) |
| Stop placing new teams on a node | **Nodes → Drain** (then Undrain) |
| Provision a team | **Provision a team** — a slug; the scheduler places it (or pin a node / label) |
| See every team and its status | **Teams** table |
| Move a team to another node | **Teams → move** (the stack is rebuilt empty; the Forgejo volume doesn't follow) |
| Destroy a team | **Teams → destroy** — the stack **and every app it deployed** |
| Stop a deployed app | **Apps → stop** |
| Bulk provision / teardown an event | **Roster** — paste a slug list |
| Reclaim idle teams now | **Roster → Sweep idle teams now** (also runs on a timer) |

The platform admin **never logs into a team's Forgejo**. Their surface is
nodes and teams.

## Team admin

One or more per team. Signs into the same console, same email + password
as everyone else — what makes them a team admin is the `ZONE_ADMIN:<slug>`
role on that team, not a separate login or hostname. Their console is
`/teams/<slug>` — everything below is a console action there:

| Task | In the console |
|---|---|
| Add / remove team members | **Members** — creates their Forgejo account too, from their email |
| Reset your own git password / PAT | The regenerate icon beside them, on the team page's top card and under the clone URL on any app's page — always self-service, every member can do it |
| Create an app (a repo) | **Apps → Create app** — name + description; seeds the repo with a starter Dockerfile, a `compose.yaml` (what Ignition deploys — web plus any DB/cache you uncomment) + `compose.override.yaml` (local dev only), a `.env` (runtime config, read at the deployed commit), `.gitignore`, the deploy + PR-preview workflows, and every variable/secret they need. Re-running it on an existing app re-applies that config |
| Manage the team's apps | **Apps** — list, description, current version (links to the live app once deployed), stop (undeploy, keep the repo), delete (undeploy and remove the repo) |
| Restart a stuck Actions runner | **Restart runner** button |

They never touch a Forgejo admin screen; the `ignition-bot` service account
exists only as `ignition-control`'s own credential and never leaves the
controller. A team admin has no node access, no Docker access, no Forgejo
admin access, and no visibility into any other team.

![Team console — apps and members](img/team-console.png)

*The team console: your git username, password and PAT on the top card, apps
(name, description, version), and members. The same credentials also appear on
each app's page under its clone URL. Each has a copy icon, and password/PAT a
regenerate icon; nobody else's, including other admins, are ever shown.*

## Team member / developer

Everyone else on the team — same console, same `/teams/<slug>`, minus member
management. This is the day-to-day surface for actually shipping code; see
**Operating model**, below.

## Operating model — how a team actually works day to day

**Generally, leave Forgejo's own web UI alone.** It's there, and nothing
stops you opening it directly, but day-to-day work — issues, branches, pull
requests, merging, releases — all happens from an app's **management page**
in the console (click the app's name from the team console's Apps table),
not by clicking around in Forgejo. The one thing you still do with a normal
git client is `clone`/`push` — that part is unchanged; it's the
issue/PR/release *lifecycle* that has one intended path.

![An app's management page](img/app-management.png)

*An app's management page — the clone URL with your git username / password /
PAT beneath it, the current version, the **major** / **minor** / **fix**
release buttons, **Deploy from main**, an editable description, and the issue
list. This is where day-to-day work happens, not in Forgejo's own UI.*

1. **Open an issue** for the work, on the app's management page. This
   automatically creates its branch too — `<issue-number>-<title, slugified>`
   off `main` — so there's no separate "create a branch" step, and every
   branch traces back to the issue that justified it.

   ![An issue, with its branch already created](img/issue-opened.png)

2. **Clone the repo** (the clone URL and a copy button are right there on the
   management page) and push your commits to that issue's branch, same as any
   git workflow.
3. **Open a PR** — a button on the issue's own row once it has commits to
   merge. It always targets `main` (the only target this project uses); no
   need to pick a base branch.

   ![A PR opened for the issue, with merge and close available](img/pr-opened.png)

4. **Merge or close** — once Forgejo reports the PR mergeable, **merge**
   closes the issue and deletes the branch automatically. If it turns out
   there's nothing to merge after all, **close** does the same cleanup
   without merging — either way, an issue's branch never outlives the issue,
   and there's no way to reopen a PR for a closed issue (open a new issue
   instead).
5. **Ship a build — a release, from the same management page.** The Release
   card has three buttons — **major**, **minor**, **fix**. Click the one that
   fits the change and `ignition-control` tags the next `vX.Y.Z` on `main` for
   that [semver](https://semver.org/) bump (`fix` → **patch**, `minor` →
   **minor**, `major` → **major**; first release `v0.1.0`, or `v1.0.0` from
   **major**). Nobody types a version.

   The card also shows **what's on `main` that the last release doesn't
   have** — the merged PRs and a count of unreleased commits, with the bump
   the commit messages suggest already highlighted — and a **"Closed, not
   released yet"** list of issues you've closed since the last release. If
   there's nothing pending, the buttons ask you to confirm before cutting a
   release that just redeploys the same commit. So a merge that hasn't been
   shipped is visible, not silent.

   ![After merging and releasing — v0.1.0, no open issues](img/released.png)

The new tag fires the `build and deploy` workflow; on success the app is live
at `https://<APP_NAME>.apps.<slug>.<event-domain>/` within a minute or two.
**Only a release deploys to that host** — a plain push to `main` does not (and
`main` is protected against direct pushes anyway — every change goes through a
PR) — so every running image carries a version you can redeploy or roll back to.

**See `main` before you release — the dev host.** Next to the release buttons
is **Deploy from main**. It builds the current `main` HEAD and runs it at
`https://<APP_NAME>.dev.<slug>.<event-domain>/` — a second, always-on preview
of the latest merged code, alongside the release. It's **public**, like the
release, and it only ever changes when someone clicks the button (a plain push
still doesn't deploy). **stop** on that row tears down the dev copy; the
release is untouched.

**Preview a pull request.** Add the **`deploy-preview`** label to a PR and it
deploys as a throwaway app at
`https://<APP_NAME>-pr-<number>.apps.<slug>.<event-domain>/`, built from that
PR's branch — including any `compose.yaml` / `.env` changes on the branch, so a
preview can carry different config without touching `main`. Pushes to the PR
rebuild it; closing the PR (or removing the label) tears it down. Live previews
are listed on the app's page. The label is the gate on purpose — a fork PR gets
no secrets, so a maintainer adding it is also vouching for the code.
Re-pushing an image to the **same** tag later (a base-image rebuild, say)
needs no new release: the per-node Watchtower notices the new digest and
rolls the app forward on its own within ~60s.

## The line between the roles

- Platform admin: *which* hosts exist, *where* teams run, *whether* a team
  exists at all.
- Team admin: *who's on* one team and at what role — everything else is the
  same surface every member gets.
- Team member: the operating model above — issues, PRs, releases, all from
  the app's management page, never a Forgejo admin screen.

The control plane (`ignition-control`) is the single process that holds every
credential (platform, per-team service accounts, per-member git logins, the
org keys behind the shared-service proxies) and enforces the split: it
authenticates the caller, decides what role(s) it holds, and only ever acts
within that scope.
