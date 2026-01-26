package com.example.birjaHW;

import java.util.UUID;

public class BuyOrder {

    private final UUID orderId;
    private final UUID owner;
    private int amount;
    private final double price;

    public BuyOrder(UUID orderId, UUID owner, int amount, double price) {
        this.orderId = orderId;
        this.owner = owner;
        this.amount = amount;
        this.price = price;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getOwner() {
        return owner;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public double getPrice() {
        return price;
    }
}
