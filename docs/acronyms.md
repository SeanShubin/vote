# Acronyms and Terminology

A working glossary for the AWS, DynamoDB, and infrastructure terms that show
up in this repo. Each entry is grounded in where the concept actually appears
in the code or deploy template — not a generic definition.

## AWS platform

### AWS — Amazon Web Services
The cloud provider hosting everything in `deploy/template.yaml`. Account
`964638509728`, region `us-east-1`.

### ARN — Amazon Resource Name
A globally-unique identifier for any AWS resource, formatted as
`arn:aws:<service>:<region>:<account>:<resource>`. IAM policies use ARNs to
say *what* an action applies to. Examples from this repo:

- `arn:aws:dynamodb:us-east-1:964638509728:table/vote_data` — the projection table.
- `arn:aws:dynamodb:us-east-1:964638509728:table/vote_data/index/discord-id-index` — a specific GSI on that table; index ARNs are children of the table ARN.
- `arn:aws:sts::964638509728:assumed-role/...` — the identity of an assumed role; this is what shows up in IAM `AccessDenied` errors.

### IAM — Identity and Access Management
AWS's permission system. Every API call is gated by an IAM policy that pairs
an *action* (e.g., `dynamodb:PartiQLSelect`) with a *resource* ARN. The
Lambda's role in `deploy/template.yaml:352-376` is a stack of IAM policy
statements: each one grants a specific action on a specific resource. By
default, nothing is allowed — every action has to be opted in.

### IAM role
An IAM identity that AWS services *assume* at runtime instead of carrying
long-lived credentials. The backend Lambda runs as the `BackendFunctionRole`
created by SAM; CloudFront uses a separate role to read S3, etc. Roles are
the "no static keys" pattern — credentials are minted per invocation and
expire.

### STS — Security Token Service
Mints the temporary credentials behind an assumed role. The `sts:` in
`arn:aws:sts::.../assumed-role/...` is STS reporting "here's the session
running right now."

### KMS — Key Management Service
AWS's managed encryption-key service. Used in this repo only to decrypt the
Discord client-secret SSM parameter (`deploy/template.yaml:375-384`). The
`Condition: kms:ViaService = ssm.<region>.amazonaws.com` restricts the grant
to "decrypt only when SSM is the one asking" — so a compromised Lambda
can't reuse the KMS grant for arbitrary decrypts.

### SSM — Systems Manager (Parameter Store)
Key-value config store. The four Discord OAuth values (client id, secret,
redirect URI, guild id) live here at `/<stack-name>/discord/...` and the
backend reads them on cold start. SecureString parameters are KMS-encrypted
at rest, which is why the Lambda role also needs `kms:Decrypt`.

### SES — Simple Email Service
AWS's outbound email. The backend uses it for alert emails (`ses:SendEmail`
in `deploy/template.yaml:389-395`). The `Condition: ses:FromAddress`
pins the role to a specific verified sender — a compromised Lambda can't
impersonate arbitrary `From:` addresses.

### SAM — Serverless Application Model
A CloudFormation extension with shortcuts for Lambda + API Gateway +
DynamoDB apps. `deploy/template.yaml` is a SAM template — `Transform:
AWS::Serverless-2016-10-31` is what tells CloudFormation to expand SAM
shortcuts (like `DynamoDBCrudPolicy`) into raw CloudFormation. Deployed
with `sam deploy`.

### CFN — CloudFormation
AWS's declarative infrastructure-as-code service. SAM transforms compile
down to CloudFormation under the hood. Relevant constraint in this repo:
**one GSI add/delete per stack update** — multi-GSI changes need sequential
deploys, which is the reason `rebuild-projection` exists as an out-of-band
ceremony rather than a stack update.

## DynamoDB

### DynamoDB
AWS's managed key-value/document database. Single-digit-ms reads at any
scale, but the query model is much more restrictive than SQL — you query by
the keys you designed for, or you Scan. This repo uses the single-table
design: every entity (users, elections, candidates, ballots, notes) lives
in one table (`vote_data`) discriminated by `PK` prefix. See
`docs/dynamodb-single-table-design.md`.

### PK / SK — Partition Key / Sort Key
The two-part primary key of a DynamoDB table. `PK` decides which physical
partition stores the item; `SK` orders items within a partition and allows
range queries (`SK BETWEEN ...`, `begins_with(SK, ...)`). In this repo:

