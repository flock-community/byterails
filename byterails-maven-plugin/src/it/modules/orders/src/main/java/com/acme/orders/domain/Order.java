package com.acme.orders.domain;

public class Order implements com.acme.orders.api.OrderApi {
    java.util.List<com.acme.common.Money> lines;

    public String id() { return "1"; }
}
