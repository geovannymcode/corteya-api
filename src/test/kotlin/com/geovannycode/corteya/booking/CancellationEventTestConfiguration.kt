package com.geovannycode.corteya.booking

import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.event.TransactionalEventListener

class CancellationEventRecorder {
    val events = CopyOnWriteArrayList<AppointmentCancelled>()
    @TransactionalEventListener
    fun cancelled(event: AppointmentCancelled) { events.add(event) }
}

@TestConfiguration(proxyBeanMethods = false)
class CancellationEventTestConfiguration {
    @Bean fun cancellationEventRecorder() = CancellationEventRecorder()
}