- `vote_data`: `PK = "USER#sean"`, `SK = "METADATA"` — one user record. `PK = "ELECTION#x"`, `SK = "BALLOT#sean"` — one ballot in that election.
- `vote_event_log`: `PK = "EVENT_LOG"` (constant — the whole log is one partition), `SK = event_id` (Number).

### GSI — Global Secondary Index
A second view of the same table, indexed by a different key. Lets you query
by an attribute that isn't the table's primary key. From
`DynamoDbSingleTableSchema.kt:82-86`:

- `discord-id-index` — find a user by Discord id.
- `voter-name-index` — find every ballot cast by a given voter, across elections.
- `owner-name-index` — find every election owned by a given user.
- `manager-user-index` — find every election a user manages.
- `election-listing-index` — list all elections (sparse to election METADATA items only).

GSIs are *sparse* when the indexed attribute is only present on certain
item types — only those items appear in the index. `election-listing-index`
is sparse because only election-METADATA items carry the `election_listing`
attribute, so the index contains exactly the elections and nothing else.

### LSI — Local Secondary Index
A secondary index that shares the table's `PK` but uses a different `SK`.
Must be defined at table-create time and can't be added later. **This repo
doesn't use any** — every secondary access pattern is a GSI.

### PITR — Point In Time Recovery
DynamoDB's continuous-backup feature. Once enabled, you can restore the
table to any second in the last 35 days. Cheap insurance against operator
error. Enabled on both `vote_data` and `vote_event_log` in
`DynamoDbSingleTableSchema.enablePointInTimeRecovery`. For the event log
specifically it's the last-resort recovery; see `docs/event-log-protection.md`.

### TTL — Time To Live
A DynamoDB feature where items with a timestamp attribute get auto-deleted
when the timestamp passes. **Not used in this repo** — the event log is
append-only and the projection is rebuilt from it, so nothing needs to
expire automatically.

### RCU / WCU — Read / Write Capacity Unit
DynamoDB's billing-and-throughput unit. One RCU = one strongly-consistent
read of an item up to 4 KB (or two eventually-consistent reads). One WCU =
one write up to 1 KB. The relevant property here: **a Scan charges RCUs
for every item it examines**, not just the ones the filter keeps. So a
filter-by-`discord_id` Scan over 10k users costs ~10k RCUs; the equivalent
GSI Query costs 1. Both tables in this repo are on `BillingMode:
PayPerRequest`, so you don't provision capacity — you just get billed per
request. The Scan-vs-Query asymmetry still applies.

### Scan vs Query
- **Query** = "give me items where `PK = X` (and optionally `SK <op> Y`)." Bounded, indexed, cheap.
- **Scan** = "read every item in the table, then filter." Unbounded, full table read, expensive.

PartiQL chooses between the two based on whether your `WHERE` clause pins
a partition key with equality. `WHERE PK = 'USER#sean'` → Query. `WHERE
discord_id = '...'` (no `PK`, no index) → Scan.

### PartiQL
SQL-ish query syntax that DynamoDB accepts as an alternative to its native
API. Looks like SQL but obeys DynamoDB semantics — e.g., `ORDER BY` only
works on the sort key when the partition key is constrained. Gated by its
own IAM actions (`dynamodb:PartiQLSelect`, `PartiQLInsert`, etc.) that are
**not** included in SAM's `DynamoDBCrudPolicy`. The admin "query" button
uses read-only PartiQL via the explicit `PartiQLSelect` grant added to
the Lambda role.

## Other

### OAuth — Open Authorization
The "log in with Discord" flow. Discord verifies the user, then redirects
back with a code the backend exchanges for an access token. The four
Discord SSM parameters configure this exchange.

### CLI — Command Line Interface
In this repo, almost always `aws` (the AWS CLI) or `sam` (the SAM CLI).

### CRUD — Create / Read / Update / Delete
The standard four operations on a record. `DynamoDBCrudPolicy` is SAM's
shortcut for "grant all four" on a table — but note it grants the *native*
DynamoDB actions (`GetItem`, `PutItem`, `Query`, `Scan`, etc.), **not**
the PartiQL ones. PartiQL actions are a separate axis.

### JSON / JSONL
JSON = the wire format for events (`event_data` column in the log is a
JSON string). JSONL = "JSON Lines" = one JSON object per line; used for
the prod snapshot files in `.local/prod-snapshots/`.

### SQL / NoSQL
SQL = the relational query language (also the family of relational
databases — MySQL is the alternate backend in this repo). NoSQL = the
opposite-of-relational family DynamoDB belongs to; "no fixed schema, no
joins, no SQL." PartiQL is the bridge — SQL syntax over a NoSQL store.
