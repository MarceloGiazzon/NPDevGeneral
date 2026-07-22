# NPDev Box/Object/Truth Vision

## Status

Accepted as the Phase 0 doctrine for the NPDev new vision.

## Purpose

NPDev is evolving from a contract/generator/runtime/evidence system into a human-centered AI development system.

The goal is not to make AI development rigid. The goal is to let humans create freely while NPDev keeps the truth visible, traceable, and release-safe.

The guiding sentence is:

```text
Simple by default.
Deep when needed.
Personalizable everywhere.
Truthful always.
Restrictive only at release time.
```

## Core truth

NPDev must support maximum creative freedom and maximum truth transparency.

Truth must protect freedom, not block it.

A user should be able to create an idea, a module, an entity, a custom panel, a custom procedure, a rule, a strange business exception, an integration, or an experiment.

NPDev should not prematurely block creation. It should classify what exists:

```text
This exists.
This is custom.
This is human-authored.
This is AI-assisted.
This is generated.
This is experimental.
This is runnable.
This is tested.
This is evidence-backed.
This is release-approved.
```

Strictness increases only when the user claims stronger truth:

```text
This is tested.
This is evidence-backed.
This is release-ready.
This is safe to tag.
```

Release gates block false release claims. They do not block imagination.

## The Box concept

A Box is a human-facing unit of structure, meaning, ownership, truth, evidence, and release classification.

A Box hides complexity by default, but it must not become a black box.

Every Box must allow deeper inspection.

Every standard must have an escape hatch.

Every default must have a personalization layer.

Every generated artifact must have a truth level.

Every advanced customization must remain traceable.

## Correct hierarchy

The official hierarchy is:

```text
Application Box
  Module Box
    Entity Box
      Rule Box
    Integration Box
      Rule Box
    Panel Object
    Procedure Object
    Rule Box
    Evidence Box
  Application-level Rule Box
  Application-level Integration Box
  Evidence Box
  Release Box
```

## Meaning of the hierarchy

The Application Box is the outer product boundary.

The Module Box is the main business capability boundary.

Inside a Module Box:

- Entity Box represents business data and business identity.
- Entity-level Rule Box represents invariants attached to an entity, such as `User Email cannot be empty`.
- Integration Box represents communication with another module, external API, service, provider, or internal contract.
- Integration-level Rule Box represents required parameters, authentication, idempotency, retry policy, response validity, and failure behavior for the integration.
- Panel Object represents user interface and human interaction.
- Procedure Object represents executable business operation or backend process.
- Module-level Rule Box represents cross-entity or module policy.
- Evidence Box represents proof for the module.

At Application level:

- Application-level Rule Box represents global policy or invariant.
- Application-level Integration Box represents global or shared integrations.
- Evidence Box represents application-level proof.
- Release Box represents formal release scope, evidence, exclusions, and release decision.

## Placement rule for Rule Boxes

Rule Boxes should live close to the thing they protect.

```text
Entity invariant      -> Entity Box > Rule Box
Integration contract  -> Integration Box > Rule Box
Module policy         -> Module Box > Rule Box
Application policy    -> Application Box > Application-level Rule Box
Release requirement   -> Release Box / release evidence policy
```

Examples:

```text
Customer Email cannot be empty
  Application Box > Customer Module > Customer Entity Box > Rule Box

Payment gateway requires idempotency key
  Application Box > Billing Module > Payment Gateway Integration Box > Rule Box

AI cannot execute arbitrary shell directly
  Application Box > Application-level Rule Box

Module A can call Module B only through declared parameters
  Application Box > Source Module Box > Integration Box > Rule Box
```

## Boxes

### Application Box

Represents the whole system or product.

It answers:

```text
What is this application?
Who is it for?
What modules does it contain?
What global defaults exist?
What truth level does the whole product have?
What is default, custom, experimental, or release-ready?
```

### Module Box

Represents a business capability or functional area.

It answers:

```text
What business capability does this module own?
What entities belong here?
What integrations enter or leave this module?
What panels expose it to users?
What procedures operate here?
What rules protect module-level truth?
What evidence proves the module?
```

### Entity Box

Represents a core business object.

It answers:

```text
What data does this business object own?
What fields does it have?
What local invariants protect it?
What panels/procedures use it?
What can be customized?
```

### Rule Box

