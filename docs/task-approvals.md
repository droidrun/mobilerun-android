← [README](../README.md) · [Partner guide](https://docs.mobilerun.ai/partners)

## Task approvals

The app talks to **your backend**. Never ship an API key in an app. The backend returns a
`PendingApproval(approvalId, kind, details, expiresAt, deviceId)` and accepts an `ApprovalDecision`
(`decision.value` is the lowercase backend value, `approve` or `deny`).
Unknown kinds or invalid payment details show the generic heading and keep the actions available.

```kotlin
val controller = TaskApprovalController(
    scope = scope,
    fetchApproval = { taskId -> backend.pendingApproval(taskId) },
    decide = { taskId, approvalId, decision -> backend.decide(taskId, approvalId, decision.value) },
    // subscribe = { taskId, changed -> backend.subscribe(taskId, changed) }, // returns AutoCloseable
)
controller.setTask(taskId)
// In Compose:
val state by controller.state.collectAsState()
TaskApproval(
    approval = state.approval,
    onDecide = controller::decide,
    isPending = state.isDeciding,
    decision = state.decision,
    error = state.error?.message,
    labels = TaskApprovalLabels(approve = "Allow", deny = "Reject"),
    // device = { DeviceStream(session, Modifier.fillMaxSize()) },
    // actions = { state -> YourApprovalActions(state.onDecide, state.isPending, state.decision) },
)
// On host disposal:
controller.close() // scope cancellation also cleans up
```

The controller loads immediately and polls every 3 seconds after the latest completed fetch. A
successful subscription turns polling off. If subscribing fails, the error is reported through
`onError` and `state.error` until the next successful fetch, and polling resumes every 3 seconds. Push notifications only refetch. Overlapping/old-task results are dropped. `refetch()` does
not re-subscribe. Decisions are deduplicated, and every success/error triggers a refetch, including
404/409. A decision error survives successful fetches until the next decision or task change; a fetch
error clears after a successful fetch. `setTask(null)` clears state and stops work. Integrator timeouts/cancellation are errors, while
closing the controller cancels its work. Subscription, unsubscribe and error-handler failures cannot
crash the host.

`PendingApproval.paymentCardDetails()` validates only payment-card kinds.
`paymentCardDetails()` accepts a nonempty label (falling back to credentialName), targetPackage
and optional targetHost, ignoring wrongly typed optional fields. The card's isolated one-second
countdown stops ticking at expiry and is display only: **the backend
decides expiry and permission**. Expired approvals still forward decisions. The optional device slot
appears beside details on wide layouts; narrow layouts switch Details/Device while retaining the
slot's composition, including when resizing across the 768 dp breakpoint. The approval actions are independently replaceable.

