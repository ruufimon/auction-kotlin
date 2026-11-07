package com.gavel.onboarding.service

import com.gavel.kernel.events.EventPublisher
import com.gavel.onboarding.api.AccountCreatedV1
import com.gavel.onboarding.api.AccountEvent
import com.gavel.onboarding.api.AccountReactivatedV1
import com.gavel.onboarding.api.AccountSuspendedV1
import com.gavel.onboarding.api.CreditEligibilityAssessedV1
import com.gavel.onboarding.api.EmailVerifiedV1
import com.gavel.onboarding.api.ProfileUpdatedV1
import com.gavel.onboarding.api.SellerEnabledV1
import com.gavel.onboarding.api.UserOnboardingEvents
import kotlinx.serialization.json.Json
import java.util.UUID

/** Serializes aggregate events and hands them to the outbox inside the current transaction. */
class AccountEventPublisher(private val publisher: EventPublisher, private val json: Json = Json) {
    fun publishAll(aggregateId: UUID, events: List<AccountEvent>, correlationId: UUID?) {
        events.forEach { publisher.publish(it.eventType, 1, UserOnboardingEvents.AGGREGATE_TYPE, aggregateId, encode(it), correlationId) }
    }

    fun encode(event: AccountEvent): String = when (event) {
        is AccountCreatedV1 -> json.encodeToString(AccountCreatedV1.serializer(), event)
        is EmailVerifiedV1 -> json.encodeToString(EmailVerifiedV1.serializer(), event)
        is CreditEligibilityAssessedV1 -> json.encodeToString(CreditEligibilityAssessedV1.serializer(), event)
        is ProfileUpdatedV1 -> json.encodeToString(ProfileUpdatedV1.serializer(), event)
        is SellerEnabledV1 -> json.encodeToString(SellerEnabledV1.serializer(), event)
        is AccountSuspendedV1 -> json.encodeToString(AccountSuspendedV1.serializer(), event)
        is AccountReactivatedV1 -> json.encodeToString(AccountReactivatedV1.serializer(), event)
    }
}