Represents a business rule, validation, invariant, policy, or constraint.

It answers:

```text
What must always be true?
Where does the rule apply?
When is the rule checked?
What happens if it fails?
Is it blocking, warning, advisory, or release-only?
```

### Integration Box

Represents communication boundaries.

It can describe external APIs, module-to-module contracts, service calls, provider contracts, file import/export, AI command bridges, or declared procedure interfaces.

It answers:

```text
What system, service, module, or object is connected?
What data enters or leaves?
What parameters are required?
What authentication is needed?
What error states exist?
What rules protect this integration?
What evidence proves the integration works?
```

### Evidence Box

Represents proof.

It answers:

```text
What claim does this evidence prove?
What command/script generated it?
What files are included?
Is it fresh?
Is it reproducible?
Does it match the current workspace?
```

### Release Box

Represents a formal publication boundary.

It answers:

```text
What version/candidate is this?
What boxes and objects are included?
What evidence is required?
What blocks release?
What is explicitly deferred?
Can the release tag be created?
```

## Objects

Panel and Procedure are Objects, not Boxes.

This is intentional.

Boxes provide structure, ownership, evidence, and release boundaries.

Objects provide creative and operational expression.

Panel Objects and Procedure Objects are the main places where the user directly codes.

They are not only JSON declarations.

They are code-bearing Objects.

## Panel Object

A Panel Object is a frontend code-bearing Object.

It may contain direct user-authored resources:

```text
HTML
CSS
JavaScript
assets
custom layout
frontend events
custom interaction logic
```

Example structure:

```text
objects/panels/customer-list/
  panel.object.json
  index.html
  styles.css
  panel.js
  assets/
    customer-badge.svg
```

The manifest does not replace the code.

The manifest describes how NPDev integrates, preserves, validates, evidences, and releases that code.

A Panel Object can interact with:

```text
Entity data
Procedure Objects
Flow definitions
Rule Boxes
Integration Boxes through declared procedures or safe bridges
Auth
Roles
Tenancy
Customization Registry
Evidence
```

## Procedure Object

A Procedure Object is a backend Java code-bearing Object.

It may contain direct user-authored resources:

```text
Java source files
Java tests
service logic
backend operations
data access through declared contracts
integration calls through declared Integration Boxes
```

Example structure:

```text
objects/procedures/generate-monthly-invoices/
  procedure.object.json
  src/
    GenerateMonthlyInvoices.java
  tests/
    GenerateMonthlyInvoicesTest.java
```

The Java code is real user code.

The manifest describes how it is integrated into the final generated app.

A Procedure Object can interact with:

```text
Entity data
Rule Boxes
Integration Boxes
Other Procedure Objects
Flows
Auth
Roles
Tenancy
Evidence
```

## Code-bearing Object rule

NPDev must not say:

```text
Custom HTML/CSS/JS/Java is unsupported.
```

NPDev should say:

```text
Custom HTML/CSS/JS/Java is supported as code-bearing Objects.
Its truth level depends on validation, tests, evidence, and release gates.
```

Creation is free.

Release claims require proof.

## Role of model.json

`model.json` remains the normalized generation and execution contract.

It answers:

```text
What should be generated?
What entities exist?
What panels/procedures/flows exist?
What fields and bindings exist?
What normalized structure should the runtime understand?
```

In the new vision:

```text
Box/Object Manifests = human/semantic contract
model.json           = machine/generation contract
```

Box/Object manifests may later compile to or map from `model.json`.

The current `model.json` should not be removed or broken during the foundation slice.

## Role of config.json

`config.json` becomes the runtime, environment, and governance configuration.

It answers:

```text
How should this run?
Which environment is active?
Which generator/runtime settings apply?
Which features are enabled?
Which integrations are configured?
Which security/auth/tenancy policies are active?
Where are outputs/evidence written?
What defaults are used?
```

## Flows

Flows show behavior paths across Boxes and Objects.

Boxes show structure.

Objects show actions and screens.

Flows show how behavior travels through the system.

Example:

```text
Manager opens Invoice Approval Panel
-> Panel reads Invoice Entity
-> Panel calls Approve Invoice Procedure
-> Procedure validates Invoice rules
-> Procedure calls Payment Gateway Integration
-> Integration validates idempotency rule
-> Procedure updates Invoice status
-> Evidence records approval behavior
```

