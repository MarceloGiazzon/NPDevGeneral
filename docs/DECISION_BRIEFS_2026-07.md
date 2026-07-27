# Decision briefs — C1, D4, D5, F8 (2026-07-27)

> **STATUS: ACTIVE.** Written per `docs/REMAINDER_CLOSURE_PLAN.md` Phase 4 ("AI drafts, owner rules
> — never both"). Each brief below states options, consequences, what each option unblocks, and a
> recommendation. **None of the four verdict lines is filled in.** That is deliberate, not an
> oversight: ADR-0009's own honesty contract lists exactly this class of call ("owner policy
> decisions... an AI may draft the decision brief; it does not make the decision") as
> non-delegable, and `POST_BETA0_HUMAN_ACTION_REGISTER.md` already records E5/E6/E7 the same way.
> Fill in `Verdict:` under each brief, then propagate the answer to the document(s) named in that
> brief's "Where this lands" line.

---

## C1 — The repository is private

**The situation.** `MarceloGiazzon/NPDevGeneral` returns HTTP 404 to an unauthenticated `GET
/repos/...` call (confirmed live, 2026-07-27, from inside a genuinely clean container attempting
M4's third-party-clone half — see `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §3.2's "Concrete blocker"
note). No one outside this project can clone it, at all, without being explicitly granted access
first. This is a **precondition**, not a finding about code quality: REG-17 (third-party
reproduction), D4 (below), and the entire external-security-review rationale
(`EXTERNAL_SECURITY_REVIEW_BRIEF.md`) all silently assume a third party CAN get the source, and none
of them can proceed past that assumption while it's false.

**Why this is arguably a decision already made, not a new one.** ADR-0007 already ratified
Apache-2.0, source-first distribution as this project's licensing posture — the code is *destined*
to be public. A private repo under that ratified decision is a **ratified decision not carried
out**, not a fresh open question. That framing doesn't answer C1 (timing, scope, and readiness are
still real judgment calls the owner alone can make) but it does mean "keep it private forever" is
in tension with a decision already on the books, and should be named as such rather than treated as
a neutral third option.

**Options.**
- **(a) Make the repository public.** Simplest, and the most literal execution of ADR-0007. Unblocks
  REG-17's literal third-party-clone path, D4 outright, and lets `EXTERNAL_SECURITY_REVIEW_BRIEF.md`
  be handed to an actual outside reviewer rather than staying theoretical. Consequence: every commit,
  including this session's own working notes and the `NPDev_General__OutsideRepo` evidence trail (NOT
  in the repo, so unaffected) becomes visible; worth a last look for anything that should have been
  redacted before the first public push (secrets are already excluded by policy and by
  `secret-content-patterns.json`, but a human skim before flipping the switch costs little and this
  brief does not substitute for it).
- **(b) Scoped read access, or a published read-only snapshot mirror.** A named external reviewer (or
  a periodic export to a public mirror repo) gets clone access without the live private repo (with
  its issue tracker, etc.) going fully public. Unblocks REG-17 and D4 for that specific reviewer;
  does not unblock the broader "any stranger can inspect this" posture ADR-0007 implies. More moving
  parts to maintain (a sync job, or a manual invite per reviewer) than (a).
- **(c) Accept the structural cap.** REG-17 stays at "automated external CI reproduction, no literal
  third-party clone," D4 stays entangled/blocked, and the external-security-review brief stays
  undeliverable to an actual outside party. Zero engineering cost, but it leaves three separate
  register items capped by one un-executed decision, indefinitely.

**Recommendation:** (a) or (b) — this single item is the quietest, highest-leverage blocker in the
open register: one decision unblocks REG-17, D4, and the external-review brief at once, and (a) is
already the destination ADR-0007 named.

**Where this lands:** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §3.2 (REG-17), this brief's own D4 section,
`docs/EXTERNAL_SECURITY_REVIEW_BRIEF.md`.

**Verdict:** _(owner to fill in — (a) / (b) / (c), and if (b), who the named reviewer is)_

---

## D4 — REG-17's DoD ruling: does the automated + AI-operator path close it, or is a literal human still required?

