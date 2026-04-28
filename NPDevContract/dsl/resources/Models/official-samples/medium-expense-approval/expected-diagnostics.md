# Expected Diagnostics

## Expected runtime evidence

- the first request persists the expense and enters a waiting state
- runtime evidence shows the execution is waiting for a later event
- the approval event can be published with the provided payload shape
- the execution resumes and the post-resume branch is observable through runtime evidence

## Expected learning signal

This sample should prove the first medium-complexity NPDev path:

- persistence before completion
- await-event orchestration
- later resume
- branch behavior after resume
- evidence that the whole process remained governed and inspectable

## Expected warning and error signals

- warning output should explain incomplete or pending resume situations without hiding the waiting state
- error output should identify invalid expense payloads or malformed approval events clearly