## Tenancy

Tenancy is a cross-cutting policy.

It may apply at Application, Module, Entity, Procedure, Panel, and Integration levels.

Examples:

```text
Application-level tenancy:
  Tenant data must be isolated.

Entity-level tenancy:
  Customer must belong to current tenant.

Procedure-level tenancy:
  Procedure reads/writes only current tenant data.

Integration-level tenancy:
  Payment credentials are tenant-specific.
```

## Auth and roles

Auth defines identity.

Roles define permission groups.

They can be used by:

```text
Application-level Rule Boxes
Panel Objects
Procedure Objects
Integration Boxes
Release Boxes
Evidence checks
```

Example:

```text
Panel Object: Invoice Approval
Requires:
  Authenticated user
  Role: BillingManager

Procedure Object: Approve Invoice
Can be run by:
  HumanUser with BillingManager role
  AIWithApproval only if human confirms
```

## Entity fields

Entity fields remain essential.

They are the data anatomy of an Entity Box.

Fields should connect to:

```text
Rules
Panels
Procedures
Integrations
Truth
Evidence
Customization
Security
Tenancy
```

Example:

```text
Entity Box: User
  Field: Email
  Rule Box: User Email Required
```

The field is the data point.

The Rule Box is the business truth.

## Truth Classification

Truth Classification should not be a single vague score.

It should tell the user:

```text
What do we know?
How do we know it?
What remains unproven?
What can be released?
What is only experimental?
```

Truth levels:

```text
T0 Idea
T1 Declared
T2 Generated
T3 RunsLocally
T4 Tested
T5 EvidenceBacked
T6 ReleaseApproved
```

Extra dimensions:

```text
freshness
source
evidenceType
confidence
risk
releaseImpact
knownLimitations
nextTruthStep
```

Critical rule:

```text
Truth classification should never block creation.
It only blocks false claims.
```

## Customization Registry

The Customization Registry is the memory of user freedom.

It records every place where the user changed, extended, personalized, or overrode the default.

It answers:

```text
What did the user customize?
Where is the customization?
Why was it done?
Who created it?
Is it human-authored or AI-proposed?
Should regeneration preserve it?
Does it require retesting?
Does it affect release?
```

It must track code resources:

```text
Frontend HTML
Frontend CSS
Frontend JavaScript
Frontend assets
Backend Java code
Backend Java tests
Manual overrides
Generated template overrides
Integration mappings
Procedure logic
UI behavior
```

Important rule:

```text
Generated files can be replaced.
Protected customizations cannot be silently replaced.
```

## Promotion Workflow

Promotion Workflow moves a Box or Object from creative idea to release-approved artifact.

Stages:

```text
S0 Idea
S1 Declared
S2 Generated
S3 Customized
S4 Runnable
S5 Tested
S6 EvidenceBacked
S7 ReleaseApproved
S8 Released
```

Promotion rule:

```text
Users can create freely at low stages.
NPDev becomes strict only when promoting to evidence-backed or release-approved.
```

## Generator responsibility

The generator must integrate generated and user-authored resources into the final generated app.

It must:

```text
Generate default app shell.
Generate entity/runtime infrastructure.
Generate adapters for Panel Objects.
Copy/bundle Panel Object HTML/CSS/JS/assets.
Generate routes/mount points for Panel Objects.
Generate service/API adapters for Panel actions.
Compile/include Procedure Object Java code.
Register Procedure Objects in backend procedure registry.
Wire Procedure Objects to allowed execution endpoints.
Wire Procedure calls to entities/integrations/rules.
Preserve protected custom resources.
Detect conflicts.
Emit integration reports.
```

The generator must never silently overwrite protected human-authored resources.

Allowed conflict outcomes:

```text
KeepCustom
ReplaceGenerated
AskUser
Block
```

## Final model

```text
Boxes give structure.
Objects give creative and operational expression.
Layers give depth.
Defaults give speed.
Customization gives freedom.
The registry protects human work.
Truth classification prevents fake certainty.
Promotion workflow turns creativity into releasable software.
Release gates protect public claims.
```

The user should always feel:

```text
I can create anything.
I can start simple.
I can go deeper.
I can personalize defaults.
The system will not lie to me about what is proven.
The system will not erase my human work.
The system will not block my imagination.
It will only block false release claims.
```
