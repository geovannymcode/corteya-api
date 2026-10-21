package com.geovannycode.corteya.booking

/** Safe audit codes: never store values supplied by the provider in a discrepancy reason. */
enum class DepositDiscrepancyReason {
    METHOD_MISMATCH, TRANSACTION_MISMATCH, REFERENCE_MISMATCH,
    AMOUNT_MISMATCH, CURRENCY_MISMATCH, STATUS_MISMATCH,
}
