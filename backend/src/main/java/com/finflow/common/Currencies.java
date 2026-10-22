package com.finflow.common;

/** Currencies the simulator supports. All use two minor-unit digits, which keeps display simple. */
public final class Currencies {

    public static final String PATTERN = "USD|EUR|GBP|INR";
    public static final String MESSAGE = "must be one of USD, EUR, GBP, INR";

    private Currencies() {
    }
}
