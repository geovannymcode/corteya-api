package com.geovannycode.corteya.booking.dto

import com.geovannycode.corteya.booking.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class TimeOffRequest(val barberId: UUID, val startsAt: OffsetDateTime, val endsAt: OffsetDateTime,
    @field:NotBlank @field:Size(max = 255) val reason: String)
data class TimeOffResponse(val id: UUID, val barberId: UUID, val startsAt: OffsetDateTime, val endsAt: OffsetDateTime, val reason: String?)
data class AppointmentConflict(val id: UUID, val publicToken: String, val startsAt: OffsetDateTime, val endsAt: OffsetDateTime)
data class ManualAppointmentRequest(val barberId: UUID, val serviceId: UUID, val startsAt: OffsetDateTime,
    @field:NotBlank @field:Size(max = 150) val customerName: String, @field:Size(max = 16) val customerPhone: String? = null)
data class StaffCancelRequest(@field:NotBlank @field:Size(max = 500) val reason: String)
data class StaffAppointmentResult(val id: UUID, val publicToken: String, val status: AppointmentStatus,
    val source: AppointmentSource, val startsAt: OffsetDateTime, val endsAt: OffsetDateTime)

enum class SummaryPeriod { DAY, WEEK, MONTH }
data class StaffMetrics(val totalAppointments: Long, val completed: Long, val noShows: Long, val noShowRate: BigDecimal,
    val depositsReceivedCop: Long, val estimatedRevenueCop: Long, val pendingRefunds: Long, val pendingRefundsCop: Long, val completedRefunds: Long, val completedRefundsCop: Long)
data class BarberSummary(val barberId: UUID, val barberName: String, val metrics: StaffMetrics)
data class StaffSummaryResponse(val period: SummaryPeriod, val from: OffsetDateTime, val to: OffsetDateTime,
    val metrics: StaffMetrics, val barbers: List<BarberSummary>)
data class ActionItem(val depositId: UUID, val appointmentId: UUID, val publicToken: String, val barberId: UUID,
    val startsAt: OffsetDateTime, val amountCop: Long, val reference: String, val status: DepositStatus, val refundStatus: RefundStatus,
    val method: DepositMode, val providerTransactionId: String?, val confirmedAt: OffsetDateTime?,
    val barberName: String = "", val serviceName: String = "", val customerName: String = "", val customerPhone: String? = null)
data class ActionItemsResponse(val pendingBreB: List<ActionItem>, val latePayments: List<ActionItem>, val pendingRefunds: List<ActionItem>)
