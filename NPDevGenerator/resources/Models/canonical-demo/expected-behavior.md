# Canonical Demo Expected Behavior

## Expected authoring story

The canonical demo should feel real, but still be learnable in one sitting.

- `Patient` carries person-oriented details plus one nested structure
- `Patient` also carries one repeated nested collection to show repeated-section behavior
- `Patient` proves one collection invariant through allergy-code uniqueness
- `Patient` also freezes one static default, one dynamic default, and one derived field
- `Provider` is the referenced clinician-side concept
- `Appointment` is the central business object with lifecycle semantics
- `Appointment` freezes the canonical explicit state machine with named states and transition actions
- `Appointment` proves one richer semantic invariant through provider-overlap protection
- `InsuranceClaim` demonstrates downstream consequence after an appointment is completed

## Expected business path

1. Author or submit an `Appointment` through the `CreateAppointment` flow.
2. The model validates appointment data, persists the appointment record, and captures the created appointment id for downstream flow metadata.
3. The appointment lifecycle supports:
   - `Scheduled -> CheckedIn`
   - `CheckedIn -> Completed`
   - `Scheduled -> Cancelled`
   and exposes user-facing actions `Check In`, `Complete Appointment`, and `Cancel Appointment`.
4. While an appointment is still `Scheduled`, the canonical flow can preview a delayed reminder step through `AppointmentReminderDue`.
5. When an appointment reaches `Completed`, the lifecycle event family exposes `AppointmentCompleted`.
6. The canonical orchestration rule reacts to `AppointmentCompleted`.
7. That orchestration creates a related `InsuranceClaim` in `Draft` status.
8. The same orchestration invokes the `notification.send` capability to represent downstream communication.

## Expected generated/runtime surfaces

At a high level, the canonical demo should reliably produce:

- generated CRUD/runtime surfaces for the four concepts
- deterministic invariant diagnostics that identify invariant name and failing path
- flow/action metadata rich enough to preview branch paths, correlation hints, and delayed reminder intent
- lifecycle-aware metadata for appointment transitions, including guard and action labels
- event/runtime DTOs covering appointment completion
- orchestration/runtime behavior showing downstream claim creation and notification
- UI projection surfaces with visible field labels, widgets, and ordering metadata
- interaction metadata for conditional visibility, enablement, readonly state, and picker behavior
- permission-aware UI shaping for hidden, readonly, and disabled runtime metadata states
- layout metadata for tabs, grid columns, width hints, summary cards, and list/table ordering
- rich reference-picker metadata for display templates, picker columns, preview cards, and default filters
- enum option UI hints for appointment status badges and icons
- nested object and repeated-list metadata for `emergencyContact` and `allergies`
- deterministic value-behavior metadata for `preferredLanguage`, `reminderLanguage`, and `chartLabel`
- runtime-owned overlap evaluation for provider scheduling conflicts

## Expected authoring experience

The canonical demo should help answer these questions quickly:

- how concepts reference one another
- how UI metadata appears in the projected UI model
- how nested objects and repeated sections surface through metadata paths like `allergies[].substance`
- how static defaults, dynamic defaults, and derived fields behave deterministically
- how fields like `checkInTime` and `checkOutTime` can appear or enable conditionally from declarative metadata
- how the same lifecycle fields can become readonly or hidden when the runtime actor lacks the right role profile
- how the appointment surface can be grouped into `Overview` and `Visit lifecycle` tabs with model-driven grid hints
- how patient and provider references can preview picker rows and linked-data cards without the UI guessing field layout
- how `CreateAppointment` can stay visible but disabled with an explanation when the actor lacks scheduling permission
- how collection uniqueness and provider-slot protection surface as governed invariants
- how a lifecycle state produces domain events
- how an event can trigger a downstream governed action

It should not try to prove every platform feature at once. Official samples remain the place for alternate or narrower learning paths.
