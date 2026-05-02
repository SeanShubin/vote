## Account Management
- Register a new account with a username, email address, and password
- Log in with my username or email address and password
- Stay logged in across browser sessions without re-entering my password
- Change my password (only via the email password-reset flow — proves control
  of the email address; there is no authenticated "change password" endpoint)
- Update my username or email address
- Log out of the application

## Access Control
- Wait for an administrator to grant me access after registering
- Browse the application without voting or making changes (observer role)
- Cast votes in elections I am eligible for (voter role)
- Create and manage my own elections (user role)
- Manage other users' roles and access (admin role)
- View sensitive system information such as confirmation numbers and audit data (auditor role)
- Have a single designated owner who can transfer ownership to another user

## Authorization

The role hierarchy is `NO_ACCESS < OBSERVER < VOTER < USER < ADMIN < AUDITOR < OWNER`.
Every authenticated endpoint requires at minimum `VIEW_APPLICATION` (≥ OBSERVER),
so a `NO_ACCESS` user can authenticate and refresh tokens but cannot do anything
else. The frontend shows them a "your account is awaiting admin approval" stub.

### Permissions by role

| Permission         | Minimum role | Used for                                                                                                     |
| ------------------ | ------------ | ------------------------------------------------------------------------------------------------------------ |
| `VIEW_APPLICATION` | OBSERVER     | All authenticated read endpoints                                                                             |
| `VOTE`             | VOTER        | Casting and removing one's own ballot                                                                        |
| `USE_APPLICATION`  | USER         | Creating elections, editing one's own user record                                                            |
| `MANAGE_USERS`     | ADMIN        | Listing users, viewing user emails, changing roles, removing other users, deleting any election (moderation) |
| `VIEW_SECRETS`     | AUDITOR      | Raw and debug table inspection, event log                                                                    |
| `TRANSFER_OWNER`   | OWNER        | Promoting another user to OWNER (atomic handoff)                                                             |

### Per-action rules

Read endpoints (any OBSERVER+ unless noted):
- List elections, view one election (name, owner, candidates, tiers, ballot count, description)
- List candidates, list tiers
- View any voter's ranking and ballot summary, view the tally — by design,
  elections are public and ballots are not secret. OBSERVER+ can see who cast
  what.
- View one's own activity (own role, own counts) — any authenticated user
  including NO_ACCESS, since they need this to render their own state

Read endpoints requiring **MANAGE_USERS (ADMIN+)** — except for self, which
is always allowed (you can always look up your own email and your own profile):
- List all users (exposes emails)
- View any user's profile (exposes email)

Read endpoints requiring **VIEW_SECRETS (AUDITOR+)**:
- Raw tables, debug tables, event log, table-name lists, counts of those

Write endpoints:
- Cast ballot, delete own ballot — VOTER+, **and** the ballot's voter must
  match the authenticated user (no proxy)
- Create election — USER+
- Set candidates, set tiers, edit election description — USER+ **and**
  caller is the election owner. `setTiers` additionally requires
  `ballotCount == 0` (tier names are part of the meaning of a cast ballot)
- Delete election — election owner **OR** MANAGE_USERS (ADMIN+ as moderator)
- Update own user record (username, email) — USER+ on self
- Update another user's record — MANAGE_USERS **and** strictly higher role
  than target. ADMINs may change other users' emails (e.g. correcting typos)
- Remove user — self anytime (with state checks: OWNER must be alone +
  empty, non-OWNER must own no elections), or MANAGE_USERS + strictly
  higher role than target
- Set role — MANAGE_USERS, never on self, the role being assigned must be
  strictly less than caller's role, and the target's current role must be
  strictly less than caller's role. Promoting USER → USER is intentionally
  blocked: assigning equal roles is denied. Special case: a user with
  TRANSFER_OWNER (i.e. the OWNER) may promote another user to OWNER, which
  emits a single `OwnershipTransferred` event that atomically demotes the
  caller to AUDITOR
- Change password — **no authenticated endpoint exists**. The only path is
  the password-reset email flow: request a reset, click the email link,
  submit the new password with the signed reset token

### Out-of-band auth

- Password reset uses a short-lived signed JWT in the email link rather than
  an access token. The endpoint that consumes it is public; the token itself
  is the proof of identity (control of the email address).

### Public (unauthenticated) endpoints

- `register`, `authenticate`, `refresh`, `logout`
- `requestPasswordReset`, `resetPassword`
- `health` (status check)
- `log-client-error` (frontend error reporting)

There is **no public `/sync` endpoint**. Projection rebuild happens
automatically inside the service after every mutating call; surfacing it as
HTTP would expose an implementation detail with no legitimate caller.

## Election Setup
- Create a new election by giving it a name
- Add candidates to an election
- Specify which registered users are eligible to vote in an election
- View the list of candidates for an election
- View the list of eligible voters for an election

## Election Configuration
- Choose whether ballots are secret (voter identities hidden from results)
- Set an earliest date and time when voting may begin
- Set a deadline after which voting is no longer accepted
- Allow or disallow voters from changing their ballot after submitting it
- Rename an election
- Delete an election and all its data

## Election Lifecycle
- Open an election so that eligible voters can cast ballots
- Close an election to lock in results and prevent further voting

## Voting
- Cast a ranked ballot by ordering candidates from most to least preferred
- Leave candidates unranked if I do not wish to rank them all
- Change my ballot after submitting it, if the election permits edits
- View my submitted ballot along with its confirmation number

## Results
- View the final ranked results of a closed election
- See the pairwise preference counts between every pair of candidates
- See which users cast ballots (when the election is not secret)
- View individual ballots with their rankings (when the election is not secret)
- See anonymized ballots identified only by confirmation number (when the election is secret)
