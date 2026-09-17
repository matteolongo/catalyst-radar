package com.catalystradar.application.catalyst

class CatalystNotFoundException(ticker: String) :
    RuntimeException("No catalyst state exists for ticker $ticker") {
    val ticker: String = ticker
}
