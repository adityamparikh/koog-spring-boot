package dev.aparikh.moneytransfer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling // step 5: the abandoned-conversation TTL sweep (ConversationCleanup)
class MoneyTransferApplication

fun main(args: Array<String>) {
    runApplication<MoneyTransferApplication>(*args)
}
