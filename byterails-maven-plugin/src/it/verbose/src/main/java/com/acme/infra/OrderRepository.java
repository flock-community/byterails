package com.acme.infra;

import com.acme.domain.Order;
import java.util.ArrayList;

public class OrderRepository {
    private final ArrayList<Order> orders = new ArrayList<>();
    public void save(Order order) { orders.add(order); }
}
