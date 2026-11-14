// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

/** Approval loading/decision progress. Decision errors take precedence over fetch errors. */
public class TaskApprovalState(
    public val approval: PendingApproval? = null,
    public val isLoading: Boolean = false,
    public val error: Throwable? = null,
    public val isDeciding: Boolean = false,
    public val decision: ApprovalDecision? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is TaskApprovalState &&
            approval == other.approval &&
            isLoading == other.isLoading &&
            error == other.error &&
            isDeciding == other.isDeciding &&
            decision == other.decision

    override fun hashCode(): Int {
        var result = approval.hashCode()
        result = 31 * result + isLoading.hashCode()
        result = 31 * result + error.hashCode()
        result = 31 * result + isDeciding.hashCode()
        result = 31 * result + decision.hashCode()
        return result
    }

    override fun toString(): String =
        "TaskApprovalState(approval=$approval, isLoading=$isLoading, error=$error, " +
        "isDeciding=$isDeciding, decision=$decision)"

    /** Creates a value with selected properties replaced. */
    internal fun copy(
        approval: PendingApproval? = this.approval,
        isLoading: Boolean = this.isLoading,
        error: Throwable? = this.error,
        isDeciding: Boolean = this.isDeciding,
        decision: ApprovalDecision? = this.decision,
    ): TaskApprovalState = TaskApprovalState(
        approval, isLoading, error, isDeciding, decision,
    )
}
