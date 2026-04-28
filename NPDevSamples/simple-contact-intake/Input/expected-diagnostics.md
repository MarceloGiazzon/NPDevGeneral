# Expected Diagnostics

## Expected runtime evidence

- execution succeeds for the provided contact payload
- the contact message is persisted
- the notification capability path is executed
- an event is emitted and runtime evidence is inspectable afterward

## Expected learning signal

This sample should prove that NPDev can keep an external-style action governed by semantic runtime flow instead of pushing that behavior into ad hoc application code.

## Expected warning and error signals

- warning output should stay quiet for the happy path and become specific when optional runtime evidence is unavailable
- error output should name the failing required field or invariant when the payload is invalid
