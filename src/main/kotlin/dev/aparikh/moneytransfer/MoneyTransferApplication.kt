package dev.aparikh.moneytransfer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class MoneyTransferApplication

fun main(args: Array<String>) {
    runApplication<MoneyTransferApplication>(*args)
}
