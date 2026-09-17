package com.catalystradar.application.company

class CompanyNotFoundException(ticker: String) :
    RuntimeException("No supported company exists for ticker $ticker") {
    val ticker: String = ticker
}
