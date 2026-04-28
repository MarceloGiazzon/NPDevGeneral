# Expected Diagnostics

## Expected runtime evidence

- execution succeeds for the provided input payload
- persistence evidence is visible through the normal runtime flow result
- a `UserCreated`-style event is emitted
- audit/timeline or trace-oriented evidence can be inspected after execution

## Expected learning signal

This sample should prove the minimum useful NPDev path:

- concept input
- invariant enforcement
- persistence
- event emission
- observable runtime evidence

## Expected warning and error signals

- warning output should stay quiet for the valid payload and remain deterministic when optional evidence surfaces are unavailable
- error output should identify duplicate or invalid user submissions clearly
