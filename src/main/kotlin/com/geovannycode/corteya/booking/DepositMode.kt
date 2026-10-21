package com.geovannycode.corteya.booking

enum class DepositMode { NONE, BREB_MANUAL, WOMPI }
enum class BrebKeyType { CELULAR, ALIAS, CORREO, DOCUMENTO }
enum class WompiEnvironment { SANDBOX, PRODUCTION }
enum class DepositStatus { PENDING, APPROVED, DECLINED, VOIDED, ERROR, EXPIRED, LATE_PAYMENT }
enum class DepositEventSource { WEBHOOK, STAFF, SYSTEM }