**The situation.** REG-17's stated Definition of Done was third-party reproduction. As of 2026-07-24
this is **substantially met by mechanism**: the full CI suite runs green end-to-end on GitHub-hosted
runners from a clean checkout (automated external reproduction, on hardware this project has never
touched) — see `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §3.2's "GREEN END-TO-END" note. Separately, this
project has also run blind, project-context-free AI-agent operators against parts of the gate suite
(the REG-13/14 closure run's Task C, and M4's cold-start/blind-clone mission under ADR-0009) — a
different, complementary kind of "not the author" check. What has **never** happened is a literal
human being, outside this project, who is not the owner and was not coached, cloning the repo and
running the gates themselves start to finish (blocked on C1, and — even once C1 resolves — on
finding and scheduling that person).

**Why this is the owner's call and not mine.** ADR-0009 states this explicitly and by name: "For
REG-17 it satisfies only the not-the-author half of the DoD; the ruling stays the owner's (D4)." An
AI verdict — including everything in this document — is defined by that same ADR to never be
"independent human review," and REG-17's own DoD was written in human-review terms. Whether
automated CI + a blind AI operator *counts as* satisfying a DoD written with a human in mind is a
policy question about what the DoD meant, not a technical question this session can resolve by
producing more evidence.

**Options.**
- **(a) Automated external repro (achieved) + a blind external-AI operator run closes REG-17.** Rules
  that the DoD's intent — "not self-verified, not rubber-stamped by the same hands that built it" —
  is satisfied by the combination already achieved, even without a literal human third party.
  Closes REG-17 today, no further work needed.
- **(b) A literal human third party is still required.** The DoD means what it says; CI-green and an
  AI operator are valuable but not a substitute for a person. REG-17 stays open (or moves to a new
  "advanced, pending human repro" state) until C1 resolves AND a real person is found and scheduled.
- **(c) Defer until C1 resolves.** A literal third party cannot clone a private repo, so ruling now
  is premature — the two are entangled by construction. This defers the decision rather than making
  it, and is honest about why: it isn't avoidance, it's sequencing.

**Recommendation:** no recommendation given here beyond what ADR-0009 already states — this is
squarely the "the executor must never make this call" class of decision (the same standing rule
applied to the REG-17 DoD ruling by name in that ADR). If forced to name the least-committal path:
(c), since it costs nothing now and the answer may become moot once C1 resolves (a real third party,
once one exists, settles (a) vs (b) directly by trying).

**Where this lands:** `docs/adr/ADR-0009-external-ai-delegation.md` decision table (D4 row),
`docs/NPDEV_OPEN_ITEMS_REGISTER.md` §3.2 (REG-17's own status).

**Verdict:** _(owner to fill in — (a) / (b) / (c))_

---

## D5 — E5 (real participant sessions): permanently open, schedule sessions, or an explicitly-labeled AI walkthrough?

**The situation.** `POST_BETA0_HUMAN_ACTION_REGISTER.md` tracks E5 as a real-participant human action
that is, by ADR-0009's own rule, **not AI-delegable**: "An AI persona is not a participant." No
external-AI mission, however well-calibrated, closes this by producing a transcript that reads like
one. This brief exists only to lay out what's actually available, not to suggest AI can stand in.

**Options.**
- **(a) Permanently open, with an honest label.** Record E5 as a boundary this project accepts it
  cannot close alone (no real users/participants exist yet, or scheduling one is out of scope for
  now), same treatment `ACCEPTED_BOUNDARIES.md` gives other "stop and ask a human" limits. Zero cost,
  fully honest, but the register keeps one item permanently in an open state rather than a closed or
  scheduled one.
- **(b) Schedule N real sessions post-launch.** Commit to running actual participant sessions once
  there's a live product to run them against (naturally sequenced after launch, not before). Concrete
  and closeable, but requires the owner's time/relationships to actually schedule — nothing in this
  repo can make that happen.
- **(c) An AI persona walkthrough, explicitly recorded as NOT a substitute.** Could produce a useful,
  cheap dry run of a user flow — but ADR-0009 forbids counting it as closure of E5, so this option
  only has value as a supplementary artifact alongside (a) or (b), never as a replacement for either.

**Recommendation:** (a) now, with (b) as the natural graduation once there is a live product and
real users to invite — this is a sequencing observation, not a push toward a particular answer;
the actual choice of when/whether to run (b) is the owner's, not something advanced by scheduling
pressure from this brief.

**Where this lands:** `POST_BETA0_HUMAN_ACTION_REGISTER.md`'s E5 row.

**Verdict:** _(owner to fill in — (a) / (b) / (c), or (a) now + (b) later)_

---

## F8 — Row-level write authorization is check-then-act, not atomic (LNCH13-F4, informational)

**The situation.** `DefaultConceptGateway.save()`/`delete()` snapshot the previous row (`store.findById`)
before evaluating `isRowWritable`, then persist later — a race window where a concurrent, *already
legitimately write-authorized* actor could reassign the row's ownership between the check and the
later write, making the authorization decision stale by the time it commits. Found and recorded as
**LNCH13-F4, INFORMATIONAL** (`docs/REG16_LNCH13_ROWLEVEL_AUTHZ_ADVERSARIAL_REVIEW.md` §"R2", no
register item filed — the triage rule at the time recorded INFO-severity findings rather than filing
them). Narrow in practice: it requires a second actor who *already has* legitimate write access to
change ownership inside the window; it is not a way for an unauthorized actor to gain access.

**Why every option here changes a published port, not a patch.** `ConceptGateway` is the kernel's own
contract (`CapabilityContractCatalog`-adjacent, consumed by every adapter — in-proc, JDBC/H2,
Postgres — and by every generated app's service layer). Any of the three real fixes below changes
what callers of `save()`/`delete()` can rely on, or what the store contract requires columns/locks
to exist, which is why this was never a same-session patch the way, say, REG-48's reordering was.

**Options.**
- **(a) Row locking** (e.g. `SELECT ... FOR UPDATE` held across the check-then-act window). Closes
  the race directly. Cost: a held lock across an authorization check is a new contention/deadlock
  surface every adapter must handle identically, and the in-proc adapter has no natural analogue of a
  DB-level row lock to mirror it with — asymmetric adapter behavior is exactly the class of drift
  `ADR-0009` and this project's own "single source of truth" rule keeps warning about.
- **(b) Optimistic CAS / version column.** The platform already has an **opt-in** mechanism shaped
  exactly like this: `ConceptWriteRequest.expectedRowVersion` +
  `ConceptGatewayOptimisticLockException` (`NPDevKernel/kernel/.../concepts/`), used today for
  concurrent-*edit* conflicts, not wired to also gate the authorization re-check specifically. Two
  sub-choices: (b1) document that a caller who cares about this race should pass
  `expectedRowVersion` themselves (no code change, a documentation/API-contract clarification); or
  (b2) make version-checked writes the default for row-scoped concepts (a real behavior change every
  existing caller and adapter must be re-verified against, and a bigger lift than it sounds — this is
  the option that most resembles "fix it properly").
- **(c) Accept, with a documented revisit trigger.** Record it as an accepted boundary (matching how
  `ACCEPTED_BOUNDARIES.md` already treats other narrow, already-mitigated-elsewhere gaps), with an
  explicit trigger for revisiting it — e.g., "revisit if any concept's `access.write` rule can be
  reassigned by a role other than the row's own owner/admin," which is the actual precondition for
  the race to matter at all.

**Recommendation:** (c) now, with (b1) as a cheap, immediate companion (document the existing
`expectedRowVersion` escape hatch so an app author who needs the stronger guarantee already has it
without waiting on a platform change) — reserve (a)/(b2) for if the revisit trigger in (c) actually
fires. This is offered as the most conservative, lowest-blast-radius path; the owner may reasonably
weigh the contention/complexity cost differently.

**Where this lands:** `docs/REG16_LNCH13_ROWLEVEL_AUTHZ_ADVERSARIAL_REVIEW.md` (LNCH13-F4's own
entry), `docs/ACCEPTED_BOUNDARIES.md` (if (c) is chosen), `docs/ROW_LEVEL_AUTHORIZATION.md`.

**Verdict:** _(owner to fill in — (a) / (b1) / (b2) / (c), or a combination)_

---

*Companion documents: `docs/REMAINDER_CLOSURE_PLAN.md` §4 (the plan that called for these four
briefs) · `docs/adr/ADR-0009-external-ai-delegation.md` (the honesty-contract rule that makes these
non-delegable) · `POST_BETA0_HUMAN_ACTION_REGISTER.md` (E5/E6/E7's own permanently-non-delegable
tracking).*
